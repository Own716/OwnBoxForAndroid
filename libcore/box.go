package libcore

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/protocol/juicity"
	"libcore/protocol/loadbalance"
	"libcore/protocol/mieru"
	"libcore/protocol/urltest"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"runtime"
	"runtime/debug"
	"runtime/metrics"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gofrs/uuid/v5"
	"golang.org/x/net/http2"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	F "github.com/sagernet/sing/common/format"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

var mainInstance *BoxInstance
var boxInstanceSequence atomic.Uint64
var lastUrlTestGc atomic.Int64
var tunStackRe = regexp.MustCompile(`(?i)"stack"\s*:\s*"(?:gvisor|mixed|system)"`)

func sanitizeTunStack(cfg string) string {
	return tunStackRe.ReplaceAllString(cfg, `"stack": "go"`)
}

func deduplicateTags(tags []string) []string {
	if len(tags) == 0 {
		return []string{"direct"}
	}
	seen := make(map[string]struct{}, len(tags))
	result := make([]string, 0, len(tags))
	for _, t := range tags {
		trimmed := strings.TrimSpace(t)
		if trimmed == "" {
			continue
		}
		if _, ok := seen[trimmed]; !ok {
			seen[trimmed] = struct{}{}
			result = append(result, trimmed)
		}
	}
	if len(result) == 0 {
		return []string{"direct"}
	}
	return result
}

func sanitizeOutbounds(outbounds []option.Outbound) {
	for i := range outbounds {
		ob := &outbounds[i]

		if dialerWrapper, ok := ob.Options.(option.DialerOptionsWrapper); ok {
			dialerOpt := dialerWrapper.TakeDialerOptions()
			changed := false
			// 拨号策略冲突防护：bind_interface / inet*_bind_address 不得与 network_strategy 并存
			if dialerOpt.BindInterface != "" || dialerOpt.Inet4BindAddress != nil || dialerOpt.Inet6BindAddress != nil {
				if dialerOpt.NetworkStrategy != nil || len(dialerOpt.NetworkType) > 0 || len(dialerOpt.FallbackNetworkType) > 0 {
					dialerOpt.NetworkStrategy = nil
					dialerOpt.NetworkType = nil
					dialerOpt.FallbackNetworkType = nil
					changed = true
				}
			}
			// QUIC / UDP 协议族：在双网络加速 (hybrid) / 并发拨号 (fallback) 下 UDP connect() 为 0-RTT 本地成功，
			// 多接口竞速时备用蜂窝网可能抢跑并被 SO_BINDTOIFINDEX 绑定至无策略路由的黑洞网卡。
			// 默认锁定 network_strategy="default" 走当前物理默认出口（用户显式指定绑卡/策略时尊重用户配置）。
			switch ob.Type {
			case constant.TypeHysteria2, constant.TypeHysteria, constant.TypeTUIC, juicity.TypeJuicity:
				if dialerOpt.NetworkStrategy == nil && len(dialerOpt.NetworkType) == 0 && len(dialerOpt.FallbackNetworkType) == 0 &&
					dialerOpt.BindInterface == "" && dialerOpt.Inet4BindAddress == nil && dialerOpt.Inet6BindAddress == nil {
					dialerOpt.TCPFastOpen = false
					defaultStrategy := option.NetworkStrategy(constant.NetworkStrategyDefault)
					dialerOpt.NetworkStrategy = &defaultStrategy
					changed = true
				}
			}
			// anytls 协议不支持 TCP Fast Open (sing-box 官方内核在此处开启 TFO 时直接抛出致命错误)
			if ob.Type == "anytls" && dialerOpt.TCPFastOpen {
				dialerOpt.TCPFastOpen = false
				changed = true
			}
			// 拨号策略冲突防护：network_strategy 与 tcp_fast_open 冲突
			if dialerOpt.TCPFastOpen && (dialerOpt.NetworkStrategy != nil || len(dialerOpt.NetworkType) > 0) {
				dialerOpt.TCPFastOpen = false
				changed = true
			}
			if changed {
				dialerWrapper.ReplaceDialerOptions(dialerOpt)
			}
		}

		switch ob.Type {
		case "direct":
			if dirOpt, ok := ob.Options.(*option.DirectOutboundOptions); ok {
				dirOpt.Detour = ""
			}
		case "anytls":
			if anyOpt, ok := ob.Options.(*option.AnyTLSOutboundOptions); ok {
				anyOpt.TCPFastOpen = false
			}
		case "shadowtls":
			if stOpt, ok := ob.Options.(*option.ShadowTLSOutboundOptions); ok {
				if stOpt.TLS == nil {
					stOpt.TLS = &option.OutboundTLSOptions{
						Enabled: true,
					}
				} else {
					stOpt.TLS.Enabled = true
				}
				if stOpt.Version == 0 {
					stOpt.Version = 3
				}
			}
		case "juicity":
			if jOpt, ok := ob.Options.(*juicity.JuicityOutboundOptions); ok {
				if jOpt.TLS == nil {
					jOpt.TLS = &option.OutboundTLSOptions{
						Enabled: true,
					}
				} else {
					jOpt.TLS.Enabled = true
				}
			}
		case mieru.TypeMieru:
			if mOpt, ok := ob.Options.(*mieru.MieruOutboundOptions); ok {
				mOpt.Transport = strings.ToUpper(strings.TrimSpace(mOpt.Transport))
				if mOpt.Transport == "" {
					mOpt.Transport = "TCP"
				}
				if mOpt.Transport == "UDP" &&
					mOpt.NetworkStrategy == nil && len(mOpt.NetworkType) == 0 && len(mOpt.FallbackNetworkType) == 0 &&
					mOpt.BindInterface == "" && mOpt.Inet4BindAddress == nil && mOpt.Inet6BindAddress == nil {
					mOpt.TCPFastOpen = false
					defaultStrategy := option.NetworkStrategy(constant.NetworkStrategyDefault)
					mOpt.NetworkStrategy = &defaultStrategy
				}
			}
		case "hysteria2":
			if hy2Opt, ok := ob.Options.(*option.Hysteria2OutboundOptions); ok {
				if hy2Opt.Obfs != nil {
					if hy2Opt.Obfs.Password == "" || (hy2Opt.Obfs.Type != "" && hy2Opt.Obfs.Type != "salamander") {
						hy2Opt.Obfs = nil
					}
				}
			}
		case "tuic":
			if tuicOpt, ok := ob.Options.(*option.TUICOutboundOptions); ok {
				if tuicOpt.UDPOverStream && tuicOpt.UDPRelayMode != "" {
					tuicOpt.UDPOverStream = false
				}
			}
		case "wireguard":
			if wgOpt, ok := ob.Options.(*option.WireGuardEndpointOptions); ok {
				if wgOpt.Detour != "" && wgOpt.ListenPort != 0 {
					wgOpt.ListenPort = 0
				}
			}
		case "snell":
			if snellOpt, ok := ob.Options.(*option.SnellOutboundOptions); ok {
				if snellOpt.Version == 0 {
					snellOpt.Version = 4
				}
			}
		case "selector":
			if selOpt, ok := ob.Options.(*option.SelectorOutboundOptions); ok {
				selOpt.Outbounds = deduplicateTags(selOpt.Outbounds)
				if selOpt.Default != "" {
					found := false
					for _, tag := range selOpt.Outbounds {
						if tag == selOpt.Default {
							found = true
							break
						}
					}
					if !found {
						selOpt.Default = ""
					}
				}
			}
		case "urltest":
			if urlOpt, ok := ob.Options.(*option.URLTestOutboundOptions); ok {
				urlOpt.Outbounds = deduplicateTags(urlOpt.Outbounds)
			}
		case "loadbalance":
			if lbOpt, ok := ob.Options.(*loadbalance.LoadBalanceOptions); ok {
				lbOpt.Outbounds = deduplicateTags(lbOpt.Outbounds)
			}
		case "naive":
			if naiveOpt, ok := ob.Options.(*option.NaiveOutboundOptions); ok {
				naiveOpt.TCPFastOpen = false
				if naiveOpt.TLS != nil {
					naiveOpt.TLS.DisableSNI = false
					naiveOpt.TLS.Insecure = false
					naiveOpt.TLS.ALPN = nil
					naiveOpt.TLS.MinVersion = ""
					naiveOpt.TLS.MaxVersion = ""
					naiveOpt.TLS.CipherSuites = nil
					naiveOpt.TLS.CurvePreferences = nil
					naiveOpt.TLS.ClientCertificate = nil
					naiveOpt.TLS.ClientCertificatePath = ""
					naiveOpt.TLS.ClientKey = nil
					naiveOpt.TLS.ClientKeyPath = ""
					naiveOpt.TLS.Fragment = false
					naiveOpt.TLS.RecordFragment = false
					naiveOpt.TLS.KernelTx = false
					naiveOpt.TLS.KernelRx = false
					naiveOpt.TLS.UTLS = nil
					naiveOpt.TLS.Reality = nil
				}
			}
		}
	}
}

