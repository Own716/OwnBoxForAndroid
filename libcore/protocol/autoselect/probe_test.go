package autoselect

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

var errTestFail = errors.New("probe failed")

func TestProbeTierSelection(t *testing.T) {
	opts := AutoSelectOptions{
		Outbounds:           []string{"n0", "n1", "n2", "n3", "n4", "n5"},
		StandbySize:         intPtr(2),
		IntensiveCheckCount: intPtr(3),
		RankTopN:            5, // handled on Android mostly, Go tests full list order
	}.normalized()

	all := []string{"n0", "n1", "n2", "n3", "n4", "n5"}
	scorer := newScorer(all, opts)
	now := time.Unix(1700000000, 0)
	for i, tag := range all {
		scorer.recordSample(tag, uint16(100-i*10), nil, now)
	}
	scorer.evaluate(now, nil) // n5=50ms active

	watch := selectWatchTargets(all, scorer, nil, now)
	if len(watch) == 0 || watch[0] != scorer.activeTagSnapshot() {
		t.Fatalf("expected active node first in watch targets, got %v", watch)
	}

	intensive := selectIntensiveTargets(all, scorer, nil, opts, now)
	if len(intensive) < 3 {
		t.Fatalf("expected at least intensiveCheckCount=3 targets, got %v", intensive)
	}
}

func TestProbeConnectivityGuardRollsBackMassOutage(t *testing.T) {
	now := time.Unix(1700000000, 0)
	opts := AutoSelectOptions{
		Outbounds: []string{"n1", "n2", "n3"},
	}.normalized()

	scorer := newScorer([]string{"n1", "n2", "n3"}, opts)
	scorer.recordSample("n1", 40, nil, now)
	scorer.recordSample("n2", 50, nil, now)
	scorer.recordSample("n3", 60, nil, now)
	active, _ := scorer.evaluate(now, nil)
	if active != "n1" {
		t.Fatalf("expected n1 active initially, got %s", active)
	}

	var offlineFlag atomic.Bool
	offlineFlag.Store(true)

	prober := newProber(opts, scorer, newPassiveTracker(60*time.Minute), nil, nil, nil, nil)
	prober.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		return 0, errTestFail // 100% failure (e.g. airplane mode / tunnel outage)
	}
	prober.connectivityCheckFn = func(ctx context.Context) bool {
		return !offlineFlag.Load() // direct internet check says offline!
	}

	prober.runProbeBatch(context.Background(), []string{"n1", "n2", "n3"}, now.Add(10*time.Second))

	if !prober.IsLocalOffline() {
		t.Fatalf("expected prober to mark localOffline=true when all probes fail and connectivity fails")
	}
	// Crucial guarantee: n1 MUST NOT accumulate probe failures due to local internet outage!
	if _, fails, _ := scorer.sampleCounts("n1"); fails != 0 {
		t.Fatalf("expected 0 recorded failures during local outage immunity, got %d", fails)
	}
	if activeAfter := scorer.activeTagSnapshot(); activeAfter != "n1" {
		t.Fatalf("expected active node n1 preserved during outage, got %s", activeAfter)
	}
}
