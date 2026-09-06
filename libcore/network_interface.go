package libcore

import (
	C "github.com/sagernet/sing-box/constant"
)

// 网络接口类型常量（gomobile 导出，Kotlin 侧 getInterfaces 使用）。
const (
	InterfaceTypeWIFI     = int32(C.InterfaceTypeWIFI)
	InterfaceTypeCellular = int32(C.InterfaceTypeCellular)
	InterfaceTypeEthernet = int32(C.InterfaceTypeEthernet)
	InterfaceTypeOther    = int32(C.InterfaceTypeOther)
)

// NetworkInterface 是 gomobile 桥接结构（Kotlin 侧构造，参考 husi libcore/tun.go）。
type NetworkInterface struct {
	Index     int32
	MTU       int32
	Name      string
	Addresses StringIterator
	Flags     int32

	Type      int32
	DNSServer StringIterator
	Metered   bool
}

type NetworkInterfaceIterator interface {
	Next() *NetworkInterface
	HasNext() bool
	Length() int32
}
