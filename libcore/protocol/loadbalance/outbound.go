package loadbalance

import (
	"context"
	"math/rand"
	"net"
	"slices"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const TypeLoadBalance = "loadbalance"

type LoadBalanceOptions struct {
	option.SelectorOutboundOptions
	Strategy string `json:"strategy,omitempty"`
}

func RegisterLoadBalance(registry *outbound.Registry) {
	outbound.Register[LoadBalanceOptions](registry, TypeLoadBalance, NewLoadBalance)
}

var (
	_ adapter.Outbound                = (*LoadBalance)(nil)
	_ adapter.ConnectionHandler       = (*LoadBalance)(nil)
	_ adapter.PacketConnectionHandler = (*LoadBalance)(nil)
	_ adapter.Referrer                = (*LoadBalance)(nil)
)

type nodeStats struct {
	consecutiveFails atomic.Int32
	lastFailTime     atomic.Int64 // UnixMilli
	totalDials       atomic.Int64
	successDials     atomic.Int64
	latencyEmaMs     atomic.Int64
}

func (s *nodeStats) recordSuccess(latencyMs int64) {
	s.consecutiveFails.Store(0)
	s.totalDials.Add(1)
	s.successDials.Add(1)
	if latencyMs > 0 {
		old := s.latencyEmaMs.Load()
		if old <= 0 {
			s.latencyEmaMs.Store(latencyMs)
		} else {
			newEma := (old*8 + latencyMs*2) / 10
			s.latencyEmaMs.Store(newEma)
		}
	}
}

func (s *nodeStats) recordFailure() {
	s.consecutiveFails.Add(1)
	s.totalDials.Add(1)
	s.lastFailTime.Store(time.Now().UnixMilli())
}

type LoadBalance struct {
	outbound.Adapter
	ctx            context.Context
	outbound       adapter.OutboundManager
	connection     adapter.ConnectionManager
	logger         logger.ContextLogger
	tags           []string
	strategy       string
	outbounds      []adapter.Outbound
	counter        uint64
	activeConns    []*atomic.Int64
	stats          []*nodeStats
	interruptGroup *interrupt.Group
}

func NewLoadBalance(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options LoadBalanceOptions) (adapter.Outbound, error) {
	lb := &LoadBalance{
		Adapter:        outbound.NewAdapter(TypeLoadBalance, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:            ctx,
		outbound:       service.FromContext[adapter.OutboundManager](ctx),
		connection:     service.FromContext[adapter.ConnectionManager](ctx),
		logger:         logger,
		tags:           options.Outbounds,
		strategy:       options.Strategy,
		interruptGroup: interrupt.NewGroup(),
	}
	if len(lb.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return lb, nil
}

func (s *LoadBalance) References() []string {
	return s.tags
}

func (s *LoadBalance) Start() error {
	s.outbounds = make([]adapter.Outbound, 0, len(s.tags))
	s.activeConns = make([]*atomic.Int64, len(s.tags))
	s.stats = make([]*nodeStats, len(s.tags))
	for i, tag := range s.tags {
		detour, loaded := s.outbound.Outbound(tag)
		if !loaded {
			return E.New("outbound ", i, " not found: ", tag)
		}
		s.outbounds = append(s.outbounds, detour)
		s.activeConns[i] = new(atomic.Int64)
		s.stats[i] = new(nodeStats)
	}
	return nil
}

func hashDestination(dest M.Socksaddr) uint32 {
	var key string
	if dest.Fqdn != "" {
		key = dest.Fqdn
	} else if dest.IsIP() {
		key = dest.Addr.String()
	} else {
		key = dest.String()
	}
	var h uint32 = 2166136261
	for i := 0; i < len(key); i++ {
		h ^= uint32(key[i])
		h *= 16777619
	}
	return h
}

func (s *LoadBalance) candidateIndices(dest M.Socksaddr) []int {
	n := len(s.outbounds)
	if n == 0 {
		return nil
	}
	indices := make([]int, n)
	for i := 0; i < n; i++ {
		indices[i] = i
	}
	switch s.strategy {
	case "failover":
		now := time.Now().UnixMilli()
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			fails := s.stats[i].consecutiveFails.Load()
			lastFail := s.stats[i].lastFailTime.Load()
			// If >= 2 consecutive failures and within 30s cooldown, mark as degraded
			if fails >= 2 && now-lastFail < 30_000 {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			// All degraded, try in original order
			return indices
		}
		return append(healthy, degraded...)
	case "stable":
		now := time.Now().UnixMilli()
		scores := make([]int64, n)
		for i := 0; i < n; i++ {
			total := s.stats[i].totalDials.Load()
			success := s.stats[i].successDials.Load()
			var successRate int64 = 100
			if total > 0 {
				successRate = (success * 100) / total
			}
			fails := int64(s.stats[i].consecutiveFails.Load())
			lastFail := s.stats[i].lastFailTime.Load()
			var failPenalty int64 = 0
			if fails > 0 && now-lastFail < 60_000 {
				failPenalty = fails * 200
			}
			latency := s.stats[i].latencyEmaMs.Load()
			if latency <= 0 {
				latency = 50
			}
			scores[i] = (successRate * 10) - failPenalty - (latency / 5)
		}
		slices.SortStableFunc(indices, func(a, b int) int {
			sa := scores[a]
			sb := scores[b]
			if sa > sb {
				return -1
			} else if sa < sb {
				return 1
			}
			return 0
		})
	case "leastLoad":
		// Sort outbounds by active connections ascending
		slices.SortStableFunc(indices, func(a, b int) int {
			ca := s.activeConns[a].Load()
			cb := s.activeConns[b].Load()
			if ca < cb {
				return -1
			} else if ca > cb {
				return 1
			}
			return 0
		})
	case "consistent_hash":
		if dest.Fqdn != "" || dest.IsIP() {
			start := int(hashDestination(dest) % uint32(n))
			for i := 0; i < n; i++ {
				indices[i] = (start + i) % n
			}
		} else {
			start := int(atomic.AddUint64(&s.counter, 1) % uint64(n))
			for i := 0; i < n; i++ {
				indices[i] = (start + i) % n
			}
		}
	case "random":
		start := rand.Intn(n)
		for i := 0; i < n; i++ {
			indices[i] = (start + i) % n
		}
	case "round_robin", "roundRobin":
		fallthrough
	default:
		start := int(atomic.AddUint64(&s.counter, 1) % uint64(n))
		for i := 0; i < n; i++ {
			indices[i] = (start + i) % n
		}
	}
	return indices
}

type trackedConn struct {
	net.Conn
	onClose func()
	closed  atomic.Bool
}

func (c *trackedConn) Close() error {
	if c.closed.CompareAndSwap(false, true) {
		if c.onClose != nil {
			c.onClose()
		}
	}
	return c.Conn.Close()
}

func (s *LoadBalance) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	indices := s.candidateIndices(destination)
	n := len(indices)
	if n == 0 {
		return nil, E.New("no outbounds available")
	}
	var lastErr error
	for i, idx := range indices {
		candidate := s.outbounds[idx]
		var (
			conn net.Conn
			err  error
		)
		start := time.Now()
		if i < n-1 {
			candidateCtx, cancel := context.WithTimeout(ctx, 3500*time.Millisecond)
			conn, err = candidate.DialContext(candidateCtx, network, destination)
			cancel()
		} else {
			conn, err = candidate.DialContext(ctx, network, destination)
		}
		if err == nil {
			elapsed := time.Since(start).Milliseconds()
			if idx < len(s.stats) && s.stats[idx] != nil {
				s.stats[idx].recordSuccess(elapsed)
			}
			if s.strategy == "leastLoad" {
				s.activeConns[idx].Add(1)
				conn = &trackedConn{
					Conn: conn,
					onClose: func() {
						s.activeConns[idx].Add(-1)
					},
				}
			}
			return s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		if idx < len(s.stats) && s.stats[idx] != nil {
			s.stats[idx].recordFailure()
		}
		lastErr = err
	}
	return nil, lastErr
}

type trackedPacketConn struct {
	net.PacketConn
	onClose func()
	closed  atomic.Bool
}

func (c *trackedPacketConn) Close() error {
	if c.closed.CompareAndSwap(false, true) {
		if c.onClose != nil {
			c.onClose()
		}
	}
	return c.PacketConn.Close()
}

func (s *LoadBalance) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	indices := s.candidateIndices(destination)
	n := len(indices)
	if n == 0 {
		return nil, E.New("no outbounds available")
	}
	var lastErr error
	for i, idx := range indices {
		candidate := s.outbounds[idx]
		var (
			conn net.PacketConn
			err  error
		)
		start := time.Now()
		if i < n-1 {
			candidateCtx, cancel := context.WithTimeout(ctx, 3500*time.Millisecond)
			conn, err = candidate.ListenPacket(candidateCtx, destination)
			cancel()
		} else {
			conn, err = candidate.ListenPacket(ctx, destination)
		}
		if err == nil {
			elapsed := time.Since(start).Milliseconds()
			if idx < len(s.stats) && s.stats[idx] != nil {
				s.stats[idx].recordSuccess(elapsed)
			}
			if s.strategy == "leastLoad" {
				s.activeConns[idx].Add(1)
				conn = &trackedPacketConn{
					PacketConn: conn,
					onClose: func() {
						s.activeConns[idx].Add(-1)
					},
				}
			}
			return s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		if idx < len(s.stats) && s.stats[idx] != nil {
			s.stats[idx].recordFailure()
		}
		lastErr = err
	}
	return nil, lastErr
}

func (s *LoadBalance) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewConnection(ctx, s, conn, metadata, onClose)
}

func (s *LoadBalance) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx)
	s.connection.NewPacketConnection(ctx, s, conn, metadata, onClose)
}

func (s *LoadBalance) Close() error {
	if s.interruptGroup != nil {
		s.interruptGroup.Interrupt(true)
	}
	return nil
}

