package autoselector

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type mockOutbound struct {
	tag      string
	networks []string
	dialFn   func(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error)
}

func (m *mockOutbound) Type() string          { return "direct" }
func (m *mockOutbound) Tag() string           { return m.tag }
func (m *mockOutbound) Network() []string     { return m.networks }
func (m *mockOutbound) Dependencies() []string { return nil }
func (m *mockOutbound) DialContext(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
	if m.dialFn != nil {
		return m.dialFn(ctx, network, dest)
	}
	return nil, errors.New("not configured")
}
func (m *mockOutbound) ListenPacket(_ context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("not supported")
}

func newStartedTestSelector(t *testing.T, opts AutoSelectorOptions, members map[string]adapter.Outbound) *AutoSelector {
	t.Helper()
	// Use adapter.OutboundManager via outbound.NewManager? Instead test using a tiny wrapper or constructor directly.
	reg := outbound.NewRegistry()
	RegisterAutoSelector(reg)
	_ = reg

	ctx := context.Background()
	groupCtx, cancel := context.WithCancel(ctx)
	t.Cleanup(cancel)

	tags := deduplicateMemberTags(opts.Outbounds)
	s := &AutoSelector{
		Adapter:                      outbound.NewAdapter(TypeAutoSelector, "autosel", []string{N.NetworkTCP, N.NetworkUDP}, tags),
		ctx:                          groupCtx,
		cancel:                       cancel,
		tags:                         tags,
		link:                         opts.URL,
		connectURL:                   opts.ConnectivityURL,
		interval:                     time.Duration(opts.Interval),
		benchInterval:                time.Duration(opts.BenchInterval),
		watchInterval:                time.Duration(opts.WatchInterval),
		activeSize:                   opts.ActiveSize,
		sampling:                     opts.Sampling,
		probeTimeout:                 time.Duration(opts.Timeout),
		concurrency:                  opts.Concurrency,
		maxRTT:                       time.Duration(opts.MaxRTT),
		failTolerance:                opts.FailTolerance,
		expected:                     opts.Expected,
		balance:                      opts.Balance,
		balanceMode:                  opts.BalanceMode,
		balanceInterval:              time.Duration(opts.BalanceInterval),
		dialRetries:                  defaultAutoDialRetries,
		interruptGroup:               interrupt.NewGroup(),
		attachedGroup:                interrupt.NewGroup(),
		interruptExternalConnections: opts.InterruptExistConnections,
		members:                      make(map[string]*memberHealth, len(tags)),
		outbounds:                    make(map[string]adapter.Outbound, len(tags)),
		handles:                      make(map[string]adapter.Outbound, len(tags)),
		activeSet:                    make(map[string]bool),
		recentDialFails:              make(map[string]time.Time),
		kick:                         make(chan struct{}, 1),
		close:                        make(chan struct{}),
	}
	if opts.Tolerance != nil {
		if *opts.Tolerance > 0 {
			s.tolerance = time.Duration(*opts.Tolerance) * time.Millisecond
		}
	} else {
		s.tolerance = defaultAutoTolerance * time.Millisecond
	}
	if opts.DialRetries != nil {
		if *opts.DialRetries > 0 {
			s.dialRetries = *opts.DialRetries
		}
	} else {
		s.dialRetries = defaultAutoDialRetries
	}
	s.applyDefaults()
	validity := s.interval * time.Duration(s.sampling) * 2
	for _, tag := range s.tags {
		s.members[tag] = newMemberHealth(tag, s.sampling, validity)
		if ob, ok := members[tag]; ok {
			s.outbounds[tag] = ob
			s.handles[tag] = &memberHandle{Outbound: ob, selector: s}
		}
	}
	s.restoreWarm(opts.Warm, time.Now())
	if opts.Pinned != "" {
		if _, ok := s.outbounds[opts.Pinned]; ok {
			s.pinnedTag = opts.Pinned
		}
	}
	s.benchOrder = append(s.benchOrder, s.tags...)
	s.ranked = make([]*rankedNode, 0, len(s.tags))
	for _, tag := range s.tags {
		s.ranked = append(s.ranked, &rankedNode{tag: tag, tier: tierUntested})
	}
	s.refreshActiveSetLocked()
	s.evaluateLocked(time.Now())
	if s.selectedFor(N.NetworkTCP) == nil && len(s.tags) > 0 {
		s.setSelected(s.outbounds[s.tags[0]], N.NetworkTCP, "initial")
	}
	t.Cleanup(func() { _ = s.shutdown() })
	return s
}

