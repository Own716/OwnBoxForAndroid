package autoselector

import (
	"context"
	"io"
	"math/rand"
	"net"
	"slices"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func RegisterAutoSelector(registry *outbound.Registry) {
	outbound.Register[AutoSelectorOptions](registry, TypeAutoSelector, NewAutoSelector)
}

var (
	_ adapter.OutboundGroup           = (*AutoSelector)(nil)
	_ adapter.URLTestGroup            = (*AutoSelector)(nil)
	_ adapter.Referrer                = (*AutoSelector)(nil)
	_ adapter.InterfaceUpdateListener = (*AutoSelector)(nil)
	_ AutoSelectorGroup               = (*AutoSelector)(nil)
)

const (
	BalanceModeRotate     = "rotate"
	BalanceModeConnection = "connection"
)

const (
	defaultAutoInterval      = 1 * time.Minute
	defaultAutoBenchInterval = 10 * time.Minute
	defaultAutoSampling      = 10
	defaultAutoActiveSize    = 10
	defaultAutoTimeout       = 5 * time.Second
	defaultAutoConcurrency   = 16
	defaultAutoTolerance     = 100
	defaultAutoFailTolerance = 0.5
	defaultAutoExpected      = 5
	defaultAutoBalanceInterval = 30 * time.Second
	defaultAutoDialRetries     = 2

	defaultAutoWatchInterval = 15 * time.Second
	minKickGap               = 5 * time.Second

	outageProbeQuorum    = 4
	outageRollbackWindow = 45 * time.Second
	recoveryInterval     = 5 * time.Second
	osResumeGrace        = 5 * time.Minute
	optimisticProbeInterval = 30 * time.Second
	optimisticProbeSize     = 3
	dialOutageSuspicion     = 3
	connectivityTTL         = 5 * time.Second
)

var dialBackoff = []time.Duration{
	5 * time.Second,
	15 * time.Second,
	45 * time.Second,
	2 * time.Minute,
	5 * time.Minute,
}

type AutoSelector struct {
	outbound.Adapter
	ctx      context.Context
	cancel   context.CancelFunc
	outbound adapter.OutboundManager
	network  adapter.NetworkManager
	pause    pause.Manager
	history  *urltest.HistoryStorage
	logger   log.ContextLogger

	tags       []string
	link       string
	connectURL string

	interval        time.Duration
	benchInterval   time.Duration
	watchInterval   time.Duration
	activeSize      int
	sampling        int
	probeTimeout    time.Duration
	concurrency     int
	tolerance       time.Duration
	maxRTT          time.Duration
	failTolerance   float64
	baselines       []time.Duration
	expected        int
	balance         bool
	balanceMode     string
	balanceInterval time.Duration
	dialRetries     int

	interruptGroup               *interrupt.Group
	attachedGroup                *interrupt.Group
	interruptExternalConnections bool

	access     sync.Mutex
	members    map[string]*memberHealth
	outbounds  map[string]adapter.Outbound
	handles    map[string]adapter.Outbound
	ranked     []*rankedNode
	qualified  []string
	activeSet  map[string]bool
	benchOrder []string
	benchIdx   int

	selectedTCP common.TypedValue[adapter.Outbound]
	selectedUDP common.TypedValue[adapter.Outbound]

	pinnedTag        string
	suspended        bool
	suspendedSince   time.Time
	optimisticIdx    int
	roundsCompleted  int
	lastRoundAt      time.Time
	nextRoundAt      time.Time
	probesInFlight   int
	lastSwitchAt     time.Time
	lastSwitchReason string
	started          bool
	nextRotateAt     time.Time
	lastKickAt       time.Time
	recentDialFails  map[string]time.Time

	connAccess    sync.Mutex
	connOK        bool
	connCheckedAt time.Time

	pauseCallback *list.Element[pause.Callback]
	kick          chan struct{}
	close         chan struct{}
	closeOnce     sync.Once
}

func deduplicateMemberTags(tags []string) []string {
	if len(tags) <= 1 {
		return tags
	}
	seen := make(map[string]struct{}, len(tags))
	result := make([]string, 0, len(tags))
	for _, tag := range tags {
		if tag == "" {
			continue
		}
		if _, ok := seen[tag]; ok {
			continue
		}
		seen[tag] = struct{}{}
		result = append(result, tag)
	}
	return result
}

func NewAutoSelector(ctx context.Context, _ adapter.Router, logger log.ContextLogger, tag string, options AutoSelectorOptions) (adapter.Outbound, error) {
	tags := deduplicateMemberTags(options.Outbounds)
	if len(tags) == 0 {
		return nil, E.New("missing tags")
	}
	groupCtx, cancel := context.WithCancel(ctx)
	s := &AutoSelector{
		Adapter:                      outbound.NewAdapter(TypeAutoSelector, tag, []string{N.NetworkTCP, N.NetworkUDP}, tags),
		ctx:                          groupCtx,
		cancel:                       cancel,
		outbound:                     service.FromContext[adapter.OutboundManager](ctx),
		network:                      service.FromContext[adapter.NetworkManager](ctx),
		pause:                        service.FromContext[pause.Manager](ctx),
		history:                      service.PtrFromContext[urltest.HistoryStorage](ctx),
		logger:                       logger,
		tags:                         tags,
		link:                         options.URL,
		connectURL:                   options.ConnectivityURL,
		interval:                     time.Duration(options.Interval),
		benchInterval:                time.Duration(options.BenchInterval),
		watchInterval:                time.Duration(options.WatchInterval),
		activeSize:                   options.ActiveSize,
		sampling:                     options.Sampling,
		probeTimeout:                 time.Duration(options.Timeout),
		concurrency:                  options.Concurrency,
		maxRTT:                       time.Duration(options.MaxRTT),
		failTolerance:                options.FailTolerance,
		expected:                     options.Expected,
		balance:                      options.Balance,
		balanceMode:                  options.BalanceMode,
		balanceInterval:              time.Duration(options.BalanceInterval),
		interruptGroup:               interrupt.NewGroup(),
		attachedGroup:                interrupt.NewGroup(),
		interruptExternalConnections: options.InterruptExistConnections,
		members:                      make(map[string]*memberHealth, len(tags)),
		outbounds:                    make(map[string]adapter.Outbound, len(tags)),
		handles:                      make(map[string]adapter.Outbound, len(tags)),
		activeSet:                    make(map[string]bool),
		recentDialFails:              make(map[string]time.Time),
		kick:                         make(chan struct{}, 1),
		close:                        make(chan struct{}),
	}
	if options.Tolerance != nil {
		if *options.Tolerance > 0 {
			s.tolerance = time.Duration(*options.Tolerance) * time.Millisecond
		}
	} else {
		s.tolerance = defaultAutoTolerance * time.Millisecond
	}
	if options.DialRetries != nil {
		if *options.DialRetries > 0 {
			s.dialRetries = *options.DialRetries
		}
	} else {
		s.dialRetries = defaultAutoDialRetries
	}
	for _, baseline := range options.Baselines {
		s.baselines = append(s.baselines, time.Duration(baseline))
	}
	s.applyDefaults()

	validity := s.interval * time.Duration(s.sampling) * 2
	if benchValidity := s.benchInterval * 2; benchValidity > validity {
		validity = benchValidity
	}
	for _, memberTag := range s.tags {
		if _, exists := s.members[memberTag]; exists {
			continue
		}
		s.members[memberTag] = newMemberHealth(memberTag, s.sampling, validity)
	}
	s.restoreWarm(options.Warm, time.Now())
	if common.Contains(s.tags, options.Pinned) {
		s.pinnedTag = options.Pinned
	}
	s.benchOrder = append(s.benchOrder, s.tags...)
	return s, nil
}

func (s *AutoSelector) restoreWarm(entries []WarmEntry, now time.Time) int {
	restored := 0
	seen := make(map[string]bool, len(entries))
	for _, entry := range entries {
		health, loaded := s.members[entry.Tag]
		if !loaded || seen[entry.Tag] {
			continue
		}
		seen[entry.Tag] = true
		value := rttFailed
		if entry.RTT > 0 {
			value = time.Duration(entry.RTT) * time.Millisecond
		}
		age := entry.Age
		if age < 0 {
			age = 0
		}
		at := now.Add(-time.Duration(age) * time.Second)
		if now.Sub(at) > health.validity {
			continue
		}
		health.put(value, at)
		restored++
	}
	return restored
}

func (s *AutoSelector) applyDefaults() {
	if s.interval <= 0 {
		s.interval = defaultAutoInterval
	}
	if s.benchInterval <= 0 {
		s.benchInterval = defaultAutoBenchInterval
	}
	if s.benchInterval < s.interval {
		s.benchInterval = s.interval
	}
	if s.sampling <= 0 {
		s.sampling = defaultAutoSampling
	}
	if s.activeSize <= 0 {
		s.activeSize = defaultAutoActiveSize
	}
	if s.expected <= 0 {
		s.expected = defaultAutoExpected
	}
	if s.activeSize < s.expected {
		s.activeSize = s.expected
	}
	if s.activeSize > len(s.tags) {
		s.activeSize = len(s.tags)
	}
	if s.watchInterval <= 0 {
		s.watchInterval = defaultAutoWatchInterval
	}
	if s.watchInterval > s.interval {
		s.watchInterval = s.interval
	}
	if s.probeTimeout <= 0 {
		s.probeTimeout = defaultAutoTimeout
	}
	if s.concurrency <= 0 {
		s.concurrency = defaultAutoConcurrency
	}
	if s.failTolerance <= 0 || s.failTolerance > 1 {
		s.failTolerance = defaultAutoFailTolerance
	}
	if s.balanceMode != BalanceModeConnection {
		s.balanceMode = BalanceModeRotate
	}
	if s.balanceInterval <= 0 {
		s.balanceInterval = defaultAutoBalanceInterval
	}
}

func (s *AutoSelector) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	switch stage {
	case adapter.StartStateStart:
		return s.start()
	case adapter.StartStateStarted:
		s.access.Lock()
		s.started = true
		s.access.Unlock()
		if scope != nil {
			scope.Add(s.shutdown)
		}
		go s.loop()
		go s.watchLoop()
	}
	return nil
}