func ruleActionRefs(action option.RuleAction) (outboundTag, serverTag string) {
	switch action.Action {
	case "", constant.RuleActionTypeRoute:
		outboundTag = action.RouteOptions.Outbound
	case constant.RuleActionTypeBypass:
		outboundTag = action.BypassOptions.Outbound
	case constant.RuleActionTypeResolve:
		serverTag = action.ResolveOptions.Server
	}
	return outboundTag, serverTag
}

func dnsRuleActionServerTag(action option.DNSRuleAction) string {
	switch action.Action {
	case "", constant.RuleActionTypeRoute:
		return action.RouteOptions.Server
	case constant.RuleActionTypeEvaluate:
		return action.EvaluateOptions.Server
	}
	return ""
}

// sanitizeConfigOptions clears deprecated TUN stack settings and prunes stale
// outbound/DNS server references so v1.15.0-alpha.14 startup validation does not abort.
func sanitizeConfigOptions(options *option.Options) {
	if options == nil {
		return
	}
	for i := range options.Inbounds {
		if tunOpt, ok := options.Inbounds[i].Options.(*option.TunInboundOptions); ok && tunOpt != nil {
			tunOpt.Stack = ""
		}
	}

	validOutbounds := make(map[string]bool, len(options.Outbounds)+len(options.Endpoints)+1)
	for i, ep := range options.Endpoints {
		tag := ep.Tag
		if tag == "" {
			tag = F.ToString(i)
		}
		validOutbounds[tag] = true
	}
	for i, ob := range options.Outbounds {
		tag := ob.Tag
		if tag == "" {
			tag = F.ToString(i)
		}
		validOutbounds[tag] = true
	}
	if len(options.Outbounds) == 0 && len(options.Endpoints) == 0 {
		validOutbounds["direct"] = true
	}

	validDNSServers := make(map[string]bool)
	if options.DNS != nil && len(options.DNS.Servers) > 0 {
		for i, srv := range options.DNS.Servers {
			tag := srv.Tag
			if tag == "" {
				tag = F.ToString(i)
			}
			validDNSServers[tag] = true
		}
	} else {
		validDNSServers["local"] = true
	}

	if options.DNS != nil {
		if options.DNS.Final != "" && !validDNSServers[options.DNS.Final] {
			log.Printf("sanitizeConfigOptions: clear missing dns.final=%q", options.DNS.Final)
			options.DNS.Final = ""
		}
		if len(options.DNS.Rules) > 0 {
			keptDNSRules := options.DNS.Rules[:0]
			for i, rule := range options.DNS.Rules {
				var action option.DNSRuleAction
				switch rule.Type {
				case "", constant.RuleTypeDefault:
					action = rule.DefaultOptions.DNSRuleAction
				case constant.RuleTypeLogical:
					action = rule.LogicalOptions.DNSRuleAction
				}
				server := dnsRuleActionServerTag(action)
				if server != "" && !validDNSServers[server] {
					log.Printf("sanitizeConfigOptions: drop dns.rules[%d] referencing missing server=%q", i, server)
					continue
				}
				keptDNSRules = append(keptDNSRules, rule)
			}
			options.DNS.Rules = keptDNSRules
		}
	}

	if options.Route != nil {
		if options.Route.Final != "" && !validOutbounds[options.Route.Final] {
			log.Printf("sanitizeConfigOptions: clear missing route.final=%q", options.Route.Final)
			options.Route.Final = ""
		}
		if len(options.Route.Rules) > 0 {
			keptRouteRules := options.Route.Rules[:0]
			for i, rule := range options.Route.Rules {
				var action option.RuleAction
				switch rule.Type {
				case "", constant.RuleTypeDefault:
					action = rule.DefaultOptions.RuleAction
				case constant.RuleTypeLogical:
					action = rule.LogicalOptions.RuleAction
				}
				outboundTag, serverTag := ruleActionRefs(action)
				if outboundTag != "" && !validOutbounds[outboundTag] {
					log.Printf("sanitizeConfigOptions: drop route.rules[%d] referencing missing outbound=%q", i, outboundTag)
					continue
				}
				if serverTag != "" && !validDNSServers[serverTag] {
					log.Printf("sanitizeConfigOptions: drop route.rules[%d] referencing missing dns server=%q", i, serverTag)
					continue
				}
				keptRouteRules = append(keptRouteRules, rule)
			}
			options.Route.Rules = keptRouteRules
		}
	}
}