func TestMemberHealthAndTierSorting(t *testing.T) {
	now := time.Now()
	fastStable := &rankedNode{
		tag:   "fast",
		tier:  tierHealthy,
		stats: memberStats{All: 5, Fail: 0, Average: 50 * time.Millisecond, Deviation: 5 * time.Millisecond},
	}
	fasterJittery := &rankedNode{
		tag:   "jittery",
		tier:  tierHealthy,
		stats: memberStats{All: 5, Fail: 0, Average: 30 * time.Millisecond, Deviation: 20 * time.Millisecond},
	}
	untested := &rankedNode{tag: "untested", tier: tierUntested}
	dead := &rankedNode{tag: "dead", tier: tierDead, stats: memberStats{All: 3, Fail: 3}}
	cooling := &rankedNode{tag: "cooling", tier: tierCooldown}

	nodes := []*rankedNode{cooling, dead, untested, fasterJittery, fastStable}
	sortNodes(nodes)

	got := []string{nodes[0].tag, nodes[1].tag, nodes[2].tag, nodes[3].tag, nodes[4].tag}
	want := []string{"fast", "jittery", "untested", "dead", "cooling"}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("sortNodes[%d] = %q, want %q (all=%v)", i, got[i], want[i], got)
		}
	}

	h := newMemberHealth("a", 4, time.Minute)
	h.put(20*time.Millisecond, now.Add(-10*time.Second))
	h.put(rttFailed, now.Add(-2*time.Second))
	h.penalize(now.Add(-1*time.Second), dialBackoff, errors.New("boom"))
	if !h.inCooldown(now) {
		t.Fatal("expected cooldown")
	}
	rolled := h.rollbackFailuresSince(now.Add(-5 * time.Second))
	if rolled != 1 {
		t.Fatalf("rolled = %d, want 1", rolled)
	}
	if !h.clearCooldownSince(now.Add(-5 * time.Second)) {
		t.Fatal("expected cooldown cleared")
	}
	if h.inCooldown(now) {
		t.Fatal("expected cooldown cleared")
	}
}

func TestAutoSelectorWarmRestoreToleranceAndPin(t *testing.T) {
	obA := &mockOutbound{tag: "a", networks: []string{N.NetworkTCP, N.NetworkUDP}}
	obB := &mockOutbound{tag: "b", networks: []string{N.NetworkTCP, N.NetworkUDP}}
	obC := &mockOutbound{tag: "c", networks: []string{N.NetworkTCP, N.NetworkUDP}}
	members := map[string]adapter.Outbound{"a": obA, "b": obB, "c": obC}

	tol := 50
	s := newStartedTestSelector(t, AutoSelectorOptions{
		Outbounds: []string{"a", "b", "c"},
		Tolerance: &tol,
		Warm: []WarmEntry{
			{Tag: "a", RTT: 0, Age: 10},
			{Tag: "b", RTT: 90, Age: 5},
			{Tag: "c", RTT: 110, Age: 5},
		},
	}, members)

	if sel := s.Selected(N.NetworkTCP); sel == nil || sel.Tag() != "b" {
		t.Fatalf("warm selected = %v, want b", sel)
	}
	if !s.SelectOutbound("c") {
		t.Fatal("pin c failed")
	}
	if sel := s.Selected(N.NetworkTCP); sel == nil || sel.Tag() != "c" {
		t.Fatalf("pinned selected = %v, want c", sel)
	}

	// Mark pinned "c" dead -> falls back to healthy "b".
	s.access.Lock()
	s.members["c"].put(rttFailed, time.Now())
	s.members["c"].put(rttFailed, time.Now())
	// Force only recent failed samples on "c" by resetting ring to dead
	s.members["c"] = newMemberHealth("c", 2, time.Minute)
	s.members["c"].put(rttFailed, time.Now())
	s.evaluateLocked(time.Now())
	s.access.Unlock()

	if sel := s.Selected(N.NetworkTCP); sel == nil || sel.Tag() != "b" {
		t.Fatalf("fallback from dead pin = %v, want b", sel)
	}

	// Unpin -> stays on automatic best.
	if !s.SelectOutbound("") {
		t.Fatal("unpin failed")
	}
	if st := s.Status(); st.Pinned != "" {
		t.Fatalf("pinned = %q, want empty", st.Pinned)
	}
}

