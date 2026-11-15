package autoselect

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	customUrltest "libcore/protocol/urltest"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/route"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

type routeTestOutboundMgr struct {
	mu        sync.RWMutex
	outbounds map[string]adapter.Outbound
}

func newRouteTestOutboundMgr(items ...adapter.Outbound) *routeTestOutboundMgr {
	m := &routeTestOutboundMgr{
		outbounds: make(map[string]adapter.Outbound, len(items)),
	}
	for _, it := range items {
		m.outbounds[it.Tag()] = it
	}
	return m
}

func (m *routeTestOutboundMgr) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	return nil
}
func (m *routeTestOutboundMgr) Close() error                         { return nil }
func (m *routeTestOutboundMgr) Outbounds() []adapter.Outbound {
	m.mu.RLock()
	defer m.mu.RUnlock()
	list := make([]adapter.Outbound, 0, len(m.outbounds))
	for _, it := range m.outbounds {
		list = append(list, it)
	}
	return list
}
func (m *routeTestOutboundMgr) Outbound(tag string) (adapter.Outbound, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	ob, ok := m.outbounds[tag]
	return ob, ok
}
func (m *routeTestOutboundMgr) Default() adapter.Outbound { return nil }
func (m *routeTestOutboundMgr) Remove(tag string) error   { return nil }
func (m *routeTestOutboundMgr) Create(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, outboundType string, options any) error {
	return nil
}

type routePipeOutbound struct {
	outbound.Adapter
	mu             sync.Mutex
	dialCount      atomic.Int64
	packetCount    atomic.Int64
	dialErr        error
	dropAfterWrite bool
	payload        []byte
}

func newRoutePipeOutbound(tag string, payload []byte) *routePipeOutbound {
	return &routePipeOutbound{
		Adapter: outbound.NewAdapter("mock", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
		payload: payload,
	}
}

func (m *routePipeOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	m.dialCount.Add(1)
	m.mu.Lock()
	dialErr := m.dialErr
	dropAfterWrite := m.dropAfterWrite
	payload := append([]byte(nil), m.payload...)
	m.mu.Unlock()
	if dialErr != nil {
		return nil, dialErr
	}
	clientSide, serverSide := net.Pipe()
	go func() {
		defer serverSide.Close()
		buf := make([]byte, 1024)
		_, _ = serverSide.Read(buf)
		if dropAfterWrite {
			return
		}
		time.Sleep(5 * time.Millisecond)
		if len(payload) > 0 {
			_, _ = serverSide.Write(payload)
		}
		_, _ = io.Copy(io.Discard, serverSide)
	}()
	return clientSide, nil
}

func (m *routePipeOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	m.packetCount.Add(1)
	clientConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	serverConn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		_ = clientConn.Close()
		return nil, err
	}
	payload := append([]byte(nil), m.payload...)
	go func() {
		defer serverConn.Close()
		buf := make([]byte, 2048)
		_ = serverConn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, peerAddr, readErr := serverConn.ReadFrom(buf)
		if readErr == nil && n > 0 && len(payload) > 0 {
			_, _ = serverConn.WriteTo(payload, peerAddr)
		}
	}()
	return &redirectPacketConn{
		PacketConn: clientConn,
		peerAddr:   serverConn.LocalAddr(),
	}, nil
}

type redirectPacketConn struct {
	net.PacketConn
	peerAddr net.Addr
}

func (c *redirectPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	return c.PacketConn.WriteTo(p, c.peerAddr)
}

func newTestAutoSelectWithRoute(t *testing.T, opts AutoSelectOptions, members ...adapter.Outbound) (*AutoSelect, *routeTestOutboundMgr) {
	t.Helper()
	ctx := context.Background()
	hist := urltest.NewHistoryStorage()
	logger := log.NewNOPFactory().NewLogger("autoselect-route-test")
	cm := route.NewConnectionManager(ctx, logger)
	mgr := newRouteTestOutboundMgr(members...)
	ctx = service.ContextWith[adapter.OutboundManager](ctx, mgr)
	ctx = service.ContextWith[adapter.ConnectionManager](ctx, cm)
	ctx = service.ContextWithPtr(ctx, hist)

	g, err := NewAutoSelect(ctx, nil, logger, "auto-proxy", opts)
	if err != nil {
		t.Fatalf("NewAutoSelect failed: %v", err)
	}
	mgr.mu.Lock()
	mgr.outbounds["auto-proxy"] = g
	mgr.mu.Unlock()
	if err := g.Start(adapter.StartStateStart, nil); err != nil {
		t.Fatalf("Start(StartStateStart) failed: %v", err)
	}
	return g, mgr
}