type boxLifecycleState uint8

const (
	boxStateNew boxLifecycleState = iota
	boxStateStarting
	boxStateStarted
	boxStateStartFailed
	boxStateClosing
	boxStateClosed
)

func (s boxLifecycleState) String() string {
	switch s {
	case boxStateNew:
		return "new"
	case boxStateStarting:
		return "starting"
	case boxStateStarted:
		return "started"
	case boxStateStartFailed:
		return "start-failed"
	case boxStateClosing:
		return "closing"
	case boxStateClosed:
		return "closed"
	default:
		return fmt.Sprintf("unknown(%d)", s)
	}
}

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	// 官方无 conntrack；等价能力是 NetworkManager.ResetNetwork()：
	// CloseAll 连接 + 通知 endpoint/inbound/outbound.InterfaceUpdated()
	// （hy2/quic 等会丢弃死路径上的会话，下次拨号重建）。
	// 正常切网由 interfaceMonitor → notifyInterfaceUpdate 自动 ResetNetwork
	// （对齐官方 libbox，app 侧不应再叠一层）。本函数仅供手动
	// Action.RESET_UPSTREAM_CONNECTIONS / wakeResetConnections 等显式入口。
	b := mainInstance
	if b == nil || b.Box == nil {
		log.Println("ResetAllConnections: no main instance, skip system=", system)
		return
	}
	b.Network().ResetNetwork(context.Background())
	log.Println("ResetAllConnections: Network.ResetNetwork() done system=", system)
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  boxLifecycleState

	startBox func() error
	closeBox func() error
	startErr error

	v2api           *v2rayapi.StatsService
	selector        *group.Selector
	pauseManager    pause.Manager
	trafficManager  *trafficcontrol.Manager
	dnsRouter       adapter.DNSRouter
	platformWrapper *boxPlatformInterfaceWrapper

	diagnosticID  uint64
	diagnosticTag string
	isURLTest     bool

	appTrafficMu sync.Mutex
	appConnSeen  map[uuid.UUID]appConnTrafficMark
	appPending   map[appTrafficKey]*appTrafficDeltaItem
}

func (b *BoxInstance) urlTestTrace(stage string, format string, args ...any) {
	if b == nil || !b.isURLTest {
		return
	}
	prefix := fmt.Sprintf("URLTestTrace goId=%d tag=%q stage=%s ", b.diagnosticID, b.diagnosticTag, stage)
	log.Printf(prefix+format, args...)
}

func (b *BoxInstance) lifecycleTrace(stage string, format string, args ...any) {
	if b == nil || b.isURLTest {
		return
	}
	prefix := fmt.Sprintf("BoxLifecycleTrace goId=%d stage=%s ", b.diagnosticID, stage)
	log.Printf(prefix+format, args...)
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	return newSingBoxInstance(config, localTransport, true)
}

// NewTestSingBoxInstance 供 URL 测速等一次性实例使用：不注册 PlatformLogWriter。
// 官方内核在 PlatformLogWriter != nil 时无条件创建 CacheFile 与 ClashServer
// （官方 box.go 的 needCacheFile/needClashAPI 分支）：主进程批量测速并发创建的
// 大量实例曾共享默认 cache.db（bbolt）把 freelist 写坏，并在 bbolt 定时器
// goroutine 里 panic 导致主进程闪退；即便退而求其次做文件隔离也是纯浪费——
// 测速实例根本不需要 cache 与 Clash API。置 nil 后两者均不再创建，
// box 日志回落到 stderr（logcat 仍可见）。
func NewTestSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	return newSingBoxInstance(config, localTransport, false)
}

