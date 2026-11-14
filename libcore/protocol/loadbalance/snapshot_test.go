package loadbalance

import (
	"bufio"
	"context"
	"errors"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	urltestPkg "libcore/protocol/urltest"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	singUrltest "github.com/sagernet/sing-box/common/urltest"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type lbMockOutbound struct {
	outbound.Adapter
	dialFn   func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error)
	packetFn func(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error)
}

var _ adapter.Outbound = (*lbMockOutbound)(nil)

func newLBMockOutbound(tag string) *lbMockOutbound {
	return &lbMockOutbound{
		Adapter: outbound.NewAdapter("mock", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
	}
}

func (m *lbMockOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if m.dialFn != nil {
		return m.dialFn(ctx, network, destination)
	}
	c1, c2 := net.Pipe()
	_ = c2.Close()
	return c1, nil
}

func (m *lbMockOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	if m.packetFn != nil {
		return m.packetFn(ctx, destination)
	}
	return nil, nil
}

func createTestLoadBalance(strategy string, tags []string) (*LoadBalance, []*lbMockOutbound) {
	outbounds := make([]adapter.Outbound, len(tags))
	mocks := make([]*lbMockOutbound, len(tags))
	stats := make([]*nodeStats, len(tags))
	activeConns := make([]*atomic.Int64, len(tags))
	for i, tag := range tags {
		ob := newLBMockOutbound(tag)
		outbounds[i] = ob
		mocks[i] = ob
		stats[i] = new(nodeStats)
		activeConns[i] = new(atomic.Int64)
	}
	lb := &LoadBalance{
		Adapter:        outbound.NewAdapter("loadbalance", "proxy", []string{N.NetworkTCP, N.NetworkUDP}, tags),
		ctx:            context.Background(),
		tags:           tags,
		outbounds:      outbounds,
		stats:          stats,
		activeConns:    activeConns,
		interruptGroup: interrupt.NewGroup(),
		strategy:       strategy,
		tolerance:      50,
		history:        singUrltest.NewHistoryStorage(),
		link:           "http://127.0.0.1/generate_204",
		interval:       5 * time.Minute,
	}
	lb.lastSelectedIdx.Store(-1)
	return lb, mocks
}

func TestLoadBalanceRoundRobinSnapshotDoesNotAdvance(t *testing.T) {
	lb, mocks := createTestLoadBalance("round_robin", []string{"rr-1", "rr-2", "rr-3"})
	for _, m := range mocks {
		tag := m.Tag()
		m.dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
			c1, c2 := net.Pipe()
			_ = c2.Close()
			_ = tag
			return c1, nil
		}
	}

	// Repeated snapshot/Now queries before dialing must not advance round-robin counter.
	snap1 := lb.GroupSnapshot()
	snap2 := lb.GroupSnapshot()
	if snap1.StrategyType != "round_robin" {
		t.Fatalf("expected strategyType round_robin, got %q", snap1.StrategyType)
	}
	if snap1.ActiveTag != snap2.ActiveTag {
		t.Fatalf("expected non-mutating snapshot ActiveTag, got %q then %q", snap1.ActiveTag, snap2.ActiveTag)
	}

	dest := M.ParseSocksaddrHostPort("example.com", 443)
	for i := 0; i < 2; i++ {
		conn, err := lb.DialContext(context.Background(), N.NetworkTCP, dest)
		if err != nil {
			t.Fatalf("dial %d failed: %v", i, err)
		}
		_ = conn.Close()
	}
	activeAfterDial := lb.GroupSnapshot().ActiveTag
	if activeAfterDial == "" {
		t.Fatal("expected non-empty ActiveTag after dialing")
	}
	// Reading Now() / GroupSnapshot() again must still remain consistent without advancing counter.
	for i := 0; i < 5; i++ {
		if got := lb.GroupSnapshot().ActiveTag; got != activeAfterDial {
			t.Fatalf("snapshot %d mutated ActiveTag from %q to %q", i, activeAfterDial, got)
		}
	}
}

