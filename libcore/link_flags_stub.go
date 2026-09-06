//go:build !unix

package libcore

import "net"

func linkFlags(rawFlags uint32) net.Flags {
	return 0
}