func newSingBoxInstance(config string, localTransport LocalDNSTransport, platformLog bool) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })
	diagnosticID := boxInstanceSequence.Add(1)
	createStarted := time.Now()

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), wrapOutboundRegistry(nekoboxAndroidOutboundRegistry()), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	// 每 box 注册独立的 PlatformInterface 实例（对齐官方 libbox 结构）。
	// 若用进程级单例，并发测速时各 box 的 Initialize 会互相覆盖 wrapper.networkManager，
	// 导致 interfaceMonitor.UpdateDefaultInterface 里 UpdateInterfaces() 刷的是"最新 box"
	// 的 NetworkManager 缓存，落选 box 自己的接口缓存永远为空 → 所有拨号秒报
	// "no available network interface"（见 platform_box.go 批注）。
	platformWrapper := &boxPlatformInterfaceWrapper{
		diagnosticID: diagnosticID,
		isURLTest:    !platformLog,
	}
	service.MustRegister[adapter.PlatformInterface](ctx, platformWrapper)

	// 自动降级与清洗：如果外部配置（如机场 sing-box 订阅或用户自定义 JSON）指定了 stack: "gvisor" 或 "mixed"，
	// 鉴于 Android 环境未编入 -tags with_gvisor（避免超额常驻内存与系统兼容性隐患），
	// 平滑重写为 stack: "go"，无缝切换至 sing-tun 高性能纯 Go 协议栈，杜绝启动报错。
	if strings.Contains(config, "gvisor") || strings.Contains(config, "mixed") {
		config = sanitizeTunStack(config)
	}

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		if !platformLog {
			log.Printf("URLTestTrace goId=%d stage=parse-config failed elapsed=%s error=%v", diagnosticID, time.Since(createStarted), err)
		}
		return nil, fmt.Errorf("decode config: %v", err)
	}
	if !platformLog {
		log.Printf("URLTestTrace goId=%d stage=parse-config ok elapsed=%s", diagnosticID, time.Since(createStarted))
	}

	// 全协议出站安全清洗：全面防范各协议特定字段冲突与非法参数（如 AnyTLS/Naive 不支持 TFO、
	// Hysteria2 空密码 obfs、TUIC stream/relay 冲突、WireGuard detour/listen_port 冲突、Snell 缺失版本等），
	// 无论上层配置或自定义 JSON 如何输入，彻底杜绝内核出站初始化阶段产生致命异常中断服务。
	sanitizeOutbounds(options.Outbounds)
	sanitizeConfigOptions(&options)
	if platformLog {
		urltest.SetSniffOverrideEnabled(configHasSniffRule(&options))
	}

	// 官方内核不支持 fork 私有的 "geoip:xxx"/"geosite:xxx" 伪路径 local rule-set，
	// 这里预处理：从 geoip.db/geosite.db 生成 .srs 缓存并改写为真实路径。
	if options.Route != nil {
		err = prepareLocalGeoRuleSets(options.Route.RuleSet)
		if err != nil {
			cancel()
			if !platformLog {
				log.Printf("URLTestTrace goId=%d stage=prepare-rulesets failed elapsed=%s error=%v", diagnosticID, time.Since(createStarted), err)
			}
			return nil, fmt.Errorf("prepare geo rule-sets: %v", err)
		}
		err = prepareRemoteRuleSets(options.Route.RuleSet)
		if err != nil {
			cancel()
			if !platformLog {
				log.Printf("URLTestTrace goId=%d stage=prepare-remote-rulesets failed elapsed=%s error=%v", diagnosticID, time.Since(createStarted), err)
			}
			return nil, fmt.Errorf("prepare remote rule-sets: %v", err)
		}
	}

	// create box
	// 测速实例（platformLog=false）传 nil：见 NewTestSingBoxInstance 批注。
	var logWriter sblog.PlatformWriter
	if platformLog {
		logWriter = boxPlatformLogWriter
		// 官方内核的 PlatformWriter 通道不做级别过滤（observable.go 无条件
		// 转发所有级别），在此记录配置级别供 WriteMessage 侧过滤；
		// 空级别对齐官方默认 trace。级别非法时 box.New 会报同样的错，此处忽略。
		if options.Log != nil && options.Log.Level != "" {
			if parsedLevel, parseErr := sblog.ParseLevel(options.Log.Level); parseErr == nil {
				setPlatformLogLevel(parsedLevel)
			}
		} else {
			setPlatformLogLevel(sblog.LevelTrace)
		}
	}
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: logWriter,
	})
	if err != nil {
		cancel()
		if !platformLog {
			log.Printf("URLTestTrace goId=%d stage=create-box failed elapsed=%s error=%v", diagnosticID, time.Since(createStarted), err)
		}
		return nil, fmt.Errorf("create service: %v", err)
	}
	diagnosticTag := ""
	if defaultOutbound := instance.Outbound().Default(); defaultOutbound != nil {
		diagnosticTag = defaultOutbound.Tag()
	}
	platformWrapper.diagnosticTag = diagnosticTag

	b = &BoxInstance{
		Box:             instance,
		cancel:          cancel,
		startBox:        instance.Start,
		closeBox:        instance.Close,
		pauseManager:    service.FromContext[pause.Manager](ctx),
		trafficManager:  service.PtrFromContext[trafficcontrol.Manager](ctx),
		dnsRouter:       service.FromContext[adapter.DNSRouter](ctx),
		platformWrapper: platformWrapper,
		diagnosticID:    diagnosticID,
		diagnosticTag:   diagnosticTag,
		isURLTest:       !platformLog,
	}
	b.urlTestTrace("create-box", "ok elapsed=%s", time.Since(createStarted))

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}
	if b.selector == nil {
		for _, outbound := range b.Outbound().Outbounds() {
			if selector, ok := outbound.(*group.Selector); ok {
				b.selector = selector
				break
			}
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()
	started := time.Now()
	b.urlTestTrace("box-start", "begin")
	b.lifecycleTrace("start", "begin state=%s", b.state)

	if b.state != boxStateNew {
		b.lifecycleTrace("start", "rejected state=%s elapsed=%s", b.state, time.Since(started))
		return errors.New("already started")
	}

	b.state = boxStateStarting
	defer func() {
		if err != nil {
			b.state = boxStateStartFailed
			b.startErr = err
			b.urlTestTrace("box-start", "failed elapsed=%s error=%v", time.Since(started), err)
			b.lifecycleTrace("start", "failed state=%s elapsed=%s error=%v", b.state, time.Since(started), err)
		} else {
			b.state = boxStateStarted
			if b.platformWrapper != nil && b.Box != nil {
				if router := b.Box.Router(); router != nil {
					b.platformWrapper.setRouterReset(func() {
						router.ResetNetwork()
						b.resetStatefulUDPTransports()
					})
				}
			}
			b.urlTestTrace("box-start", "ok elapsed=%s", time.Since(started))
			b.lifecycleTrace("start", "success state=%s elapsed=%s", b.state, time.Since(started))
			if !b.isURLTest {
				go b.warmUpMainTunnel()
			}
		}
	}()
	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.startBox != nil {
		err = b.startBox()
	} else if b.Box != nil {
		err = b.Box.Start()
	} else {
		err = errors.New("box is nil")
	}
	return err
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()
	started := time.Now()
	b.urlTestTrace("box-close", "begin state=%s", b.state)
	b.lifecycleTrace("close", "begin state=%s", b.state)

	if b.state == boxStateClosed {
		b.urlTestTrace("box-close", "skip already-closed elapsed=%s", time.Since(started))
		b.lifecycleTrace("close", "skip already-closed elapsed=%s", time.Since(started))
		return nil
	}
	previousState := b.state
	b.state = boxStateClosing
	defer func() {
		b.state = boxStateClosed
		if errors.Is(err, os.ErrClosed) {
			b.urlTestTrace("box-close", "normalized already-closed previous=%s elapsed=%s", previousState, time.Since(started))
			b.lifecycleTrace("close", "normalized already-closed previous=%s elapsed=%s startError=%v", previousState, time.Since(started), b.startErr)
			err = nil
		}
		b.urlTestTrace("box-close", "done state=%s elapsed=%s error=%v", b.state, time.Since(started), err)
		b.lifecycleTrace("close", "done state=%s previous=%s elapsed=%s error=%v", b.state, previousState, time.Since(started), err)
	}()
	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	if b.platformWrapper != nil {
		b.platformWrapper.setRouterReset(nil)
	}

	// clear main instance
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.closeBox != nil {
		err = b.closeBox()
	} else if b.Box != nil {
		err = b.Box.Close()
	}
	if b.isURLTest {
		now := time.Now().UnixMilli()
		if now-lastUrlTestGc.Load() > 2000 {
			lastUrlTestGc.Store(now)
			go func() {
				runtime.GC()
				debug.FreeOSMemory()
			}()
		}
	}
	return err
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) warmUpMainTunnel() {
	if b == nil || b.Box == nil {
		return
	}
	if b.dnsRouter == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3500*time.Millisecond)
	defer cancel()
	// Concurrently pre-warm DoH & remote proxy tunnel (without polling UI) so first browser/app request hits a warm TLS/H2 session.
	var wg sync.WaitGroup
	for _, host := range []string{"www.gstatic.com", "cp.cloudflare.com"} {
		domain := host
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = b.dnsRouter.Lookup(ctx, domain, adapter.DNSQueryOptions{})
		}()
	}
	wg.Wait()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	// 官方 experimental/v2rayapi 的 StatsService 即 adapter.ConnectionTracker
	b.v2api = v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api)
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	resp, err := b.v2api.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
		Name:   fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct),
		Reset_: true,
	})
	if err != nil || resp.Stat == nil {
		return 0
	}
	return resp.Stat.Value
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		if b.selector.SelectOutbound(tag) {
			// 替代 fork 的 nekoutils.Selector_OnProxySelected 钩子。
			// 注意：仅覆盖 app 内的切换路径；通过 Clash API（yacd 面板）
			// 切换不会触发该回调（官方内核无此钩子，待有具体案例再修）。
			if intfNB4A != nil {
				intfNB4A.Selector_OnProxySelected(b.selector.Tag(), tag)
			}
			return true
		}
	}
	return false
}

