package autoselect

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"sync/atomic"
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

func TestAutoSelectPinFailoverSnapshotAndUnifiedInterrupt(t *testing.T) {
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
		Outbounds:                 []string{"sg-1", "sg-2"},
		SelectionMode:             SelectionModePriority,
		InterruptExistConnections: true,
	}

	ob, err := NewAutoSelect(ctx, nil, log.NewNOPFactory().Logger(), "strategy-sg", opts)
	if err != nil {
		t.Fatalf("NewAutoSelect error: %v", err)
	}
	now := time.Now()
	ob.scorer.recordSample("sg-1", 45, nil, now)
	ob.scorer.recordSample("sg-2", 90, nil, now)
	ob.scorer.evaluate(now, ob.passive)

	snap := ob.GroupSnapshot()
	if snap.ActiveTag != "sg-1" || snap.StrategyType != TypeAutoSelect {
		t.Fatalf("unexpected initial snapshot: active=%q type=%q", snap.ActiveTag, snap.StrategyType)
	}
	if snap.AvailableCount != 2 || snap.TotalCount != 2 {
		t.Fatalf("expected 2 available / 2 total, got %d/%d", snap.AvailableCount, snap.TotalCount)
	}
	// All healthy non-active nodes must appear as standby without hidden standbySize truncation.
	if snap.Members[1].Role != RoleStandby {
		t.Fatalf("expected healthy backup role='standby', got %q", snap.Members[1].Role)
	}

	// DialContext fails on sg-1 and automatically tries fallback sg-2
	conn, err := ob.DialContext(ctx, N.NetworkTCP, metadata.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("expected internal dial failover to sg-2, got err: %v", err)
	}

	// Track interrupt on priority failback via InterruptExistConnections
	var interruptCalled atomic.Int64
	ob.prober.onSwitchFn = func() {
		interruptCalled.Add(1)
		ob.handleSwitchInterrupt()
	}
	ob.prober.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		if tag == "sg-1" {
			return 0, errors.New("probe error")
		}
		return 80, nil
	}
	ob.prober.runProbeBatch(ctx, []string{"sg-1", "sg-2"}, now.Add(time.Second))
	if ob.Now() != "sg-2" {
		t.Fatalf("expected immediate 1-fail switch to sg-2, got %q", ob.Now())
	}

	// sg-1 passes 1 probe -> immediate priority failback AND unified interrupt!
	ob.prober.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		return 40, nil
	}
	beforeCount := interruptCalled.Load()
	ob.prober.runProbeBatch(ctx, []string{"sg-1", "sg-2"}, now.Add(2*time.Second))
	if ob.Now() != "sg-1" {
		t.Fatalf("expected immediate priority failback to sg-1, got %q", ob.Now())
	}
	if interruptCalled.Load() != beforeCount+1 {
		t.Fatalf("expected InterruptExistConnections callback on priority failback")
	}
	// Connection opened earlier under InterruptGroup should now observe closure if external/managed
	_ = conn.Close()

	// Strict pin holds sg-1 regardless of auto scores
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
	if decoded["selectionMode"] != SelectionModePriority || int(decoded["learnedSiteCount"].(float64)) != 1 {
		t.Fatalf("unexpected decoded snapshot telemetry: %v", decoded)
	}

	// Control token :clear_learned clears site memory without pinning
	if !ob.PinOutbound(":clear_learned") {
		t.Fatalf("expected ':clear_learned' control token to succeed")
	}
	if ob.GroupSnapshot().LearnedSiteCount != 0 {
		t.Fatalf("expected LearnedSiteCount == 0 after ':clear_learned'")
	}
}

func TestAutoSelectLearnFromConnectionsDisabledDisablesPassiveTracker(t *testing.T) {
	m1 := newMockMember("sg-1")
	m2 := newMockMember("sg-2")
	mgr := &mockOutboundManager{
		items: map[string]adapter.Outbound{"sg-1": m1, "sg-2": m2},
	}
	ctx := newAutoSelectTestContext(mgr)
	learnFalse := false

	ob, err := NewAutoSelect(ctx, nil, log.NewNOPFactory().Logger(), "strategy-off", AutoSelectOptions{
		Outbounds:            []string{"sg-1", "sg-2"},
		LearnFromConnections: &learnFalse,
	})
	if err != nil {
		t.Fatalf("NewAutoSelect error: %v", err)
	}
	if ob.passive != nil {
		t.Fatalf("expected passive tracker to be nil when learn_from_connections=false")
	}
	snap := ob.GroupSnapshot()
	if snap.LearnEnabled || snap.LearnedSiteCount != 0 {
		t.Fatalf("expected LearnEnabled=false and LearnedSiteCount=0, got %+v", snap)
	}
}
