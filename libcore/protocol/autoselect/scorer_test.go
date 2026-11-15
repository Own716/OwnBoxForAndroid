package autoselect

import (
	"testing"
	"time"

	"github.com/sagernet/sing/common/json/badoption"
)

func TestScorerStrictToleranceZeroAndExactBoundaries(t *testing.T) {
	now := time.Unix(1700000000, 0)

	// 1. Tolerance = 0ms -> ANY lower delay switches immediately; identical delay stays.
	zeroTol := uint16(0)
	optsZero := AutoSelectOptions{
		Outbounds: []string{"node-a", "node-b"},
		Tolerance: &zeroTol,
	}.normalized()
	sZero := newScorer([]string{"node-a", "node-b"}, optsZero)
	sZero.recordSample("node-a", 100, nil, now)
	sZero.recordSample("node-b", 100, nil, now)
	active, _ := sZero.evaluate(now, nil)
	if active != "node-a" {
		t.Fatalf("expected tie-break to node-a, got %s", active)
	}

	// node-b improves by 1ms (99ms < 100ms, diff = 1ms > 0ms) -> switches immediately!
	sZero.recordSample("node-b", 99, nil, now.Add(5*time.Second))
	active, _ = sZero.evaluate(now.Add(5*time.Second), nil)
	if active != "node-b" {
		t.Fatalf("expected 0ms tolerance to switch on 1ms difference, got %s", active)
	}

	// 2. Tolerance = 10ms -> <=10ms stays, 11ms switches (even when balanceEnabled=true, NO 50ms floor!)
	tenTol := uint16(10)
	optsTen := AutoSelectOptions{
		Outbounds:      []string{"node-a", "node-b", "node-c"},
		Tolerance:      &tenTol,
		BalanceEnabled: true,
	}.normalized()
	sTen := newScorer([]string{"node-a", "node-b", "node-c"}, optsTen)
	sTen.recordSample("node-a", 100, nil, now)
	sTen.recordSample("node-b", 90, nil, now)
	sTen.recordSample("node-c", 115, nil, now) // 90+10=100 < 115
	sTen.evaluate(now, nil)

	sTen.mu.RLock()
	pool := sTen.verifiedPoolLocked(now, nil)
	sTen.mu.RUnlock()
	if len(pool) != 2 || pool[0] != "node-b" || pool[1] != "node-a" {
		t.Fatalf("expected strict 10ms pool [node-b, node-a] excluding 115ms node-c, got %v", pool)
	}

	// node-c improves to 80ms -> diff from active node-b (90ms) = 10ms <= 10ms -> stay on node-b
	sTen.recordSample("node-c", 80, nil, now.Add(5*time.Second))
	active, _ = sTen.evaluate(now.Add(5*time.Second), nil)
	if active != "node-b" {
		t.Fatalf("expected 10ms diff to stay within 10ms tolerance, got %s", active)
	}

	// node-c improves to 79ms -> diff = 11ms > 10ms -> switch to node-c
	sTen.recordSample("node-c", 79, nil, now.Add(10*time.Second))
	active, reason := sTen.evaluate(now.Add(10*time.Second), nil)
	if active != "node-c" || reason != "best available" {
		t.Fatalf("expected switch to node-c on 11ms > 10ms tolerance, got %s (%s)", active, reason)
	}
}

func TestScorerPassiveTTFBNeverDistortsProbeDelayOrTolerance(t *testing.T) {
	now := time.Unix(1700000000, 0)
	tol := uint16(30)
	learnTrue := true
	opts := AutoSelectOptions{
		Outbounds:            []string{"node-a", "node-b"},
		Tolerance:            &tol,
		LearnFromConnections: &learnTrue,
	}.normalized()

	passive := newPassiveTracker(defaultSiteStickyTTL)
	// Simulate terrible historical TTFB on node-a and fast TTFB on node-b:
	// MUST NOT distort active probe latency or bypass tolerance!
	passive.RecordSuccess("node-a", "a.com", 900*time.Millisecond)
	passive.RecordSuccess("node-b", "b.com", 5*time.Millisecond)

	s := newScorer([]string{"node-a", "node-b"}, opts)
	s.recordSample("node-a", 100, nil, now)
	s.recordSample("node-b", 120, nil, now)
	active, _ := s.evaluate(now, passive)
	if active != "node-a" {
		t.Fatalf("expected pure probe winner node-a (100ms < 120ms), got %s", active)
	}

	// node-b probes at 80ms -> improvement = 20ms <= 30ms tolerance -> MUST stay on node-a!
	s.recordSample("node-b", 80, nil, now.Add(5*time.Second))
	active, _ = s.evaluate(now.Add(5*time.Second), passive)
	if active != "node-a" {
		t.Fatalf("passive TTFB must not distort tolerance comparison, expected node-a, got %s", active)
	}
}