func (b *BoxInstance) GetActiveOutboundTag(groupTag string) string {
	if b == nil || b.Box == nil {
		return ""
	}
	var detour adapter.Outbound
	if groupTag != "" {
		var ok bool
		detour, ok = b.Outbound().Outbound(groupTag)
		if !ok {
			return ""
		}
	} else {
		var ok bool
		detour, ok = b.Outbound().Outbound("proxy")
		if !ok {
			detour = b.Outbound().Default()
		}
	}
	if detour == nil {
		return ""
	}
	return resolveRealTag(b.Outbound(), detour, "tcp")
}

func (b *BoxInstance) resolveStrategyController(groupTag string) urltest.StrategyGroupController {
	if b == nil || b.Box == nil {
		return nil
	}
	outboundMgr := b.Outbound()
	if outboundMgr == nil {
		return nil
	}
	var detour adapter.Outbound
	if groupTag != "" {
		detour, _ = outboundMgr.Outbound(groupTag)
	} else {
		var ok bool
		detour, ok = outboundMgr.Outbound("proxy")
		if !ok {
			detour = outboundMgr.Default()
		}
	}
	if detour == nil {
		return nil
	}
	if ctrl, ok := detour.(urltest.StrategyGroupController); ok {
		return ctrl
	}
	return nil
}

func (b *BoxInstance) GetGroupSnapshotJSON(groupTag string) string {
	ctrl := b.resolveStrategyController(groupTag)
	if ctrl == nil {
		return ""
	}
	snap := ctrl.GroupSnapshot()
	data, err := json.Marshal(snap)
	if err != nil {
		return ""
	}
	return string(data)
}

func (b *BoxInstance) CheckGroupOutboundsJSON(groupTag string) string {
	ctrl := b.resolveStrategyController(groupTag)
	if ctrl == nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	snap := ctrl.CheckAndSnapshot(ctx)
	data, err := json.Marshal(snap)
	if err != nil {
		return ""
	}
	return string(data)
}

func (b *BoxInstance) PinGroupOutbound(groupTag, memberTag string) bool {
	ctrl := b.resolveStrategyController(groupTag)
	if ctrl == nil {
		return false
	}
	return ctrl.PinOutbound(memberTag)
}

func (b *BoxInstance) resetStatefulUDPTransports() {
	if b == nil || b.Box == nil {
		return
	}
	ctx := context.Background()
	if outboundManager := b.Box.Outbound(); outboundManager != nil {
		for _, ob := range outboundManager.Outbounds() {
			switch ob.Type() {
			case constant.TypeHysteria2, constant.TypeHysteria, constant.TypeTUIC, juicity.TypeJuicity:
				if listener, ok := ob.(adapter.InterfaceUpdateListener); ok {
					listener.InterfaceUpdated(ctx)
				}
			}
		}
	}
	if endpointManager := b.Box.Endpoint(); endpointManager != nil {
		for _, ep := range endpointManager.Endpoints() {
			if ep.Type() == constant.TypeWireGuard {
				if listener, ok := ep.(adapter.InterfaceUpdateListener); ok {
					listener.InterfaceUpdated(ctx)
				}
			}
		}
	}
}

type activeConnectionItem struct {
	ID            string   `json:"id"`
	Network       string   `json:"network"`
	Protocol      string   `json:"protocol"`
	Host          string   `json:"host"`
	Port          int      `json:"port"`
	Inbound       string   `json:"inbound"`
	InboundType   string   `json:"inboundType"`
	SourceIP      string   `json:"sourceIP"`
	SourcePort    int      `json:"sourcePort"`
	DestinationIP string   `json:"destinationIP"`
	IpVersion     string   `json:"ipVersion"`
	PackageName   string   `json:"packageName"`
	ProcessPath   string   `json:"processPath"`
	Uid           int32    `json:"uid"`
	Rule          string   `json:"rule"`
	Chains        []string `json:"chains"`
	Outbound      string   `json:"outbound"`
	OutboundType  string   `json:"outboundType"`
	Upload        int64    `json:"upload"`
	Download      int64    `json:"download"`
	StartMs       int64    `json:"startMs"`
	ClosedMs      int64    `json:"closedMs"`
	Active        bool     `json:"active"`
}

