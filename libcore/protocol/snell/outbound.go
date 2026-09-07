package snell

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	snellprotocol "libcore/protocol/snell/internal/singsnell"
	"libcore/protocol/snell/internal/singsnell/snellv4"
	"libcore/protocol/snell/internal/singsnell/snellv6"
)

const TypeSnell = "snell"

type SnellOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	PSK      string             `json:"psk"`
	UserKey  string             `json:"userkey,omitempty"`
	Version  int                `json:"version,omitempty"`
	Network  option.NetworkList `json:"network,omitempty"`
	ObfsMode string             `json:"obfs_mode,omitempty"`
	ObfsHost string             `json:"obfs_host,omitempty"`
	Mode     string             `json:"mode,omitempty"`
	Reuse    bool               `json:"reuse,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[SnellOutboundOptions](registry, TypeSnell, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	logger     logger.ContextLogger
	dialer     N.Dialer
	client     snellClient
	serverAddr M.Socksaddr
	reuse      bool
}

var (
	_ adapter.InterfaceUpdateListener = (*Outbound)(nil)
	_ adapter.OutboundWithMultiplex   = (*Outbound)(nil)
)

type snellClient interface {
	snellprotocol.Method
	DialContext(ctx context.Context, destination M.Socksaddr) (net.Conn, error)
	Reset()
	SetKeepIdleConnections(keep bool)
	CloseIdleConnections()
	Close() error
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options SnellOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	serverAddr := options.ServerOptions.Build()
	var client snellClient
	version := options.Version
	if version == 0 {
		if options.Mode != "" && options.Mode != "default" {
			version = 6
		} else {
			version = 4
		}
	}
	switch version {
	case 1, 2, 3, 4:
		var obfsMode snellprotocol.ObfsMode
		obfsMode, err = snellprotocol.ParseObfsMode(options.ObfsMode)
		if err != nil {
			return nil, err
		}
		client, err = snellv4.NewClient(snellv4.ClientOptions{
			PSK:      []byte(options.PSK),
			UserKey:  []byte(options.UserKey),
			Reuse:    options.Reuse,
			ObfsMode: obfsMode,
			ObfsHost: options.ObfsHost,
			Dialer:   outboundDialer,
			Server:   serverAddr,
		})
	case 6:
		var mode snellv6.Mode
		mode, err = snellv6.ParseMode(options.Mode)
		if err != nil {
			return nil, err
		}
		client, err = snellv6.NewClient(snellv6.ClientOptions{
			PSK:     []byte(options.PSK),
			UserKey: []byte(options.UserKey),
			Mode:    mode,
			Reuse:   options.Reuse,
			Dialer:  outboundDialer,
			Server:  serverAddr,
		})
	default:
		return nil, E.New("snell: unsupported version: ", version)
	}
	if err != nil {
		return nil, err
	}
	outbound := &Outbound{
		Adapter:    outbound.NewAdapterWithDialerOptions(TypeSnell, tag, options.Network.Build(), options.DialerOptions),
		logger:     logger,
		dialer:     outboundDialer,
		client:     client,
		serverAddr: serverAddr,
		reuse:      options.Reuse,
	}
	return outbound, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	networkName := N.NetworkName(network)
	switch networkName {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
		return h.client.DialContext(ctx, destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
		conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
		if err != nil {
			return nil, err
		}
		packetConn, err := h.client.DialPacketConn(conn)
		if err != nil {
			conn.Close()
			return nil, err
		}
		return bufio.NewBindPacketConn(packetConn, destination), nil
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
	if err != nil {
		return nil, err
	}
	packetConn, err := h.client.DialPacketConn(conn)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return packetConn, nil
}

func (h *Outbound) InterfaceUpdated() {
	h.client.Reset()
}

func (h *Outbound) MultiplexEnabled() bool {
	return h.reuse
}

func (h *Outbound) SetKeepIdleConnections(keep bool) {
	h.client.SetKeepIdleConnections(keep)
}

func (h *Outbound) CloseIdleConnections() {
	h.client.CloseIdleConnections()
}

func (h *Outbound) Close() error {
	return h.client.Close()
}