func TestAutoSelectNotOutboundGroupSoRouterInvokesHandlers(t *testing.T) {
	nodeA := newRoutePipeOutbound("node-a", []byte("OK-A"))
	nodeB := newRoutePipeOutbound("node-b", []byte("OK-B"))
	g, mgr := newTestAutoSelectWithRoute(t, AutoSelectOptions{
		Outbounds: []string{"node-a", "node-b"},
	}, nodeA, nodeB)
	defer g.Close()
	now := time.Now()
	g.scorer.recordSample("node-a", 40, nil, now)
	g.scorer.recordSample("node-b", 120, nil, now)
	g.scorer.evaluate(now, g.passive)

	// Critical invariant: sing-box Router.resolveOutbound unwraps adapter.OutboundGroup and bypasses
	// DialContext / NewConnection / passive learning / dial retries. AutoSelect must NOT implement it.
	var asAny any = g
	if _, isGroup := asAny.(adapter.OutboundGroup); isGroup {
		t.Fatalf("AutoSelect MUST NOT implement adapter.OutboundGroup")
	}
	if _, isURLTest := asAny.(adapter.URLTestGroup); isURLTest {
		t.Fatalf("AutoSelect MUST NOT implement adapter.URLTestGroup")
	}
	if got := customUrltest.ResolveOutboundRealTag(mgr, g, N.NetworkTCP); got != "node-a" {
		t.Fatalf("expected ResolveOutboundRealTag -> node-a, got %q", got)
	}
}