func buildConnectionItem(m *trafficcontrol.TrackerMetadata, active bool) activeConnectionItem {
	network := strings.ToUpper(strings.TrimSpace(m.Metadata.Network))
	if network == "" {
		network = "TCP"
	}
	host := m.Metadata.Domain
	if host == "" {
		host = m.Metadata.Destination.Fqdn
	}
	if host == "" && m.Metadata.Destination.Addr.IsValid() {
		host = m.Metadata.Destination.Addr.String()
	}
	inbound := m.Metadata.Inbound
	if inbound == "" {
		inbound = m.Metadata.InboundType
	}
	inboundFull := inbound
	if m.Metadata.InboundType != "" && m.Metadata.Inbound != "" {
		inboundFull = m.Metadata.InboundType + "/" + m.Metadata.Inbound
	} else if m.Metadata.InboundType != "" {
		inboundFull = m.Metadata.InboundType
	}

	sourceIP := ""
	if m.Metadata.Source.Addr.IsValid() {
		sourceIP = m.Metadata.Source.Addr.String()
	}
	destIP := ""
	ipVersion := ""
	if m.Metadata.Destination.Addr.IsValid() {
		destIP = m.Metadata.Destination.Addr.String()
		if m.Metadata.Destination.Addr.Is6() {
			ipVersion = "IPv6"
		} else {
			ipVersion = "IPv4"
		}
	}

	pkg := ""
	var uid int32 = -1
	processPath := ""
	if m.Metadata.ProcessInfo != nil {
		uid = m.Metadata.ProcessInfo.UserId
		for _, p := range m.Metadata.ProcessInfo.PackageNames {
			if trimmed := strings.TrimSpace(p); trimmed != "" {
				pkg = trimmed
				break
			}
		}
		if pkg == "" {
			for _, p := range m.Metadata.ProcessInfo.ProcessPaths {
				if trimmed := strings.TrimSpace(p); trimmed != "" {
					pkg = trimmed
					break
				}
			}
		}
		if pkg != "" && uid != -1 {
			processPath = fmt.Sprintf("%s (%d)", pkg, uid)
		} else if pkg != "" {
			processPath = pkg
		} else if uid != -1 {
			processPath = fmt.Sprintf("%d", uid)
		}
	}

	rule := "final"
	if m.Rule != nil {
		rule = F.ToString(m.Rule, " => ", m.Rule.Action())
	}

	chains := make([]string, 0, len(m.Chain))
	for i := len(m.Chain) - 1; i >= 0; i-- {
		if tag := strings.TrimSpace(m.Chain[i]); tag != "" {
			chains = append(chains, tag)
		}
	}
	if len(chains) == 0 && m.Outbound != "" {
		chains = append(chains, m.Outbound)
	}

	var up, down int64
	if m.Upload != nil {
		up = m.Upload.Load()
	}
	if m.Download != nil {
		down = m.Download.Load()
	}
	var closedMs int64
	if !active && !m.ClosedAt.IsZero() {
		closedMs = m.ClosedAt.UnixMilli()
	}

	return activeConnectionItem{
		ID:            m.ID.String(),
		Network:       network,
		Protocol:      strings.TrimSpace(m.Metadata.Protocol),
		Host:          host,
		Port:          int(m.Metadata.Destination.Port),
		Inbound:       inbound,
		InboundType:   inboundFull,
		SourceIP:      sourceIP,
		SourcePort:    int(m.Metadata.Source.Port),
		DestinationIP: destIP,
		IpVersion:     ipVersion,
		PackageName:   pkg,
		ProcessPath:   processPath,
		Uid:           uid,
		Rule:          rule,
		Chains:        chains,
		Outbound:      m.Outbound,
		OutboundType:  m.OutboundType,
		Upload:        up,
		Download:      down,
		StartMs:       m.CreatedAt.UnixMilli(),
		ClosedMs:      closedMs,
		Active:        active,
	}
}

func (b *BoxInstance) GetConnectionsJSON() string {
	if b == nil || b.trafficManager == nil {
		return ""
	}
	conns := b.trafficManager.Connections()
	closedConns := b.trafficManager.ClosedConnections()
	upTotal, downTotal := b.trafficManager.Total()

	filtered := make([]*trafficcontrol.TrackerMetadata, 0, len(conns))
	for _, m := range conns {
		if m == nil || m.OutboundType == constant.TypeDNS {
			continue
		}
		filtered = append(filtered, m)
	}
	sort.Slice(filtered, func(i, j int) bool {
		if !filtered[i].CreatedAt.Equal(filtered[j].CreatedAt) {
			return filtered[i].CreatedAt.After(filtered[j].CreatedAt)
		}
		return filtered[i].ID.String() < filtered[j].ID.String()
	})

	items := make([]activeConnectionItem, 0, len(filtered))
	for _, m := range filtered {
		items = append(items, buildConnectionItem(m, true))
	}

	closedFiltered := make([]*trafficcontrol.TrackerMetadata, 0, len(closedConns))
	for _, m := range closedConns {
		if m == nil || m.OutboundType == constant.TypeDNS {
			continue
		}
		closedFiltered = append(closedFiltered, m)
	}
	sort.Slice(closedFiltered, func(i, j int) bool {
		if !closedFiltered[i].ClosedAt.Equal(closedFiltered[j].ClosedAt) {
			return closedFiltered[i].ClosedAt.After(closedFiltered[j].ClosedAt)
		}
		if !closedFiltered[i].CreatedAt.Equal(closedFiltered[j].CreatedAt) {
			return closedFiltered[i].CreatedAt.After(closedFiltered[j].CreatedAt)
		}
		return closedFiltered[i].ID.String() < closedFiltered[j].ID.String()
	})
	const maxClosedConnections = 300
	if len(closedFiltered) > maxClosedConnections {
		closedFiltered = closedFiltered[:maxClosedConnections]
	}

	closedItems := make([]activeConnectionItem, 0, len(closedFiltered))
	for _, m := range closedFiltered {
		closedItems = append(closedItems, buildConnectionItem(m, false))
	}

	payload := struct {
		UploadTotal       int64                  `json:"uploadTotal"`
		DownloadTotal     int64                  `json:"downloadTotal"`
		Connections       []activeConnectionItem `json:"connections"`
		ClosedConnections []activeConnectionItem `json:"closedConnections"`
	}{
		UploadTotal:       upTotal,
		DownloadTotal:     downTotal,
		Connections:       items,
		ClosedConnections: closedItems,
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return ""
	}
	return string(data)
}

func (b *BoxInstance) CloseConnection(id string) bool {
	if b == nil || b.trafficManager == nil || strings.TrimSpace(id) == "" {
		return false
	}
	uid, err := uuid.FromString(strings.TrimSpace(id))
	if err != nil {
		return false
	}
	tracker := b.trafficManager.Connection(uid)
	if tracker == nil {
		return false
	}
	_ = tracker.Close()
	return true
}

func (b *BoxInstance) CloseAllConnections() {
	if b == nil || b.trafficManager == nil {
		return
	}
	b.trafficManager.CloseAllConnections()
}

func (b *BoxInstance) ClearClosedConnections() {
	if b == nil || b.trafficManager == nil {
		return
	}
	b.appTrafficMu.Lock()
	b.collectAppTrafficDeltasLocked()
	b.appTrafficMu.Unlock()
	b.trafficManager.Clear()
}

type appConnTrafficMark struct {
	up   int64
	down int64
}

type appTrafficKey struct {
	pkg string
	uid int32
}

type appTrafficDeltaItem struct {
	PackageName string `json:"packageName"`
	Uid         int32  `json:"uid"`
	ProxyDown   int64  `json:"proxyDown"`
	ProxyUp     int64  `json:"proxyUp"`
	DirectDown  int64  `json:"directDown"`
	DirectUp    int64  `json:"directUp"`
}

