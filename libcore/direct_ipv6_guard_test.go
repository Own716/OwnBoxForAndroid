package libcore

import (
	"context"
	"errors"
	"net/netip"
	"syscall"
	"testing"

	customDirect "libcore/protocol/direct"

	"github.com/sagernet/sing-box/adapter"

	mDNS "github.com/miekg/dns"
)

type stubDNSTransport struct {
	tag       string
	exchanged int
}

func (s *stubDNSTransport) Start(stage adapter.StartStage, scope *adapter.Scope) error { return nil }
func (s *stubDNSTransport) Close() error                                             { return nil }
func (s *stubDNSTransport) Type() string                                             { return "udp" }
func (s *stubDNSTransport) Tag() string                                              { return s.tag }
func (s *stubDNSTransport) Dependencies() []string                                   { return nil }
func (s *stubDNSTransport) Reset()                                                   {}
func (s *stubDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	s.exchanged++
	return &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			Id:       message.Id,
			Response: true,
			Rcode:    mDNS.RcodeSuccess,
		},
		Question: message.Question,
	}, nil
}
func (s *stubDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	resp, err := s.Exchange(ctx, message)
	callback(resp, err)
}

func makeQuery(domain string, qtype uint16) *mDNS.Msg {
	return &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{Id: 42, RecursionDesired: true},
		Question: []mDNS.Question{
			{Name: mDNS.Fqdn(domain), Qtype: qtype, Qclass: mDNS.ClassINET},
		},
	}
}

func resetDirectIPv6TestState() {
	customDirect.SetPolicy(customDirect.IPv6ModeDisable, "")
	customDirect.SetNetworkIPv6Available(true)
}

func TestDirectIPv6PolicyMatrix(t *testing.T) {
	t.Cleanup(resetDirectIPv6TestState)

	cases := []struct {
		name           string
		mode           int32
		strategy       string
		hasPublicIPv6  bool
		wantSuppress6  bool
		wantSuppress4  bool
		wantCanFall4   bool
		wantPrefer4Now bool
	}{
		{"DISABLE_with_v6", customDirect.IPv6ModeDisable, "prefer_ipv4", true, true, false, false, false},
		{"DISABLE_without_v6", customDirect.IPv6ModeDisable, "ipv6_only", false, true, false, false, false},
		{"ENABLE_with_v6", customDirect.IPv6ModeEnable, "prefer_ipv4", true, false, false, true, false},
		{"ENABLE_without_v6", customDirect.IPv6ModeEnable, "prefer_ipv4", false, true, false, true, true},
		{"ENABLE_direct_ipv4_only", customDirect.IPv6ModeEnable, "ipv4_only", true, true, false, true, true},
		{"ENABLE_direct_ipv6_only", customDirect.IPv6ModeEnable, "ipv6_only", false, false, true, false, false},
		{"PREFER_with_v6", customDirect.IPv6ModePrefer, "prefer_ipv6", true, false, false, true, false},
		{"PREFER_without_v6", customDirect.IPv6ModePrefer, "prefer_ipv6", false, true, false, true, true},
		{"ONLY_with_v6", customDirect.IPv6ModeOnly, "ipv6_only", true, false, true, false, false},
		{"ONLY_without_v6_no_leak", customDirect.IPv6ModeOnly, "", false, false, true, false, false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			customDirect.SetPolicy(tc.mode, tc.strategy)
			customDirect.SetNetworkIPv6Available(tc.hasPublicIPv6)

			if got := customDirect.ShouldSuppressDirectAAAA(); got != tc.wantSuppress6 {
				t.Fatalf("ShouldSuppressDirectAAAA() = %v, want %v", got, tc.wantSuppress6)
			}
			if got := customDirect.ShouldSuppressDirectA(); got != tc.wantSuppress4 {
				t.Fatalf("ShouldSuppressDirectA() = %v, want %v", got, tc.wantSuppress4)
			}
			if got := customDirect.CanFallbackDirectIPv6(); got != tc.wantCanFall4 {
				t.Fatalf("CanFallbackDirectIPv6() = %v, want %v", got, tc.wantCanFall4)
			}
			if got := customDirect.ShouldPreferDirectIPv4Immediately(); got != tc.wantPrefer4Now {
				t.Fatalf("ShouldPreferDirectIPv4Immediately() = %v, want %v", got, tc.wantPrefer4Now)
			}
		})
	}
}