func TestScorerSingleProbeFailureFailsOverAndImmediatePriorityFailback(t *testing.T) {
	now := time.Unix(1700000000, 0)
	opts := AutoSelectOptions{
		Outbounds:     []string{"pri-1", "pri-2", "pri-3"},
		SelectionMode: SelectionModePriority,
	}.normalized()

	s := newScorer([]string{"pri-1", "pri-2", "pri-3"}, opts)
	s.recordSample("pri-1", 180, nil, now)
	s.recordSample("pri-2", 40, nil, now)
	s.recordSample("pri-3", 60, nil, now)

	active, _ := s.evaluate(now, nil)
	if active != "pri-1" {
		t.Fatalf("expected priority mode to pick pri-1 first, got %s", active)
	}

	// Single probe failure -> failover immediately to pri-2
	now = now.Add(5 * time.Second)
	s.recordSample("pri-1", 0, errTestFail, now)
	active, reason := s.evaluate(now, nil)
	if active != "pri-2" || reason != "priority failover" {
		t.Fatalf("expected immediate failover to pri-2 on 1st failure, got %s (%q)", active, reason)
	}

	// Single passing probe -> immediate failback to pri-1 with zero hidden check/delay gate!
	now = now.Add(5 * time.Second)
	s.recordSample("pri-1", 90, nil, now)
	active, reason, switched, isFailback := s.evaluateWithTransition(now, nil)
	if active != "pri-1" || reason != "priority failback" || !switched || !isFailback {
		t.Fatalf("expected immediate failback to pri-1 on recovery check, got %s reason=%q switched=%v failback=%v",
			active, reason, switched, isFailback)
	}
}

func TestScorerBalanceTimedRotationVsPerConnection(t *testing.T) {
	now := time.Unix(1700000000, 0)
	optsTimed := AutoSelectOptions{
		Outbounds:       []string{"n1", "n2", "n3"},
		BalanceEnabled:  true,
		BalanceMode:     BalanceModeTimedRotation,
		BalanceInterval: badoption.Duration(30 * time.Second),
		Tolerance:       uint16Ptr(50),
	}.normalized()

	s := newScorer([]string{"n1", "n2", "n3"}, optsTimed)
	s.recordSample("n1", 50, nil, now)
	s.recordSample("n2", 60, nil, now)
	s.recordSample("n3", 400, nil, now) // exceeds n1 + 50ms tolerance
	s.evaluate(now, nil)

	first := s.pickConnectionCarrier(now, nil)
	second := s.pickConnectionCarrier(now.Add(10*time.Second), nil)
	if first != second {
		t.Fatalf("expected timed_rotation to keep same carrier within 30s window, got %s then %s", first, second)
	}

	s.advanceRotation(now.Add(31*time.Second), nil)
	third := s.pickConnectionCarrier(now.Add(31*time.Second), nil)
	if third == first || third == "n3" {
		t.Fatalf("expected timed_rotation to advance to another healthy node (not %s, not slow n3), got %s", first, third)
	}

	optsPerConn := optsTimed
	optsPerConn.balanceMode = BalanceModePerConnection
	s2 := newScorer([]string{"n1", "n2", "n3"}, optsPerConn)
	s2.recordSample("n1", 50, nil, now)
	s2.recordSample("n2", 60, nil, now)
	s2.recordSample("n3", 400, nil, now)
	s2.evaluate(now, nil)

	seen := map[string]int{}
	for i := 0; i < 6; i++ {
		c := s2.pickConnectionCarrier(now, nil)
		seen[c]++
	}
	if seen["n1"] == 0 || seen["n2"] == 0 || seen["n3"] > 0 {
		t.Fatalf("expected per_connection to distribute across n1 and n2 only, got %v", seen)
	}
}

