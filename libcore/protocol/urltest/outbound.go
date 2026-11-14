package urltest

import (
	"context"
	"io"
	"maps"
	"net"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/batch"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

type URLTestOptions struct {
	Outbounds                 []string           `json:"outbounds" reference:"outbound"`
	URL                       string             `json:"url,omitempty"`
	Interval                  badoption.Duration `json:"interval,omitempty"`
	Tolerance                 *uint16            `json:"tolerance,omitempty"`
	IdleTimeout               badoption.Duration `json:"idle_timeout,omitempty"`
	InterruptExistConnections bool               `json:"interrupt_exist_connections,omitempty"`
}

func RegisterURLTest(registry *outbound.Registry) {
	outbound.Register[URLTestOptions](registry, C.TypeURLTest, NewURLTest)
}

var (
	_ adapter.OutboundGroup           = (*URLTest)(nil)
	_ adapter.InterfaceUpdateListener = (*URLTest)(nil)
	_ adapter.Referrer                = (*URLTest)(nil)
)

type URLTest struct {
	outbound.Adapter
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	connection                   adapter.ConnectionManager
	logger                       log.ContextLogger
	tags                         []string
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	group                        *URLTestGroup
	checkAccess                  sync.Mutex
	lastInterfaceCheck           atomic.Int64
	interruptExternalConnections bool
}

func NewURLTest(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options URLTestOptions) (adapter.Outbound, error) {
	tolerance := uint16(50)
	if options.Tolerance != nil {
		tolerance = *options.Tolerance
	}
	interval := time.Duration(options.Interval)
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	idleTimeout := time.Duration(options.IdleTimeout)
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	outbound := &URLTest{
		Adapter:                      outbound.NewAdapter(C.TypeURLTest, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:                          ctx,
		outbound:                     service.FromContext[adapter.OutboundManager](ctx),
		connection:                   service.FromContext[adapter.ConnectionManager](ctx),
		logger:                       logger,
		tags:                         options.Outbounds,
		link:                         options.URL,
		interval:                     interval,
		tolerance:                    tolerance,
		idleTimeout:                  idleTimeout,
		interruptExternalConnections: options.InterruptExistConnections,
	}
	if len(outbound.tags) == 0 {
		return nil, E.New("missing tags")
	}
	return outbound, nil
}

func (s *URLTest) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	switch stage {
	case adapter.StartStateStart:
		outbounds := make([]adapter.Outbound, 0, len(s.tags))
		for i, tag := range s.tags {
			detour, loaded := s.outbound.Outbound(tag)
			if !loaded {
				return E.New("outbound ", i, " not found: ", tag)
			}
			outbounds = append(outbounds, detour)
		}
		grp, err := NewURLTestGroup(s.ctx, s.outbound, s.logger, outbounds, s.link, s.interval, s.tolerance, s.idleTimeout, s.interruptExternalConnections)
		if err != nil {
			return err
		}
		s.group = grp
	case adapter.StartStateStarted:
		s.group.PostStart()
		scope.Add(s.group.Close)
	}
	return nil
}

func (s *URLTest) Now() string {
	if s.group == nil {
		return ""
	}
	if tcp := s.group.currentSelected(N.NetworkTCP); tcp != nil {
		return tcp.Tag()
	} else if udp := s.group.currentSelected(N.NetworkUDP); udp != nil {
		return udp.Tag()
	}
	if outbound, _ := s.group.Select(N.NetworkTCP); outbound != nil {
		return outbound.Tag()
	}
	return ""
}

func (s *URLTest) All() []string {
	return s.tags
}

func (s *URLTest) Selected(network string) adapter.Outbound {
	grp := s.group
	if grp == nil {
		return nil
	}
	outbound := grp.currentSelected(network)
	if outbound == nil {
		outbound, _ = grp.Select(network)
	}
	return outbound
}

func (s *URLTest) AttachConnection(closer io.Closer) func() {
	s.group.Touch()
	return s.group.interruptGroup.Add(closer, true)
}

func (s *URLTest) References() []string {
	return s.tags
}

func (s *URLTest) URLTest(ctx context.Context) (map[string]uint16, error) {
	return s.group.URLTest(ctx)
}

func (s *URLTest) CheckOutbounds() {
	s.group.CheckOutbounds(s.ctx, true)
}

func (s *URLTest) PerformUpdateCheck() {
	s.group.performUpdateCheck()
}

func (s *URLTest) InterfaceUpdated(ctx context.Context) {
	grp := s.group
	if grp == nil {
		return
	}
	if grp.pause.IsDevicePaused() || grp.pause.IsNetworkPaused() {
		return
	}
	go func() {
		s.checkAccess.Lock()
		defer s.checkAccess.Unlock()
		if ctx.Err() != nil {
			return
		}
		now := time.Now().Unix()
		last := s.lastInterfaceCheck.Load()
		if now-last < 30 {
			return
		}
		s.lastInterfaceCheck.Store(now)
		grp.CheckOutbounds(ctx, false)
	}()
}

func (s *URLTest) wrapConn(ctx context.Context, conn net.Conn) net.Conn {
	if s.group.interruptGroup == nil {
		return conn
	}
	return s.group.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx))
}