func TestRouteNewConnectionPassiveLearningSiteStickyAndDialRetries(t *testing.T) {
	nodeA := newRoutePipeOutbound("node-a", []byte("HTTP/1.1 200 OK\r\n\r\n"))
	nodeB := newRoutePipeOutbound("node-b", []byte("HTTP/1.1 200 OK\r\n\r\n"))
	learnTrue := true
	tol := uint16(30)

	g, _ := newTestAutoSelectWithRoute(t, AutoSelectOptions{
		Outbounds:            []string{"node-a", "node-b"},
		Tolerance:            &tol,
		LearnFromConnections: &learnTrue,
	}, nodeA, nodeB)
	defer g.Close()
	initNow := time.Now()
	g.scorer.recordSample("node-a", 40, nil, initNow)
	g.scorer.recordSample("node-b", 120, nil, initNow)
	g.scorer.evaluate(initNow, g.passive)

	// 1. Real routed TCP connection via NewConnection -> ConnectionManager -> AutoSelect.DialContext
	clientApp, clientRouter := net.Pipe()
	done := make(chan error, 1)
	meta := adapter.InboundContext{
		Domain:      "sub.example.com",
		Destination: M.ParseSocksaddrHostPort("93.184.216.34", 443),
	}
	go g.NewConnection(context.Background(), clientRouter, meta, func(err error) {
		done <- err
	})
	if _, err := clientApp.Write([]byte("GET / HTTP/1.1\r\n\r\n")); err != nil {
		t.Fatalf("client write failed: %v", err)
	}
	reply := make([]byte, 64)
	_ = clientApp.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := clientApp.Read(reply)
	if err != nil || n == 0 {
		t.Fatalf("expected reply through NewConnection, err=%v n=%d", err, n)
	}
	_ = clientApp.Close()

	if nodeA.dialCount.Load() != 1 {
		t.Fatalf("expected node-a dialCount=1, got %d", nodeA.dialCount.Load())
	}
	snap := g.GroupSnapshot()
	if snap.LearnedSiteCount != 1 {
		t.Fatalf("expected LearnedSiteCount=1 after real routed connection, got %d", snap.LearnedSiteCount)
	}
	if pref := g.passive.PreferredSiteNode("sub.example.com", time.Now()); pref != "node-a" {
		t.Fatalf("expected sub.example.com site memory -> node-a, got %q", pref)
	}

	// 2. Real unanswered blackhole: 1 unanswered drop on badsite.org triggers immediate per-site avoidance!
	nodeA.mu.Lock()
	nodeA.dropAfterWrite = true
	nodeA.mu.Unlock()

	cAppDrop, cRouterDrop := net.Pipe()
	dropClosed := make(chan struct{})
	go g.NewConnection(context.Background(), cRouterDrop, adapter.InboundContext{
		Domain:      "app.badsite.org",
		Destination: M.ParseSocksaddrHostPort("1.1.1.1", 443),
	}, func(error) {
		close(dropClosed)
	})
	_, _ = cAppDrop.Write([]byte("CLIENT HELLO"))
	select {
	case <-dropClosed:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for unanswered drop close on badsite.org")
	}
	_ = cAppDrop.Close()

	if !g.passive.ShouldAvoidSite("node-a", "badsite.org", time.Now()) {
		t.Fatalf("expected node-a to be marked avoid for badsite.org after unanswered connection")
	}

	// 3. Subsequent connection to cdn.badsite.org (same eTLD+1) routes via node-b and succeeds!
	cApp, cRouter := net.Pipe()
	go g.NewConnection(context.Background(), cRouter, adapter.InboundContext{
		Domain:      "cdn.badsite.org",
		Destination: M.ParseSocksaddrHostPort("1.1.1.1", 443),
	}, nil)
	if _, err := cApp.Write([]byte("GET / HTTP/1.1\r\n\r\n")); err != nil {
		t.Fatalf("fallback write failed: %v", err)
	}
	_ = cApp.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err = cApp.Read(reply)
	_ = cApp.Close()
	if err != nil || n == 0 {
		t.Fatalf("expected failover to node-b for badsite.org, err=%v", err)
	}
	if nodeB.dialCount.Load() == 0 {
		t.Fatalf("expected node-b to serve failover traffic")
	}

	// 4. Two consecutive global unanswered failures demote active node-a via soft score penalty without global quarantine.
	cA, cR := net.Pipe()
	doneCh := make(chan struct{})
	go g.NewConnection(context.Background(), cR, adapter.InboundContext{
		Domain:      "drop-0.org",
		Destination: M.ParseSocksaddrHostPort("1.1.1.1", 443),
	}, func(error) {
		close(doneCh)
	})
	_, _ = cA.Write([]byte("CLIENT HELLO"))
	select {
	case <-doneCh:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for unanswered drop-0.org")
	}
	_ = cA.Close()

	snapAfterDrops := g.GroupSnapshot()
	if snapAfterDrops.AvailableCount != 2 {
		t.Fatalf("expected node-a to remain globally available (no suspect cooldown ban), got avail=%d", snapAfterDrops.AvailableCount)
	}
	for _, m := range snapAfterDrops.Members {
		if m.Role == "suspect" || m.SuspectRemainingSec > 0 {
			t.Fatalf("did not expect global suspect cooldown role in snapshot, got %+v", m)
		}
	}

	nodeB.dialCount.Store(0)
	fApp, fRouter := net.Pipe()
	go g.NewConnection(context.Background(), fRouter, adapter.InboundContext{
		Domain:      "brandnew-site.io",
		Destination: M.ParseSocksaddrHostPort("1.1.1.1", 443),
	}, nil)
	_, _ = fApp.Write([]byte("PING"))
	_ = fApp.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err = fApp.Read(reply)
	_ = fApp.Close()
	if err != nil || n == 0 || nodeB.dialCount.Load() != 1 {
		t.Fatalf("expected passive penalty to switch active node to node-b for fresh site, err=%v bCount=%d", err, nodeB.dialCount.Load())
	}

	// 5. Hard dial error triggers dialRetries automatically on real routed connection
	nodeB.dialCount.Store(0)
	nodeA.mu.Lock()
	nodeA.dropAfterWrite = false
	nodeA.dialErr = errors.New("connection refused")
	nodeA.mu.Unlock()
	dApp, dRouter := net.Pipe()
	go g.NewConnection(context.Background(), dRouter, adapter.InboundContext{
		Domain:      "sub.example.com",
		Destination: M.ParseSocksaddrHostPort("93.184.216.34", 443),
	}, nil)
	_, _ = dApp.Write([]byte("PING"))
	_ = dApp.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err = dApp.Read(reply)
	_ = dApp.Close()
	if err != nil || n == 0 || nodeB.dialCount.Load() != 1 {
		t.Fatalf("expected dialRetries to fail over from node-a error to node-b, err=%v bCount=%d", err, nodeB.dialCount.Load())
	}
}

