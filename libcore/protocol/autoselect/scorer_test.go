package autoselect

import (
	"testing"
	"time"

	"github.com/sagernet/sing/common/json/badoption"
)

func TestScorerLatencyHysteresisAndMaxRTT(t *testing.T) {
	now := time.Unix(1700000000, 0)
	opts := AutoSelectOptions{
		Outbounds:   []string{"node-a", "node-b", "node-c"},
		Tolerance:   uint16Ptr(30),
		MaxRTT:      badoption.Duration(200 * time.Millisecond),
		SampleCount: intPtr(3),
	}.normalized()

	s := newScorer([]string{"node-a", "node-b", "node-c"}, opts)
	s.recordSample("node-a", 120, nil, now)
	s.recordSample("node-b", 100, nil, now)
	s.recordSample("node-c", 250, nil, now) // exceeds maxRtt=200ms

	active, _ := s.evaluate(now, nil)
	if active != "node-b" {
		t.Fatalf("expected node-b (100ms), got %s", active)
	}

	// node-a stabilizes at 80ms: improvement = 100 - 80 = 20ms <= tolerance(30ms) -> keep node-b
	for i := 0; i < 3; i++ {
		now = now.Add(5 * time.Second)
		s.recordSample("node-a", 80, nil, now)
	}
	active, _ = s.evaluate(now, nil)
	if active != "node-b" {
		t.Fatalf("expected hysteresis to keep node-b for 20ms diff <= 30ms tolerance, got %s", active)
	}

	// node-a stabilizes at 60ms: improvement = 100 - 60 = 40ms > tolerance(30ms) -> switch to node-a
	for i := 0; i < 3; i++ {
		now = now.Add(5 * time.Second)
		s.recordSample("node-a", 60, nil, now)
	}
	active, reason := s.evaluate(now, nil)
	if active != "node-a" {
		t.Fatalf("expected switch to node-a for 40ms diff > 30ms tolerance, got %s", active)
	}
	if reason == "" {
		t.Fatalf("expected non-empty switch reason")
	}

	// Verify node-c (250ms > maxRtt 200ms) is not picked even if node-a and node-b degrade slightly below 200ms
	now = now.Add(5 * time.Second)
	s.recordSample("node-a", 180, nil, now)
	s.recordSample("node-b", 190, nil, now)
	s.recordSample("node-c", 250, nil, now)
	active, _ = s.evaluate(now, nil)
	if active == "node-c" {
		t.Fatalf("did not expect node-c above max_rtt to be selected")
	}
}

func TestScorerPriorityFailoverAndDualGateFailback(t *testing.T) {
	now := time.Unix(1700000000, 0)
	opts := AutoSelectOptions{
		Outbounds:      []string{"pri-1", "pri-2", "pri-3"},
		SelectionMode:  SelectionModePriority,
		FailbackChecks: intPtr(3),
		FailbackDelay:  durationPtr(60 * time.Second),
	}.normalized()

	s := newScorer([]string{"pri-1", "pri-2", "pri-3"}, opts)
	s.recordSample("pri-1", 180, nil, now)
	s.recordSample("pri-2", 40, nil, now) // lower ping, but lower priority
	s.recordSample("pri-3", 60, nil, now)

	// Priority mode MUST select pri-1 even though pri-2 is faster
	active, _ := s.evaluate(now, nil)
	if active != "pri-1" {
		t.Fatalf("expected priority mode to pick pri-1 first, got %s", active)
	}

	// pri-1 fails twice -> failover to pri-2
	now = now.Add(5 * time.Second)
	s.recordSample("pri-1", 0, errTestFail, now)
	now = now.Add(2 * time.Second)
	s.recordSample("pri-1", 0, errTestFail, now)
	active, reason := s.evaluate(now, nil)
	if active != "pri-2" {
		t.Fatalf("expected failover to pri-2, got %s", active)
	}
	if reason != "priority failover" {
		t.Fatalf("expected reason 'priority failover', got %q", reason)
	}

	// pri-1 recovers immediately: 3 quick checks in 15s (< 60s delay) -> MUST NOT fail back yet!
	for i := 0; i < 3; i++ {
		now = now.Add(5 * time.Second)
		s.recordSample("pri-1", 90, nil, now)
	}
	active, _ = s.evaluate(now, nil)
	if active != "pri-2" {
		t.Fatalf("must NOT failback before failbackDelay (60s) elapsed, got %s", active)
	}

	// Even after 60s passes, if pri-1 had only 2 checks after a fail reset -> must satisfy BOTH gates!
	// Advance past 60s since last fail (already at +15s, advance +50s => +65s total with 3 consecutive checks)
	now = now.Add(50 * time.Second)
	active, reason = s.evaluate(now, nil)
	if active != "pri-1" {
		t.Fatalf("expected failback to pri-1 after 3 checks AND >=60s fail-free, got %s (reason=%q)", active, reason)
	}
	if reason != "priority failback" {
		t.Fatalf("expected reason 'priority failback', got %q", reason)
	}
}