func (s *URLTest) wrapPacketConn(ctx context.Context, conn net.PacketConn) net.PacketConn {
	if s.group.interruptGroup == nil {
		return conn
	}
	return s.group.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx))
}

func (s *URLTest) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	s.group.Touch()
	netName := N.NetworkName(network)
	if netName != N.NetworkTCP && netName != N.NetworkUDP {
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	detour := s.group.currentSelected(netName)
	if detour == nil {
		detour, _ = s.group.Select(netName)
	}
	if detour == nil {
		return nil, E.New("missing supported outbound")
	}
	conn, err := detour.DialContext(ctx, network, destination)
	if err == nil {
		s.group.recordDialSuccess(detour)
		return s.wrapConn(ctx, conn), nil
	}
	if s.logger != nil {
		s.logger.ErrorContext(ctx, err)
	}
	if ctx.Err() != nil {
		return nil, err
	}

	// 按测速延迟优先、近期故障降权排序后容灾拨号，确认主节点连续失败后自动避让并保持现有健康连接不断流
	for _, alt := range s.group.failoverCandidates(netName, detour) {
		if ctx.Err() != nil {
			break
		}
		altConn, altErr := alt.DialContext(ctx, network, destination)
		if altErr == nil {
			s.group.recordDialFailover(netName, detour, alt)
			return s.wrapConn(ctx, altConn), nil
		}
		s.group.recordBackupDialFailure(alt)
	}
	return nil, err
}

func (s *URLTest) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	s.group.Touch()
	detour := s.group.currentSelected(N.NetworkUDP)
	if detour == nil {
		detour, _ = s.group.Select(N.NetworkUDP)
	}
	if detour == nil {
		return nil, E.New("missing supported outbound")
	}
	conn, err := detour.ListenPacket(ctx, destination)
	if err == nil {
		s.group.recordDialSuccess(detour)
		return s.wrapPacketConn(ctx, conn), nil
	}
	if s.logger != nil {
		s.logger.ErrorContext(ctx, err)
	}
	if ctx.Err() != nil {
		return nil, err
	}

	// UDP 候选按测速延迟优先快速容灾
	for _, alt := range s.group.failoverCandidates(N.NetworkUDP, detour) {
		if ctx.Err() != nil {
			break
		}
		altConn, altErr := alt.ListenPacket(ctx, destination)
		if altErr == nil {
			s.group.recordDialFailover(N.NetworkUDP, detour, alt)
			return s.wrapPacketConn(ctx, altConn), nil
		}
		s.group.recordBackupDialFailure(alt)
	}
	return nil, err
}

func (s *URLTest) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx, true)
	s.connection.NewConnection(ctx, s, conn, metadata, onClose)
}

func (s *URLTest) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	ctx = interrupt.ContextWithIsExternalConnection(ctx, true)
	s.connection.NewPacketConnection(ctx, s, conn, metadata, onClose)
}

const (
	dialFailThreshold     = 2
	dialFailWindow        = 30 * time.Second
	failoverProbeCooldown = 15 * time.Second
)

