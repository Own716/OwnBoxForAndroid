package autoselect

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

type mockMemberOutbound struct {
	outbound.Adapter
	dialFn func(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error)
}

func newMockMember(tag string) *mockMemberOutbound {
	return &mockMemberOutbound{
		Adapter: outbound.NewAdapter("mock", tag, []string{N.NetworkTCP, N.NetworkUDP}, nil),
	}
}

func (m *mockMemberOutbound) DialContext(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
	if m.dialFn != nil {
		return m.dialFn(ctx, network, destination)
	}
	c1, c2 := net.Pipe()
	_ = c2.Close()
	return c1, nil
}

func (m *mockMemberOutbound) ListenPacket(ctx context.Context, destination metadata.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

type mockOutboundManager struct {
	items map[string]adapter.Outbound
}

func (m *mockOutboundManager) Start(stage adapter.StartStage, scope *adapter.Scope) error { return nil }
func (m *mockOutboundManager) Close() error                                              { return nil }
func (m *mockOutboundManager) Outbounds() []adapter.Outbound {
	out := make([]adapter.Outbound, 0, len(m.items))
	for _, v := range m.items {
		out = append(out, v)
	}
	return out
}
func (m *mockOutboundManager) Outbound(tag string) (adapter.Outbound, bool) {
	v, ok := m.items[tag]
	return v, ok
}
func (m *mockOutboundManager) Default() adapter.Outbound { return nil }
func (m *mockOutboundManager) Create(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, outboundType string, options any) error {
	return nil
}

func newAutoSelectTestContext(mgr adapter.OutboundManager) context.Context {
	ctx := context.Background()
	ctx = service.ContextWith[adapter.OutboundManager](ctx, mgr)
	ctx = service.ContextWithPtr(ctx, urltest.NewHistoryStorage())
	return ctx
}

func TestAutoSelectColdStartPinRetriesAndSnapshot(t *testing.T) {
	m1 := newMockMember("sg-1")
	m2 := newMockMember("sg-2")
	m1.dialFn = func(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
		return nil, errors.New("dial timeout on sg-1")
	}
	m2.dialFn = func(ctx context.Context, network string, destination metadata.Socksaddr) (net.Conn, error) {
		c1, _ := net.Pipe()
		return c1, nil
	}

	mgr := &mockOutboundManager{
		items: map[string]adapter.Outbound{
			"sg-1": m1,
			"sg-2": m2,
		},
	}
	ctx := newAutoSelectTestContext(mgr)

	opts := AutoSelectOptions{
		Outbounds:   []string{"sg-1", "sg-2"},
		DialRetries: intPtr(2),
		InitialDelaysMs: map[string]uint16{
			"sg-1": 45,
			"sg-2": 90,
		},
	}

	ob, err := NewAutoSelect(ctx, nil, log.NewNOPFactory().Logger(), "strategy-sg", opts)
	if err != nil {
		t.Fatalf("NewAutoSelect error: %v", err)
	}

	// Cold start immediately knows active = sg-1 (45ms) before any live probe!
	snap := ob.GroupSnapshot()
	if snap.ActiveTag != "sg-1" || snap.StrategyType != TypeAutoSelect {
		t.Fatalf("unexpected cold start snapshot: active=%q type=%q", snap.ActiveTag, snap.StrategyType)
	}
	if snap.AvailableCount != 2 || snap.TotalCount != 2 {
		t.Fatalf("expected 2 available / 2 total on cold start, got %d/%d", snap.AvailableCount, snap.TotalCount)
	}

	// DialContext fails on sg-1 and automatically fails over to sg-2 via dialRetries=2
	conn, err := ob.DialContext(ctx, N.NetworkTCP, metadata.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("expected dialRetries failover to sg-2, got err: %v", err)
	}
	_ = conn.Close()

	// Strict pin holds sg-2 (or sg-1) regardless of auto scores
	if !ob.PinOutbound("sg-1") {
		t.Fatalf("expected PinOutbound('sg-1') to return true")
	}
	if ob.PinnedOutbound() != "sg-1" {
		t.Fatalf("expected PinnedOutbound() == 'sg-1', got %q", ob.PinnedOutbound())
	}

	// Verify JSON serialization of GroupSnapshot preserves extended telemetry
	ob.passive.RecordSuccess("sg-2", "chat.openai.com", 35*time.Millisecond)
	snap = ob.GroupSnapshot()
	raw, err := json.Marshal(snap)
	if err != nil {
		t.Fatalf("json marshal error: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("json unmarshal error: %v", err)
	}
	if decoded["selectionMode"] != SelectionModeLatency || int(decoded["learnedSiteCount"].(float64)) != 1 {
		t.Fatalf("unexpected decoded snapshot telemetry: %v", decoded)
	}

	// Control token :clear_learned clears site memory without crashing or pinning
	if !ob.PinOutbound(":clear_learned") {
		t.Fatalf("expected ':clear_learned' control token to succeed")
	}
	if ob.GroupSnapshot().LearnedSiteCount != 0 {
		t.Fatalf("expected LearnedSiteCount == 0 after ':clear_learned'")
	}
}