func TestDirectIPv6GuardTransportDynamicToggle(t *testing.T) {
	t.Cleanup(resetDirectIPv6TestState)

	inner := &stubDNSTransport{tag: "dns-direct"}
	guard := newDirectIPv6GuardTransport(inner)
	ctx := context.Background()

	// 1. ENABLE + no public IPv6 -> AAAA suppressed immediately without hitting upstream, A passes through
	customDirect.SetPolicy(customDirect.IPv6ModeEnable, "prefer_ipv4")
	customDirect.SetNetworkIPv6Available(false)

	resp6, err := guard.Exchange(ctx, makeQuery("m.baidu.com", mDNS.TypeAAAA))
	if err != nil {
		t.Fatalf("Exchange AAAA failed: %v", err)
	}
	if resp6.Rcode != mDNS.RcodeSuccess || len(resp6.Answer) != 0 {
		t.Fatalf("expected empty NOERROR for AAAA, got rcode=%d answers=%d", resp6.Rcode, len(resp6.Answer))
	}
	if inner.exchanged != 0 {
		t.Fatalf("expected 0 upstream exchanges for suppressed AAAA, got %d", inner.exchanged)
	}

	_, err = guard.Exchange(ctx, makeQuery("m.baidu.com", mDNS.TypeA))
	if err != nil {
		t.Fatalf("Exchange A failed: %v", err)
	}
	if inner.exchanged != 1 {
		t.Fatalf("expected A query to reach upstream once, got %d", inner.exchanged)
	}

	// 2. Hand over to 5G (public IPv6 available) -> AAAA now passes through
	customDirect.SetNetworkIPv6Available(true)
	_, err = guard.Exchange(ctx, makeQuery("m.baidu.com", mDNS.TypeAAAA))
	if err != nil {
		t.Fatalf("Exchange AAAA on IPv6 net failed: %v", err)
	}
	if inner.exchanged != 2 {
		t.Fatalf("expected AAAA to reach upstream when IPv6 available, got %d", inner.exchanged)
	}

	// 3. ExchangeAsync respects dynamic suppression too
	customDirect.SetPolicy(customDirect.IPv6ModePrefer, "prefer_ipv6")
	customDirect.SetNetworkIPv6Available(false)
	var asyncResp *mDNS.Msg
	var asyncErr error
	guard.ExchangeAsync(ctx, makeQuery("zhihu.com", mDNS.TypeAAAA), func(r *mDNS.Msg, e error) {
		asyncResp, asyncErr = r, e
	})
	if asyncErr != nil || asyncResp == nil || asyncResp.Rcode != mDNS.RcodeSuccess || len(asyncResp.Answer) != 0 {
		t.Fatalf("expected empty NOERROR on ExchangeAsync, got resp=%v err=%v", asyncResp, asyncErr)
	}
	if inner.exchanged != 2 {
		t.Fatalf("expected suppressed ExchangeAsync not to hit upstream, got %d", inner.exchanged)
	}

	// 4. ONLY mode NEVER suppresses AAAA even if physical IPv6 is absent
	customDirect.SetPolicy(customDirect.IPv6ModeOnly, "ipv6_only")
	customDirect.SetNetworkIPv6Available(false)
	_, err = guard.Exchange(ctx, makeQuery("ipv6.test-ipv6.com", mDNS.TypeAAAA))
	if err != nil {
		t.Fatalf("Exchange AAAA in ONLY mode failed: %v", err)
	}
	if inner.exchanged != 3 {
		t.Fatalf("expected AAAA in ONLY mode to hit upstream, got %d", inner.exchanged)
	}
}

func TestDirectIPv6AddressAndErrorHelpers(t *testing.T) {
	pubAddrs := []string{
		"240e:940:602:daa:0:ff:b065:c07a",
		"2409:8a00:1234::1",
		"2001:4860:4860::8888",
	}
	for _, s := range pubAddrs {
		addr := netip.MustParseAddr(s)
		if !customDirect.IsPublicIPv6(addr) {
			t.Fatalf("expected %s to be recognized as public IPv6", s)
		}
	}

	nonPubAddrs := []string{
		"fe80::1",
		"fc00::1",
		"fd12:3456:789a::1",
		"::1",
		"ff02::1",
		"124.237.178.246",
		"::ffff:124.237.178.246",
	}
	for _, s := range nonPubAddrs {
		addr := netip.MustParseAddr(s)
		if customDirect.IsPublicIPv6(addr) {
			t.Fatalf("expected %s NOT to be treated as public IPv6", s)
		}
	}

	if !customDirect.IsUnreachableIPv6Error(syscall.ENETUNREACH) {
		t.Fatal("expected ENETUNREACH to match IsUnreachableIPv6Error")
	}
	if !customDirect.IsUnreachableIPv6Error(syscall.EHOSTUNREACH) {
		t.Fatal("expected EHOSTUNREACH to match IsUnreachableIPv6Error")
	}
	if !customDirect.IsUnreachableIPv6Error(errors.New("dial tcp [240e:940::1]:443: connect: network is unreachable")) {
		t.Fatal("expected string 'network is unreachable' to match IsUnreachableIPv6Error")
	}
	if customDirect.IsUnreachableIPv6Error(errors.New("connection refused")) {
		t.Fatal("did not expect connection refused to match IsUnreachableIPv6Error")
	}
	if customDirect.IsUnreachableIPv6Error(nil) {
		t.Fatal("did not expect nil error to match IsUnreachableIPv6Error")
	}
}