func TestScorerPriorityTiersSelectionFailoverFailbackAndBalance(t *testing.T) {
	now := time.Unix(1700000000, 0)
	order := []string{"t1-slow", "t1-fast", "t2-ultra", "fallback"}
	opts := AutoSelectOptions{
		Outbounds:      order,
		SelectionMode:  SelectionModePriority,
		Tolerance:      uint16Ptr(30),
		BalanceEnabled: true,
		BalanceMode:    BalanceModePerConnection,
		Tiers: [][]string{
			{"t1-slow", "t1-fast"},
			{"t2-ultra"},
		},
	}.normalized()

	s := newScorer(order, opts)
	if !s.hasTiers || s.tierCount != 3 {
		t.Fatalf("expected hasTiers=true tierCount=3, got hasTiers=%v tierCount=%d", s.hasTiers, s.tierCount)
	}
	if s.members["fallback"].tier != 2 {
		t.Fatalf("expected unlisted member to sit in implicit fallback tier 2, got %d", s.members["fallback"].tier)
	}

	s.recordSample("t1-slow", 120, nil, now)
	s.recordSample("t1-fast", 70, nil, now)
	s.recordSample("t2-ultra", 10, nil, now)
	s.recordSample("fallback", 5, nil, now)

	active, _, switched, _ := s.evaluateWithTransition(now, nil)
	if !switched || active != "t1-fast" {
		t.Fatalf("expected tier-1 fastest t1-fast, got %s", active)
	}

	// Within tolerance (70 - 50 = 20ms <= 30ms), stay on t1-fast.
	s.recordSample("t1-slow", 50, nil, now.Add(5*time.Second))
	active, _, switched, _ = s.evaluateWithTransition(now.Add(5*time.Second), nil)
	if switched || active != "t1-fast" {
		t.Fatalf("expected hysteresis within tier 1, got %s switched=%v", active, switched)
	}

	// Beyond tolerance (90 - 40 = 50ms > 30ms), switch within tier 1 to t1-slow.
	s.recordSample("t1-fast", 90, nil, now.Add(10*time.Second))
	s.recordSample("t1-slow", 40, nil, now.Add(10*time.Second))
	active, reason, switched, _ := s.evaluateWithTransition(now.Add(10*time.Second), nil)
	if !switched || active != "t1-slow" || reason != "tier best available" {
		t.Fatalf("expected intra-tier switch to t1-slow, got %s reason=%s switched=%v", active, reason, switched)
	}

	// Tiered verifiedPool includes Tier 1 members within tolerance.
	s.recordSample("t1-fast", 55, nil, now.Add(11*time.Second))
	s.mu.RLock()
	pool := s.verifiedPoolLocked(now.Add(11*time.Second), nil)
	s.mu.RUnlock()
	if len(pool) != 2 || (pool[0] != "t1-slow" && pool[1] != "t1-slow") || (pool[0] != "t1-fast" && pool[1] != "t1-fast") {
		t.Fatalf("expected tiered balance pool to only contain Tier 1 members, got %v", pool)
	}

	// Fail all Tier 1 members once -> immediate failover to Tier 2.
	tFail := now.Add(15 * time.Second)
	s.recordSample("t1-slow", 0, errTestFail, tFail)
	s.recordSample("t1-fast", 0, errTestFail, tFail)
	active, reason, switched, _ = s.evaluateWithTransition(tFail, nil)
	if !switched || active != "t2-ultra" || reason != "priority failover" {
		t.Fatalf("expected failover to t2-ultra, got %s reason=%s", active, reason)
	}

	// Fail Tier 2 -> implicit fallback Tier 3 takes over immediately.
	s.recordSample("t2-ultra", 0, errTestFail, tFail.Add(2*time.Second))
	active, _, switched, _ = s.evaluateWithTransition(tFail.Add(2*time.Second), nil)
	if !switched || active != "fallback" {
		t.Fatalf("expected implicit fallback tier member fallback, got %s", active)
	}

	// Recover t1-fast once -> immediate priority failback to Tier 1!
	tRecover := tFail.Add(5 * time.Second)
	s.recordSample("t1-fast", 60, nil, tRecover)
	active, reason, switched, isFailback := s.evaluateWithTransition(tRecover, nil)
	if !switched || !isFailback || active != "t1-fast" || reason != "priority failback" {
		t.Fatalf("expected immediate priority failback to t1-fast, got %s reason=%s isFailback=%v", active, reason, isFailback)
	}

	// Verify candidateOrder ranks healthy Tier 1 before Tier 2 before implicit fallback.
	s.recordSample("t2-ultra", 10, nil, tRecover)
	optsSingle := opts
	optsSingle.balanceEnabled = false
	s.mu.Lock()
	s.cfg = optsSingle
	s.mu.Unlock()
	cand := s.candidateOrder("", tRecover, nil)
	if len(cand) != 4 || cand[0] != "t1-fast" {
		t.Fatalf("expected candidateOrder to lead with t1-fast, got %v", cand)
	}
}
