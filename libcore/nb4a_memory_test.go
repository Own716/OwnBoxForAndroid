package libcore

import (
	"strings"
	"testing"
)

// v3.0.5 regression test: the balanced (default) memory profile must stay at
// the pre-v3.0.3 values (GOGC=80, 256 MiB soft limit). v3.0.3 widened them to
// 100/512 MiB and users reported a clear resident-memory increase, because the
// Go heap was allowed to grow ~2x before GC pressure applied.
func TestSetMemoryProfileBalanced(t *testing.T) {
	SetMemoryProfile(false)
	if currentGOGC != 80 {
		t.Fatalf("balanced GOGC = %d, want 80", currentGOGC)
	}
	if currentMemoryLimitBytes != 256*1024*1024 {
		t.Fatalf("balanced memory limit = %d, want %d", currentMemoryLimitBytes, 256*1024*1024)
	}
}

func TestSetMemoryProfilePerformance(t *testing.T) {
	SetMemoryProfile(true)
	if currentGOGC != 100 {
		t.Fatalf("performance GOGC = %d, want 100", currentGOGC)
	}
	if currentMemoryLimitBytes != -1 {
		t.Fatalf("performance memory limit = %d, want -1 (unlimited)", currentMemoryLimitBytes)
	}
	// restore default for other tests
	SetMemoryProfile(false)
}

// RuntimeStatsJSON must always return a well-formed, parseable snapshot and
// must not disturb the live GC/memory settings as a side effect.
func TestRuntimeStatsJSON(t *testing.T) {
	SetMemoryProfile(false)
	beforeGOGC, beforeLimit := currentGOGC, currentMemoryLimitBytes

	stats := RuntimeStatsJSON()
	for _, key := range []string{
		`"goroutines":`, `"gomaxprocs":`, `"heap_alloc_mib":`,
		`"heap_sys_mib":`, `"gc_num":`, `"gc_pause_total_ms":`,
		`"gc_cpu_fraction":`, `"memory_limit_mib":`, `"gogc":`,
	} {
		if !strings.Contains(stats, key) {
			t.Fatalf("RuntimeStatsJSON() missing key %s: %s", key, stats)
		}
	}
	if !strings.HasPrefix(stats, "{") || !strings.HasSuffix(stats, "}") {
		t.Fatalf("RuntimeStatsJSON() is not a JSON object: %s", stats)
	}
	// reporting must be side-effect free
	if currentGOGC != beforeGOGC || currentMemoryLimitBytes != beforeLimit {
		t.Fatalf("RuntimeStatsJSON() changed live memory profile: gogc %d->%d, limit %d->%d",
			beforeGOGC, currentGOGC, beforeLimit, currentMemoryLimitBytes)
	}
}
