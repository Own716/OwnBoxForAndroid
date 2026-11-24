package libcore

import (
	"context"
	"log"

	customDirect "libcore/protocol/direct"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	sbLog "github.com/sagernet/sing-box/log"

	mDNS "github.com/miekg/dns"
)

// SetDirectNetworkIPv6Available notifies libcore whether the default physical network
// currently has global unicast IPv6 connectivity. When the state flips, it purges the
// main BoxInstance's DNS cache so cached AAAA/A answers adapt without restarting VPN.
func SetDirectNetworkIPv6Available(available bool) {
	if customDirect.SetNetworkIPv6Available(available) {
		log.Println("SetDirectNetworkIPv6Available: state flipped ->", available, "clearing main DNS cache")
		clearMainInstanceDNSCache()
	}
}

// SetDirectIPv6Policy synchronizes the active IPv6Mode and direct DNS domain strategy
// into the Go core for both dns-direct query filtering and direct outbound IPv4 fallback.
func SetDirectIPv6Policy(ipv6Mode int32, directDomainStrategy string) {
	customDirect.SetPolicy(ipv6Mode, directDomainStrategy)
}

func clearMainInstanceDNSCache() {
	b := mainInstance
	if b != nil && b.dnsRouter != nil {
		b.dnsRouter.ClearCache()
	}
}

type directIPv6GuardTransport struct {
	adapter.DNSTransport
}

func newDirectIPv6GuardTransport(inner adapter.DNSTransport) adapter.DNSTransport {
	return &directIPv6GuardTransport{DNSTransport: inner}
}

func (t *directIPv6GuardTransport) suppressedResponse(message *mDNS.Msg) *mDNS.Msg {
	if message == nil || len(message.Question) == 0 {
		return nil
	}
	q := message.Question[0]
	if q.Qtype == mDNS.TypeAAAA && customDirect.ShouldSuppressDirectAAAA() {
		return dns.FixedResponse(message.Id, q, nil, 5)
	}
	if q.Qtype == mDNS.TypeA && customDirect.ShouldSuppressDirectA() {
		return dns.FixedResponse(message.Id, q, nil, 5)
	}
	return nil
}

func (t *directIPv6GuardTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if resp := t.suppressedResponse(message); resp != nil {
		return resp, nil
	}
	return t.DNSTransport.Exchange(ctx, message)
}

func (t *directIPv6GuardTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	if resp := t.suppressedResponse(message); resp != nil {
		callback(resp, nil)
		return
	}
	t.DNSTransport.ExchangeAsync(ctx, message, callback)
}

func registerDirectGuardedTransport[Options any](
	registry *dns.TransportRegistry,
	transportType string,
	base dns.TransportConstructorFunc[Options],
) {
	dns.RegisterTransport[Options](registry, transportType, func(ctx context.Context, logger sbLog.ContextLogger, tag string, options Options) (adapter.DNSTransport, error) {
		inner, err := base(ctx, logger, tag, options)
		if err != nil {
			return nil, err
		}
		if tag == "dns-direct" {
			return newDirectIPv6GuardTransport(inner), nil
		}
		return inner, nil
	})
}