func (s *AutoSelector) start() error {
	if s.outbound == nil {
		return E.New("missing outbound manager")
	}
	for i, tag := range s.tags {
		detour, loaded := s.outbound.Outbound(tag)
		if !loaded {
			return E.New("outbound ", i, " not found: ", tag)
		}
		s.outbounds[tag] = detour
		_, isGroup := detour.(adapter.OutboundGroup)
		_, isFlow := detour.(adapter.FlowOutbound)
		if !isGroup && !isFlow {
			s.handles[tag] = &memberHandle{Outbound: detour, selector: s}
		}
	}

	s.access.Lock()
	s.ranked = make([]*rankedNode, 0, len(s.tags))
	for _, tag := range s.tags {
		s.ranked = append(s.ranked, &rankedNode{tag: tag, tier: tierUntested})
	}
	s.refreshActiveSetLocked()
	warmed := 0
	for _, health := range s.members {
		if health.probes > 0 {
			warmed++
		}
	}
	if warmed > 0 {
		s.evaluateLocked(time.Now())
	}
	s.access.Unlock()
	if warmed > 0 && s.logger != nil {
		s.logger.Info("auto-selector: restored health for ", warmed, " of ", len(s.tags), " members")
	}

	if s.selectedFor(N.NetworkTCP) != nil {
		return nil
	}

	var restored adapter.Outbound
	if s.Tag() != "" {
		if cacheFile := service.FromContext[adapter.CacheFile](s.ctx); cacheFile != nil {
			if selected := cacheFile.LoadSelected(s.Tag()); selected != "" {
				restored = s.outbounds[selected]
			}
		}
	}
	if restored == nil {
		restored = s.outbounds[s.tags[0]]
	}
	s.setSelected(restored, N.NetworkTCP, "initial")
	if common.Contains(restored.Network(), N.NetworkUDP) {
		s.setSelected(restored, N.NetworkUDP, "initial")
	} else {
		for _, tag := range s.tags {
			if detour := s.outbounds[tag]; common.Contains(detour.Network(), N.NetworkUDP) {
				s.setSelected(detour, N.NetworkUDP, "initial")
				break
			}
		}
	}
	return nil
}

