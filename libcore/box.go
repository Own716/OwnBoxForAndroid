package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

var mainInstance *BoxInstance
var boxInstanceSequence atomic.Uint64

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

	v2api        *v2rayapi.StatsService
	selector     *group.Selector
	pauseManager pause.Manager

	diagnosticID  uint64
	diagnosticTag string
	isURLTest     bool
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
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
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
		Box:           instance,
		cancel:        cancel,
		startBox:      instance.Start,
		closeBox:      instance.Close,
		pauseManager:  service.FromContext[pause.Manager](ctx),
		diagnosticID:  diagnosticID,
		diagnosticTag: diagnosticTag,
		isURLTest:     !platformLog,
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
			b.urlTestTrace("box-start", "ok elapsed=%s", time.Since(started))
			b.lifecycleTrace("start", "success state=%s elapsed=%s", b.state, time.Since(started))
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
	return err
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
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

const (
	defaultFallbackURL = "https://www.gstatic.com/generate_204"
	defaultCFURL       = "https://cp.cloudflare.com/generate_204"
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
	if i == nil {
		// 无实例：直连测试（单 GET，计时含拨号）
		client := &http.Client{Timeout: time.Duration(timeout) * time.Millisecond}
		latency, err = urlTestDirect(client, link)
		if err != nil {
			fallback := getFallbackLink(link)
			boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest direct failed: %v, trying fallback: %s", err, fallback))
			latency, err = urlTestDirect(client, fallback)
		}
	} else {
		latency, err = urlTest(i, link, timeout)
		if err != nil {
			primaryErr := err
			fallback := getFallbackLink(link)
			boxPlatformLogWriter.WriteMessage(sblog.LevelDebug, fmt.Sprintf("box.UrlTest primary failed: %v, trying fallback: %s", err, fallback))
			var fbErr error
			latency, fbErr = urlTest(i, fallback, timeout)
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
// 阶段二（测量）：复用连接池中已就绪的长连接发送 HTTP HEAD 探测，测得纯 1-RTT 网络往返时延（~100-180ms），
// 彻底消除冷启动握手与 TLS 重建带来的额外虚高延迟。
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
		},
		DisableKeepAlives: false,
		MaxIdleConns:      5,
		IdleConnTimeout:   10 * time.Second,
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	// 阶段一：预热（建链与握手）
	req1, err := http.NewRequestWithContext(ctx, http.MethodHead, link, nil)
	if err != nil {
		return 0, err
	}
	req1.Header.Set("User-Agent", browserUserAgent)

	start1 := time.Now()
	resp1, err := client.Do(req1)
	if err != nil {
		return 0, err
	}
	_, _ = io.Copy(io.Discard, resp1.Body)
	_ = resp1.Body.Close()
	if resp1.StatusCode >= 400 {
		return 0, fmt.Errorf("HTTP error %d", resp1.StatusCode)
	}
	pass1 := time.Since(start1)

	// 阶段二：复用保活连接，测得纯 1-RTT
	req2, err := http.NewRequestWithContext(ctx, http.MethodHead, link, nil)
	if err == nil {
		req2.Header.Set("User-Agent", browserUserAgent)
		start2 := time.Now()
		resp2, err2 := client.Do(req2)
		if err2 == nil {
			_, _ = io.Copy(io.Discard, resp2.Body)
			_ = resp2.Body.Close()
			if resp2.StatusCode < 400 {
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
	req1, err := http.NewRequest(http.MethodHead, link, nil)
	if err != nil {
		return 0, err
	}
	req1.Header.Set("User-Agent", browserUserAgent)
	start1 := time.Now()
	resp1, err := client.Do(req1)
	if err != nil {
		return 0, err
	}
	_, _ = io.Copy(io.Discard, resp1.Body)
	_ = resp1.Body.Close()
	if resp1.StatusCode >= 400 {
		return 0, fmt.Errorf("HTTP error %d", resp1.StatusCode)
	}
	pass1 := time.Since(start1)

	req2, err := http.NewRequest(http.MethodHead, link, nil)
	if err == nil {
		req2.Header.Set("User-Agent", browserUserAgent)
		start2 := time.Now()
		resp2, err2 := client.Do(req2)
		if err2 == nil {
			_, _ = io.Copy(io.Discard, resp2.Body)
			_ = resp2.Body.Close()
			if resp2.StatusCode < 400 {
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
		protectCloser = serveProtect("protect_path", func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
