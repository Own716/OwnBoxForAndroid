package autoselect

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"

	customUrltest "libcore/protocol/urltest"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	singUrltest "github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

var (
	_ adapter.Outbound                      = (*AutoSelect)(nil)
	_ adapter.ConnectionHandler             = (*AutoSelect)(nil)
	_ adapter.PacketConnectionHandler       = (*AutoSelect)(nil)
	_ adapter.InterfaceUpdateListener       = (*AutoSelect)(nil)
	_ adapter.Referrer                      = (*AutoSelect)(nil)
	_ customUrltest.StrategyGroupController = (*AutoSelect)(nil)
)

// RegisterAutoSelect registers the unified autoselect outbound type with sing-box.
func RegisterAutoSelect(registry *outbound.Registry) {
	outbound.Register(registry, TypeAutoSelect, func(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options AutoSelectOptions) (adapter.Outbound, error) {
		return NewAutoSelect(ctx, router, logger, tag, options)
	})
}

type AutoSelect struct {
	outbound.Adapter
	ctx        context.Context
	outbound   adapter.OutboundManager
	connection adapter.ConnectionManager
	logger     log.Logger
	cfg        normalizedConfig
	tags       []string

	outboundsMu sync.RWMutex
	outbounds   map[string]adapter.Outbound

	scorer         *scorer
	passive        *passiveTracker
	prober         *prober
	history        *singUrltest.HistoryStorage
	interruptGroup *interrupt.Group
	dialFailMu     sync.Mutex
	dialFails      map[string]int
	nowFn          func() time.Time
	started        atomic.Bool
}

func NewAutoSelect(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options AutoSelectOptions) (*AutoSelect, error) {
	if len(options.Outbounds) == 0 {
		return nil, E.New("missing autoselect outbounds")
	}
	outboundMgr := service.FromContext[adapter.OutboundManager](ctx)
	if outboundMgr == nil {
		return nil, E.New("missing outbound manager")
	}
	history := service.PtrFromContext[singUrltest.HistoryStorage](ctx)
	if history == nil {
		history = singUrltest.NewHistoryStorage()
	}

	cfg := options.normalized()
	var passive *passiveTracker
	if cfg.learnFromConnections {
		passive = newPassiveTracker(defaultSiteStickyTTL)
	}

	g := &AutoSelect{
		Adapter:        outbound.NewAdapter(TypeAutoSelect, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:            ctx,
		outbound:       outboundMgr,
		connection:     service.FromContext[adapter.ConnectionManager](ctx),
		logger:         logger,
		cfg:            cfg,
		tags:           append([]string(nil), options.Outbounds...),
		outbounds:      make(map[string]adapter.Outbound, len(options.Outbounds)),
		scorer:         newScorer(options.Outbounds, cfg),
		passive:        passive,
		history:        history,
		interruptGroup: interrupt.NewGroup(),
		dialFails:      make(map[string]int),
		nowFn:          time.Now,
	}

	g.resolveOutboundsLocked()
	if len(options.InitialDelays) > 0 {
		now := g.now()
		for tag, delay := range options.InitialDelays {
			if delay > 0 {
				g.scorer.recordSample(tag, delay, nil, now)
				if history != nil {
					history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
						Time:  now,
						Delay: delay,
					})
				}
			}
		}
	}
	g.scorer.evaluate(g.now(), g.passive)

	pauseMgr := service.FromContext[pause.Manager](ctx)
	g.prober = newProber(cfg, g.scorer, g.passive, history, pauseMgr, logger, g.outboundByTag)
	g.prober.onSwitchFn = g.handleSwitchInterrupt
	return g, nil
}

func (g *AutoSelect) now() time.Time {
	if g.nowFn != nil {
		return g.nowFn()
	}
	return time.Now()
}

func (g *AutoSelect) resolveOutboundsLocked() error {
	g.outboundsMu.Lock()
	defer g.outboundsMu.Unlock()
	for _, memberTag := range g.tags {
		if ob, loaded := g.outbound.Outbound(memberTag); loaded {
			g.outbounds[memberTag] = ob
		}
	}
	return nil
}

func (g *AutoSelect) outboundByTag(tag string) adapter.Outbound {
	g.outboundsMu.RLock()
	ob := g.outbounds[tag]
	g.outboundsMu.RUnlock()
	if ob != nil {
		return ob
	}
	if loaded, ok := g.outbound.Outbound(tag); ok {
		g.outboundsMu.Lock()
		g.outbounds[tag] = loaded
		g.outboundsMu.Unlock()
		return loaded
	}
	return nil
}

