package loadbalance

import (
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

func TestStrategies(t *testing.T) {
	n := 3
	lb := &LoadBalance{
		tags:     []string{"n0", "n1", "n2"},
		stats:    make([]*nodeStats, n),
		strategy: "failover",
	}
	for i := 0; i < n; i++ {
		lb.stats[i] = new(nodeStats)
	}
	lb.outbounds = make([]adapter.Outbound, n)

	// Test 1: failover under normal conditions
	indices := lb.candidateIndices(M.Socksaddr{})
	if len(indices) != 3 || indices[0] != 0 || indices[1] != 1 || indices[2] != 2 {
		t.Fatalf("expected [0, 1, 2], got %v", indices)
	}

	// Test 2: failover when node 0 degrades (2 consecutive fails recently)
	lb.stats[0].consecutiveFails.Store(2)
	lb.stats[0].lastFailTime.Store(time.Now().UnixMilli())
	indices = lb.candidateIndices(M.Socksaddr{})
	if len(indices) != 3 || indices[0] != 1 || indices[1] != 2 || indices[2] != 0 {
		t.Fatalf("expected [1, 2, 0] after node 0 fails, got %v", indices)
	}

	// Test 3: failover recovery after cooldown
	lb.stats[0].lastFailTime.Store(time.Now().Add(-35 * time.Second).UnixMilli())
	indices = lb.candidateIndices(M.Socksaddr{})
	if len(indices) != 3 || indices[0] != 0 {
		t.Fatalf("expected node 0 to recover after cooldown, got %v", indices)
	}

	// Test 4: stable strategy
	lb.strategy = "stable"
	// Node 1: high success, low latency
	lb.stats[1].totalDials.Store(100)
	lb.stats[1].successDials.Store(99)
	lb.stats[1].latencyEmaMs.Store(20)

	// Node 0: recent failures
	lb.stats[0].consecutiveFails.Store(3)
	lb.stats[0].lastFailTime.Store(time.Now().UnixMilli())
	lb.stats[0].totalDials.Store(100)
	lb.stats[0].successDials.Store(70)
	lb.stats[0].latencyEmaMs.Store(150)

	// Node 2: medium stats
	lb.stats[2].totalDials.Store(50)
	lb.stats[2].successDials.Store(45)
	lb.stats[2].latencyEmaMs.Store(80)

	indices = lb.candidateIndices(M.Socksaddr{})
	if len(indices) != 3 || indices[0] != 1 {
		t.Fatalf("expected node 1 to be highest score, got %v", indices)
	}
	if indices[2] != 0 {
		t.Fatalf("expected node 0 to be lowest score due to recent fails, got %v", indices)
	}

	// Test 5: round_robin strategy
	lb.strategy = "round_robin"
	lb.counter = 0
	i1 := lb.candidateIndices(M.Socksaddr{})
	i2 := lb.candidateIndices(M.Socksaddr{})
	if i1[0] == i2[0] {
		t.Fatalf("expected round robin rotation, got i1=%v, i2=%v", i1, i2)
	}

	// Test 6: leastLoad dead node isolation
	lb.strategy = "leastLoad"
	lb.activeConns = make([]*atomic.Int64, n)
	for i := 0; i < n; i++ {
		lb.activeConns[i] = new(atomic.Int64)
	}
	// Node 0 has 0 active conns, BUT is degraded (dead)
	lb.activeConns[0].Store(0)
	lb.stats[0].consecutiveFails.Store(3)
	lb.stats[0].lastFailTime.Store(time.Now().UnixMilli())
	// Node 1 has 2 active conns, and is healthy
	lb.activeConns[1].Store(2)
	lb.stats[1].consecutiveFails.Store(0)
	// Node 2 has 5 active conns, and is healthy
	lb.activeConns[2].Store(5)
	lb.stats[2].consecutiveFails.Store(0)

	llIndices := lb.candidateIndices(M.Socksaddr{})
	if llIndices[0] != 1 {
		t.Fatalf("expected healthy node 1 with 2 conns to be chosen before degraded node 0 with 0 conns, got %v", llIndices)
	}
	if llIndices[2] != 0 {
		t.Fatalf("expected degraded node 0 to be placed last, got %v", llIndices)
	}

	// Test 7: destination stickiness
	lb.strategy = "round_robin"
	destA := M.Socksaddr{Fqdn: "video.youtube.com"}
	destA1 := lb.candidateIndices(destA)
	destA2 := lb.candidateIndices(destA)
	if destA1[0] != destA2[0] {
		t.Fatalf("expected consistent destination stickiness for same FQDN, got %v and %v", destA1, destA2)
	}
}