func (b *BoxInstance) GetCoreMemoryBytes() int64 {
	samples := [2]metrics.Sample{
		{Name: "/memory/classes/total:bytes"},
		{Name: "/memory/classes/heap/released:bytes"},
	}
	metrics.Read(samples[:])
	if samples[0].Value.Kind() == metrics.KindUint64 {
		total := samples[0].Value.Uint64()
		var released uint64
		if samples[1].Value.Kind() == metrics.KindUint64 {
			released = samples[1].Value.Uint64()
		}
		if total > released {
			return int64(total - released)
		}
	}
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	if ms.Sys > ms.HeapReleased {
		return int64(ms.Sys - ms.HeapReleased)
	}
	return int64(ms.HeapInuse + ms.StackInuse)
}

func (b *BoxInstance) GetActiveConnectionCount() int32 {
	if b == nil || b.trafficManager == nil {
		return 0
	}
	b.appTrafficMu.Lock()
	b.collectAppTrafficDeltasLocked()
	b.appTrafficMu.Unlock()

	var count int32
	for _, m := range b.trafficManager.Connections() {
		if m == nil || m.OutboundType == constant.TypeDNS {
			continue
		}
		count++
	}
	return count
}

func (b *BoxInstance) collectAppTrafficDeltasLocked() {
	if b == nil || b.trafficManager == nil {
		return
	}
	conns := b.trafficManager.Connections()
	closedConns := b.trafficManager.ClosedConnections()

	if b.appConnSeen == nil {
		b.appConnSeen = make(map[uuid.UUID]appConnTrafficMark)
	}
	if b.appPending == nil {
		b.appPending = make(map[appTrafficKey]*appTrafficDeltaItem)
	}

	seenNow := make(map[uuid.UUID]struct{}, len(conns)+len(closedConns))

	processConn := func(m *trafficcontrol.TrackerMetadata) {
		if m == nil || m.OutboundType == constant.TypeDNS {
			return
		}
		if _, dup := seenNow[m.ID]; dup {
			return
		}
		seenNow[m.ID] = struct{}{}

		var up, down int64
		if m.Upload != nil {
			up = m.Upload.Load()
		}
		if m.Download != nil {
			down = m.Download.Load()
		}
		prev := b.appConnSeen[m.ID]
		b.appConnSeen[m.ID] = appConnTrafficMark{up: up, down: down}

		dUp := up - prev.up
		dDown := down - prev.down
		if dUp <= 0 && dDown <= 0 {
			return
		}
		if dUp < 0 {
			dUp = 0
		}
		if dDown < 0 {
			dDown = 0
		}

		pkg := ""
		var uid int32 = -1
		if m.Metadata.ProcessInfo != nil {
			uid = m.Metadata.ProcessInfo.UserId
			for _, p := range m.Metadata.ProcessInfo.PackageNames {
				if trimmed := strings.TrimSpace(p); trimmed != "" {
					pkg = trimmed
					break
				}
			}
			if pkg == "" {
				for _, p := range m.Metadata.ProcessInfo.ProcessPaths {
					if trimmed := strings.TrimSpace(p); trimmed != "" {
						pkg = trimmed
						break
					}
				}
			}
		}

		key := appTrafficKey{pkg: pkg, uid: uid}
		entry := b.appPending[key]
		if entry == nil {
			entry = &appTrafficDeltaItem{
				PackageName: pkg,
				Uid:         uid,
			}
			b.appPending[key] = entry
		}

		isDirect := strings.EqualFold(m.Outbound, "bypass") ||
			strings.EqualFold(m.Outbound, "direct") ||
			m.OutboundType == constant.TypeDirect
		if isDirect {
			entry.DirectUp += dUp
			entry.DirectDown += dDown
		} else {
			entry.ProxyUp += dUp
			entry.ProxyDown += dDown
		}
	}

	for _, m := range conns {
		processConn(m)
	}
	for _, m := range closedConns {
		processConn(m)
	}

	for id := range b.appConnSeen {
		if _, ok := seenNow[id]; !ok {
			delete(b.appConnSeen, id)
		}
	}
}

func (b *BoxInstance) DrainAppTrafficDeltaJSON() string {
	if b == nil || b.trafficManager == nil {
		return ""
	}
	b.appTrafficMu.Lock()
	b.collectAppTrafficDeltasLocked()
	if len(b.appPending) == 0 {
		b.appTrafficMu.Unlock()
		return ""
	}
	items := make([]appTrafficDeltaItem, 0, len(b.appPending))
	for _, item := range b.appPending {
		if item.ProxyDown > 0 || item.ProxyUp > 0 || item.DirectDown > 0 || item.DirectUp > 0 {
			items = append(items, *item)
		}
	}
	b.appPending = make(map[appTrafficKey]*appTrafficDeltaItem)
	b.appTrafficMu.Unlock()

	if len(items) == 0 {
		return ""
	}
	data, err := json.Marshal(items)
	if err != nil {
		return ""
	}
	return string(data)
}

func resolveRealTag(outboundMgr adapter.OutboundManager, detour adapter.Outbound, network string) string {
	visited := make(map[string]bool)
	for detour != nil {
		tag := detour.Tag()
		if visited[tag] {
			return tag
		}
		visited[tag] = true
		if group, isGroup := detour.(adapter.OutboundGroup); isGroup {
			selected := group.Selected(network)
			if selected == nil {
				return tag
			}
			detour = selected
			continue
		}
		if nowGroup, hasNow := detour.(interface{ Now() string }); hasNow {
			activeTag := nowGroup.Now()
			if activeTag == "" || activeTag == tag {
				return tag
			}
			if outboundMgr == nil {
				return activeTag
			}
			next, loaded := outboundMgr.Outbound(activeTag)
			if !loaded || next == nil {
				return activeTag
			}
			detour = next
			continue
		}
		return tag
	}
	return ""
}

