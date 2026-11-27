package urltest

import (
	"context"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

func TestRewriteSniffedDestination(t *testing.T) {
	SetSniffOverrideEnabled(true)

	withMeta := func(protocol, domain string, resolved ...netip.Addr) context.Context {
		meta := &adapter.InboundContext{
			Protocol:             protocol,
			Domain:               domain,
			DestinationAddresses: resolved,
		}
		return adapter.WithContext(context.Background(), meta)
	}

	// 1. FakeIP + sniffed domain -> always rewrite to domain.
	fakeDest := M.Socksaddr{Addr: netip.MustParseAddr("198.18.0.15"), Port: 443}
	got := RewriteSniffedDestination(withMeta("", "x.com"), fakeDest)
	if got.Fqdn != "x.com" || got.Port != 443 {
		t.Fatalf("expected FakeIP rewrite to x.com:443, got %+v", got)
	}

	// 2. Poisoned public IP + TLS/HTTP/QUIC sniffed domain -> rewrite to domain.
	publicDest := M.Socksaddr{Addr: netip.MustParseAddr("104.244.42.193"), Port: 443}
	got = RewriteSniffedDestination(withMeta("tls", "api.github.com"), publicDest)
	if got.Fqdn != "api.github.com" {
		t.Fatalf("expected TLS poisoned public IP rewrite to api.github.com, got %+v", got)
	}

	// 3. Explicit resolved destination addresses -> keep IP so resolve rule isn't overridden.
	got = RewriteSniffedDestination(withMeta("tls", "api.github.com", netip.MustParseAddr("140.82.121.6")), publicDest)
	if got != publicDest {
		t.Fatalf("expected explicit DestinationAddresses to preserve IP destination, got %+v", got)
	}

	// 4. Telegram DC literal IP (even if TLS-like) -> must stay literal IP.
	tgDest := M.Socksaddr{Addr: netip.MustParseAddr("149.154.167.50"), Port: 443}
	got = RewriteSniffedDestination(withMeta("tls", "venus.web.telegram.org"), tgDest)
	if got != tgDest {
		t.Fatalf("expected Telegram DC IP to remain literal IP, got %+v", got)
	}

	// 5. Private LAN IP -> must stay literal IP.
	lanDest := M.Socksaddr{Addr: netip.MustParseAddr("192.168.1.1"), Port: 443}
	got = RewriteSniffedDestination(withMeta("http", "router.local"), lanDest)
	if got != lanDest {
		t.Fatalf("expected private IP to remain literal IP, got %+v", got)
	}
}
