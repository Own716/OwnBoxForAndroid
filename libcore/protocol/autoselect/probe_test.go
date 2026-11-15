package autoselect

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing/common/json/badoption"
)

var errTestFail = errors.New("probe failed")

func TestProberPreservesExactTestURLAndProbesAllMembers(t *testing.T) {
	customURL := "https://www.gstatic.com/generate_204"
	opts := AutoSelectOptions{
		Outbounds:     []string{"n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8", "n9"},
		URL:           customURL,
		CheckInterval: badoption.Duration(300 * time.Second),
	}.normalized()

	s := newScorer([]string{"n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7", "n8", "n9"}, opts)
	p := newProber(opts, s, nil, urltest.NewHistoryStorage(), nil, nil, nil)
	if p.link != customURL {
		t.Fatalf("expected prober link to strictly match user testUrl %q, got %q", customURL, p.link)
	}

	var mu sync.Mutex
	probed := map[string]bool{}
	p.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		mu.Lock()
		probed[tag] = true
		mu.Unlock()
		return 50, nil
	}

	now := time.Unix(1700000000, 0)
	p.nowFn = func() time.Time { return now }
	p.CheckAllNow(context.Background())

	if len(probed) != 10 {
		t.Fatalf("expected all 10 members probed with no standby/topN cap, got %d (%v)", len(probed), probed)
	}
	if rem := p.NextCheckRemainingSec(now); rem != 300 {
		t.Fatalf("expected NextCheckRemainingSec to reset to full 300s after CheckAllNow, got %d", rem)
	}
}

func TestProberSingleFailureMarksUnavailableImmediatelyAndClearsHistory(t *testing.T) {
	now := time.Unix(1700000000, 0)
	opts := AutoSelectOptions{
		Outbounds: []string{"n1", "n2"},
	}.normalized()
	hist := urltest.NewHistoryStorage()
	s := newScorer([]string{"n1", "n2"}, opts)
	p := newProber(opts, s, nil, hist, nil, nil, nil)

	p.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		if tag == "n1" {
			return 35, nil
		}
		return 80, nil
	}
	p.runProbeBatch(context.Background(), []string{"n1", "n2"}, now)
	if active := s.activeTagSnapshot(); active != "n1" {
		t.Fatalf("expected n1 active initially, got %s", active)
	}
	if hist.LoadURLTestHistory("n1") == nil {
		t.Fatalf("expected history stored for n1")
	}

	// One single probe failure immediately switches away and clears history for n1.
	p.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		if tag == "n1" {
			return 0, errTestFail
		}
		return 80, nil
	}
	p.runProbeBatch(context.Background(), []string{"n1", "n2"}, now.Add(10*time.Second))
	if active := s.activeTagSnapshot(); active != "n2" {
		t.Fatalf("expected immediate switch to n2 after 1 probe failure, got %s", active)
	}
	if hist.LoadURLTestHistory("n1") != nil {
		t.Fatalf("expected URLTest history deleted after single probe failure")
	}
}

