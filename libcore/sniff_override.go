package libcore

import (
	"context"
	"io"
	customUrltest "libcore/protocol/urltest"
	"net"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	M "github.com/sagernet/sing/common/metadata"
)

type sniffOverrideOutboundRegistry struct {
	*outbound.Registry
}

func wrapOutboundRegistry(inner *outbound.Registry) adapter.OutboundRegistry {
	return &sniffOverrideOutboundRegistry{Registry: inner}
}

func (r *sniffOverrideOutboundRegistry) CreateOutbound(
	ctx context.Context,
	router adapter.Router,
	logger log.ContextLogger,
	tag string,
	outboundType string,
	options any,
) (adapter.Outbound, error) {
	ob, err := r.Registry.CreateOutbound(ctx, router, logger, tag, outboundType, options)
	if err != nil || ob == nil {
		return ob, err
	}
	switch outboundType {
	case C.TypeDirect, C.TypeBlock, C.TypeDNS, C.TypeSelector, C.TypeURLTest, "autoselect", "loadbalance", C.TypeWireGuard:
		return ob, nil
	}
	if _, isGroup := ob.(adapter.OutboundGroup); isGroup {
		return ob, nil
	}
	if _, isHandler := ob.(adapter.ConnectionHandler); isHandler {
		return ob, nil
	}
	return &sniffOverrideOutbound{Outbound: ob}, nil
}

// sniffOverrideOutbound upgrades poisoned literal destination IPs to the sniffed
// TLS/HTTP/QUIC domain on single-node and custom-chain leaf outbounds as well.
type sniffOverrideOutbound struct {
	adapter.Outbound
}

func (w *sniffOverrideOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	dest := customUrltest.RewriteSniffedDestination(ctx, destination)
	return w.Outbound.DialContext(ctx, network, dest)
}

func (w *sniffOverrideOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	dest := customUrltest.RewriteSniffedDestination(ctx, destination)
	conn, err := w.Outbound.ListenPacket(ctx, dest)
	if err != nil && dest != destination && strings.Contains(err.Error(), "domain destination is not supported") {
		return w.Outbound.ListenPacket(ctx, destination)
	}
	return conn, err
}

func (w *sniffOverrideOutbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if lc, ok := w.Outbound.(adapter.Lifecycle); ok {
		return lc.Start(stage, scope)
	}
	return nil
}

func (w *sniffOverrideOutbound) Close() error {
	if cl, ok := w.Outbound.(io.Closer); ok {
		return cl.Close()
	}
	return nil
}

func (w *sniffOverrideOutbound) InterfaceUpdated(ctx context.Context) {
	if iu, ok := w.Outbound.(adapter.InterfaceUpdateListener); ok {
		iu.InterfaceUpdated(ctx)
	}
}

func (w *sniffOverrideOutbound) References() ([]string, []string) {
	if rf, ok := w.Outbound.(adapter.Referrer); ok {
		return rf.References()
	}
	return nil, nil
}

func (w *sniffOverrideOutbound) MultiplexEnabled() bool {
	if m, ok := w.Outbound.(adapter.OutboundWithMultiplex); ok {
		return m.MultiplexEnabled()
	}
	return false
}

func (w *sniffOverrideOutbound) SetKeepIdleConnections(keep bool) {
	if k, ok := w.Outbound.(adapter.IdleConnectionKeeper); ok {
		k.SetKeepIdleConnections(keep)
	}
}

func (w *sniffOverrideOutbound) CloseIdleConnections() {
	if k, ok := w.Outbound.(adapter.IdleConnectionKeeper); ok {
		k.CloseIdleConnections()
	}
}

func (w *sniffOverrideOutbound) Upstream() any {
	return w.Outbound
}

func configHasSniffRule(options *option.Options) bool {
	if options == nil || options.Route == nil {
		return false
	}
	for _, rule := range options.Route.Rules {
		switch rule.Type {
		case "", C.RuleTypeDefault:
			if rule.DefaultOptions.Action == C.RuleActionTypeSniff {
				return true
			}
		case C.RuleTypeLogical:
			if rule.LogicalOptions.Action == C.RuleActionTypeSniff {
				return true
			}
		}
	}
	return false
}

var _ adapter.Lifecycle = (*sniffOverrideOutbound)(nil)
var _ adapter.InterfaceUpdateListener = (*sniffOverrideOutbound)(nil)
var _ adapter.Referrer = (*sniffOverrideOutbound)(nil)
var _ adapter.OutboundWithMultiplex = (*sniffOverrideOutbound)(nil)
var _ adapter.IdleConnectionKeeper = (*sniffOverrideOutbound)(nil)
var _ common.WithUpstream = (*sniffOverrideOutbound)(nil)