type URLTestGroup struct {
	ctx                          context.Context
	outbound                     adapter.OutboundManager
	logger                       log.Logger
	outbounds                    []adapter.Outbound
	link                         string
	interval                     time.Duration
	tolerance                    uint16
	idleTimeout                  time.Duration
	history                      *urltest.HistoryStorage
	checking                     atomic.Bool
	pause                        pause.Manager
	pauseCallback                *list.Element[pause.Callback]
	interruptGroup               *interrupt.Group
	selectedOutboundTCP          adapter.Outbound
	selectedOutboundUDP          adapter.Outbound
	interruptExternalConnections bool
	selectAccess                 sync.RWMutex
	dialFailCount                map[string]int
	dialFailTime                 map[string]time.Time
	lastFailProbe                atomic.Int64
	access                       sync.Mutex
	updateAccess                 sync.Mutex
	ticker                       *time.Ticker
	close                        chan struct{}
	started                      atomic.Bool
	lastActive                   common.TypedValue[time.Time]
}

func NewURLTestGroup(ctx context.Context, outboundManager adapter.OutboundManager, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, tolerance uint16, idleTimeout time.Duration, interruptExternalConnections bool) (*URLTestGroup, error) {
	if interval == 0 {
		interval = C.DefaultURLTestInterval
	}
	if idleTimeout == 0 {
		idleTimeout = C.DefaultURLTestIdleTimeout
	}
	if interval > idleTimeout {
		return nil, E.New("interval must be less or equal than idle_timeout")
	}
	history := service.PtrFromContext[urltest.HistoryStorage](ctx)
	if history == nil {
		return nil, E.New("missing URL test history storage")
	}
	return &URLTestGroup{
		ctx:                          ctx,
		outbound:                     outboundManager,
		logger:                       logger,
		outbounds:                    outbounds,
		link:                         link,
		interval:                     interval,
		tolerance:                    tolerance,
		idleTimeout:                  idleTimeout,
		history:                      history,
		close:                        make(chan struct{}),
		pause:                        service.FromContext[pause.Manager](ctx),
		interruptGroup:               interrupt.NewGroup(),
		interruptExternalConnections: interruptExternalConnections,
	}, nil
}

func (g *URLTestGroup) PostStart() {
	g.access.Lock()
	defer g.access.Unlock()
	g.started.Store(true)
	g.lastActive.Store(time.Now())
	go g.CheckOutbounds(g.ctx, false)
}

func (g *URLTestGroup) Touch() {
	if !g.started.Load() {
		return
	}
	g.access.Lock()
	defer g.access.Unlock()
	if g.ticker != nil {
		g.lastActive.Store(time.Now())
		return
	}
	ticker := time.NewTicker(g.interval)
	g.ticker = ticker
	g.pauseCallback = pause.RegisterTicker(g.pause, ticker, g.interval, nil)
	go g.loopCheck(ticker, g.close)
}

func (g *URLTestGroup) Close() error {
	g.access.Lock()
	defer g.access.Unlock()
	if g.ticker == nil {
		return nil
	}
	g.ticker.Stop()
	g.ticker = nil
	g.pause.UnregisterCallback(g.pauseCallback)
	g.pauseCallback = nil
	close(g.close)
	return nil
}

func (g *URLTestGroup) currentSelected(network string) adapter.Outbound {
	g.selectAccess.RLock()
	defer g.selectAccess.RUnlock()
	switch network {
	case N.NetworkTCP:
		return g.selectedOutboundTCP
	case N.NetworkUDP:
		return g.selectedOutboundUDP
	default:
		return nil
	}
}

func (g *URLTestGroup) clearDialFailLocked(tag string) {
	if tag == "" || g.dialFailCount == nil {
		return
	}
	delete(g.dialFailCount, tag)
	delete(g.dialFailTime, tag)
}

func (g *URLTestGroup) incrementDialFailLocked(tag string, now time.Time) int {
	if tag == "" {
		return 0
	}
	if g.dialFailCount == nil {
		g.dialFailCount = make(map[string]int)
		g.dialFailTime = make(map[string]time.Time)
	}
	if prev, ok := g.dialFailTime[tag]; ok && now.Sub(prev) > dialFailWindow {
		g.dialFailCount[tag] = 0
	}
	g.dialFailCount[tag]++
	g.dialFailTime[tag] = now
	return g.dialFailCount[tag]
}

func (g *URLTestGroup) recordDialSuccess(outbound adapter.Outbound) {
	if outbound == nil {
		return
	}
	tag := outbound.Tag()
	g.selectAccess.RLock()
	hasFailure := g.dialFailCount != nil && g.dialFailCount[tag] > 0
	g.selectAccess.RUnlock()
	if !hasFailure {
		return
	}
	g.selectAccess.Lock()
	g.clearDialFailLocked(tag)
	g.selectAccess.Unlock()
}

