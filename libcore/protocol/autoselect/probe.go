package autoselect

import (
	"context"
	"errors"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	customUrltest "libcore/protocol/urltest"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	singUrltest "github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/batch"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service/pause"
)

const (
	probeConcurrency = 8
	probeTimeout     = 5 * time.Second
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
	onSwitchFn func(isFailback bool)

	// Injectable hooks for deterministic unit testing
	probeFn             func(ctx context.Context, tag string) (uint16, error)
	connectivityCheckFn func(ctx context.Context) bool
	nowFn               func() time.Time

	checking     atomic.Bool
	localOffline atomic.Bool

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

	watchTicker    *time.Ticker
	checkTicker    *time.Ticker
	fullScanTicker *time.Ticker
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
	p := &prober{
		cfg:      cfg,
		scorer:   s,
		passive:  passive,
		history:  history,
		pauseMgr: pauseMgr,
		logger:   logger,
		link:     customUrltest.GetFallbackLink(cfg.url),
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
	p.nextCheckAt = now.Add(p.cfg.checkInterval)
	p.mu.Unlock()

	// Trigger immediate initial warm-up check in background
	go func() {
		all := append([]string(nil), p.scorer.order...)
		if p.cfg.rankTopN > 0 && len(all) > p.cfg.rankTopN {
			all = all[:p.cfg.rankTopN]
		}
		p.runProbeBatch(ctx, all, p.now())
	}()

	p.startSchedulers(ctx)
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
	p.fullScanTicker = registerTicker(p.cfg.fullScanInterval)
	if p.cfg.watchInterval > 0 {
		p.watchTicker = registerTicker(p.cfg.watchInterval)
	}
	if p.cfg.balanceEnabled && p.cfg.balanceMode == BalanceModeTimedRotation {
		p.rotateTicker = registerTicker(p.cfg.balanceInterval)
	}
	p.nextCheckAt = p.now().Add(p.cfg.checkInterval)

	watchT := p.watchTicker
	checkT := p.checkTicker
	fullT := p.fullScanTicker
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
					now := p.now()
					p.mu.Lock()
					p.nextCheckAt = now.Add(p.cfg.checkInterval)
					p.mu.Unlock()
					targets := selectIntensiveTargets(p.scorer.order, p.scorer, p.passive, p.cfg, now)
					p.runProbeBatch(ctx, targets, now)
				}
			}
		}()
	}

	if fullT != nil {
		go func() {
			for {
				select {
				case <-closeCh:
					return
				case <-fullT.C:
					now := p.now()
					targets := selectFullScanTargets(p.scorer.order, p.cfg)
					p.runProbeBatch(ctx, targets, now)
				}
			}
		}()
	}

	if watchT != nil {
		go func() {
			for {
				select {
				case <-closeCh:
					return
				case <-watchT.C:
					now := p.now()
					targets := selectWatchTargets(p.scorer.order, p.scorer, p.passive, now)
					p.runProbeBatch(ctx, targets, now)
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
	for _, t := range []*time.Ticker{p.watchTicker, p.checkTicker, p.fullScanTicker, p.rotateTicker} {
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
	p.watchTicker = nil
	p.checkTicker = nil
	p.fullScanTicker = nil
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
		targets := selectIntensiveTargets(p.scorer.order, p.scorer, p.passive, p.cfg, now)
		p.runProbeBatch(baseCtx, targets, now)
	}()
}

func (p *prober) CheckAllNow(ctx context.Context) {
	if ctx == nil {
		ctx = p.ctx
		if ctx == nil {
			ctx = context.Background()
		}
	}
	targets := selectFullScanTargets(p.scorer.order, p.cfg)
	p.runProbeBatch(ctx, targets, p.now())
}

func (p *prober) defaultProbe(ctx context.Context, tag string) (uint16, error) {
	if p.getOutFn == nil {
		return 0, errors.New("outbound manager unavailable")
	}
	ob := p.getOutFn(tag)
	if ob == nil {
		return 0, errors.New("outbound not found")
	}
	return customUrltest.ProbeOutbound(ctx, ob, p.link, probeTimeout)
}

func (p *prober) defaultConnectivityCheck(ctx context.Context) bool {
	if p.pauseMgr != nil && p.pauseMgr.IsPaused() {
		return false
	}
	if p.cfg.connectivityURL == "" {
		return true
	}
	checkCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	d, err := dialer.NewDefault(checkCtx, option.DialerOptions{})
	if err != nil {
		return false
	}
	tr := &http.Transport{
		DialContext: func(c context.Context, network, addr string) (net.Conn, error) {
			return d.DialContext(c, network, M.ParseSocksaddr(addr))
		},
		DisableKeepAlives: true,
	}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, Timeout: 3 * time.Second}
	req, err := http.NewRequestWithContext(checkCtx, http.MethodGet, p.cfg.connectivityURL, nil)
	if err != nil {
		return false
	}
	resp, err := client.Do(req)
	if err != nil {
		return false
	}
	_ = resp.Body.Close()
	return resp.StatusCode < 500
}

func (p *prober) runProbeBatch(ctx context.Context, targets []string, now time.Time) map[string]uint16 {
	if len(targets) == 0 {
		return nil
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if now.IsZero() {
		now = p.now()
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

	b, _ := batch.New[probeOutcome](ctx, batch.WithConcurrencyNum[probeOutcome](probeConcurrency))
	for _, tag := range uniq {
		memberTag := tag
		b.Go(memberTag, func() (probeOutcome, error) {
			delay, err := p.probeFn(ctx, memberTag)
			return probeOutcome{tag: memberTag, delay: delay, err: err}, nil
		})
	}
	resMap, _ := b.WaitAndGetResult()
	if ctx.Err() != nil {
		return nil
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

	// Outage immunity: when >= 2 targets tested and 100% failed, check connectivity guard
	if len(outcomes) >= 2 && successCount == 0 {
		if p.connectivityCheckFn != nil && !p.connectivityCheckFn(ctx) {
			p.localOffline.Store(true)
			return delays
		}
	}
	p.localOffline.Store(false)

	p.mu.Lock()
	p.lastCheckAt = now
	if p.nextCheckAt.IsZero() || !now.Before(p.nextCheckAt) {
		p.nextCheckAt = now.Add(p.cfg.checkInterval)
	}
	p.mu.Unlock()

	for _, out := range outcomes {
		p.scorer.recordSample(out.tag, out.delay, out.err, now)
		if p.history != nil {
			if out.err == nil && out.delay > 0 {
				p.history.StoreURLTestHistory(out.tag, &adapter.URLTestHistory{
					Time:  now,
					Delay: out.delay,
				})
			} else {
				_, fails, _ := p.scorer.sampleCounts(out.tag)
				if fails > 1 {
					p.history.DeleteURLTestHistory(out.tag)
				}
			}
		}
	}

	_, _, switched, isFailback := p.scorer.evaluateWithTransition(now, p.passive)
	if p.history != nil {
		p.history.NotifyUpdated()
	}
	if switched && p.onSwitchFn != nil {
		p.onSwitchFn(isFailback)
	}
	return delays
}

func selectWatchTargets(all []string, s *scorer, passive *passiveTracker, now time.Time) []string {
	if s == nil {
		return nil
	}
	s.mu.RLock()
	defer s.mu.RUnlock()

	var targets []string
	seen := make(map[string]bool)
	add := func(tag string) {
		if tag != "" && !seen[tag] {
			seen[tag] = true
			targets = append(targets, tag)
		}
	}

	if s.pinnedTag != "" {
		add(s.pinnedTag)
		return targets
	}

	add(s.activeTag)
	if s.cfg.balanceEnabled {
		for _, tag := range s.verifiedPoolLocked(now, passive) {
			add(tag)
		}
	}
	// Plus top-1 standby healthy candidate
	for _, tag := range s.order {
		if tag == s.activeTag {
			continue
		}
		st := s.members[tag]
		_, _, _, _, avail := s.memberScoreLocked(st, passive, now)
		if avail {
			add(tag)
			break
		}
	}
	if len(targets) == 0 && len(all) > 0 {
		add(all[0])
	}
	return targets
}

func selectIntensiveTargets(all []string, s *scorer, passive *passiveTracker, cfg normalizedConfig, now time.Time) []string {
	if s == nil {
		return append([]string(nil), all...)
	}
	s.mu.RLock()
	defer s.mu.RUnlock()

	limit := cfg.rankTopN
	if limit <= 0 || limit > len(all) {
		limit = len(all)
	}

	seen := make(map[string]bool)
	var targets []string
	add := func(tag string) {
		if tag != "" && !seen[tag] {
			seen[tag] = true
			targets = append(targets, tag)
		}
	}

	add(s.pinnedTag)
	add(s.activeTag)

	// Standby layer
	standbyAdded := 0
	for i := 0; i < limit; i++ {
		tag := all[i]
		if tag == s.activeTag {
			continue
		}
		if st, ok := s.members[tag]; ok {
			_, _, _, _, avail := s.memberScoreLocked(st, passive, now)
			if avail && standbyAdded < cfg.standbySize {
				add(tag)
				standbyAdded++
			}
		}
	}

	// Head intensiveCheckCount
	headCap := cfg.intensiveCheckCount
	if headCap > limit {
		headCap = limit
	}
	for i := 0; i < headCap; i++ {
		add(all[i])
	}

	// Any nodes whose suspect cooldown just expired (give chance to recover)
	if passive != nil {
		for i := 0; i < limit; i++ {
			tag := all[i]
			if !passive.IsSuspect(tag, now) && !passive.LastFailureAt(tag).IsZero() {
				add(tag)
			}
		}
	}
	return targets
}

func selectFullScanTargets(all []string, cfg normalizedConfig) []string {
	if cfg.rankTopN > 0 && cfg.rankTopN < len(all) {
		return append([]string(nil), all[:cfg.rankTopN]...)
	}
	return append([]string(nil), all...)
}

// Keep unused N import check quiet or check packet dial helpers cleanly
var _ = N.NetworkTCP