func (s *AutoSelector) shutdown() error {
	s.closeOnce.Do(func() {
		close(s.close)
		s.cancel()
	})
	s.access.Lock()
	if s.pauseCallback != nil && s.pause != nil {
		s.pause.UnregisterCallback(s.pauseCallback)
		s.pauseCallback = nil
	}
	s.access.Unlock()
	return nil
}

func (s *AutoSelector) All() []string {
	return s.tags
}

func (s *AutoSelector) Selected(network string) adapter.Outbound {
	detour := s.selectedFor(network)
	if detour == nil {
		return nil
	}
	if handle, loaded := s.handles[detour.Tag()]; loaded {
		return handle
	}
	return detour
}

func (s *AutoSelector) AttachConnection(closer io.Closer) func() {
	if s.attachedGroup == nil {
		return func() {}
	}
	return s.attachedGroup.Add(closer, true)
}

func (s *AutoSelector) References() []string {
	s.access.Lock()
	defer s.access.Unlock()
	var references []string
	selectedTCP := s.selectedTCP.Load()
	if selectedTCP != nil {
		references = append(references, selectedTCP.Tag())
	}
	if selectedUDP := s.selectedUDP.Load(); selectedUDP != nil && selectedUDP != selectedTCP {
		references = append(references, selectedUDP.Tag())
	}
	if s.balance && s.pinnedTag == "" {
		references = append(references, s.qualified...)
	}
	return references
}

func (s *AutoSelector) notifyReferences() {
	if s.history != nil {
		s.history.NotifyUpdated()
	}
}

func (s *AutoSelector) SelectOutbound(tag string) bool {
	if tag == "" {
		s.access.Lock()
		s.pinnedTag = ""
		s.evaluateLocked(time.Now())
		balance := s.balance
		s.access.Unlock()
		if s.logger != nil {
			s.logger.Info("auto-selector: released the pinned member, back to automatic")
		}
		if balance {
			s.notifyReferences()
		}
		return true
	}
	detour, loaded := s.outbounds[tag]
	if !loaded {
		return false
	}
	s.access.Lock()
	s.pinnedTag = tag
	balance := s.balance
	s.access.Unlock()

	s.setSelected(detour, N.NetworkTCP, "pinned by user")
	if common.Contains(detour.Network(), N.NetworkUDP) {
		s.setSelected(detour, N.NetworkUDP, "pinned by user")
	}
	if balance {
		s.notifyReferences()
	}
	return true
}

func (s *AutoSelector) pinnedFor(network string, byTag map[string]*rankedNode) adapter.Outbound {
	if s.pinnedTag == "" {
		return nil
	}
	node, loaded := byTag[s.pinnedTag]
	if !loaded || node.tier != tierHealthy {
		return nil
	}
	detour, loaded := s.outbounds[s.pinnedTag]
	if !loaded || !common.Contains(detour.Network(), network) {
		return nil
	}
	return detour
}

func (s *AutoSelector) registerTicker(ticker *time.Ticker, duration time.Duration) *list.Element[pause.Callback] {
	if s.pause == nil {
		return nil
	}
	return pause.RegisterTicker(s.pause, ticker, duration, nil)
}

func (s *AutoSelector) unregisterTicker(cb *list.Element[pause.Callback]) {
	if cb != nil && s.pause != nil {
		s.pause.UnregisterCallback(cb)
	}
}

func (s *AutoSelector) loop() {
	s.runRound(true)

	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	cb := s.registerTicker(ticker, s.interval)
	s.access.Lock()
	s.pauseCallback = cb
	s.nextRoundAt = time.Now().Add(s.interval)
	s.access.Unlock()

	for {
		urgent := false
		select {
		case <-s.close:
			return
		case <-s.kick:
			urgent = true
		case <-ticker.C:
		}
		if s.isSuspended() {
			continue
		}
		s.runRound(urgent)
		s.access.Lock()
		s.nextRoundAt = time.Now().Add(s.interval)
		s.access.Unlock()
	}
}

func (s *AutoSelector) requestRound() {
	s.access.Lock()
	if !s.lastKickAt.IsZero() && time.Since(s.lastKickAt) < minKickGap {
		s.access.Unlock()
		return
	}
	s.lastKickAt = time.Now()
	s.access.Unlock()
	select {
	case s.kick <- struct{}{}:
	default:
	}
}

func (s *AutoSelector) InterfaceUpdated(_ context.Context) {
	if s.pause != nil && s.pause.IsPaused() {
		return
	}
	s.access.Lock()
	if !s.started || s.suspended {
		s.access.Unlock()
		return
	}
	s.lastKickAt = time.Now()
	s.access.Unlock()
	select {
	case s.kick <- struct{}{}:
	default:
	}
}