func (g *URLTestGroup) recordBackupDialFailure(outbound adapter.Outbound) {
	if outbound == nil {
		return
	}
	g.selectAccess.Lock()
	g.incrementDialFailLocked(outbound.Tag(), time.Now())
	g.selectAccess.Unlock()
}

type failoverCandidate struct {
	outbound       adapter.Outbound
	recentlyFailed bool
	delay          uint16
}

// failoverCandidates returns compatible backup outbounds ordered by:
// non-recently-failed with lowest URLTest delay -> non-recently-failed untested -> recently-failed.
func (g *URLTestGroup) failoverCandidates(network string, failed adapter.Outbound) []adapter.Outbound {
	now := time.Now()
	g.selectAccess.RLock()
	candidates := make([]failoverCandidate, 0, len(g.outbounds))
	for _, alt := range g.outbounds {
		if alt == nil || alt == failed || !common.Contains(alt.Network(), network) {
			continue
		}
		tag := alt.Tag()
		recentFail := false
		if g.dialFailCount != nil && g.dialFailCount[tag] > 0 {
			if t, ok := g.dialFailTime[tag]; ok && now.Sub(t) <= dialFailWindow {
				recentFail = true
			}
		}
		candidates = append(candidates, failoverCandidate{
			outbound:       alt,
			recentlyFailed: recentFail,
		})
	}
	g.selectAccess.RUnlock()

	if g.history != nil {
		for i := range candidates {
			if hist := g.history.LoadURLTestHistory(group.RealTag(candidates[i].outbound, network)); hist != nil && hist.Delay > 0 {
				candidates[i].delay = hist.Delay
			}
		}
	}

	slices.SortStableFunc(candidates, func(a, b failoverCandidate) int {
		if a.recentlyFailed != b.recentlyFailed {
			if !a.recentlyFailed {
				return -1
			}
			return 1
		}
		aHasDelay := a.delay > 0
		bHasDelay := b.delay > 0
		if aHasDelay != bHasDelay {
			if aHasDelay {
				return -1
			}
			return 1
		}
		if aHasDelay && a.delay != b.delay {
			if a.delay < b.delay {
				return -1
			}
			return 1
		}
		return 0
	})

	ordered := make([]adapter.Outbound, len(candidates))
	for i, c := range candidates {
		ordered[i] = c.outbound
	}
	return ordered
}

// recordDialFailover tracks consecutive primary dial failures and promotes backup after
// dialFailThreshold is reached without interrupting healthy existing connections.
func (g *URLTestGroup) recordDialFailover(network string, failed, backup adapter.Outbound) {
	if failed == nil || backup == nil {
		return
	}
	now := time.Now()
	var (
		promoted    bool
		shouldProbe bool
	)

	g.selectAccess.Lock()
	g.clearDialFailLocked(backup.Tag())
	count := g.incrementDialFailLocked(failed.Tag(), now)
	if count >= dialFailThreshold {
		switch network {
		case N.NetworkTCP:
			if g.selectedOutboundTCP == failed {
				g.selectedOutboundTCP = backup
				promoted = true
			}
		case N.NetworkUDP:
			if g.selectedOutboundUDP == failed {
				g.selectedOutboundUDP = backup
				promoted = true
			}
		}
		if promoted {
			g.clearDialFailLocked(failed.Tag())
		}
	}
	if !g.checking.Load() && g.outbound != nil && g.started.Load() &&
		(g.pause == nil || (!g.pause.IsDevicePaused() && !g.pause.IsNetworkPaused())) {
		nowUnix := now.Unix()
		prevProbe := g.lastFailProbe.Load()
		if nowUnix-prevProbe >= int64(failoverProbeCooldown/time.Second) &&
			g.lastFailProbe.CompareAndSwap(prevProbe, nowUnix) {
			shouldProbe = true
		}
	}
	g.selectAccess.Unlock()

	if promoted && g.history != nil {
		// 仅惩罚已确认失效的原主节点测速缓存（不中断现有健康连接），避免下轮 CheckOutbounds 读回旧低延迟或跳过重测
		failedTag := group.RealTag(failed, network)
		if oldHist := g.history.LoadURLTestHistory(failedTag); oldHist != nil && oldHist.Delay > 0 {
			penalized := oldHist.Delay + 1000
			if penalized < oldHist.Delay || penalized > 65000 {
				penalized = 65000
			}
			staleTime := oldHist.Time
			probeCutoff := now.Add(-g.interval - time.Second)
			if g.interval > 0 && staleTime.After(probeCutoff) {
				staleTime = probeCutoff
			}
			g.history.StoreURLTestHistory(failedTag, &adapter.URLTestHistory{
				Time:  staleTime,
				Delay: penalized,
			})
		}
		g.history.NotifyUpdated()
	}

	if shouldProbe {
		go g.CheckOutbounds(g.ctx, false)
	}
}

