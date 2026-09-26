package loadbalance

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"io"
	"math/rand"
	"net"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	urltestPkg "libcore/protocol/urltest"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

const TypeLoadBalance = "loadbalance"

type LoadBalanceOptions struct {
	option.URLTestOutboundOptions
	Strategy string `json:"strategy,omitempty"`
	Default  string `json:"default,omitempty"`
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

const virtualNodesPerPhysicalNode = 160

type ringEntry struct {
	hash    uint32
	nodeIdx int
}

type consistentHashRing struct {
	entries []ringEntry
}

func hash32(key string) uint32 {
	h := sha256.Sum256([]byte(key))
	return binary.BigEndian.Uint32(h[:4])
}

func newConsistentHashRing(tags []string) *consistentHashRing {
	n := len(tags)
	if n == 0 {
		return nil
	}
	totalVirtual := n * virtualNodesPerPhysicalNode
	entries := make([]ringEntry, 0, totalVirtual)
	for idx, tag := range tags {
		for v := 0; v < virtualNodesPerPhysicalNode; v++ {
			vKey := tag + "#v" + strconv.Itoa(v)
			entries = append(entries, ringEntry{
				hash:    hash32(vKey),
				nodeIdx: idx,
			})
		}
	}
	slices.SortFunc(entries, func(a, b ringEntry) int {
		if a.hash < b.hash {
			return -1
		} else if a.hash > b.hash {
			return 1
		}
		return a.nodeIdx - b.nodeIdx
	})
	return &consistentHashRing{entries: entries}
}

func (r *consistentHashRing) getCandidates(destHash uint32, n int, isDegraded func(int) bool) []int {
	if r == nil || len(r.entries) == 0 || n <= 0 {
		return nil
	}
	pos := sort.Search(len(r.entries), func(i int) bool {
		return r.entries[i].hash >= destHash
	})
	if pos >= len(r.entries) {
		pos = 0
	}

	seen := make([]bool, n)
	healthy := make([]int, 0, n)
	degraded := make([]int, 0, n)
	visitedCount := 0

	totalEntries := len(r.entries)
	for i := 0; i < totalEntries && visitedCount < n; i++ {
		entryIdx := (pos + i) % totalEntries
		nodeIdx := r.entries[entryIdx].nodeIdx
		if nodeIdx >= 0 && nodeIdx < n && !seen[nodeIdx] {
			seen[nodeIdx] = true
			visitedCount++
			if isDegraded(nodeIdx) {
				degraded = append(degraded, nodeIdx)
			} else {
				healthy = append(healthy, nodeIdx)
			}
		}
	}

	if visitedCount < n {
		for i := 0; i < n; i++ {
			if !seen[i] {
				seen[i] = true
				if isDegraded(i) {
					degraded = append(degraded, i)
				} else {
					healthy = append(healthy, i)
				}
			}
		}
	}

	if len(healthy) == 0 {
		return degraded
	}
	return append(healthy, degraded...)
}

type LoadBalance struct {
	outbound.Adapter
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	connection                   adapter.ConnectionManager
	history                      *urltest.HistoryStorage
	pause                        pause.Manager
	pauseCallback                *list.Element[pause.Callback]
	logger                       log.ContextLogger
	tags                         []string
	strategy                     string
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	interruptExternalConnections bool
	outbounds                    []adapter.Outbound
	counter                      uint64
	activeConns                  []*atomic.Int64
	stats                        []*nodeStats
	interruptGroup               *interrupt.Group
	ring                         *consistentHashRing
	ticker                       *time.Ticker
	close                        chan struct{}
	started                      bool
	lastActive                   common.TypedValue[time.Time]
	checking                     atomic.Bool
	access                       sync.Mutex
}

func NewLoadBalance(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options LoadBalanceOptions) (adapter.Outbound, error) {
	interval := time.Duration(options.Interval)
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	idleTimeout := time.Duration(options.IdleTimeout)
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	link := options.URL
	if link == "" {
		link = urltestPkg.DefaultCFURL
	}
	lb := &LoadBalance{
		Adapter:                      outbound.NewAdapter(TypeLoadBalance, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:                          ctx,
		outbound:                     service.FromContext[adapter.OutboundManager](ctx),
		connection:                   service.FromContext[adapter.ConnectionManager](ctx),
		history:                      service.PtrFromContext[urltest.HistoryStorage](ctx),
		pause:                        service.FromContext[pause.Manager](ctx),
		logger:                       logger,
		tags:                         options.Outbounds,
		strategy:                     options.Strategy,
		link:                         link,
		interval:                     interval,
		tolerance:                    options.Tolerance,
		idleTimeout:                  idleTimeout,
		interruptExternalConnections: options.InterruptExistConnections,
		interruptGroup:               interrupt.NewGroup(),
		close:                        make(chan struct{}),
	}
	if len(lb.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return lb, nil
}

func (s *LoadBalance) References() []string {
	return s.tags
}

func (s *LoadBalance) All() []string {
	return s.tags
}

func (s *LoadBalance) Now() string {
	candidates := s.candidateIndices(nil, M.Socksaddr{})
	if len(candidates) > 0 && candidates[0] < len(s.tags) {
		return s.tags[candidates[0]]
	}
	if len(s.tags) > 0 {
		return s.tags[0]
	}
	return ""
}

func (s *LoadBalance) AttachConnection(closer io.Closer) func() {
	s.Touch()
	return s.interruptGroup.Add(closer, true)
}

func (s *LoadBalance) URLTest(ctx context.Context) (map[string]uint16, error) {
	if s.checking.Swap(true) {
		return make(map[string]uint16), nil
	}
	defer s.checking.Store(false)

	result := urltestPkg.URLTestOutbounds(ctx, s.outbound, s.history, s.logger, s.outbounds, s.link, s.interval, true)
	for i, detour := range s.outbounds {
		tag := detour.Tag()
		if delay, ok := result[tag]; ok && delay > 0 {
			if i < len(s.stats) && s.stats[i] != nil {
				s.stats[i].recordSuccess(int64(delay))
			}
		}
	}
	return result, nil
}

func (s *LoadBalance) isLeastPing() bool {
	return s.strategy == "leastPing" || s.strategy == "least_ping"
}

func (s *LoadBalance) CheckOutbounds() {
	ctx, cancel := context.WithTimeout(s.ctx, 15*time.Second)
	defer cancel()
	_, _ = s.URLTest(ctx)
}

func (s *LoadBalance) PerformUpdateCheck() {
	if s.isLeastPing() {
		go s.CheckOutbounds()
	}
}

func (s *LoadBalance) Touch() {
	if !s.started || !s.isLeastPing() {
		return
	}
	s.access.Lock()
	defer s.access.Unlock()
	if s.ticker != nil {
		s.lastActive.Store(time.Now())
		return
	}
	if s.interval <= 0 {
		return
	}
	ticker := time.NewTicker(s.interval)
	s.ticker = ticker
	if s.pause != nil {
		s.pauseCallback = pause.RegisterTicker(s.pause, ticker, s.interval, nil)
	}
	go s.loopCheck(ticker, s.close)
}

func (s *LoadBalance) loopCheck(ticker *time.Ticker, closeChan <-chan struct{}) {
	for {
		select {
		case <-closeChan:
			return
		case <-ticker.C:
		}
		if s.idleTimeout > 0 && time.Since(s.lastActive.Load()) > s.idleTimeout {
			s.access.Lock()
			if s.ticker == ticker {
				s.ticker.Stop()
				s.ticker = nil
				if s.pause != nil && s.pauseCallback != nil {
					s.pause.UnregisterCallback(s.pauseCallback)
					s.pauseCallback = nil
				}
			}
			s.access.Unlock()
			return
		}
		s.CheckOutbounds()
	}
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
	s.ring = newConsistentHashRing(s.tags)
	return nil
}

func (s *LoadBalance) PostStart() error {
	s.access.Lock()
	defer s.access.Unlock()
	s.started = true
	s.lastActive.Store(time.Now())
	if s.interval > 0 && s.isLeastPing() {
		go s.CheckOutbounds()
	}
	return nil
}

func (s *LoadBalance) Close() error {
	s.access.Lock()
	if s.ticker != nil {
		s.ticker.Stop()
		s.ticker = nil
		if s.pause != nil && s.pauseCallback != nil {
			s.pause.UnregisterCallback(s.pauseCallback)
			s.pauseCallback = nil
		}
	}
	if s.close != nil {
		select {
		case <-s.close:
		default:
			close(s.close)
		}
	}
	s.access.Unlock()
	if s.interruptGroup != nil {
		s.interruptGroup.Interrupt(true)
	}
	return nil
}

func extractRootDomain(fqdn string) string {
	s := strings.TrimSpace(strings.ToLower(fqdn))
	s = strings.TrimSuffix(s, ".")
	if s == "" {
		return ""
	}
	if host, _, err := net.SplitHostPort(s); err == nil {
		s = host
	} else if idx := strings.IndexByte(s, ':'); idx != -1 {
		s = s[:idx]
	}

	parts := strings.Split(s, ".")
	n := len(parts)
	if n <= 2 {
		return s
	}

	tld := parts[n-1]
	sld := parts[n-2]
	if len(tld) == 2 {
		switch sld {
		case "com", "net", "org", "edu", "gov", "co", "ne", "ac", "go", "gen", "firm", "ind", "re", "mil":
			if n >= 3 {
				return parts[n-3] + "." + sld + "." + tld
			}
		}
	}

	return parts[n-2] + "." + parts[n-1]
}

func hashDestination(ctx context.Context, dest M.Socksaddr) uint32 {
	var domain string
	if dest.Fqdn != "" {
		domain = dest.Fqdn
	} else if ctx != nil {
		if inCtx := adapter.ContextFrom(ctx); inCtx != nil && inCtx.Domain != "" {
			domain = inCtx.Domain
		}
	}
	if domain != "" {
		root := extractRootDomain(domain)
		if root != "" {
			return hash32(root)
		}
		return hash32(strings.ToLower(domain))
	}
	if dest.IsIP() {
		addr := dest.Addr
		if addr.Is4() {
			b := addr.As4()
			key := net.IPv4(b[0], b[1], b[2], 0).String() + "/24"
			return hash32(key)
		} else if addr.Is6() {
			b := addr.As16()
			key := net.IP{b[0], b[1], b[2], b[3], b[4], b[5], 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}.String() + "/48"
			return hash32(key)
		}
		return hash32(addr.String())
	}
	s := dest.String()
	if s != "" && s != "0.0.0.0:0" && s != "[::]:0" {
		return hash32(s)
	}
	return 0
}

func (s *LoadBalance) isNodeDegraded(idx int, now int64) bool {
	if idx < 0 || idx >= len(s.stats) || s.stats[idx] == nil {
		return false
	}
	fails := s.stats[idx].consecutiveFails.Load()
	lastFail := s.stats[idx].lastFailTime.Load()
	return fails >= 2 && now-lastFail < 10_000
}

func (s *LoadBalance) candidateIndices(ctx context.Context, dest M.Socksaddr) []int {
	n := len(s.outbounds)
	if n == 0 {
		n = len(s.tags)
	}
	if n == 0 {
		return nil
	}
	indices := make([]int, n)
	for i := 0; i < n; i++ {
		indices[i] = i
	}
	now := time.Now().UnixMilli()

	switch s.strategy {
	case "failover":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			return indices
		}
		return append(healthy, degraded...)

	case "stable":
		scores := make([]int64, n)
		for i := 0; i < n; i++ {
			var total, success, fails, lastFail, latency int64
			if i < len(s.stats) && s.stats[i] != nil {
				total = s.stats[i].totalDials.Load()
				success = s.stats[i].successDials.Load()
				fails = int64(s.stats[i].consecutiveFails.Load())
				lastFail = s.stats[i].lastFailTime.Load()
				latency = s.stats[i].latencyEmaMs.Load()
			}
			var successRate int64 = 100
			if total > 0 {
				successRate = (success * 100) / total
			}
			var failPenalty int64 = 0
			if fails > 0 && now-lastFail < 60_000 {
				failPenalty = fails * 200
			}
			if latency <= 0 && s.history != nil && i < len(s.tags) {
				if h := s.history.LoadURLTestHistory(s.tags[i]); h != nil && h.Delay > 0 {
					latency = int64(h.Delay)
				}
			}
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
		return indices

	case "leastPing", "least_ping":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		slices.SortStableFunc(healthy, func(a, b int) int {
			var la int64
			if a < len(s.stats) && s.stats[a] != nil {
				la = s.stats[a].latencyEmaMs.Load()
			}
			if la <= 0 && s.history != nil && a < len(s.tags) {
				if h := s.history.LoadURLTestHistory(s.tags[a]); h != nil && h.Delay > 0 {
					la = int64(h.Delay)
				}
			}
			if la <= 0 {
				la = 9999
			}

			var lb int64
			if b < len(s.stats) && s.stats[b] != nil {
				lb = s.stats[b].latencyEmaMs.Load()
			}
			if lb <= 0 && s.history != nil && b < len(s.tags) {
				if h := s.history.LoadURLTestHistory(s.tags[b]); h != nil && h.Delay > 0 {
					lb = int64(h.Delay)
				}
			}
			if lb <= 0 {
				lb = 9999
			}

			if la < lb {
				return -1
			} else if la > lb {
				return 1
			}
			return 0
		})
		return append(healthy, degraded...)

	case "leastLoad", "least_load":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		hn := len(healthy)
		rotated := make([]int, hn)
		start := int(atomic.AddUint64(&s.counter, 1) % uint64(hn))
		for i := 0; i < hn; i++ {
			rotated[i] = healthy[(start+i)%hn]
		}
		slices.SortStableFunc(rotated, func(a, b int) int {
			var ca, cb int64
			if a < len(s.activeConns) && s.activeConns[a] != nil {
				ca = s.activeConns[a].Load()
			}
			if b < len(s.activeConns) && s.activeConns[b] != nil {
				cb = s.activeConns[b].Load()
			}
			if ca < cb {
				return -1
			} else if ca > cb {
				return 1
			}
			return 0
		})
		return append(rotated, degraded...)

	case "consistent_hash", "consistentHash":
		if s.ring == nil && len(s.tags) > 0 {
			s.ring = newConsistentHashRing(s.tags)
		}
		if s.ring != nil {
			var h uint32
			hasDest := dest.Fqdn != "" || dest.IsIP()
			if !hasDest && ctx != nil {
				if inCtx := adapter.ContextFrom(ctx); inCtx != nil && inCtx.Domain != "" {
					hasDest = true
				}
			}
			if hasDest {
				h = hashDestination(ctx, dest)
			}
			if h == 0 {
				h = uint32(atomic.AddUint64(&s.counter, 1))
			}
			return s.ring.getCandidates(h, n, func(idx int) bool {
				return s.isNodeDegraded(idx, now)
			})
		}
		return indices

	case "random":
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		hn := len(healthy)
		start := rand.Intn(hn)
		rotated := make([]int, hn)
		for i := 0; i < hn; i++ {
			rotated[i] = healthy[(start+i)%hn]
		}
		return append(rotated, degraded...)

	case "round_robin", "roundRobin":
		fallthrough
	default:
		healthy := make([]int, 0, n)
		degraded := make([]int, 0, n)
		for i := 0; i < n; i++ {
			if s.isNodeDegraded(i, now) {
				degraded = append(degraded, i)
			} else {
				healthy = append(healthy, i)
			}
		}
		if len(healthy) == 0 {
			healthy = indices
			degraded = nil
		}
		hn := len(healthy)
		rotated := make([]int, hn)
		start := int(atomic.AddUint64(&s.counter, 1) % uint64(hn))
		for i := 0; i < hn; i++ {
			rotated[i] = healthy[(start+i)%hn]
		}
		return append(rotated, degraded...)
	}
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
	s.Touch()
	indices := s.candidateIndices(ctx, destination)
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
			timeout := 5 * time.Second
			if s.isLeastPing() {
				timeout = 2000 * time.Millisecond
				if idx < len(s.stats) && s.stats[idx] != nil {
					ema := s.stats[idx].latencyEmaMs.Load()
					if s.stats[idx].consecutiveFails.Load() > 0 {
						timeout = 1000 * time.Millisecond
					} else if ema > 0 {
						dynamic := time.Duration(ema*3) * time.Millisecond
						if dynamic < 800*time.Millisecond {
							timeout = 800 * time.Millisecond
						} else if dynamic > 2000*time.Millisecond {
							timeout = 2000 * time.Millisecond
						} else {
							timeout = dynamic
						}
					}
				}
			}
			candidateCtx, cancel := context.WithTimeout(ctx, timeout)
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
			if (s.strategy == "leastLoad" || s.strategy == "least_load") && idx < len(s.activeConns) && s.activeConns[idx] != nil {
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
	s.Touch()
	indices := s.candidateIndices(ctx, destination)
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
			timeout := 5 * time.Second
			if s.isLeastPing() {
				timeout = 2000 * time.Millisecond
				if idx < len(s.stats) && s.stats[idx] != nil {
					ema := s.stats[idx].latencyEmaMs.Load()
					if s.stats[idx].consecutiveFails.Load() > 0 {
						timeout = 1000 * time.Millisecond
					} else if ema > 0 {
						dynamic := time.Duration(ema*3) * time.Millisecond
						if dynamic < 800*time.Millisecond {
							timeout = 800 * time.Millisecond
						} else if dynamic > 2000*time.Millisecond {
							timeout = 2000 * time.Millisecond
						} else {
							timeout = dynamic
						}
					}
				}
			}
			candidateCtx, cancel := context.WithTimeout(ctx, timeout)
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
			if (s.strategy == "leastLoad" || s.strategy == "least_load") && idx < len(s.activeConns) && s.activeConns[idx] != nil {
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