func (s *AutoSelector) watchLoop() {
	ticker := time.NewTicker(s.watchInterval)
	defer ticker.Stop()
	pauseCallback := s.registerTicker(ticker, s.watchInterval)
	defer s.unregisterTicker(pauseCallback)
	for {
		select {
		case <-s.close:
			return
		case <-ticker.C:
		}
		if s.isSuspended() {
			continue
		}
		detour := s.selectedFor(N.NetworkTCP)
		if detour == nil {
			continue
		}
		value, err := s.probe(detour)
		now := time.Now()
		if value != rttFailed {
			s.access.Lock()
			if health, loaded := s.members[detour.Tag()]; loaded {
				health.put(value, now)
				health.lastDialErr = ""
				health.clearCooldown()
			}
			s.access.Unlock()
			continue
		}
		if isLocalNetworkError(err) {
			go s.assessOutage()
			continue
		}
		s.access.Lock()
		if health, loaded := s.members[detour.Tag()]; loaded {
			health.put(rttFailed, now)
			if err != nil {
				health.lastDialErr = err.Error()
			}
		}
		s.evaluateLocked(now)
		s.access.Unlock()
		if s.logger != nil {
			s.logger.Debug("auto-selector: selected member ", detour.Tag(), " failed its watch probe, checking the active tier")
		}
		s.requestRound()
	}
}

func (s *AutoSelector) roundBatch(urgent bool) []string {
	s.access.Lock()
	defer s.access.Unlock()

	batch := make([]string, 0, s.activeSize+8)
	seen := make(map[string]bool, s.activeSize+8)
	for i := 0; i < len(s.ranked) && len(batch) < s.activeSize; i++ {
		tag := s.ranked[i].tag
		if seen[tag] {
			continue
		}
		seen[tag] = true
		batch = append(batch, tag)
	}
	if urgent || len(s.benchOrder) == 0 {
		return batch
	}

	benchTotal := len(s.benchOrder)
	roundsPerCycle := int(s.benchInterval / s.interval)
	if roundsPerCycle < 1 {
		roundsPerCycle = 1
	}
	perRound := (benchTotal + roundsPerCycle - 1) / roundsPerCycle
	for i := 0; i < perRound; i++ {
		tag := s.benchOrder[s.benchIdx%benchTotal]
		s.benchIdx = (s.benchIdx + 1) % benchTotal
		if seen[tag] {
			continue
		}
		seen[tag] = true
		batch = append(batch, tag)
	}
	return batch
}

type probeResult struct {
	tag   string
	value time.Duration
	err   error
	at    time.Time
}

func (s *AutoSelector) runRound(urgent bool) {
	batch := s.roundBatch(urgent)
	if len(batch) == 0 {
		return
	}
	startedAt := time.Now()
	results := s.probeBatch(batch, urgent)
	if len(results) == 0 {
		return
	}
	s.commitRound(results, startedAt)
}

func (s *AutoSelector) probeBatch(batch []string, urgent bool) []probeResult {
	var (
		wg      sync.WaitGroup
		mu      sync.Mutex
		results = make([]probeResult, 0, len(batch))
		limiter = make(chan struct{}, s.concurrency)
	)
	spread := s.interval / 2
	for _, tag := range batch {
		detour, loaded := s.outbounds[tag]
		if !loaded {
			continue
		}
		wg.Add(1)
		go func(tag string, detour adapter.Outbound) {
			defer wg.Done()
			if !urgent && spread > 0 {
				delay := time.Duration(rand.Int63n(int64(spread)))
				select {
				case <-time.After(delay):
				case <-s.close:
					return
				}
				if s.pause != nil && s.pause.IsPaused() {
					return
				}
			}
			select {
			case limiter <- struct{}{}:
			case <-s.close:
				return
			}
			defer func() { <-limiter }()

			s.access.Lock()
			s.probesInFlight++
			s.access.Unlock()
			value, err := s.probe(detour)
			s.access.Lock()
			s.probesInFlight--
			s.access.Unlock()

			mu.Lock()
			results = append(results, probeResult{tag: tag, value: value, err: err, at: time.Now()})
			mu.Unlock()
		}(tag, detour)
	}
	wg.Wait()
	return results
}

func (s *AutoSelector) probe(detour adapter.Outbound) (time.Duration, error) {
	ctx, cancel := context.WithTimeout(s.ctx, s.probeTimeout)
	defer cancel()
	delay, err := urltest.URLTest(ctx, s.link, detour)
	if err != nil {
		return rttFailed, err
	}
	return time.Duration(delay) * time.Millisecond, nil
}