func (g *AutoSelect) Start(stage adapter.StartStage, _ *adapter.Scope) error {
	switch stage {
	case adapter.StartStateStart:
		g.outboundsMu.Lock()
		for _, memberTag := range g.tags {
			detour, loaded := g.outbound.Outbound(memberTag)
			if !loaded {
				g.outboundsMu.Unlock()
				return E.New("outbound ", memberTag, " not found")
			}
			g.outbounds[memberTag] = detour
		}
		g.outboundsMu.Unlock()
	case adapter.StartStateStarted:
		g.started.Store(true)
		g.prober.Start(g.ctx)
	}
	return nil
}

func (g *AutoSelect) Close() error {
	if g.prober != nil {
		g.prober.Close()
	}
	return nil
}

func (g *AutoSelect) Touch() {
	if g.prober != nil {
		g.prober.Touch()
	}
}

func (g *AutoSelect) All() []string {
	return append([]string(nil), g.tags...)
}

func (g *AutoSelect) References() ([]string, []string) {
	return append([]string(nil), g.tags...), nil
}

func (g *AutoSelect) Now() string {
	return g.scorer.activeTagSnapshot()
}

func (g *AutoSelect) PerformUpdateCheck() {
	g.scorer.evaluate(g.now(), g.passive)
}

func (g *AutoSelect) URLTest(ctx context.Context) (map[string]uint16, error) {
	results := g.prober.runProbeBatch(ctx, append([]string(nil), g.tags...), time.Time{})
	return results, nil
}

func (g *AutoSelect) InterfaceUpdated(ctx context.Context) {
	if g.prober != nil {
		g.prober.InterfaceUpdated(ctx)
	}
}

func (g *AutoSelect) PinnedOutbound() string {
	return g.scorer.pinned()
}

func (g *AutoSelect) PinOutbound(tag string) bool {
	if tag == ClearLearnedControlTag {
		if g.passive != nil {
			g.passive.ClearLearned()
		}
		g.dialFailMu.Lock()
		g.dialFails = make(map[string]int)
		g.dialFailMu.Unlock()
		g.scorer.evaluate(g.now(), g.passive)
		if g.history != nil {
			g.history.NotifyUpdated()
		}
		return true
	}

	ok := g.scorer.pin(tag, g.now())
	if !ok {
		return false
	}
	if tag == "" {
		g.scorer.evaluate(g.now(), g.passive)
	} else {
		g.dialFailMu.Lock()
		delete(g.dialFails, tag)
		g.dialFailMu.Unlock()
	}
	g.handleSwitchInterrupt()
	if g.history != nil {
		g.history.NotifyUpdated()
	}
	return true
}

func (g *AutoSelect) handleSwitchInterrupt() {
	if g.interruptGroup == nil {
		return
	}
	if g.cfg.interruptOnSwitch {
		g.interruptGroup.Interrupt(true)
	}
}

func (g *AutoSelect) siteHostFromContext(ctx context.Context, destination M.Socksaddr) string {
	if ctx != nil {
		if meta := adapter.ContextFrom(ctx); meta != nil && meta.Domain != "" {
			return meta.Domain
		}
	}
	if destination.Fqdn != "" {
		return destination.Fqdn
	}
	if destination.Addr.IsValid() {
		return destination.Addr.String()
	}
	return ""
}

func (g *AutoSelect) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	g.Touch()
	if ctx == nil {
		ctx = g.ctx
	}
	now := g.now()
	siteHost := g.siteHostFromContext(ctx, destination)
	candidates := g.scorer.candidateOrder(normalizeSiteKey(siteHost), now, g.passive)
	maxAttempts := 1 + defaultMaxDialRetries
	if g.PinnedOutbound() != "" {
		maxAttempts = 1
	}

	var lastErr error
	attempts := 0
	for _, tag := range candidates {
		if attempts >= maxAttempts {
			break
		}
		if ctx.Err() != nil {
			if lastErr != nil {
				return nil, lastErr
			}
			return nil, ctx.Err()
		}
		ob := g.outboundByTag(tag)
		if ob == nil || !common.Contains(ob.Network(), network) {
			continue
		}
		attempts++

		attemptCtx := ctx
		var cancel context.CancelFunc
		if attempts < maxAttempts && len(candidates) > 1 {
			if _, hasDeadline := ctx.Deadline(); !hasDeadline {
				attemptCtx, cancel = context.WithTimeout(ctx, defaultFastFailoverTimeout)
			}
		}

		conn, err := ob.DialContext(attemptCtx, network, destination)
		if cancel != nil {
			cancel()
		}
		if err == nil {
			g.resetDialFail(tag)
			if g.cfg.learnFromConnections && g.passive != nil {
				nodeTag := tag
				destHost := siteHost
				conn = newPassiveConn(conn, func(ttfb time.Duration) {
					g.passive.RecordSuccess(nodeTag, destHost, ttfb)
				}, func() {
					g.onPassiveFailure(nodeTag, destHost)
				}, g.nowFn)
			}
			if g.interruptGroup != nil {
				conn = g.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx))
			}
			return conn, nil
		}

		lastErr = err
		if ctx.Err() != nil {
			return nil, err
		}
		g.onDialAttemptFailed(tag, siteHost, now)
	}
	if lastErr == nil {
		return nil, errors.New("no available outbound in autoselect group")
	}
	return nil, lastErr
}

