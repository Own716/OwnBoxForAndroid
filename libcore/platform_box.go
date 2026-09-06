package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/sys/unix"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

// 官方内核（v1.13.15）的平台接口是 adapter.PlatformInterface
// （旧的 experimental/libbox/platform 包已不存在）。
//
// 注意：boxPlatformInterfaceWrapper 必须按 box 实例创建（见 box.go newSingBoxInstance），
// 不能做成进程级单例——networkManager/myTunName 是每 box 状态，单例会被并发测速的
// 多个 box 的 Initialize 互相覆盖，导致 interfaceMonitor.UpdateDefaultInterface 里
// UpdateInterfaces() 刷错 NetworkManager，落选 box 的接口缓存永远为空，
// 其所有拨号秒报 "no available network interface"。
type boxPlatformInterfaceWrapper struct {
	networkManager adapter.NetworkManager
	myTunName      string
	diagnosticID   uint64
	diagnosticTag  string
	isURLTest      bool
}

func (w *boxPlatformInterfaceWrapper) urlTestTrace(stage string, format string, args ...any) {
	if !w.isURLTest {
		return
	}
	prefix := fmt.Sprintf("URLTestTrace goId=%d tag=%q stage=%s ", w.diagnosticID, w.diagnosticTag, stage)
	log.Printf(prefix+format, args...)
}

func (w *boxPlatformInterfaceWrapper) Initialize(n adapter.NetworkManager) error {
	w.networkManager = n
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	started := time.Now()
	w.urlTestTrace("protect", "begin fd=%d processBg=%v", fd, isBgProcess)
	// call protect_path
	if !isBgProcess {
		err := sendFdToProtect(fd, "protect_path")
		if err == nil {
			w.urlTestTrace("protect", "ok fd=%d elapsed=%s via=protect_path", fd, time.Since(started))
			return nil
		}
		// protect 服务不存在/无监听 = VPN 未运行，无需 protect，放行；
		// 其余失败（如 100ms ack 超时）说明 VPN 在跑但 protect 异常，必须
		// fail-fast——吞掉错误会让未 protect 的测速流量回环进 tun，经当前
		// 节点"套娃"出站，测速结果与节点直连可用性彻底脱节。
		if errors.Is(err, unix.ENOENT) || errors.Is(err, unix.ECONNREFUSED) {
			w.urlTestTrace("protect", "skip fd=%d elapsed=%s reason=no-vpn-service error=%v", fd, time.Since(started), err)
			return nil
		}
		w.urlTestTrace("protect", "failed fd=%d elapsed=%s error=%v", fd, time.Since(started), err)
		return E.Cause(err, "protect fd via protect_path")
	}
	// bg process call VPNService
	err := intfBox.AutoDetectInterfaceControl(int32(fd))
	if err != nil {
		w.urlTestTrace("protect", "failed fd=%d elapsed=%s via=vpn-service error=%v", fd, time.Since(started), err)
	} else {
		w.urlTestTrace("protect", "ok fd=%d elapsed=%s via=vpn-service", fd, time.Since(started))
	}
	return err
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool {
	return true
}

// OpenInterface 即旧接口的 OpenTun。
func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("android: unsupported android_user option")
	}
	a, _ := json.Marshal(options)
	b, _ := json.Marshal(platformOptions)
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %v", err)
	}
	// Do you want to close it?
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %v", err)
	}
	//
	options.FileDescriptor = int(tunFd)
	w.myTunName = options.Name
	return tun.New(*options)
}

