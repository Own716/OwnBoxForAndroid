package mieru

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	mieruclient "github.com/enfein/mieru/v3/apis/client"
	mierucommon "github.com/enfein/mieru/v3/apis/common"
	mierumodel "github.com/enfein/mieru/v3/apis/model"
	mierutp "github.com/enfein/mieru/v3/apis/trafficpattern"
	mierupb "github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"google.golang.org/protobuf/proto"
)

const TypeMieru = "mieru"

type MieruOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	ServerPorts    []string `json:"server_ports,omitempty"`
	PortRange      string   `json:"port_range,omitempty"`
	Transport      string   `json:"transport,omitempty"`
	Username       string   `json:"username,omitempty"`
	Password       string   `json:"password,omitempty"`
	MTU            int      `json:"mtu,omitempty"`
	Multiplexing   string   `json:"multiplexing,omitempty"`
	HandshakeMode  string   `json:"handshake_mode,omitempty"`
	TrafficPattern string   `json:"traffic_pattern,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[MieruOutboundOptions](registry, TypeMieru, NewOutbound)
}

var (
	_ adapter.Outbound                = (*Outbound)(nil)
	_ adapter.InterfaceUpdateListener = (*Outbound)(nil)
)

type Outbound struct {
	outbound.Adapter
	ctx       context.Context
	logger    logger.ContextLogger
	dnsRouter adapter.DNSRouter
	dialer    N.Dialer
	options   MieruOutboundOptions

	mu     sync.Mutex
	client mieruclient.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options MieruOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: options.ServerIsDomain(),
	})
	if err != nil {
		return nil, err
	}
	o := &Outbound{
		Adapter:   outbound.NewAdapterWithDialerOptions(TypeMieru, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		ctx:       ctx,
		logger:    logger,
		dnsRouter: service.FromContext[adapter.DNSRouter](ctx),
		dialer:    outboundDialer,
		options:   options,
	}
	client, err := o.newConfiguredClient()
	if err != nil {
		return nil, err
	}
	o.client = client
	return o, nil
}

func (o *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	scope.Add(func() error {
		return o.resetClient()
	})
	return nil
}

func (o *Outbound) InterfaceUpdated(ctx context.Context) {
	_ = o.resetClient()
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		ctx, metadata := adapter.ExtendContext(ctx)
		metadata.Outbound = o.Tag()
		metadata.Destination = destination
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		client, err := o.ensureClient()
		if err != nil {
			return nil, err
		}
		return client.DialContext(ctx, toMieruNetAddrSpec("tcp", destination))
	case N.NetworkUDP:
		conn, err := o.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(conn, destination), nil
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	o.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	client, err := o.ensureClient()
	if err != nil {
		return nil, err
	}
	streamConn, err := client.DialContext(ctx, toMieruNetAddrSpec("udp", destination))
	if err != nil {
		return nil, err
	}
	wrapper := mierucommon.NewUDPAssociateWrapper(
		mierucommon.NewPacketOverStreamTunnel(streamConn),
		mierucommon.UDPAssociateReadFromFQDN{},
	)
	return &mieruUDPConn{UDPAssociateWrapper: wrapper}, nil
}

func (o *Outbound) ensureClient() (mieruclient.Client, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.client != nil && o.client.IsRunning() {
		return o.client, nil
	}
	if o.client == nil {
		client, err := o.newConfiguredClient()
		if err != nil {
			return nil, err
		}
		o.client = client
	}
	if err := o.client.Start(); err != nil {
		client := o.client
		o.client = nil
		if client != nil {
			_ = client.Stop()
		}
		return nil, E.Cause(err, "start mieru client")
	}
	return o.client, nil
}

func (o *Outbound) resetClient() error {
	o.mu.Lock()
	client := o.client
	o.client = nil
	o.mu.Unlock()
	if client != nil {
		return client.Stop()
	}
	return nil
}

func (o *Outbound) newConfiguredClient() (mieruclient.Client, error) {
	cfg, err := o.buildClientConfig()
	if err != nil {
		return nil, err
	}
	client := mieruclient.NewClient()
	if err := client.Store(cfg); err != nil {
		return nil, E.Cause(err, "store mieru client config")
	}
	return client, nil
}

func (o *Outbound) buildClientConfig() (*mieruclient.ClientConfig, error) {
	if strings.TrimSpace(o.options.Server) == "" {
		return nil, E.New("mieru server is empty")
	}
	if o.options.Username == "" {
		return nil, E.New("mieru username is empty")
	}
	if o.options.Password == "" {
		return nil, E.New("mieru password is empty")
	}

	transport := strings.ToUpper(strings.TrimSpace(o.options.Transport))
	if transport == "" {
		transport = "TCP"
	}
	var transportProto *mierupb.TransportProtocol
	switch transport {
	case "TCP":
		transportProto = mierupb.TransportProtocol_TCP.Enum()
	case "UDP":
		transportProto = mierupb.TransportProtocol_UDP.Enum()
	default:
		return nil, E.New("unsupported mieru transport: ", o.options.Transport)
	}

	portBindings, err := parsePortBindings(o.options, transportProto)
	if err != nil {
		return nil, err
	}

	serverEndpoint := &mierupb.ServerEndpoint{
		PortBindings: portBindings,
	}
	server := strings.TrimSpace(o.options.Server)
	if net.ParseIP(server) != nil {
		serverEndpoint.IpAddress = proto.String(server)
	} else {
		serverEndpoint.DomainName = proto.String(server)
	}

	profileName := o.Tag()
	if profileName == "" {
		profileName = "default"
	}
	profile := &mierupb.ClientProfile{
		ProfileName: proto.String(profileName),
		User: &mierupb.User{
			Name:     proto.String(o.options.Username),
			Password: proto.String(o.options.Password),
		},
		Servers: []*mierupb.ServerEndpoint{serverEndpoint},
	}

	if transport == "UDP" && o.options.MTU >= 1280 && o.options.MTU <= 1500 {
		profile.Mtu = proto.Int32(int32(o.options.MTU))
	}

	if muxRaw := strings.ToUpper(strings.TrimSpace(o.options.Multiplexing)); muxRaw != "" {
		if !strings.HasPrefix(muxRaw, "MULTIPLEXING_") {
			muxRaw = "MULTIPLEXING_" + muxRaw
		}
		v, ok := mierupb.MultiplexingLevel_value[muxRaw]
		if !ok {
			return nil, E.New("invalid mieru multiplexing: ", o.options.Multiplexing)
		}
		level := mierupb.MultiplexingLevel(v)
		profile.Multiplexing = &mierupb.MultiplexingConfig{Level: &level}
	}

	if hsRaw := strings.ToUpper(strings.TrimSpace(o.options.HandshakeMode)); hsRaw != "" {
		if !strings.HasPrefix(hsRaw, "HANDSHAKE_") {
			hsRaw = "HANDSHAKE_" + hsRaw
		}
		v, ok := mierupb.HandshakeMode_value[hsRaw]
		if !ok {
			return nil, E.New("invalid mieru handshake_mode: ", o.options.HandshakeMode)
		}
		mode := mierupb.HandshakeMode(v)
		profile.HandshakeMode = &mode
	}

	if tpRaw := strings.TrimSpace(o.options.TrafficPattern); tpRaw != "" {
		tp, err := mierutp.Decode(tpRaw)
		if err != nil {
			return nil, E.Cause(err, "decode mieru traffic_pattern")
		}
		if err := mierutp.Validate(tp); err != nil {
			return nil, E.Cause(err, "validate mieru traffic_pattern")
		}
		profile.TrafficPattern = tp
	}

	return &mieruclient.ClientConfig{
		Profile: profile,
		Dialer: &mieruDialerAdapter{
			dialer: o.dialer,
		},
		PacketDialer: &mieruPacketDialerAdapter{
			dialer: o.dialer,
		},
		Resolver: &mieruDNSResolverAdapter{
			dnsRouter: o.dnsRouter,
		},
		DNSConfig: &mierucommon.ClientDNSConfig{
			BypassDialerDNS: true,
		},
	}, nil
}

func parsePortBindings(options MieruOutboundOptions, transportProto *mierupb.TransportProtocol) ([]*mierupb.PortBinding, error) {
	var bindings []*mierupb.PortBinding
	appendEntry := func(raw string) error {
		for _, part := range strings.Split(raw, ",") {
			token := strings.TrimSpace(part)
			if token == "" {
				continue
			}
			token = strings.ReplaceAll(token, ":", "-")
			if strings.Contains(token, "-") {
				pieces := strings.Split(token, "-")
				if len(pieces) != 2 {
					return E.New("invalid mieru port_range: ", part)
				}
				begin, err1 := strconv.Atoi(strings.TrimSpace(pieces[0]))
				end, err2 := strconv.Atoi(strings.TrimSpace(pieces[1]))
				if err1 != nil || err2 != nil || begin < 1 || end > 65535 || begin > end {
					return E.New("invalid mieru port_range: ", part)
				}
				if begin == end {
					bindings = append(bindings, &mierupb.PortBinding{
						Port:     proto.Int32(int32(begin)),
						Protocol: transportProto,
					})
				} else {
					bindings = append(bindings, &mierupb.PortBinding{
						PortRange: proto.String(fmt.Sprintf("%d-%d", begin, end)),
						Protocol:  transportProto,
					})
				}
			} else {
				p, err := strconv.Atoi(token)
				if err != nil || p < 1 || p > 65535 {
					return E.New("invalid mieru port: ", part)
				}
				bindings = append(bindings, &mierupb.PortBinding{
					Port:     proto.Int32(int32(p)),
					Protocol: transportProto,
				})
			}
		}
		return nil
	}

	if options.PortRange != "" {
		if err := appendEntry(options.PortRange); err != nil {
			return nil, err
		}
	}
	for _, entry := range options.ServerPorts {
		if err := appendEntry(entry); err != nil {
			return nil, err
		}
	}
	if len(bindings) == 0 && options.ServerPort > 0 {
		bindings = append(bindings, &mierupb.PortBinding{
			Port:     proto.Int32(int32(options.ServerPort)),
			Protocol: transportProto,
		})
	}
	if len(bindings) == 0 {
		return nil, E.New("mieru server_port or port_range is required")
	}
	return bindings, nil
}

type mieruDialerAdapter struct {
	dialer N.Dialer
}

func (d *mieruDialerAdapter) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return d.dialer.DialContext(ctx, N.NetworkName(network), M.ParseSocksaddr(address))
}

type mieruPacketDialerAdapter struct {
	dialer N.Dialer
}

func (d *mieruPacketDialerAdapter) ListenPacket(ctx context.Context, network, laddr, raddr string) (net.PacketConn, error) {
	pc, err := d.dialer.ListenPacket(ctx, M.ParseSocksaddr(raddr))
	if err != nil {
		return nil, err
	}
	return &mieruUnderlyingPacketConn{PacketConn: pc}, nil
}

type mieruUnderlyingPacketConn struct {
	net.PacketConn
}

func (p *mieruUnderlyingPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, addr, err := p.PacketConn.ReadFrom(b)
	if err != nil || addr == nil {
		return n, addr, err
	}
	if u, ok := addr.(*net.UDPAddr); ok {
		return n, u, nil
	}
	if ap, parseErr := netip.ParseAddrPort(addr.String()); parseErr == nil {
		return n, net.UDPAddrFromAddrPort(ap), nil
	}
	return n, addr, nil
}

type mieruDNSResolverAdapter struct {
	dnsRouter adapter.DNSRouter
}

func (r *mieruDNSResolverAdapter) LookupIP(ctx context.Context, network, host string) ([]net.IP, error) {
	if r.dnsRouter == nil {
		return net.DefaultResolver.LookupIP(ctx, network, host)
	}
	strategy := C.DomainStrategyAsIS
	switch network {
	case "ip4":
		strategy = C.DomainStrategyIPv4Only
	case "ip6":
		strategy = C.DomainStrategyIPv6Only
	}
	addrs, err := r.dnsRouter.Lookup(ctx, host, adapter.DNSQueryOptions{
		Strategy: strategy,
	})
	if err != nil {
		return nil, err
	}
	ips := make([]net.IP, 0, len(addrs))
	for _, addr := range addrs {
		unmapped := addr.Unmap()
		if unmapped.IsValid() {
			ips = append(ips, net.IP(unmapped.AsSlice()))
		}
	}
	return ips, nil
}

type mieruUDPConn struct {
	*mierucommon.UDPAssociateWrapper
	writeMu sync.Mutex
}

func (u *mieruUDPConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if addr == nil {
		return 0, os.ErrInvalid
	}
	u.writeMu.Lock()
	defer u.writeMu.Unlock()
	var spec mierumodel.NetAddrSpec
	if s, ok := addr.(M.Socksaddr); ok {
		spec = toMieruNetAddrSpec("udp", s)
	} else {
		if err := spec.From(addr); err != nil {
			return 0, err
		}
		spec.Net = "udp"
	}
	return u.UDPAssociateWrapper.WriteTo(p, spec)
}

func (u *mieruUDPConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := u.UDPAssociateWrapper.ReadFrom(p)
	if err != nil || addr == nil {
		return n, addr, err
	}
	if nas, ok := addr.(mierumodel.NetAddrSpec); ok {
		if len(nas.IP) > 0 {
			return n, &net.UDPAddr{IP: nas.IP, Port: nas.Port}, nil
		}
		if nas.FQDN != "" {
			return n, M.ParseSocksaddrHostPort(nas.FQDN, uint16(nas.Port)), nil
		}
	}
	return n, addr, nil
}

func toMieruNetAddrSpec(network string, destination M.Socksaddr) mierumodel.NetAddrSpec {
	spec := mierumodel.NetAddrSpec{
		Net: network,
	}
	if destination.IsFqdn() {
		spec.AddrSpec = mierumodel.AddrSpec{
			FQDN: destination.Fqdn,
			Port: int(destination.Port),
		}
	} else {
		spec.AddrSpec = mierumodel.AddrSpec{
			IP:   net.IP(destination.Addr.Unmap().AsSlice()),
			Port: int(destination.Port),
		}
	}
	return spec
}