func TestLoadBalancePinStrictHoldAcrossStrategies(t *testing.T) {
	for _, strategy := range []string{"leastPing", "round_robin", "random", "failover"} {
		t.Run(strategy, func(t *testing.T) {
			lb, mocks := createTestLoadBalance(strategy, []string{"n0", "n1", "n2"})

			var backupCalls atomic.Int32
			for _, idx := range []int{0, 2} {
				mocks[idx].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
					backupCalls.Add(1)
					c1, c2 := net.Pipe()
					_ = c2.Close()
					return c1, nil
				}
				mocks[idx].packetFn = func(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
					backupCalls.Add(1)
					return nil, nil
				}
			}

			pinnedErr := errors.New("pinned failure")
			mocks[1].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
				return nil, pinnedErr
			}
			mocks[1].packetFn = func(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
				return nil, pinnedErr
			}

			if lb.PinOutbound("does-not-exist") {
				t.Fatal("expected invalid pin tag to return false")
			}
			if !lb.PinOutbound("n1") {
				t.Fatal("expected PinOutbound(n1) to succeed")
			}
			if lb.PinnedOutbound() != "n1" || lb.Now() != "n1" {
				t.Fatalf("expected pinned n1, got pinned=%q now=%q", lb.PinnedOutbound(), lb.Now())
			}

			dest := M.ParseSocksaddrHostPort("example.com", 443)
			if _, err := lb.DialContext(context.Background(), N.NetworkTCP, dest); !errors.Is(err, pinnedErr) {
				t.Fatalf("expected pinned TCP failure %v, got %v", pinnedErr, err)
			}
			if _, err := lb.ListenPacket(context.Background(), dest); !errors.Is(err, pinnedErr) {
				t.Fatalf("expected pinned UDP failure %v, got %v", pinnedErr, err)
			}
			if backupCalls.Load() != 0 {
				t.Fatalf("strict pin allowed %d backup failover calls", backupCalls.Load())
			}

			if !lb.PinOutbound("") {
				t.Fatal("expected unpin to succeed")
			}
			if lb.PinnedOutbound() != "" {
				t.Fatalf("expected empty PinnedOutbound, got %q", lb.PinnedOutbound())
			}
			conn, err := lb.DialContext(context.Background(), N.NetworkTCP, dest)
			if err != nil {
				t.Fatalf("expected auto dial after unpin to fail over cleanly, got %v", err)
			}
			_ = conn.Close()
			if backupCalls.Load() == 0 {
				t.Fatal("expected backup node to be used after unpin")
			}
		})
	}
}

func TestLoadBalanceCheckAndSnapshotRecordsErrors(t *testing.T) {
	lb, mocks := createTestLoadBalance("round_robin", []string{"healthy", "failing"})
	mocks[0].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
		time.Sleep(6 * time.Millisecond)
		client, server := net.Pipe()
		go func() {
			defer server.Close()
			reader := bufio.NewReader(server)
			_, _ = http.ReadRequest(reader)
			_, _ = server.Write([]byte("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"))
		}()
		return client, nil
	}
	mocks[1].dialFn = func(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
		return nil, errors.New("connection reset by peer")
	}

	snap := lb.CheckAndSnapshot(context.Background())
	if snap.LastCheckMs <= 0 || len(snap.Members) != 2 {
		t.Fatalf("unexpected snapshot: %+v", snap)
	}
	byTag := make(map[string]urltestPkg.MemberSnapshot, 2)
	for _, m := range snap.Members {
		byTag[m.Tag] = m
	}
	if byTag["healthy"].Error != "" || byTag["healthy"].Delay <= 0 {
		t.Fatalf("expected healthy node positive delay, got %+v", byTag["healthy"])
	}
	if !strings.Contains(byTag["failing"].Error, "connection reset") || byTag["failing"].Delay != 0 {
		t.Fatalf("expected failing error+0 delay, got %+v", byTag["failing"])
	}
}
