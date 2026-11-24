package direct

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	sbDirect "github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const (
	IPv6ModeDisable int32 = 0
	IPv6ModeEnable  int32 = 1
	IPv6ModePrefer  int32 = 2
	IPv6ModeOnly    int32 = 3
)

var (
	policyIPv6Mode             atomic.Int32
	policyDirectDomainStrategy atomic.Value
	directNetworkHasPublicIPv6 atomic.Bool
)

func init() {
	// Default to true until Android reports the active physical LinkProperties,
	// so Go tests and desktop/CLI contexts keep dual-stack behavior intact.
	directNetworkHasPublicIPv6.Store(true)
	policyDirectDomainStrategy.Store("")
}

// SetNetworkIPv6Available updates whether the current default physical network
// has routable global unicast IPv6 connectivity and returns true if the state flipped.
func SetNetworkIPv6Available(available bool) bool {
	return directNetworkHasPublicIPv6.Swap(available) != available
}

// SetPolicy records the active IPv6Mode and direct DNS domain strategy from ConfigBuilder.
func SetPolicy(ipv6Mode int32, directDomainStrategy string) {
	policyIPv6Mode.Store(ipv6Mode)
	policyDirectDomainStrategy.Store(strings.ToLower(strings.TrimSpace(directDomainStrategy)))
}

// DirectDomainStrategy returns the normalized direct DNS domain strategy.
func DirectDomainStrategy() string {
	v, _ := policyDirectDomainStrategy.Load().(string)
	return v
}

// ShouldSuppressDirectAAAA reports whether dns-direct should suppress AAAA answers.
func ShouldSuppressDirectAAAA() bool {
	mode := policyIPv6Mode.Load()
	strat := DirectDomainStrategy()
	if mode == IPv6ModeOnly {
		return false
	}
	if mode == IPv6ModeDisable {
		return true
	}
	if strat == "ipv4_only" {
		return true
	}
	if strat == "ipv6_only" {
		return false
	}
	return !directNetworkHasPublicIPv6.Load()
}

// ShouldSuppressDirectA reports whether dns-direct should suppress A answers.
func ShouldSuppressDirectA() bool {
	mode := policyIPv6Mode.Load()
	strat := DirectDomainStrategy()
	if mode == IPv6ModeDisable {
		return false
	}
	if mode == IPv6ModeOnly {
		return true
	}
	return strat == "ipv6_only"
}

// CanFallbackDirectIPv6 reports whether direct/bypass connections may fall back from public IPv6 to IPv4.
func CanFallbackDirectIPv6() bool {
	mode := policyIPv6Mode.Load()
	strat := DirectDomainStrategy()
	return (mode == IPv6ModeEnable || mode == IPv6ModePrefer) && strat != "ipv6_only"
}

// ShouldPreferDirectIPv4Immediately reports whether public IPv6 direct dials should immediately switch to IPv4.
func ShouldPreferDirectIPv4Immediately() bool {
	if !CanFallbackDirectIPv6() {
		return false
	}
	return !directNetworkHasPublicIPv6.Load() || DirectDomainStrategy() == "ipv4_only"
}

// IsPublicIPv6 reports whether addr is a global unicast (2000::/3) non-private IPv6 address.
func IsPublicIPv6(addr netip.Addr) bool {
	return addr.IsValid() && addr.Is6() && !addr.Is4In6() && addr.IsGlobalUnicast() && !addr.IsPrivate()
}

// IsUnreachableIPv6Error reports whether err represents a routing/network unreachable failure.
func IsUnreachableIPv6Error(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, syscall.ENETUNREACH) || errors.Is(err, syscall.EHOSTUNREACH) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "network is unreachable") || strings.Contains(msg, "no route to host")
}

// RegisterOutbound overrides sing-box's native "direct" outbound so public IPv6 connections
// on IPv4-only networks fall back transparently to IPv4 using the sniffed domain.
func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.DirectOutboundOptions](registry, C.TypeDirect, NewOutbound)
}

type Outbound struct {
	*sbDirect.Outbound
	dnsRouter adapter.DNSRouter
	logger    logger.ContextLogger
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.DirectOutboundOptions) (adapter.Outbound, error) {
	inner, err := sbDirect.NewOutbound(ctx, router, logger, tag, options)
	if err != nil {
		return nil, err
	}
	base, ok := inner.(*sbDirect.Outbound)
	if !ok {
		return inner, nil
	}
	return &Outbound{
		Outbound:  base,
		dnsRouter: service.FromContext[adapter.DNSRouter](ctx),
		logger:    logger,
	}, nil
}

type directIPv6FallbackKey struct{}

func sniffedDomainFromContext(ctx context.Context) string {
	if ctx == nil {
		return ""
	}
	meta := adapter.ContextFrom(ctx)
	if meta == nil {
		return ""
	}
	domain := strings.TrimSuffix(strings.TrimSpace(meta.Domain), ".")
	if domain == "" {
		return ""
	}
	if _, err := netip.ParseAddr(domain); err == nil {
		return ""
	}
	return domain
}

func lookupIPv4(ctx context.Context, dnsRouter adapter.DNSRouter, domain string) []netip.Addr {
	if dnsRouter == nil || domain == "" {
		return nil
	}
	addrs, err := dnsRouter.Lookup(ctx, domain, adapter.DNSQueryOptions{
		Strategy: C.DomainStrategyIPv4Only,
	})
	if err != nil {
		return nil
	}
	result := make([]netip.Addr, 0, len(addrs))
	for _, addr := range addrs {
		unmapped := addr.Unmap()
		if unmapped.IsValid() && unmapped.Is4() {
			result = append(result, unmapped)
		}
	}
	return result
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) == N.NetworkTCP &&
		!destination.IsFqdn() &&
		IsPublicIPv6(destination.Addr) &&
		CanFallbackDirectIPv6() &&
		ctx.Value(directIPv6FallbackKey{}) == nil {
		domain := sniffedDomainFromContext(ctx)
		if domain != "" {
			fallbackCtx := context.WithValue(ctx, directIPv6FallbackKey{}, struct{}{})
			if ShouldPreferDirectIPv4Immediately() {
				ipv4Addrs := lookupIPv4(fallbackCtx, h.dnsRouter, domain)
				if len(ipv4Addrs) > 0 {
					h.logger.InfoContext(ctx, "direct IPv6 fallback -> IPv4 for ", domain, " (", ipv4Addrs[0], ")")
					return h.Outbound.DialParallel(fallbackCtx, network, M.Socksaddr{Fqdn: domain, Port: destination.Port}, ipv4Addrs)
				}
			} else {
				conn, err := h.Outbound.DialContext(ctx, network, destination)
				if err == nil || !IsUnreachableIPv6Error(err) {
					return conn, err
				}
				ipv4Addrs := lookupIPv4(fallbackCtx, h.dnsRouter, domain)
				if len(ipv4Addrs) > 0 {
					h.logger.InfoContext(ctx, "direct IPv6 unreachable, retrying IPv4 for ", domain, " (", ipv4Addrs[0], ")")
					return h.Outbound.DialParallel(fallbackCtx, network, M.Socksaddr{Fqdn: domain, Port: destination.Port}, ipv4Addrs)
				}
				return nil, err
			}
		}
	}
	return h.Outbound.DialContext(ctx, network, destination)
}