const (
	defaultFallbackURL = "https://www.gstatic.com/generate_204"
	defaultCFURL       = "https://cp.cloudflare.com/generate_204"
	defaultMSURL       = "https://edge.microsoft.com/generate_204"
	defaultAppleURL    = "https://captive.apple.com/hotspot-detect.html"
	browserUserAgent   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

func getFallbackLink(primaryLink string) string {
	if strings.Contains(primaryLink, "cloudflare.com") {
		return defaultFallbackURL
	}
	return defaultCFURL
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if i == nil {
		i = mainInstance
	}
	boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest link=%s timeout=%dms instance=%v", link, timeout, i != nil))

	if link == "" {
		link = defaultCFURL
	}

	primaryTimeout := timeout
	fallbackTimeout := int32(2000)
	if timeout > 3500 {
		primaryTimeout = timeout - 1500
		fallbackTimeout = 2000
	} else if timeout < 2000 {
		fallbackTimeout = timeout
	}

	if i == nil {
		// 无实例：直连测试（单 GET，计时含拨号）
		client := &http.Client{Timeout: time.Duration(primaryTimeout) * time.Millisecond}
		latency, err = urlTestDirect(client, link)
		if err != nil {
			fallback := getFallbackLink(link)
			boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest direct failed: %v, trying fallback: %s", err, fallback))
			fbClient := &http.Client{Timeout: time.Duration(fallbackTimeout) * time.Millisecond}
			latency, err = urlTestDirect(fbClient, fallback)
		}
	} else {
		latency, err = urlTest(i, link, primaryTimeout)
		if err != nil {
			primaryErr := err
			fallback := getFallbackLink(link)
			boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest primary failed: %v, trying fallback: %s", err, fallback))
			var fbErr error
			latency, fbErr = urlTest(i, fallback, fallbackTimeout)
			if fbErr != nil {
				err = primaryErr
			} else {
				err = nil
			}
		}
	}
	boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest result latency=%dms err=%v", latency, err))
	return
}

// UrlTestFull 对齐官方 sing-box 与 Throne 真实 TTFB 测速标准（与 UrlTest 保持一致的单次请求 TTFB 算法）。
func UrlTestFull(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	return UrlTest(i, link, timeout)
}

// urlTest 对齐 Throne 及业界基准（两阶段 Keep-Alive 预热探测）：
// 阶段一（预热）：通过 outbound 建立代理隧道并完成目标端 HTTP 握手，将保活长连接推入连接池；
// 若中转/CDN 对 HEAD 请求不兼容（返回 EOF/405/403/400+ 等），自动以 GET 请求重试预热；
// 阶段二（测量）：复用连接池中已就绪的长连接发送探测，测得纯 1-RTT 网络往返时延（~150-250ms），
// 彻底消除冷启动握手与 TLS 重建带来的额外虚高延迟，且严格保持真实低延迟水平不退化。
func urlTest(instance *BoxInstance, link string, timeout int32) (int32, error) {
	outbound := instance.Outbound().Default()
	if outbound == nil {
		return 0, E.New("no default outbound")
	}

	if link == "" {
		link = defaultFallbackURL
	}
	linkURL, err := url.Parse(link)
	if err != nil {
		return 0, E.Cause(err, "parse test link")
	}
	hostname := linkURL.Hostname()

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return outbound.DialContext(ctx, network, M.ParseSocksaddr(addr))
		},
		TLSClientConfig: &tls.Config{
			ServerName:         hostname,
			InsecureSkipVerify: true,
			NextProtos:         []string{"h2", "http/1.1"},
		},
		ForceAttemptHTTP2: true,
		DisableKeepAlives: false,
		MaxIdleConns:      5,
		IdleConnTimeout:   10 * time.Second,
	}
	_ = http2.ConfigureTransport(transport)
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	req1, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	req1.Header.Set("User-Agent", browserUserAgent)

	start1 := time.Now()
	resp1, err := client.Do(req1)
	if err == nil {
		_, _ = io.CopyN(io.Discard, resp1.Body, 8192)
		_ = resp1.Body.Close()
		if resp1.StatusCode >= 500 {
			err = fmt.Errorf("HTTP error %d", resp1.StatusCode)
		}
	}

	if err != nil {
		return 0, err
	}
	pass1 := time.Since(start1)

	// 阶段二：复用保活连接，测得纯 1-RTT 真实低延迟
	req2, err2 := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err2 == nil {
		req2.Header.Set("User-Agent", browserUserAgent)
		start2 := time.Now()
		resp2, err2Do := client.Do(req2)
		if err2Do == nil {
			_, _ = io.CopyN(io.Discard, resp2.Body, 8192)
			_ = resp2.Body.Close()
			if resp2.StatusCode < 500 {
				latency := int32(time.Since(start2).Milliseconds())
				if latency <= 0 {
					latency = 1
				}
				return latency, nil
			}
		}
	}

	// 备选回退：若远端不支持 Keep-Alive，则使用第一阶段耗时
	latency := int32(pass1.Milliseconds())
	if latency <= 0 {
		latency = 1
	}
	return latency, nil
}

// urlTestDirect 为直连测速：同样采用两阶段 Keep-Alive 探测纯 RTT。
func urlTestDirect(client *http.Client, link string) (int32, error) {
	methodUsed := http.MethodHead
	req1, err := http.NewRequest(http.MethodHead, link, nil)
	if err != nil {
		return 0, err
	}
	req1.Header.Set("User-Agent", browserUserAgent)
	start1 := time.Now()
	resp1, err := client.Do(req1)
	if err == nil {
		_, _ = io.Copy(io.Discard, resp1.Body)
		_ = resp1.Body.Close()
		if resp1.StatusCode >= 500 {
			err = fmt.Errorf("HTTP error %d", resp1.StatusCode)
		}
	}

	if err != nil {
		req1Get, errGet := http.NewRequest(http.MethodGet, link, nil)
		if errGet == nil {
			req1Get.Header.Set("User-Agent", browserUserAgent)
			start1 = time.Now()
			resp1Get, errGetDo := client.Do(req1Get)
			if errGetDo == nil {
				_, _ = io.Copy(io.Discard, resp1Get.Body)
				_ = resp1Get.Body.Close()
				if resp1Get.StatusCode < 500 {
					err = nil
					methodUsed = http.MethodGet
				} else {
					err = fmt.Errorf("HTTP error %d", resp1Get.StatusCode)
				}
			}
		}
	}

	if err != nil {
		return 0, err
	}
	pass1 := time.Since(start1)

	req2, err := http.NewRequest(methodUsed, link, nil)
	if err == nil {
		req2.Header.Set("User-Agent", browserUserAgent)
		start2 := time.Now()
		resp2, err2 := client.Do(req2)
		if err2 == nil {
			_, _ = io.Copy(io.Discard, resp2.Body)
			_ = resp2.Body.Close()
			if resp2.StatusCode < 500 {
				latency := int32(time.Since(start2).Milliseconds())
				if latency <= 0 {
					latency = 1
				}
				return latency, nil
			}
		}
	}

	latency := int32(pass1.Milliseconds())
	if latency <= 0 {
		latency = 1
	}
	return latency, nil
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = serveProtect(GetProtectSocketPath(), func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