func (g *AutoSelect) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	g.Touch()
	if ctx == nil {
		ctx = g.ctx
	}
	now := g.now()
	siteHost := g.siteHostFromContext(ctx, destination)
	candidates := g.scorer.candidateOrder(normalizeSiteKey(siteHost), now, g.passive)
	maxAttempts := 1 + defaultMaxDialRetries
	if g.PinnedOutbound() != "" {
		maxAttempts = 1
	}

	var lastErr error
	attempts := 0
	for _, tag := range candidates {
		if attempts >= maxAttempts {
			break
		}
		if ctx.Err() != nil {
			if lastErr != nil {
				return nil, lastErr
			}
			return nil, ctx.Err()
		}
		ob := g.outboundByTag(tag)
		if ob == nil || !common.Contains(ob.Network(), N.NetworkUDP) {
			continue
		}
		attempts++

		pConn, err := ob.ListenPacket(ctx, destination)
		if err == nil {
			g.resetDialFail(tag)
			if g.cfg.learnFromConnections && g.passive != nil {
				nodeTag := tag
				destHost := siteHost
				pConn = newPassivePacketConn(pConn, func(ttfb time.Duration) {
					g.passive.RecordSuccess(nodeTag, destHost, ttfb)
				}, func() {
					g.onPassiveFailure(nodeTag, destHost)
				}, g.nowFn)
			}
			if g.interruptGroup != nil {
				pConn = g.interruptGroup.NewPacketConn(pConn, interrupt.IsExternalConnectionFromContext(ctx))
			}
			return pConn, nil
		}

		lastErr = err
		if ctx.Err() != nil {
			return nil, err
		}
		g.onDialAttemptFailed(tag, siteHost, now)
	}
	if lastErr == nil {
		return nil, errors.New("no available packet outbound in autoselect group")
	}
	return nil, lastErr
}

func (g *AutoSelect) resetDialFail(tag string) {
	g.dialFailMu.Lock()
	delete(g.dialFails, tag)
	g.dialFailMu.Unlock()
}

func (g *AutoSelect) failureThreshold() int {
	if g.cfg.selectionMode == SelectionModeFallback && g.cfg.fallbackMaxFails > 0 {
		return g.cfg.fallbackMaxFails
	}
	return 2
}

func (g *AutoSelect) onPassiveFailure(tag, siteHost string) {
	if !g.cfg.learnFromConnections || g.passive == nil {
		return
	}
	now := g.now()
	g.passive.RecordFailure(tag, siteHost)
	_, _, consecFails := g.passive.NodeStats(tag, now)
	threshold := g.failureThreshold()
	if consecFails >= threshold && tag == g.scorer.activeTagSnapshot() {
		if g.scorer.switchFromConnectionFailure(tag, "passive failover", now, g.passive) {
			g.handleSwitchInterrupt()
		}
	}
}

func (g *AutoSelect) onDialAttemptFailed(tag, siteHost string, now time.Time) {
	passiveFails := 0
	if g.cfg.learnFromConnections && g.passive != nil {
		g.passive.RecordFailure(tag, siteHost)
		_, _, passiveFails = g.passive.NodeStats(tag, now)
	}
	g.dialFailMu.Lock()
	g.dialFails[tag]++
	count := g.dialFails[tag]
	g.dialFailMu.Unlock()

	threshold := g.failureThreshold()
	if count >= threshold || passiveFails >= threshold {
		if g.scorer.switchFromConnectionFailure(tag, "dial failover", now, g.passive) {
			g.handleSwitchInterrupt()
		}
	}
}

func (g *AutoSelect) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	if g.connection != nil {
		g.connection.NewConnection(ctx, g, conn, metadata, onClose)
		return
	}
	_ = conn.Close()
}

func (g *AutoSelect) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	if g.connection != nil {
		g.connection.NewPacketConnection(ctx, g, conn, metadata, onClose)
		return
	}
	_ = conn.Close()
}