func (s *AutoSelector) commitRound(results []probeResult, startedAt time.Time) {
	var succeeded, failed, localErrors int
	for _, result := range results {
		if result.value == rttFailed {
			failed++
			if isLocalNetworkError(result.err) {
				localErrors++
			}
		} else {
			succeeded++
		}
	}

	brokeTogether := 0
	s.access.Lock()
	for _, result := range results {
		if result.value != rttFailed {
			continue
		}
		health, loaded := s.members[result.tag]
		if !loaded {
			continue
		}
		if stats := health.stats(result.at); stats.All > 0 && stats.Fail == 0 {
			brokeTogether++
		}
	}
	s.access.Unlock()

	outage := false
	if failed > 0 {
		switch {
		case s.networkInterfaceDown():
			outage = true
		case succeeded == 0 && localErrors*2 >= failed:
			outage = true
		case succeeded == 0 && failed >= outageProbeQuorum:
			outage = !s.connectivityOK(true)
		case brokeTogether >= outageProbeQuorum:
			outage = !s.connectivityOK(true)
		}
	}

	if outage && succeeded > 0 && s.isSuspended() {
		outage = false
	}

	now := time.Now()
	if outage {
		s.enterSuspended(now, startedAt)
		return
	}

	s.access.Lock()
	for _, result := range results {
		health, loaded := s.members[result.tag]
		if !loaded {
			continue
		}
		if result.err != nil && isLocalNetworkError(result.err) {
			health.lastDialErr = result.err.Error()
			continue
		}
		health.put(result.value, result.at)
		if result.err != nil {
			health.lastDialErr = result.err.Error()
		} else if result.value != rttFailed {
			health.lastDialErr = ""
			health.clearCooldown()
		}
	}
	s.roundsCompleted++
	s.lastRoundAt = now
	wasSuspended := s.suspended
	s.suspended = false
	s.suspendedSince = time.Time{}
	s.evaluateLocked(now)
	s.access.Unlock()

	if wasSuspended && s.logger != nil {
		s.logger.Info("auto-selector: local network recovered, resuming health checks")
	}
}

func (s *AutoSelector) networkInterfaceDown() bool {
	return s.defaultInterfaceIndex() < 0
}

func (s *AutoSelector) defaultInterfaceIndex() int {
	if s.network == nil {
		return 0
	}
	iif := s.network.DefaultNetworkInterface()
	if iif == nil {
		return -1
	}
	return iif.Index
}

func (s *AutoSelector) connectivityOK(force bool) bool {
	if s.connectURL == "" {
		return !s.networkInterfaceDown()
	}
	s.connAccess.Lock()
	defer s.connAccess.Unlock()
	age := time.Since(s.connCheckedAt)
	if age < connectivityTTL && !force {
		return s.connOK
	}
	if age < time.Second {
		return s.connOK
	}
	ctx, cancel := context.WithTimeout(s.ctx, s.probeTimeout)
	defer cancel()
	// Use protected "direct" outbound when available so Android VPN TUN never loops.
	var dialer N.Dialer = N.SystemDialer
	if s.outbound != nil {
		if directOutbound, ok := s.outbound.Outbound("direct"); ok && directOutbound != nil {
			dialer = directOutbound
		}
	}
	_, err := urltest.URLTest(ctx, s.connectURL, dialer)
	s.connOK = err == nil
	s.connCheckedAt = time.Now()
	return s.connOK
}

func (s *AutoSelector) isSuspended() bool {
	s.access.Lock()
	defer s.access.Unlock()
	return s.suspended
}

func (s *AutoSelector) enterSuspended(now time.Time, since time.Time) {
	s.access.Lock()
	if s.suspended {
		s.access.Unlock()
		return
	}
	s.suspended = true
	s.suspendedSince = now
	since = rollbackSince(now, since)
	rolledSamples := 0
	rolledCooldowns := 0
	for _, health := range s.members {
		rolledSamples += health.rollbackFailuresSince(since)
		if health.clearCooldownSince(since) {
			rolledCooldowns++
		}
	}
	s.access.Unlock()

	if s.logger != nil {
		s.logger.Warn("auto-selector: local network is down, suspending health checks (rolled back ",
			rolledSamples, " samples, ", rolledCooldowns, " cooldowns)")
	}
	go s.recoveryLoop()
}

func (s *AutoSelector) recoveryLoop() {
	ticker := time.NewTicker(recoveryInterval)
	defer ticker.Stop()
	pauseCallback := s.registerTicker(ticker, recoveryInterval)
	defer s.unregisterTicker(pauseCallback)
	backoff := recoveryInterval
	var (
		lastAttempt    time.Time
		lastOptimistic time.Time
		lastIndex      = -1
	)
	for {
		select {
		case <-s.close:
			return
		case <-ticker.C:
		}
		if !s.isSuspended() {
			return
		}
		index := s.defaultInterfaceIndex()
		if index < 0 {
			backoff = recoveryInterval
			lastAttempt = time.Time{}
			lastIndex = -1
		} else if index != lastIndex {
			lastIndex = index
			backoff = recoveryInterval
			lastAttempt = time.Time{}
		}

		now := time.Now()
		ready := index >= 0
		if ready && s.connectURL != "" && s.suspendedFor(now) < osResumeGrace {
			ready = s.connectivityOK(true)
		}
		if !ready {
			if lastOptimistic.IsZero() || now.Sub(lastOptimistic) >= optimisticProbeInterval {
				lastOptimistic = now
				s.optimisticRound()
				if !s.isSuspended() {
					return
				}
			}
			continue
		}

		if !lastAttempt.IsZero() && now.Sub(lastAttempt) < backoff {
			continue
		}
		lastAttempt = now
		s.runRound(true)
		if !s.isSuspended() {
			return
		}
		if backoff < s.interval {
			backoff = min(backoff*2, s.interval)
		}
	}
}

func (s *AutoSelector) suspendedFor(now time.Time) time.Duration {
	s.access.Lock()
	defer s.access.Unlock()
	if s.suspendedSince.IsZero() {
		return 0
	}
	return now.Sub(s.suspendedSince)
}

func (s *AutoSelector) optimisticRound() {
	batch := s.optimisticBatch()
	if len(batch) == 0 {
		return
	}
	startedAt := time.Now()
	succeeded := make([]probeResult, 0, len(batch))
	for _, result := range s.probeBatch(batch, true) {
		if result.value != rttFailed {
			succeeded = append(succeeded, result)
		}
	}
	if len(succeeded) == 0 {
		return
	}
	s.commitRound(succeeded, startedAt)
}