func (g *URLTestGroup) Select(network string) (adapter.Outbound, bool) {
	currentSelected := g.currentSelected(network)
	var currentDelay uint16
	if currentSelected != nil {
		if history := g.history.LoadURLTestHistory(group.RealTag(currentSelected, network)); history != nil && history.Delay > 0 {
			currentDelay = history.Delay
		}
	}

	// 阶段一：在所有候选节点中找出客观测量绝对最低延迟节点（无先手偏见）
	var bestOutbound adapter.Outbound
	var bestDelay uint16
	for _, detour := range g.outbounds {
		if !common.Contains(detour.Network(), network) {
			continue
		}
		history := g.history.LoadURLTestHistory(group.RealTag(detour, network))
		if history == nil || history.Delay == 0 {
			continue
		}
		if bestDelay == 0 || history.Delay < bestDelay {
			bestDelay = history.Delay
			bestOutbound = detour
		}
	}

	// 阶段二：平滑防抖决策
	if bestOutbound != nil {
		// 若当前无活跃节点，或当前活跃节点无有效测速/已失效，直接无条件采用最优节点
		if currentSelected == nil || currentDelay == 0 {
			return bestOutbound, true
		}
		// 若最优节点就是当前活跃节点，继续保持
		if bestOutbound == currentSelected {
			return currentSelected, true
		}
		// 容差判定：仅在最优节点比当前活跃节点快超过 tolerance 时才触发平滑切换
		if currentDelay > bestDelay+g.tolerance {
			return bestOutbound, true
		}
		// 微小抖动未超出 tolerance，保持当前活跃节点，避免频繁颠簸
		return currentSelected, true
	}

	// 阶段三：Fallback（若所有节点均无测速，返回首个支持该网络的节点作为临时占位）
	if currentSelected != nil && common.Contains(currentSelected.Network(), network) {
		return currentSelected, false
	}
	for _, detour := range g.outbounds {
		if common.Contains(detour.Network(), network) {
			return detour, false
		}
	}
	return nil, false
}

func (g *URLTestGroup) loopCheck(ticker *time.Ticker, closeChan <-chan struct{}) {
	if time.Since(g.lastActive.Load()) > g.interval {
		g.lastActive.Store(time.Now())
		g.CheckOutbounds(g.ctx, false)
	}
	for {
		select {
		case <-closeChan:
			return
		case <-ticker.C:
		}
		if time.Since(g.lastActive.Load()) > g.idleTimeout {
			g.access.Lock()
			if g.ticker == ticker {
				g.ticker.Stop()
				g.ticker = nil
				g.pause.UnregisterCallback(g.pauseCallback)
				g.pauseCallback = nil
			}
			g.access.Unlock()
			return
		}
		g.CheckOutbounds(g.ctx, false)
	}
}

func (g *URLTestGroup) CheckOutbounds(ctx context.Context, force bool) {
	_, _ = g.urlTest(ctx, force)
}

func (g *URLTestGroup) URLTest(ctx context.Context) (map[string]uint16, error) {
	return g.urlTest(ctx, true)
}

func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
	if g.checking.Swap(true) {
		return make(map[string]uint16), nil
	}
	defer g.checking.Store(false)
	result := URLTestOutbounds(ctx, g.outbound, g.history, g.logger, g.outbounds, g.link, g.interval, force)
	g.performUpdateCheck()
	return result, nil
}

type urlTestResult struct {
	delay uint16
	err   error
}

type urlTestBatch struct {
	ctx      context.Context
	outbound adapter.OutboundManager
	history  *urltest.HistoryStorage
	logger   log.Logger
	batch    *batch.Batch[any]
	checked  map[string]bool
	groups   []adapter.OutboundGroup
	access   sync.Mutex
	result   map[string]uint16
}

