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