func (s *AutoSelector) optimisticBatch() []string {
	s.access.Lock()
	defer s.access.Unlock()
	if len(s.ranked) == 0 {
		return nil
	}
	size := min(optimisticProbeSize, len(s.ranked))
	batch := make([]string, 0, size)
	for i := 0; i < size; i++ {
		s.optimisticIdx %= len(s.ranked)
		batch = append(batch, s.ranked[s.optimisticIdx].tag)
		s.optimisticIdx++
	}
	return batch
}

func (s *AutoSelector) evaluateLocked(now time.Time) {
	nodes := make([]*rankedNode, 0, len(s.tags))
	byTag := make(map[string]*rankedNode, len(s.tags))
	for _, tag := range s.tags {
		health, loaded := s.members[tag]
		if !loaded {
			continue
		}
		stats := health.stats(now)
		node := &rankedNode{tag: tag, stats: stats}
		switch {
		case health.inCooldown(now):
			node.tier = tierCooldown
		case stats.All == 0:
			node.tier = tierUntested
		case !stats.Alive():
			node.tier = tierDead
		default:
			node.tier = tierHealthy
		}
		nodes = append(nodes, node)
		byTag[tag] = node
	}
	prior := make(map[string]int, len(s.ranked))
	for i, node := range s.ranked {
		prior[node.tag] = i
	}
	ordered := make([]*rankedNode, len(nodes))
	copy(ordered, nodes)
	stableByPrior(ordered, prior)
	sortNodes(ordered)
	s.ranked = ordered

	eligible := make([]*rankedNode, 0, len(ordered))
	for _, node := range ordered {
		if node.tier != tierHealthy {
			continue
		}
		if s.maxRTT > 0 && node.stats.Average >= s.maxRTT {
			continue
		}
		if node.stats.FailRatio() > s.failTolerance {
			continue
		}
		eligible = append(eligible, node)
	}
	qualified := applyBaselines(eligible, s.baselines, s.expected)
	qualifiedTags := make([]string, 0, len(qualified))
	for _, node := range qualified {
		qualifiedTags = append(qualifiedTags, node.tag)
	}
	balancedSetChanged := s.balance && s.pinnedTag == "" && !slices.Equal(qualifiedTags, s.qualified)
	s.qualified = qualifiedTags
	if balancedSetChanged {
		s.notifyReferences()
	}
	s.refreshActiveSetLocked()
	s.updateSelectionLocked(qualified, byTag, now)
}

func (s *AutoSelector) refreshActiveSetLocked() {
	s.activeSet = make(map[string]bool, s.activeSize)
	for i := 0; i < len(s.ranked) && i < s.activeSize; i++ {
		s.activeSet[s.ranked[i].tag] = true
	}
}

func (s *AutoSelector) updateSelectionLocked(qualified []*rankedNode, byTag map[string]*rankedNode, now time.Time) {
	s.updateNetworkSelectionLocked(N.NetworkTCP, qualified, byTag, now)
	s.updateNetworkSelectionLocked(N.NetworkUDP, qualified, byTag, now)
}

func (s *AutoSelector) updateNetworkSelectionLocked(network string, qualified []*rankedNode, byTag map[string]*rankedNode, now time.Time) {
	if pinned := s.pinnedFor(network, byTag); pinned != nil {
		if current := s.selectedFor(network); current == nil || current.Tag() != pinned.Tag() {
			s.setSelectedLocked(pinned, network, "pinned by user", now, true)
		}
		return
	}

	candidates := make([]*rankedNode, 0, len(qualified))
	for _, node := range qualified {
		if detour, loaded := s.outbounds[node.tag]; loaded && common.Contains(detour.Network(), network) {
			candidates = append(candidates, node)
		}
	}
	current := s.selectedFor(network)

	if len(candidates) == 0 {
		if current != nil {
			if node, loaded := byTag[current.Tag()]; loaded && node.tier == tierHealthy {
				return
			}
		}
		for _, node := range s.ranked {
			if node.tier == tierCooldown {
				continue
			}
			if detour, loaded := s.outbounds[node.tag]; loaded && common.Contains(detour.Network(), network) {
				s.setSelectedLocked(detour, network, "fallback: no qualified member", now, true)
				return
			}
		}
		for _, node := range s.ranked {
			if detour, loaded := s.outbounds[node.tag]; loaded && common.Contains(detour.Network(), network) {
				s.setSelectedLocked(detour, network, "fallback: every member is cooling down", now, true)
				return
			}
		}
		return
	}

	if current != nil {
		for _, node := range candidates {
			if node.tag == current.Tag() {
				if node.stats.Average <= candidates[0].stats.Average+s.tolerance {
					return
				}
				break
			}
		}
	}
	s.setSelectedLocked(s.outbounds[candidates[0].tag], network, "best available", now, true)
}

func (s *AutoSelector) selectedFor(network string) adapter.Outbound {
	if network == N.NetworkUDP {
		return s.selectedUDP.Load()
	}
	return s.selectedTCP.Load()
}

func (s *AutoSelector) setSelected(detour adapter.Outbound, network string, reason string) {
	s.access.Lock()
	s.setSelectedLocked(detour, network, reason, time.Now(), true)
	s.access.Unlock()
}