func TestScorerFailbackDelaySatisfiedButChecksInsufficient(t *testing.T) {
	now := time.Unix(1700000000, 0)
	opts := AutoSelectOptions{
		Outbounds:      []string{"pri-1", "pri-2"},
		SelectionMode:  SelectionModePriority,
		FailbackChecks: intPtr(3),
		FailbackDelay:  durationPtr(30 * time.Second),
	}.normalized()

	s := newScorer([]string{"pri-1", "pri-2"}, opts)
	s.recordSample("pri-2", 50, nil, now)
	s.recordSample("pri-1", 0, errTestFail, now)
	now = now.Add(1 * time.Second)
	s.recordSample("pri-1", 0, errTestFail, now)
	active, _ := s.evaluate(now, nil)
	if active != "pri-2" {
		t.Fatalf("expected pri-2, got %s", active)
	}

	// Wait 60s (> 30s delay), run only 2 passing checks (< 3 failbackChecks) -> stay on pri-2
	now = now.Add(60 * time.Second)
	s.recordSample("pri-1", 80, nil, now)
	now = now.Add(5 * time.Second)
	s.recordSample("pri-1", 80, nil, now)
	active, _ = s.evaluate(now, nil)
	if active != "pri-2" {
		t.Fatalf("must NOT failback with only 2 checks < 3 required, got %s", active)
	}

	// 3rd check passes -> now failback!
	now = now.Add(5 * time.Second)
	s.recordSample("pri-1", 80, nil, now)
	active, _ = s.evaluate(now, nil)
	if active != "pri-1" {
		t.Fatalf("expected failback on 3rd check after delay elapsed, got %s", active)
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
	s.recordSample("n3", 400, nil, now) // much slower than n1+tolerance
	s.evaluate(now, nil)

	// Multiple connections within same 30s window stay pinned to same rotation slot for session stability
	first := s.pickConnectionCarrier(now, nil)
	second := s.pickConnectionCarrier(now.Add(10*time.Second), nil)
	if first != second {
		t.Fatalf("expected timed_rotation to keep same carrier within 30s window, got %s then %s", first, second)
	}

	// Advance past 30s interval -> rotates to second verified healthy node (skipping n3)
	s.advanceRotation(now.Add(31*time.Second), nil)
	third := s.pickConnectionCarrier(now.Add(31*time.Second), nil)
	if third == first || third == "n3" {
		t.Fatalf("expected timed_rotation to advance to another healthy node (not %s, not slow n3), got %s", first, third)
	}

	// Now test per_connection mode distributes immediately across n1 and n2
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
		SampleCount:    intPtr(1),
		FailbackChecks: intPtr(2),
		FailbackDelay:  durationPtr(20 * time.Second),
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

	// Tier 2 is much faster, but healthy Tier 1 must win; within Tier 1, lowest latency wins.
	s.recordSample("t1-slow", 120, nil, now)
	s.recordSample("t1-fast", 70, nil, now)
	s.recordSample("t2-ultra", 10, nil, now)
	s.recordSample("fallback", 5, nil, now)

	active, _, switched, _ := s.evaluateWithTransition(now, nil)
	if !switched || active != "t1-fast" {
		t.Fatalf("expected tier-1 fastest t1-fast, got %s", active)
	}

	// Within tolerance (70 -> 50 is 20ms <= 30ms), stay on t1-fast.
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

	// Tiered verifiedPool must ONLY include tier-1 healthy nodes within spread (not t2-ultra or fallback).
	s.recordSample("t1-fast", 55, nil, now.Add(11*time.Second))
	s.mu.RLock()
	pool := s.verifiedPoolLocked(now.Add(11*time.Second), nil)
	s.mu.RUnlock()
	if len(pool) != 2 || (pool[0] != "t1-slow" && pool[1] != "t1-slow") || (pool[0] != "t1-fast" && pool[1] != "t1-fast") {
		t.Fatalf("expected tiered balance pool to only contain Tier 1 members, got %v", pool)
	}

	// Fail all Tier 1 members -> immediate failover to Tier 2.
	tFail := now.Add(15 * time.Second)
	s.recordSample("t1-slow", 0, errTestFail, tFail)
	s.recordSample("t1-fast", 0, errTestFail, tFail)
	active, reason, switched, _ = s.evaluateWithTransition(tFail, nil)
	if !switched || active != "t2-ultra" || reason != "priority failover" {
		t.Fatalf("expected failover to t2-ultra, got %s reason=%s", active, reason)
	}

	// Fail Tier 2 as well -> implicit fallback Tier 3 takes over.
	s.recordSample("t2-ultra", 0, errTestFail, tFail.Add(2*time.Second))
	active, _, switched, _ = s.evaluateWithTransition(tFail.Add(2*time.Second), nil)
	if !switched || active != "fallback" {
		t.Fatalf("expected implicit fallback tier member fallback, got %s", active)
	}

	// Recover t1-fast once -> blocked by failbackChecks=2 and failbackDelay=20s.
	s.recordSample("t1-fast", 60, nil, tFail.Add(5*time.Second))
	active, _, switched, _ = s.evaluateWithTransition(tFail.Add(5*time.Second), nil)
	if switched || active != "fallback" {
		t.Fatalf("expected failback held by dual-gate, got %s switched=%v", active, switched)
	}

	// Recover t1-fast second time after failbackDelay -> fails back to Tier 1.
	tRecover := tFail.Add(25 * time.Second)
	s.recordSample("t1-fast", 60, nil, tRecover)
	active, reason, switched, isFailback := s.evaluateWithTransition(tRecover, nil)
	if !switched || !isFailback || active != "t1-fast" || reason != "priority failback" {
		t.Fatalf("expected priority failback to t1-fast, got %s reason=%s isFailback=%v", active, reason, isFailback)
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