func TestProberInterfaceUpdatedDebouncesStartupThenResetsCheckSchedule(t *testing.T) {
	cur := time.Unix(1700000000, 0)
	var mu sync.Mutex
	getNow := func() time.Time {
		mu.Lock()
		defer mu.Unlock()
		return cur
	}
	advance := func(d time.Duration) {
		mu.Lock()
		cur = cur.Add(d)
		mu.Unlock()
	}

	opts := AutoSelectOptions{
		Outbounds:     []string{"n1", "n2"},
		CheckInterval: badoption.Duration(300 * time.Second),
	}.normalized()
	s := newScorer([]string{"n1", "n2"}, opts)
	p := newProber(opts, s, nil, nil, nil, nil, nil)
	p.nowFn = getNow

	var probeRounds atomic.Int64
	doneCh := make(chan struct{}, 10)
	p.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		if tag == "n1" {
			probeRounds.Add(1)
			select {
			case doneCh <- struct{}{}:
			default:
			}
		}
		return 40, nil
	}

	p.Start(context.Background())
	defer p.Close()

	// Wait for the 1 cold-start warm-up probe batch
	select {
	case <-doneCh:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for initial cold-start probe")
	}

	// Immediate InterfaceUpdated within 15s startup debounce MUST NOT trigger a second probe batch
	p.InterfaceUpdated(context.Background())
	time.Sleep(80 * time.Millisecond)
	if rounds := probeRounds.Load(); rounds != 1 {
		t.Fatalf("expected startup InterfaceUpdated inside 15s debounce to be ignored, got %d rounds", rounds)
	}

	// Advance past 15s debounce (e.g. genuine Wi-Fi <-> 5G switch at +120s) -> triggers 1 recheck and resets 300s countdown!
	advance(120 * time.Second)
	p.InterfaceUpdated(context.Background())
	select {
	case <-doneCh:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for real InterfaceUpdated probe batch")
	}
	time.Sleep(50 * time.Millisecond)
	if rounds := probeRounds.Load(); rounds != 2 {
		t.Fatalf("expected exactly 1 recheck after real network switch, got %d rounds", rounds)
	}
	if rem := p.NextCheckRemainingSec(getNow()); rem != 300 {
		t.Fatalf("expected NextCheckRemainingSec reset to full 300s after network switch check, got %d", rem)
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

	prober := newProber(opts, scorer, newPassiveTracker(defaultSiteStickyTTL), nil, nil, nil, nil)
	prober.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		return 0, errTestFail // 100% failure (e.g. airplane mode / tunnel outage)
	}
	prober.connectivityCheckFn = func(ctx context.Context) bool {
		return !offlineFlag.Load()
	}

	prober.runProbeBatch(context.Background(), []string{"n1", "n2", "n3"}, now.Add(10*time.Second))

	if !prober.IsLocalOffline() {
		t.Fatalf("expected prober to mark localOffline=true when all probes fail and connectivity fails")
	}
	if _, fails, _ := scorer.sampleCounts("n1"); fails != 0 {
		t.Fatalf("expected 0 recorded failures during local outage immunity, got %d", fails)
	}
	if activeAfter := scorer.activeTagSnapshot(); activeAfter != "n1" {
		t.Fatalf("expected active node n1 preserved during outage, got %s", activeAfter)
	}
}

func TestProberStreamingEarlyReadyWithoutWaitingSlowMembers(t *testing.T) {
	opts := AutoSelectOptions{
		Outbounds:     []string{"dead-node", "fast-node"},
		CheckInterval: badoption.Duration(300 * time.Second),
	}.normalized()

	scorer := newScorer([]string{"dead-node", "fast-node"}, opts)
	prober := newProber(opts, scorer, nil, urltest.NewHistoryStorage(), nil, nil, nil)

	fastReturned := make(chan struct{})
	slowCanFinish := make(chan struct{})

	prober.probeFn = func(ctx context.Context, tag string) (uint16, error) {
		if tag == "fast-node" {
			close(fastReturned)
			return 120, nil
		}
		// dead-node blocks until signaled
		select {
		case <-slowCanFinish:
			return 0, errTestFail
		case <-ctx.Done():
			return 0, ctx.Err()
		}
	}

	batchDone := make(chan struct{})
	go func() {
		prober.runProbeBatch(context.Background(), []string{"dead-node", "fast-node"}, time.Now())
		close(batchDone)
	}()

	// Wait for fast-node to return
	select {
	case <-fastReturned:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for fast probe")
	}

	// Give a moment for early progress goroutine
	time.Sleep(20 * time.Millisecond)

	// Verify that fast-node is ALREADY the active node even though slowCanFinish has NOT been closed!
	if active := scorer.activeTagSnapshot(); active != "fast-node" {
		t.Fatalf("expected streaming prober to activate fast-node early while slow node is still running, got %q", active)
	}

	// Release slow node to finish batch
	close(slowCanFinish)

	select {
	case <-batchDone:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for batch to finish")
	}

	// Still fast-node
	if active := scorer.activeTagSnapshot(); active != "fast-node" {
		t.Fatalf("expected fast-node still active after batch completion, got %q", active)
	}
}
