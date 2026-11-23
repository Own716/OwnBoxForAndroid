package autoselect

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"

	customUrltest "libcore/protocol/urltest"

	"github.com/sagernet/sing-box/adapter"
	singUrltest "github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/batch"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service/pause"
)

const (
	probeConcurrency     = 8
	probeTimeout         = 5 * time.Second
	minInterfaceDebounce = 15 * time.Second
)

type probeOutcome struct {
	tag   string
	delay uint16
	err   error
}

type prober struct {
	ctx        context.Context
	cfg        normalizedConfig
	scorer     *scorer
	passive    *passiveTracker
	history    *singUrltest.HistoryStorage
	pauseMgr   pause.Manager
	logger     log.Logger
	link       string
	getOutFn   func(tag string) adapter.Outbound
	onSwitchFn func()

	// Injectable hooks for deterministic unit testing
	probeFn             func(ctx context.Context, tag string) (uint16, error)
	connectivityCheckFn func(ctx context.Context) bool
	nowFn               func() time.Time

	checking     atomic.Bool
	localOffline atomic.Bool

	probeMu         sync.Mutex
	mu              sync.Mutex
	started         bool
	closed          bool
	closeCh         chan struct{}
	cancelFn        context.CancelFunc
	lastCheckAt     time.Time
	nextCheckAt     time.Time
	lastInterfaceAt time.Time
	checkDoneMu     sync.Mutex
	checkDoneCh     chan struct{}

	checkTicker    *time.Ticker
	rotateTicker   *time.Ticker
	pauseCallbacks []*list.Element[pause.Callback]
}

func newProber(
	cfg normalizedConfig,
	s *scorer,
	passive *passiveTracker,
	history *singUrltest.HistoryStorage,
	pauseMgr pause.Manager,
	logger log.Logger,
	getOutFn func(tag string) adapter.Outbound,
) *prober {
	link := cfg.url
	if link == "" {
		link = customUrltest.DefaultCFURL
	}
	p := &prober{
		cfg:      cfg,
		scorer:   s,
		passive:  passive,
		history:  history,
		pauseMgr: pauseMgr,
		logger:   logger,
		link:     link,
		getOutFn: getOutFn,
		nowFn:    time.Now,
		closeCh:  make(chan struct{}),
	}
	p.probeFn = p.defaultProbe
	p.connectivityCheckFn = p.defaultConnectivityCheck
	return p
}

func (p *prober) now() time.Time {
	if p != nil && p.nowFn != nil {
		return p.nowFn()
	}
	return time.Now()
}

func (p *prober) IsChecking() bool     { return p.checking.Load() }
func (p *prober) IsLocalOffline() bool { return p.localOffline.Load() }

func (p *prober) LastCheckTime() time.Time {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.lastCheckAt
}

func (p *prober) NextCheckRemainingSec(now time.Time) int {
	p.mu.Lock()
	next := p.nextCheckAt
	p.mu.Unlock()
	if next.IsZero() {
		return int(p.cfg.checkInterval.Seconds())
	}
	rem := int(next.Sub(now).Seconds())
	if rem < 0 {
		return 0
	}
	return rem
}

func (p *prober) allTargets() []string {
	if p.scorer == nil {
		return nil
	}
	return append([]string(nil), p.scorer.order...)
}

func (p *prober) resetCheckScheduleLocked(now time.Time) {
	p.nextCheckAt = now.Add(p.cfg.checkInterval)
	if p.checkTicker != nil {
		p.checkTicker.Reset(p.cfg.checkInterval)
	}
}

func (p *prober) Start(parent context.Context) {
	p.mu.Lock()
	if p.started || p.closed {
		p.mu.Unlock()
		return
	}
	p.started = true
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithCancel(parent)
	p.ctx = ctx
	p.cancelFn = cancel
	now := p.now()
	// Suppress immediate startup interface-update echoes so cold start only probes once.
	p.lastInterfaceAt = now
	p.nextCheckAt = now.Add(p.cfg.checkInterval)
	p.mu.Unlock()

	p.startSchedulers(ctx)

	go func() {
		p.runProbeBatch(ctx, p.allTargets(), time.Time{})
	}()
}