func URLTestOutbounds(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool) map[string]uint16 {
	b, _ := batch.New(ctx, batch.WithConcurrencyNum[any](6))
	testBatch := &urlTestBatch{
		ctx:      ctx,
		outbound: outboundManager,
		history:  history,
		logger:   logger,
		batch:    b,
		checked:  make(map[string]bool),
		result:   make(map[string]uint16),
	}
	testBatch.test(outbounds, link, interval, force)
	b.Wait()
	for _, outboundGroup := range testBatch.groups {
		groupHistory := history.LoadURLTestHistory(group.RealTag(outboundGroup, N.NetworkTCP))
		if groupHistory != nil {
			testBatch.result[outboundGroup.Tag()] = groupHistory.Delay
		}
	}
	return testBatch.result
}

func (b *urlTestBatch) test(outbounds []adapter.Outbound, link string, interval time.Duration, force bool) {
	for _, detour := range outbounds {
		tag := detour.Tag()
		if b.checked[tag] {
			continue
		}
		switch nested := detour.(type) {
		case *URLTest:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.batch.Go(tag, func() (any, error) {
				nestedResult, _ := nested.group.urlTest(b.ctx, force)
				b.access.Lock()
				maps.Copy(b.result, nestedResult)
				b.access.Unlock()
				return nil, nil
			})
		case adapter.OutboundGroup:
			b.checked[tag] = true
			b.groups = append(b.groups, nested)
			b.test(common.FilterNotNil(common.Map(nested.All(), func(it string) adapter.Outbound {
				member, _ := b.outbound.Outbound(it)
				return member
			})), link, interval, force)
		default:
			history := b.history.LoadURLTestHistory(tag)
			if !force && history != nil && time.Since(history.Time) < interval {
				continue
			}
			b.checked[tag] = true
			b.batch.Go(tag, func() (any, error) {
				testCtx, cancel := context.WithTimeout(b.ctx, 6*time.Second)
				defer cancel()
				testChan := make(chan urlTestResult, 1)
				go func() {
					delay, testErr := ProbeOutbound(testCtx, detour, link, 5000*time.Millisecond)
					testChan <- urlTestResult{delay, testErr}
				}()
				var testResult urlTestResult
				select {
				case testResult = <-testChan:
				case <-testCtx.Done():
					testResult.err = testCtx.Err()
				}
				if testResult.err != nil {
					b.logger.Debug("outbound ", tag, " unavailable: ", testResult.err)
					oldHist := b.history.LoadURLTestHistory(tag)
					if oldHist != nil && time.Since(oldHist.Time) < 3*interval {
						// 节点历史保护：单次超时或网络瞬态抖动不直接抹除历史，增加惩罚延迟保活
						b.history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
							Time:  time.Now(),
							Delay: oldHist.Delay + 300,
						})
					} else {
						b.history.DeleteURLTestHistory(tag)
					}
				} else {
					b.logger.Debug("outbound ", tag, " available: ", testResult.delay, "ms")
					b.history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
						Time:  time.Now(),
						Delay: testResult.delay,
					})
					b.access.Lock()
					b.result[tag] = testResult.delay
					b.access.Unlock()
				}
				return nil, nil
			})
		}
	}
}

func (g *URLTestGroup) performUpdateCheck() {
	g.updateAccess.Lock()
	defer g.updateAccess.Unlock()
	var (
		updated  bool
		selected bool
	)
	if outbound, exists := g.Select(N.NetworkTCP); outbound != nil {
		g.selectAccess.Lock()
		cur := g.selectedOutboundTCP
		if cur == nil || (exists && outbound != cur) {
			if cur != nil {
				updated = true
			}
			g.selectedOutboundTCP = outbound
			g.clearDialFailLocked(outbound.Tag())
			selected = true
		}
		g.selectAccess.Unlock()
	}
	if outbound, exists := g.Select(N.NetworkUDP); outbound != nil {
		g.selectAccess.Lock()
		cur := g.selectedOutboundUDP
		if cur == nil || (exists && outbound != cur) {
			if cur != nil {
				updated = true
			}
			g.selectedOutboundUDP = outbound
			g.clearDialFailLocked(outbound.Tag())
			selected = true
		}
		g.selectAccess.Unlock()
	}
	if updated && g.interruptExternalConnections && g.interruptGroup != nil {
		g.interruptGroup.Interrupt(true)
	}
	if selected && g.history != nil {
		g.history.NotifyUpdated()
	}
}