func (s *AutoSelector) setSelectedLocked(detour adapter.Outbound, network string, reason string, now time.Time, interruptExisting bool) {
	if detour == nil {
		return
	}
	var previous adapter.Outbound
	if network == N.NetworkUDP {
		previous = s.selectedUDP.Swap(detour)
	} else {
		previous = s.selectedTCP.Swap(detour)
	}
	if previous == detour {
		return
	}
	s.lastSwitchAt = now
	s.lastSwitchReason = reason
	if network == N.NetworkTCP && s.Tag() != "" {
		if cacheFile := service.FromContext[adapter.CacheFile](s.ctx); cacheFile != nil {
			if err := cacheFile.StoreSelected(s.Tag(), detour.Tag()); err != nil && s.logger != nil {
				s.logger.Debug("auto-selector: store selected: ", err)
			}
		}
	}
	s.notifyReferences()
	if previous != nil && s.started {
		if s.logger != nil {
			s.logger.Info("auto-selector: ", network, " ", previous.Tag(), " -> ", detour.Tag(), " (", reason, ")")
		}
		if interruptExisting {
			if s.interruptGroup != nil {
				s.interruptGroup.Interrupt(s.interruptExternalConnections)
			}
			if s.attachedGroup != nil {
				s.attachedGroup.Interrupt(s.interruptExternalConnections)
			}
		}
	}
}

func (s *AutoSelector) pick(network string, tried map[string]bool) adapter.Outbound {
	s.access.Lock()
	defer s.access.Unlock()
	now := time.Now()

	balancing := s.balance && s.pinnedTag == ""
	if balancing && s.balanceMode == BalanceModeRotate && now.After(s.nextRotateAt) {
		s.rotateLocked(network, now)
	}
	if balancing && s.balanceMode == BalanceModeConnection && len(s.qualified) > 1 {
		if detour := s.pickRandomQualifiedLocked(network, tried, now); detour != nil {
			return detour
		}
	}

	if current := s.selectedFor(network); current != nil && !tried[current.Tag()] {
		if health, loaded := s.members[current.Tag()]; !loaded || !health.inCooldown(now) {
			return current
		}
	}
	for _, tag := range s.qualified {
		if detour := s.usableLocked(tag, network, tried, now, false); detour != nil {
			return detour
		}
	}
	for _, node := range s.ranked {
		if detour := s.usableLocked(node.tag, network, tried, now, false); detour != nil {
			return detour
		}
	}
	for _, node := range s.ranked {
		if detour := s.usableLocked(node.tag, network, tried, now, true); detour != nil {
			return detour
		}
	}
	return nil
}

func (s *AutoSelector) usableLocked(tag string, network string, tried map[string]bool, now time.Time, ignoreCooldown bool) adapter.Outbound {
	if tried[tag] {
		return nil
	}
	detour, loaded := s.outbounds[tag]
	if !loaded || !common.Contains(detour.Network(), network) {
		return nil
	}
	if !ignoreCooldown {
		if health, ok := s.members[tag]; ok && health.inCooldown(now) {
			return nil
		}
	}
	return detour
}

func (s *AutoSelector) pickRandomQualifiedLocked(network string, tried map[string]bool, now time.Time) adapter.Outbound {
	candidates := make([]adapter.Outbound, 0, len(s.qualified))
	for _, tag := range s.qualified {
		if detour := s.usableLocked(tag, network, tried, now, false); detour != nil {
			candidates = append(candidates, detour)
		}
	}
	if len(candidates) == 0 {
		return nil
	}
	return candidates[rand.Intn(len(candidates))]
}

func (s *AutoSelector) rotateLocked(network string, now time.Time) {
	s.nextRotateAt = now.Add(s.balanceInterval)
	if len(s.qualified) < 2 {
		return
	}
	current := s.selectedFor(network)
	start := 0
	if current != nil {
		for i, tag := range s.qualified {
			if tag == current.Tag() {
				start = i + 1
				break
			}
		}
	}
	for i := 0; i < len(s.qualified); i++ {
		tag := s.qualified[(start+i)%len(s.qualified)]
		if detour := s.usableLocked(tag, network, nil, now, false); detour != nil {
			s.setSelectedLocked(detour, network, "balance rotation", now, false)
			return
		}
	}
}

func (s *AutoSelector) noteDialResult(detour adapter.Outbound, network string, err error) {
	now := time.Now()
	tag := detour.Tag()

	if err == nil {
		s.access.Lock()
		if health, loaded := s.members[tag]; loaded {
			health.reward(now)
		}
		delete(s.recentDialFails, tag)
		if current := s.selectedFor(network); current == nil || current.Tag() != tag {
			s.setSelectedLocked(detour, network, "failover succeeded", now, false)
			if current != nil && s.started && s.interruptGroup != nil {
				s.interruptGroup.Interrupt(s.interruptExternalConnections)
			}
		}
		s.access.Unlock()
		return
	}

	if E.IsMulti(err, context.Canceled, net.ErrClosed) {
		return
	}
	if isLocalNetworkError(err) {
		go s.assessOutage()
		return
	}

	s.access.Lock()
	suspended := s.suspended
	if !suspended {
		if health, loaded := s.members[tag]; loaded {
			health.penalize(now, dialBackoff, err)
		}
		s.recentDialFails[tag] = now
		for failedTag, at := range s.recentDialFails {
			if now.Sub(at) > outageRollbackWindow {
				delete(s.recentDialFails, failedTag)
			}
		}
	}
	distinctFailures := len(s.recentDialFails)
	s.access.Unlock()

	if suspended {
		return
	}
	if distinctFailures >= dialOutageSuspicion {
		go s.assessOutage()
	}
	s.requestRound()
}

func (s *AutoSelector) assessOutage() {
	if s.isSuspended() {
		return
	}
	if s.networkInterfaceDown() || (s.connectURL != "" && !s.connectivityOK(false)) {
		s.enterSuspended(time.Now(), time.Time{})
	}
}