func (p *prober) Touch() {
	p.mu.Lock()
	started := p.started
	hasTickers := p.checkTicker != nil
	ctx := p.ctx
	p.mu.Unlock()
	if started && !hasTickers {
		p.startSchedulers(ctx)
	}
}

func (p *prober) startSchedulers(ctx context.Context) {
	p.mu.Lock()
	if p.closed || p.checkTicker != nil {
		p.mu.Unlock()
		return
	}

	registerTicker := func(d time.Duration) *time.Ticker {
		t := time.NewTicker(d)
		if p.pauseMgr != nil {
			cb := pause.RegisterTicker(p.pauseMgr, t, d, nil)
			p.pauseCallbacks = append(p.pauseCallbacks, cb)
		}
		return t
	}

	p.checkTicker = registerTicker(p.cfg.checkInterval)
	if p.cfg.balanceEnabled && p.cfg.balanceMode == BalanceModeTimedRotation {
		p.rotateTicker = registerTicker(p.cfg.balanceInterval)
	}
	p.nextCheckAt = p.now().Add(p.cfg.checkInterval)

	checkT := p.checkTicker
	rotT := p.rotateTicker
	closeCh := p.closeCh
	p.mu.Unlock()

	if checkT != nil {
		go func() {
			for {
				select {
				case <-closeCh:
					return
				case <-checkT.C:
					p.runProbeBatch(ctx, p.allTargets(), time.Time{})
				}
			}
		}()
	}

	if rotT != nil {
		go func() {
			for {
				select {
				case <-closeCh:
					return
				case <-rotT.C:
					p.scorer.advanceRotation(p.now(), p.passive)
				}
			}
		}()
	}
}

func (p *prober) Close() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.closed = true
	close(p.closeCh)
	if p.cancelFn != nil {
		p.cancelFn()
	}
	for _, t := range []*time.Ticker{p.checkTicker, p.rotateTicker} {
		if t != nil {
			t.Stop()
		}
	}
	for _, cb := range p.pauseCallbacks {
		if p.pauseMgr != nil && cb != nil {
			p.pauseMgr.UnregisterCallback(cb)
		}
	}
	p.pauseCallbacks = nil
	p.checkTicker = nil
	p.rotateTicker = nil
	p.mu.Unlock()
}

func (p *prober) InterfaceUpdated(ctx context.Context) {
	now := p.now()
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	if !p.lastInterfaceAt.IsZero() && now.Sub(p.lastInterfaceAt) < minInterfaceDebounce {
		p.mu.Unlock()
		return
	}
	p.lastInterfaceAt = now
	baseCtx := p.ctx
	p.mu.Unlock()

	if baseCtx == nil {
		baseCtx = ctx
	}
	go func() {
		p.runProbeBatch(baseCtx, p.allTargets(), time.Time{})
	}()
}

func (p *prober) CheckAllNow(ctx context.Context) {
	if ctx == nil {
		ctx = p.ctx
		if ctx == nil {
			ctx = context.Background()
		}
	}
	p.runProbeBatch(ctx, p.allTargets(), time.Time{})
}

func (p *prober) defaultProbe(ctx context.Context, tag string) (uint16, error) {
	if p.getOutFn == nil {
		return 0, errors.New("outbound manager unavailable")
	}
	ob := p.getOutFn(tag)
	if ob == nil {
		return 0, errors.New("outbound not found")
	}
	timeout := p.cfg.probeTimeout
	if timeout <= 0 {
		timeout = probeTimeout
	}
	return customUrltest.ProbeOutbound(ctx, ob, p.link, timeout)
}

func (p *prober) defaultConnectivityCheck(ctx context.Context) bool {
	return p.pauseMgr == nil || !p.pauseMgr.IsPaused()
}

