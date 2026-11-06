package libcore

import (
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

// networkChangeResetConnections 对应 app 设置
// DataStore.networkChangeResetConnections（「当网络发生变化时重置出站连接」）。
// true（默认）：name/index 变化时通知回调 → 官方 notifyInterfaceUpdate → ResetNetwork。
// false：仍更新 DefaultInterface() 供新拨号绑定，但不通知回调（不拆现有出站）；
// 从 nil 恢复时仍通知，以便 NetworkWake（否则 Lost 后会一直 pause）。
var networkChangeResetConnections atomic.Bool

func init() {
	networkChangeResetConnections.Store(true)
}

// SetNetworkChangeResetConnections 由 Kotlin 在发起接口更新前同步设置项。
func SetNetworkChangeResetConnections(enable bool) {
	networkChangeResetConnections.Store(enable)
}

// InterfaceUpdateListener 由 Kotlin 侧实现回调：
// ConnectivityManager 默认网络变化时通知 Go 默认物理接口（名称 + index，-1 表示无网络）。
// 对齐官方 experimental/libbox/monitor.go 的 InterfaceUpdateListener（本仓库 JNI
// 未传 isExpensive/isConstrained，二者由 NetworkInterfaces 缓存提供）。
type InterfaceUpdateListener interface {
	UpdateDefaultInterface(interfaceName string, interfaceIndex int32)
}

var (
	_ tun.DefaultInterfaceMonitor = (*interfaceMonitor)(nil)
	_ InterfaceUpdateListener     = (*interfaceMonitor)(nil)
)

// interfaceMonitor 对齐官方 libbox platformDefaultInterfaceMonitor。
// 官方内核 v1.13 只要注册了 PlatformInterface 就强制使用平台监视器
// （route/network.go: usePlatformDefaultInterfaceMonitor = platformInterface != nil）。
//
// 行为与官方 monitor.go 一致：
//  1. UpdateInterfaces() 刷新平台缓存
//  2. index==-1 → defaultInterface=nil 并通知回调（NetworkPause）
//  3. ByIndex 成功后，仅当 name/index 相对旧值变化时才通知回调
//     （回调 → notifyInterfaceUpdate → ResetNetwork）
//  4. ByIndex 失败只打错误日志并 return，保留旧 defaultInterface（不重试、不清空）
//
// 唯一 Android 补丁：官方只调 networkManager.InterfaceFinder().ByIndex；
// 在部分 OEM（OnePlus/Android16+VPN）上 finder 可能尚未含该 index。
// resolveInterface 在 nm-finder 失败时回退 nm-cache / net.InterfaceByIndex /
// Kotlin name+index，避免"永远 applied=0、ResetNetwork 从不触发"。
// 不做时间防抖——切网重置完全交给官方 notifyInterfaceUpdate。
type interfaceMonitor struct {
	wrapper                     *boxPlatformInterfaceWrapper
	access                      sync.Mutex
	callbacks                   list.List[tun.DefaultInterfaceUpdateCallback]
	logger                      logger.Logger
	myInterfaces                []string
	defaultInterface            *control.Interface
	defaultInterfaceInitialized bool
}

func newInterfaceMonitor(w *boxPlatformInterfaceWrapper, l logger.Logger) *interfaceMonitor {
	return &interfaceMonitor{wrapper: w, logger: l}
}

func (m *interfaceMonitor) Start() error {
	started := time.Now()
	m.wrapper.urlTestTrace("interface-monitor", "start begin")
	err := intfBox.StartDefaultInterfaceMonitor(m)
	m.access.Lock()
	defaultInterface := m.defaultInterface
	m.access.Unlock()
	if err != nil {
		m.wrapper.urlTestTrace("interface-monitor", "start failed elapsed=%s error=%v", time.Since(started), err)
	} else if defaultInterface == nil {
		m.wrapper.urlTestTrace("interface-monitor", "start returned elapsed=%s default=nil", time.Since(started))
	} else {
		m.wrapper.urlTestTrace("interface-monitor", "start ready elapsed=%s default=%s#%d", time.Since(started), defaultInterface.Name, defaultInterface.Index)
	}
	return err
}

func (m *interfaceMonitor) Close() error {
	started := time.Now()
	err := intfBox.CloseDefaultInterfaceMonitor(m)
	m.wrapper.urlTestTrace("interface-monitor", "close elapsed=%s error=%v", time.Since(started), err)
	return err
}

func (m *interfaceMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.defaultInterface
}

func (m *interfaceMonitor) OverrideAndroidVPN() bool {
	return false
}

func (m *interfaceMonitor) AndroidVPNEnabled() bool {
	return false
}

func (m *interfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *interfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *interfaceMonitor) RegisterMyInterface(interfaceName string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterfaces = append(m.myInterfaces, interfaceName)
}

func (m *interfaceMonitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return m.myInterfaces
}

// resolveInterface 在官方 ByIndex 路径之上增加平台缓存/系统/Kotlin 回退。
// 优先顺序与官方一致地以 NetworkManager 缓存为准（UpdateInterfaces 刚刷入）。
func (m *interfaceMonitor) resolveInterface(interfaceName string, interfaceIndex int32) (*control.Interface, string, error) {
	idx := int(interfaceIndex)

	if m.wrapper.networkManager != nil {
		// 官方路径：InterfaceFinder().ByIndex（UpdateInterfaces 后应命中）
		if iif, err := m.wrapper.networkManager.InterfaceFinder().ByIndex(idx); err == nil && iif != nil {
			return iif, "nm-finder", nil
		}
		// 回退：直接扫 NetworkInterfaces 缓存（finder 与 cache 偶发不同步）
		for _, ni := range m.wrapper.networkManager.NetworkInterfaces() {
			if ni.Index == idx || (interfaceName != "" && ni.Name == interfaceName) {
				iface := ni.Interface
				return &iface, "nm-cache", nil
			}
		}
	}

	if nif, err := net.InterfaceByIndex(idx); err == nil && nif != nil {
		return &control.Interface{
			Index:        nif.Index,
			MTU:          nif.MTU,
			Name:         nif.Name,
			HardwareAddr: nif.HardwareAddr,
			Flags:        nif.Flags,
		}, "net-by-index", nil
	}

	// 最后用 Kotlin 上报的 name+index 构造，保证 DefaultInterface() 非 nil 且能通知回调。
	// 官方 notifyInterfaceUpdate 若 NetworkInterfaces 缓存无此 index 会当 race 跳过
	// ResetNetwork；因此应尽量让 nm-finder/nm-cache 命中。
	if interfaceName != "" && idx > 0 {
		return &control.Interface{
			Index: idx,
			Name:  interfaceName,
			Flags: net.FlagUp | net.FlagRunning,
		}, "kotlin-fallback", nil
	}

	return nil, "", E.New("interface not found: ", interfaceName, "#", idx)
}

// UpdateDefaultInterface 对齐官方 libbox monitor.go updateDefaultInterface。
func (m *interfaceMonitor) UpdateDefaultInterface(interfaceName string, interfaceIndex int32) {
	totalStarted := time.Now()
	m.wrapper.urlTestTrace("interface-update", "begin value=%s#%d", interfaceName, interfaceIndex)
	// 官方：先刷新平台接口列表
	// 诊断：计时 —— 每次事件（含 onCapabilitiesChanged 风暴）都会触发，
	// 且 UpdateInterfaces 经 JNI 回调 Kotlin getInterfaces 全量枚举网卡。
	var updateElapsed time.Duration
	if m.wrapper.networkManager != nil {
		start := time.Now()
		if err := m.wrapper.networkManager.UpdateInterfaces(); err != nil {
			m.logger.Error(E.Cause(err, "update interfaces"))
			m.wrapper.urlTestTrace("interface-update", "refresh failed elapsed=%s error=%v", time.Since(start), err)
		}
		updateElapsed = time.Since(start)
	}

	m.access.Lock()
	if interfaceIndex == -1 {
		oldDesc := "nil"
		if m.defaultInterface != nil {
			oldDesc = m.defaultInterface.Name + "#" + strconv.Itoa(m.defaultInterface.Index)
		}
		m.defaultInterface = nil
		m.defaultInterfaceInitialized = true
		callbacks := m.callbacks.Array()
		m.access.Unlock()
		m.logger.Info("default interface lost prev ", oldDesc, " callbacks ", len(callbacks))
		m.wrapper.urlTestTrace("interface-update", "lost prev=%s callbacks=%d totalElapsed=%s", oldDesc, len(callbacks), time.Since(totalStarted))
		// 官方：立即 callback(nil) → NetworkPause + "missing default interface"
		for _, callback := range callbacks {
			callback(nil, 0)
		}
		return
	}

	oldInterface := m.defaultInterface
	newInterface, source, err := m.resolveInterface(interfaceName, interfaceIndex)
	if err != nil {
		// 官方：ByIndex 失败只报错 return，保留旧 defaultInterface，不重试、不 clear
		m.access.Unlock()
		m.logger.Error(E.Cause(err, "find updated interface: ", interfaceName))
		m.wrapper.urlTestTrace("interface-update", "resolve failed value=%s#%d refreshElapsed=%s totalElapsed=%s error=%v", interfaceName, interfaceIndex, updateElapsed, time.Since(totalStarted), err)
		return
	}
	m.defaultInterface = newInterface
	m.defaultInterfaceInitialized = true

	// 官方：name+index 未变则不通知（不 ResetNetwork）
	if oldInterface != nil && oldInterface.Name == newInterface.Name && oldInterface.Index == newInterface.Index {
		m.access.Unlock()
		m.logger.Debug("default interface unchanged ", newInterface.Name,
			" index ", newInterface.Index, " source ", source,
			" skip ResetNetwork updateInterfaces ", updateElapsed)
		m.wrapper.urlTestTrace("interface-update", "unchanged value=%s#%d source=%s refreshElapsed=%s totalElapsed=%s", newInterface.Name, newInterface.Index, source, updateElapsed, time.Since(totalStarted))
		return
	}

	// 设置项 networkChangeResetConnections=false：只更新默认接口，不拆出站。
	// 例外：old==nil（曾 Lost/pause）必须通知以 NetworkWake，否则会一直暂停。
	if oldInterface != nil && !networkChangeResetConnections.Load() {
		m.access.Unlock()
		m.logger.Info("updated default interface ", newInterface.Name,
			" index ", newInterface.Index, " source ", source,
			" skip ResetNetwork (networkChangeResetConnections=false)")
		m.wrapper.urlTestTrace("interface-update", "applied value=%s#%d source=%s callbacks=0 reset=false refreshElapsed=%s totalElapsed=%s", newInterface.Name, newInterface.Index, source, updateElapsed, time.Since(totalStarted))
		return
	}

	callbacks := m.callbacks.Array()
	oldDesc := "nil"
	if oldInterface != nil {
		oldDesc = oldInterface.Name + "#" + strconv.Itoa(oldInterface.Index)
	}
	m.access.Unlock()

	m.logger.Info("updated default interface ", newInterface.Name,
		" index ", newInterface.Index, " source ", source, " prev ", oldDesc,
		" updateInterfaces ", updateElapsed)
	m.wrapper.urlTestTrace("interface-update", "applied value=%s#%d source=%s prev=%s callbacks=%d refreshElapsed=%s totalElapsed=%s", newInterface.Name, newInterface.Index, source, oldDesc, len(callbacks), updateElapsed, time.Since(totalStarted))
	for _, callback := range callbacks {
		callback(newInterface, 0)
	}
}
