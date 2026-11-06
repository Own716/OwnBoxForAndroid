package libcore

var intfBox BoxPlatformInterface
var intfNB4A NB4AInterface

var useProcfs bool
var isBgProcess bool

type NB4AInterface interface {
	UseOfficialAssets() bool
	Selector_OnProxySelected(selectorTag string, tag string)
}

type BoxPlatformInterface interface {
	AutoDetectInterfaceControl(fd int32) error
	OpenTun(singTunOptionsJson, tunPlatformOptionsJson string) (int, error)
	UseProcFS() bool
	FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error)
	PackageNameByUid(uid int32) (string, error)
	UIDByPackageName(packageName string) (int32, error)
	WIFIState() string
	// 默认接口监视器（官方内核强制平台提供，见 interface_monitor.go）
	StartDefaultInterfaceMonitor(listener InterfaceUpdateListener) error
	CloseDefaultInterfaceMonitor(listener InterfaceUpdateListener) error
	// 平台网络接口枚举（官方内核拨号路径强制要求，见 platform_box.go NetworkInterfaces）
	GetInterfaces() (NetworkInterfaceIterator, error)
}