func (p *prober) runProbeBatch(ctx context.Context, targets []string, now time.Time) map[string]uint16 {
	if len(targets) == 0 {
		return nil
	}
	if ctx == nil {
		ctx = context.Background()
	}

	p.probeMu.Lock()
	defer p.probeMu.Unlock()

	if ctx.Err() != nil {
		return nil
	}

	// Deduplicate targets while preserving order
	uniq := make([]string, 0, len(targets))
	seen := make(map[string]bool, len(targets))
	for _, tag := range targets {
		if tag != "" && !seen[tag] {
			seen[tag] = true
			uniq = append(uniq, tag)
		}
	}
	if len(uniq) == 0 {
		return nil
	}

	p.checkDoneMu.Lock()
	doneCh := make(chan struct{})
	p.checkDoneCh = doneCh
	p.checking.Store(true)
	p.checkDoneMu.Unlock()

	defer func() {
		p.checking.Store(false)
		p.checkDoneMu.Lock()
		close(doneCh)
		if p.checkDoneCh == doneCh {
			p.checkDoneCh = nil
		}
		p.checkDoneMu.Unlock()
	}()

	var earlyMu sync.Mutex
	recordedEarly := make(map[string]bool, len(uniq))

	b, _ := batch.New[probeOutcome](ctx, batch.WithConcurrencyNum[probeOutcome](probeConcurrency))
	for _, tag := range uniq {
		memberTag := tag
		b.Go(memberTag, func() (probeOutcome, error) {
			delay, err := p.probeFn(ctx, memberTag)
			if err == nil && delay > 0 {
				earlyMu.Lock()
				recordedEarly[memberTag] = true
				earlyMu.Unlock()
				p.recordProbeProgress(memberTag, delay, p.now())
			}
			return probeOutcome{tag: memberTag, delay: delay, err: err}, nil
		})
	}
	resMap, _ := b.WaitAndGetResult()
	if ctx.Err() != nil {
		return nil
	}

	if now.IsZero() {
		now = p.now()
	}

	outcomes := make([]probeOutcome, 0, len(uniq))
	successCount := 0
	delays := make(map[string]uint16, len(uniq))
	for _, tag := range uniq {
		if r, ok := resMap[tag]; ok && r.Err == nil {
			out := r.Value
			outcomes = append(outcomes, out)
			if out.err == nil && out.delay > 0 {
				successCount++
				delays[tag] = out.delay
			}
		}
	}

	// Outage immunity: when >= 2 targets tested and 100% failed, roll back only when local device/service is paused/offline.
	if len(outcomes) >= 2 && successCount == 0 {
		if p.connectivityCheckFn != nil && !p.connectivityCheckFn(ctx) {
			p.localOffline.Store(true)
			p.mu.Lock()
			p.resetCheckScheduleLocked(now)
			p.mu.Unlock()
			return delays
		}
	}
	p.localOffline.Store(false)

	p.mu.Lock()
	p.lastCheckAt = now
	p.resetCheckScheduleLocked(now)
	p.mu.Unlock()

	for _, out := range outcomes {
		earlyMu.Lock()
		alreadyRecorded := recordedEarly[out.tag]
		earlyMu.Unlock()
		if alreadyRecorded {
			continue
		}
		p.scorer.recordSample(out.tag, out.delay, out.err, now)
		if p.history != nil {
			if out.err == nil && out.delay > 0 {
				p.history.StoreURLTestHistory(out.tag, &adapter.URLTestHistory{
					Time:  now,
					Delay: out.delay,
				})
			} else {
				p.history.DeleteURLTestHistory(out.tag)
			}
		}
	}

	_, _, switched, _ := p.scorer.evaluateWithTransition(now, p.passive)
	if p.history != nil {
		p.history.NotifyUpdated()
	}
	if switched && p.onSwitchFn != nil {
		p.onSwitchFn()
	}
	return delays
}

func (p *prober) recordProbeProgress(tag string, delay uint16, now time.Time) {
	if delay == 0 {
		return
	}
	if now.IsZero() {
		now = p.now()
	}
	p.scorer.recordSample(tag, delay, nil, now)
	if p.history != nil {
		p.history.StoreURLTestHistory(tag, &adapter.URLTestHistory{
			Time:  now,
			Delay: delay,
		})
	}
	_, _, switched, _ := p.scorer.evaluateWithTransition(now, p.passive)
	if p.history != nil {
		p.history.NotifyUpdated()
	}
	if switched && p.onSwitchFn != nil {
		p.onSwitchFn()
	}
}

var _ = N.NetworkTCP