func (w *boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool {
	return true
}

func (w *boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	return newInterfaceMonitor(w, l)
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool {
	return true
}

// NetworkInterfaces 经 JNI 枚举平台网络接口。
// 注意：这是官方内核拨号路径的硬性要求——注册 PlatformInterface 后拨号器恒走
// 并行接口选择（selectInterfaces），而 NetworkManager 只在平台分支缓存接口列表；
// 列表为空时所有拨号报 "no available network interface"。参考 husi platform_box.go。
func (w *boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	started := time.Now()
	interfaceIterator, err := intfBox.GetInterfaces()
	if err != nil {
		w.urlTestTrace("interfaces", "failed elapsed=%s error=%v", time.Since(started), err)
		return nil, err
	}
	interfaces := make([]adapter.NetworkInterface, 0, interfaceIterator.Length())
	for interfaceIterator.HasNext() {
		netInterface := interfaceIterator.Next()
		if netInterface == nil || netInterface.Name == "" || netInterface.Name == w.myTunName {
			continue
		}
		interfaces = append(interfaces, adapter.NetworkInterface{
			Interface: control.Interface{
				Index:     int(netInterface.Index),
				MTU:       int(netInterface.MTU),
				Name:      netInterface.Name,
				Addresses: common.Map(iteratorToArray[string](netInterface.Addresses), netip.MustParsePrefix),
				Flags:     linkFlags(uint32(netInterface.Flags)),
			},
			Type:        C.InterfaceType(netInterface.Type),
			DNSServers:  iteratorToArray[string](netInterface.DNSServer),
			Expensive:   netInterface.Metered,
			Constrained: false, // Android 无此概念
		})
	}
	interfaces = common.UniqBy(interfaces, func(it adapter.NetworkInterface) string {
		return it.Name
	})
	names := make([]string, 0, len(interfaces))
	for _, netInterface := range interfaces {
		names = append(names, fmt.Sprintf("%s#%d", netInterface.Name, netInterface.Index))
	}
	w.urlTestTrace("interfaces", "ok elapsed=%s count=%d values=%s", time.Since(started), len(interfaces), strings.Join(names, ","))
	return interfaces, nil
}

func (w *boxPlatformInterfaceWrapper) UnderNetworkExtension() bool {
	return false
}

// NetworkExtensionIncludeAllNetworks 即旧接口的 IncludeAllNetworks。
func (w *boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) ClearDNSCache() {
}

func (w *boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) ReadWIFIState() adapter.WIFIState {
	state := strings.Split(intfBox.WIFIState(), ",")
	return adapter.WIFIState{
		SSID:  state[0],
		BSSID: state[1],
	}
}

func (w *boxPlatformInterfaceWrapper) SystemCertificates() []string {
	return nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool {
	return true
}

// FindConnectionOwner 即旧接口的 process.Searcher（FindProcessInfo）。
func (w *boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		var network string
		switch request.IpProtocol {
		case syscall.IPPROTO_TCP:
			network = N.NetworkTCP
		case syscall.IPPROTO_UDP:
			network = N.NetworkUDP
		default:
			return nil, E.New("unknown ip protocol: ", request.IpProtocol)
		}
		sourceAddr, err := netip.ParseAddr(request.SourceAddress)
		if err != nil {
			return nil, E.Cause(err, "parse source address")
		}
		destinationAddr, err := netip.ParseAddr(request.DestinationAddress)
		if err != nil {
			return nil, E.Cause(err, "parse destination address")
		}
		uid = procfs.ResolveSocketByProcSearch(
			network,
			netip.AddrPortFrom(sourceAddr, uint16(request.SourcePort)),
			netip.AddrPortFrom(destinationAddr, uint16(request.DestinationPort)),
		)
		if uid == -1 {
			return nil, E.New("procfs: not found")
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(request.IpProtocol, request.SourceAddress, request.SourcePort, request.DestinationAddress, request.DestinationPort)
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	var packageNames []string
	if packageName != "" {
		packageNames = []string{packageName}
	}
	return &adapter.ConnectionOwner{UserId: uid, AndroidPackageNames: packageNames}, nil
}

func (w *boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) UsePlatformNotification() bool {
	return false
}

func (w *boxPlatformInterfaceWrapper) SendNotification(notification *adapter.Notification) error {
	return nil
}

func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	return nil
}

// io.Writer

var disableSingBoxLog = false

func (w *boxPlatformInterfaceWrapper) Write(p []byte) (n int, err error) {
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

// 日志

// 官方内核（observable.go）对 PlatformWriter 通道不做级别过滤：所有级别
// （含 trace）的消息都会无条件送达 WriteMessage，过滤需在本侧实现。
// platformLogLevel 由 newSingBoxInstance 按配置 log.level 记录；
// 初始值与空级别均对齐官方默认 LevelTrace（全放行）。
var platformLogLevel = int32(sblog.LevelTrace)

func setPlatformLogLevel(level sblog.Level) {
	atomic.StoreInt32(&platformLogLevel, int32(level))
}

type boxPlatformLogWriterWrapper struct {
}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (w *boxPlatformLogWriterWrapper) WriteMessage(level sblog.Level, message string) {
	if int32(level) > atomic.LoadInt32(&platformLogLevel) {
		return
	}
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	platformLog.Write([]byte(message))
}