func TestAutoSelectorDialFailoverAndReward(t *testing.T) {
	var aCalls, bCalls atomic.Int32
	obA := &mockOutbound{
		tag:      "a",
		networks: []string{N.NetworkTCP},
		dialFn: func(_ context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
			aCalls.Add(1)
			return nil, errors.New("connection refused")
		},
	}
	obB := &mockOutbound{
		tag:      "b",
		networks: []string{N.NetworkTCP},
		dialFn: func(_ context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
			bCalls.Add(1)
			c1, c2 := net.Pipe()
			_ = c2.Close()
			return c1, nil
		},
	}
	retries := 2
	s := newStartedTestSelector(t, AutoSelectorOptions{
		Outbounds:   []string{"a", "b"},
		DialRetries: &retries,
	}, map[string]adapter.Outbound{"a": obA, "b": obB})

	conn, err := s.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddrHostPort("example.com", 443))
	if err != nil {
		t.Fatalf("DialContext failed: %v", err)
	}
	_ = conn.Close()

	if aCalls.Load() != 1 || bCalls.Load() != 1 {
		t.Fatalf("dial counts a=%d b=%d, want a=1 b=1", aCalls.Load(), bCalls.Load())
	}
	if sel := s.Selected(N.NetworkTCP); sel == nil || sel.Tag() != "b" {
		t.Fatalf("active after failover = %v, want b", sel)
	}
	if st := s.Status(); st.LastSwitchReason != "failover succeeded" {
		t.Fatalf("last_switch_reason = %q, want failover succeeded", st.LastSwitchReason)
	}
}

func TestAutoSelectorProbeHTTPURLTest(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	obFast := &mockOutbound{
		tag:      "fast",
		networks: []string{N.NetworkTCP},
		dialFn: func(ctx context.Context, network string, dest M.Socksaddr) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, network, dest.String())
		},
	}
	obFail := &mockOutbound{
		tag:      "fail",
		networks: []string{N.NetworkTCP},
		dialFn: func(_ context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
			return nil, errors.New("dial failed")
		},
	}
	s := newStartedTestSelector(t, AutoSelectorOptions{
		Outbounds: []string{"fail", "fast"},
		URL:       srv.URL,
	}, map[string]adapter.Outbound{"fail": obFail, "fast": obFast})

	delays, err := s.URLTest(context.Background())
	if err != nil {
		t.Fatalf("URLTest error: %v", err)
	}
	if _, ok := delays["fast"]; !ok {
		t.Fatalf("expected fast in delays, got %v", delays)
	}
	if sel := s.Selected(N.NetworkTCP); sel == nil || sel.Tag() != "fast" {
		t.Fatalf("selected = %v, want fast", sel)
	}

	raw, err := json.Marshal(s.Status())
	if err != nil {
		t.Fatalf("json marshal status: %v", err)
	}
	if len(raw) == 0 {
		t.Fatal("empty json status")
	}
}

func TestAutoSelectorOptionsExplicitZeroJSON(t *testing.T) {
	raw := []byte(`{"outbounds":["a","a","b"],"tolerance":0,"dial_retries":0,"interrupt_exist_connections":false}`)
	var opts AutoSelectorOptions
	if err := json.Unmarshal(raw, &opts); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if opts.Tolerance == nil || *opts.Tolerance != 0 {
		t.Fatalf("tolerance = %v, want explicit 0", opts.Tolerance)
	}
	if opts.DialRetries == nil || *opts.DialRetries != 0 {
		t.Fatalf("dial_retries = %v, want explicit 0", opts.DialRetries)
	}
}
