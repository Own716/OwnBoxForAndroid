package urltest

import (
	"context"
	"net/netip"
	"strings"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

var sniffOverrideEnabled atomic.Bool

func init() {
	sniffOverrideEnabled.Store(true)
}

// SetSniffOverrideEnabled controls whether outbound dials may upgrade literal
// destination IPs to a sniffed domain when FakeIP or pre-VPN DNS poisoning occurs.
func SetSniffOverrideEnabled(enabled bool) {
	sniffOverrideEnabled.Store(enabled)
}

var (
	fakeIP4Prefix = netip.MustParsePrefix("198.18.0.0/15")
	fakeIP6Prefix = netip.MustParsePrefix("fc00::/18")
	telegramCIDRs = []netip.Prefix{
		netip.MustParsePrefix("91.105.192.0/23"),
		netip.MustParsePrefix("91.108.4.0/22"),
		netip.MustParsePrefix("91.108.8.0/22"),
		netip.MustParsePrefix("91.108.12.0/22"),
		netip.MustParsePrefix("91.108.16.0/22"),
		netip.MustParsePrefix("91.108.20.0/22"),
		netip.MustParsePrefix("91.108.56.0/22"),
		netip.MustParsePrefix("95.161.64.0/20"),
		netip.MustParsePrefix("149.154.160.0/20"),
		netip.MustParsePrefix("185.76.151.0/24"),
		netip.MustParsePrefix("2001:67c:4e8::/48"),
		netip.MustParsePrefix("2001:b28:f23c::/46"),
		netip.MustParsePrefix("2001:b88::/32"),
		netip.MustParsePrefix("2a0a:f280::/32"),
	}
	carrierNatPrefix = netip.MustParsePrefix("100.64.0.0/10")
)

func isTelegramDCAddr(addr netip.Addr) bool {
	for _, prefix := range telegramCIDRs {
		if prefix.Contains(addr) {
			return true
		}
	}
	return false
}

// RewriteSniffedDestination upgrades literal external IP destinations to the sniffed
// TLS/HTTP/QUIC domain (or FakeIP target domain) so connections queued against poisoned
// pre-VPN DNS answers succeed immediately upon VPN startup without waiting for app retries.
func RewriteSniffedDestination(ctx context.Context, destination M.Socksaddr) M.Socksaddr {
	if !sniffOverrideEnabled.Load() || ctx == nil {
		return destination
	}
	if destination.IsFqdn() || !destination.Addr.IsValid() || destination.Port == 0 {
		return destination
	}
	addr := destination.Addr.Unmap()
	isFakeIP := fakeIP4Prefix.Contains(addr) || fakeIP6Prefix.Contains(addr)
	if !isFakeIP {
		if addr.IsLoopback() || addr.IsPrivate() || addr.IsLinkLocalUnicast() ||
			addr.IsLinkLocalMulticast() || addr.IsMulticast() || addr.IsUnspecified() ||
			carrierNatPrefix.Contains(addr) || isTelegramDCAddr(addr) {
			return destination
		}
	}

	meta := adapter.ContextFrom(ctx)
	if meta == nil {
		return destination
	}
	if !isFakeIP && len(meta.DestinationAddresses) > 0 {
		return destination
	}
	domain := strings.TrimSuffix(strings.TrimSpace(meta.Domain), ".")
	if domain == "" || !strings.Contains(domain, ".") {
		return destination
	}
	lower := strings.ToLower(domain)
	if strings.HasSuffix(lower, ".local") || strings.HasSuffix(lower, ".localhost") || strings.HasSuffix(lower, ".arpa") {
		return destination
	}
	if _, err := netip.ParseAddr(domain); err == nil {
		return destination
	}

	if isFakeIP || meta.Protocol == "tls" || meta.Protocol == "http" || meta.Protocol == "quic" {
		return M.Socksaddr{
			Fqdn: domain,
			Port: destination.Port,
		}
	}
	return destination
}