func (s *AutoSelector) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP, N.NetworkUDP:
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	tried := make(map[string]bool, s.dialRetries+1)
	var lastError error
	for attempt := 0; attempt <= s.dialRetries; attempt++ {
		detour := s.pick(N.NetworkName(network), tried)
		if detour == nil {
			break
		}
		tried[detour.Tag()] = true
		conn, err := detour.DialContext(ctx, network, destination)
		if err == nil {
			s.noteDialResult(detour, N.NetworkName(network), nil)
			if s.interruptGroup != nil {
				return s.interruptGroup.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
			}
			return conn, nil
		}
		lastError = err
		s.noteDialResult(detour, N.NetworkName(network), err)
		if s.logger != nil {
			s.logger.DebugContext(ctx, "auto-selector: ", detour.Tag(), " failed: ", err)
		}
		if ctx.Err() != nil {
			break
		}
	}
	if lastError == nil {
		lastError = E.New("no usable outbound")
	}
	return nil, lastError
}

func (s *AutoSelector) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	tried := make(map[string]bool, s.dialRetries+1)
	var lastError error
	for attempt := 0; attempt <= s.dialRetries; attempt++ {
		detour := s.pick(N.NetworkUDP, tried)
		if detour == nil {
			break
		}
		tried[detour.Tag()] = true
		conn, err := detour.ListenPacket(ctx, destination)
		if err == nil {
			s.noteDialResult(detour, N.NetworkUDP, nil)
			if s.interruptGroup != nil {
				return s.interruptGroup.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
			}
			return conn, nil
		}
		lastError = err
		s.noteDialResult(detour, N.NetworkUDP, err)
		if ctx.Err() != nil {
			break
		}
	}
	if lastError == nil {
		lastError = E.New("no usable outbound")
	}
	return nil, lastError
}

type memberHandle struct {
	adapter.Outbound
	selector *AutoSelector
}

func (h *memberHandle) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return h.selector.DialContext(ctx, network, destination)
}

func (h *memberHandle) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return h.selector.ListenPacket(ctx, destination)
}

func (s *AutoSelector) URLTest(_ context.Context) (map[string]uint16, error) {
	startedAt := time.Now()
	results := s.probeBatch(s.tags, true)
	s.commitRound(results, startedAt)
	delays := make(map[string]uint16, len(results))
	for _, result := range results {
		if result.value == rttFailed {
			continue
		}
		delays[result.tag] = uint16(result.value / time.Millisecond)
	}
	return delays, nil
}

func (s *AutoSelector) PerformUpdateCheck() {
	s.access.Lock()
	defer s.access.Unlock()
	s.evaluateLocked(time.Now())
}

func (s *AutoSelector) CheckOutbounds() {
	go func() {
		startedAt := time.Now()
		results := s.probeBatch(s.tags, true)
		s.commitRound(results, startedAt)
	}()
}

func (s *AutoSelector) Status() AutoSelectorStatus {
	s.access.Lock()
	defer s.access.Unlock()
	now := time.Now()

	selectedTCP := ""
	if selected := s.selectedTCP.Load(); selected != nil {
		selectedTCP = selected.Tag()
	}
	selectedUDP := ""
	if selected := s.selectedUDP.Load(); selected != nil {
		selectedUDP = selected.Tag()
	}
	qualified := make(map[string]bool, len(s.qualified))
	for _, tag := range s.qualified {
		qualified[tag] = true
	}

	status := AutoSelectorStatus{
		Tag:              s.Tag(),
		Selected:         selectedTCP,
		SelectedUDP:      selectedUDP,
		Pinned:           s.pinnedTag,
		Balance:          s.balance,
		BalanceMode:      s.balanceMode,
		Suspended:        s.suspended,
		SuspendedSinceMs: unixMillisOrZero(s.suspendedSince),
		MembersTotal:     len(s.tags),
		MembersQualified: len(s.qualified),
		ProbesInFlight:   s.probesInFlight,
		RoundsCompleted:  s.roundsCompleted,
		LastRoundMs:      unixMillisOrZero(s.lastRoundAt),
		NextRoundMs:      unixMillisOrZero(s.nextRoundAt),
		LastSwitchMs:     unixMillisOrZero(s.lastSwitchAt),
		LastSwitchReason: s.lastSwitchReason,
		Members:          make([]AutoSelectorMemberStatus, 0, len(s.ranked)),
	}
	switch {
	case s.suspended:
		status.Phase = "suspended"
	case !s.started:
		status.Phase = "starting"
	case s.roundsCompleted == 0:
		status.Phase = "probing"
	default:
		status.Phase = "ready"
	}

	for rank, node := range s.ranked {
		health, loaded := s.members[node.tag]
		if !loaded {
			continue
		}
		stats := health.stats(now)
		if stats.All > 0 {
			status.MembersProbed++
		}
		if stats.Alive() {
			status.MembersAlive++
		}
		if health.inCooldown(now) {
			status.MembersCooldown++
		}
		status.Members = append(status.Members, AutoSelectorMemberStatus{
			Tag:             node.tag,
			Rank:            rank + 1,
			State:           node.state(),
			Selected:        node.tag == selectedTCP,
			SelectedUDP:     node.tag == selectedUDP,
			Qualified:       qualified[node.tag],
			Active:          s.activeSet[node.tag],
			AverageMs:       int(stats.Average / time.Millisecond),
			DeviationMs:     int(stats.Deviation / time.Millisecond),
			MinMs:           int(stats.Min / time.Millisecond),
			MaxMs:           int(stats.Max / time.Millisecond),
			Samples:         stats.All,
			Failures:        stats.Fail,
			Probes:          health.probes,
			DialTotal:       health.dialTotal,
			DialFail:        health.dialFail,
			LastOKMs:        unixMillisOrZero(health.lastOK),
			LastProbeMs:     unixMillisOrZero(health.lastProbe),
			CooldownUntilMs: unixMillisOrZero(health.cooldownUntil),
			LastError:       health.lastDialErr,
		})
	}
	return status
}