func TestRouteNewConnectionPerConnectionLoadBalance(t *testing.T) {
	nodeA := newRoutePipeOutbound("node-a", []byte("A"))
	nodeB := newRoutePipeOutbound("node-b", []byte("B"))
	tol := uint16(50)

	g, _ := newTestAutoSelectWithRoute(t, AutoSelectOptions{
		Outbounds:      []string{"node-a", "node-b"},
		BalanceEnabled: true,
		BalanceMode:    BalanceModePerConnection,
		Tolerance:      &tol,
	}, nodeA, nodeB)
	defer g.Close()

	now := time.Now()
	g.scorer.recordSample("node-a", 45, nil, now)
	g.scorer.recordSample("node-b", 55, nil, now)
	g.scorer.evaluate(now, nil)

	for i := 0; i < 4; i++ {
		cApp, cRouter := net.Pipe()
		go g.NewConnection(context.Background(), cRouter, adapter.InboundContext{
			Domain:      fmt.Sprintf("balance-%d.example.com", i),
			Destination: M.ParseSocksaddrHostPort("10.0.0.1", 443),
		}, nil)
		_, _ = cApp.Write([]byte("HELLO"))
		buf := make([]byte, 8)
		_ = cApp.SetReadDeadline(time.Now().Add(2 * time.Second))
		_, _ = cApp.Read(buf)
		_ = cApp.Close()
	}

	if nodeA.dialCount.Load() == 0 || nodeB.dialCount.Load() == 0 {
		t.Fatalf("expected per_connection load balance via NewConnection to distribute traffic: nodeA=%d nodeB=%d",
			nodeA.dialCount.Load(), nodeB.dialCount.Load())
	}
}

type queuedTestPacket struct {
	data []byte
	dest M.Socksaddr
}

type testInboundPacketConn struct {
	readCh   chan queuedTestPacket
	writeCh  chan []byte
	closedCh chan struct{}
	once     sync.Once
}

func newTestInboundPacketConn(payload []byte, dest M.Socksaddr) *testInboundPacketConn {
	ch := make(chan queuedTestPacket, 1)
	ch <- queuedTestPacket{data: append([]byte(nil), payload...), dest: dest}
	return &testInboundPacketConn{
		readCh:   ch,
		writeCh:  make(chan []byte, 4),
		closedCh: make(chan struct{}),
	}
}

func (c *testInboundPacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	select {
	case pkt, ok := <-c.readCh:
		if !ok {
			return M.Socksaddr{}, io.EOF
		}
		_, _ = buffer.Write(pkt.data)
		return pkt.dest, nil
	case <-c.closedCh:
		return M.Socksaddr{}, net.ErrClosed
	}
}

func (c *testInboundPacketConn) WritePacket(buffer *buf.Buffer, _ M.Socksaddr) error {
	data := append([]byte(nil), buffer.Bytes()...)
	buffer.Release()
	select {
	case c.writeCh <- data:
		return nil
	case <-c.closedCh:
		return net.ErrClosed
	}
}

func (c *testInboundPacketConn) Close() error {
	c.once.Do(func() { close(c.closedCh) })
	return nil
}

func (c *testInboundPacketConn) LocalAddr() net.Addr {
	return M.SocksaddrFrom(netip.MustParseAddr("127.0.0.1"), 12345).UDPAddr()
}
func (c *testInboundPacketConn) SetDeadline(time.Time) error      { return nil }
func (c *testInboundPacketConn) SetReadDeadline(time.Time) error  { return nil }
func (c *testInboundPacketConn) SetWriteDeadline(time.Time) error { return nil }

func TestRouteNewPacketConnectionPassiveLearning(t *testing.T) {
	nodeA := newRoutePipeOutbound("node-a", []byte("UDP-PONG"))
	nodeB := newRoutePipeOutbound("node-b", []byte("UDP-B"))
	learnTrue := true

	g, _ := newTestAutoSelectWithRoute(t, AutoSelectOptions{
		Outbounds:            []string{"node-a", "node-b"},
		LearnFromConnections: &learnTrue,
	}, nodeA, nodeB)
	defer g.Close()
	initNow := time.Now()
	g.scorer.recordSample("node-a", 30, nil, initNow)
	g.scorer.recordSample("node-b", 100, nil, initNow)
	g.scorer.evaluate(initNow, g.passive)

	dest := M.ParseSocksaddrHostPort("8.8.8.8", 443)
	inPkt := newTestInboundPacketConn([]byte("QUIC-PING"), dest)
	defer inPkt.Close()

	meta := adapter.InboundContext{
		Domain:      "quic.example.net",
		Destination: dest,
	}
	go g.NewPacketConnection(context.Background(), inPkt, meta, nil)

	select {
	case resp := <-inPkt.writeCh:
		if string(resp) != "UDP-PONG" {
			t.Fatalf("unexpected UDP reply: %q", string(resp))
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for UDP reply through AutoSelect.NewPacketConnection")
	}

	if nodeA.packetCount.Load() != 1 {
		t.Fatalf("expected node-a packetCount=1, got %d", nodeA.packetCount.Load())
	}
	if pref := g.passive.PreferredSiteNode("quic.example.net", time.Now()); pref != "node-a" {
		t.Fatalf("expected UDP site memory quic.example.net -> node-a, got %q", pref)
	}
}
