package autoselect

import (
	"math"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

type memberSample struct {
	checkedAt time.Time
	delay     uint16
	err       string
	ok        bool
}

type memberState struct {
	tag              string
	index            int
	initialDelay     uint16
	ring             []memberSample
	consecutiveOK    int
	consecutiveFail  int
	lastActiveFailAt time.Time
	lastCheckedAt    time.Time
	lastDelay        uint16
	lastError        string
	lastSuccessAt    time.Time
}

type scorer struct {
	mu           sync.RWMutex
	order        []string
	members      map[string]*memberState
	cfg          normalizedConfig
	activeTag    string
	pinnedTag    string
	lastSwitchAt time.Time
	switchReason string

	rotationIndex int
	lastRotateAt  time.Time
	connCounter   atomic.Uint64
}

func newScorer(order []string, cfg normalizedConfig) *scorer {
	cleanOrder := make([]string, 0, len(order))
	members := make(map[string]*memberState, len(order))
	for _, tag := range order {
		if tag == "" {
			continue
		}
		if _, exists := members[tag]; exists {
			continue
		}
		idx := len(cleanOrder)
		cleanOrder = append(cleanOrder, tag)
		members[tag] = &memberState{
			tag:   tag,
			index: idx,
		}
	}
	return &scorer{
		order:   cleanOrder,
		members: members,
		cfg:     cfg,
	}
}

func (s *scorer) seedInitialDelays(delays map[string]uint16) {
	if len(delays) == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for tag, d := range delays {
		if st, ok := s.members[tag]; ok && d > 0 {
			st.initialDelay = d
		}
	}
}

func (s *scorer) recordSample(tag string, delay uint16, err error, now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()

	st, ok := s.members[tag]
	if !ok {
		return
	}
	if now.IsZero() {
		now = time.Now()
	}

	sample := memberSample{
		checkedAt: now,
		delay:     delay,
		ok:        err == nil && delay > 0,
	}
	if err != nil {
		sample.err = err.Error()
		sample.delay = 0
		sample.ok = false
	}

	st.lastCheckedAt = now
	if sample.ok {
		st.consecutiveOK++
		st.consecutiveFail = 0
		st.lastDelay = delay
		st.lastError = ""
		st.lastSuccessAt = now
	} else {
		st.consecutiveFail++
		st.consecutiveOK = 0
		st.lastActiveFailAt = now
		if sample.err != "" {
			st.lastError = sample.err
		} else {
			st.lastError = "probe failed"
		}
	}

	st.ring = append(st.ring, sample)
	if len(st.ring) > s.cfg.sampleCount {
		trim := len(st.ring) - s.cfg.sampleCount
		st.ring = append([]memberSample(nil), st.ring[trim:]...)
	}
}

func (s *scorer) sampleCounts(tag string) (total, fails int, lastDelay uint16) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	st, ok := s.members[tag]
	if !ok {
		return 0, 0, 0
	}
	total = len(st.ring)
	for _, sm := range st.ring {
		if !sm.ok {
			fails++
		}
	}
	if total == 0 && st.initialDelay > 0 {
		return 0, 0, st.initialDelay
	}
	return total, fails, st.lastDelay
}

func (s *scorer) pin(tag string, now time.Time) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if now.IsZero() {
		now = time.Now()
	}
	if tag == "" {
		if s.pinnedTag != "" {
			s.pinnedTag = ""
			s.switchReason = "auto resumed"
		}
		return true
	}
	if _, ok := s.members[tag]; !ok {
		return false
	}
	s.pinnedTag = tag
	if s.activeTag != tag {
		s.activeTag = tag
		s.lastSwitchAt = now
	}
	s.switchReason = "pinned"
	return true
}

func (s *scorer) pinned() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.pinnedTag
}

func (s *scorer) activeTagSnapshot() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.activeTag
}

func (s *scorer) clearActiveSamples() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, st := range s.members {
		st.consecutiveFail = 0
		st.consecutiveOK = 0
		st.lastActiveFailAt = time.Time{}
	}
}

func (s *scorer) memberScoreLocked(st *memberState, passive *passiveTracker, now time.Time) (score float64, effectiveDelay uint16, checkCount int, failCount int, available bool) {
	checkCount = len(st.ring)
	var delays []float64
	for _, sm := range st.ring {
		if sm.ok {
			delays = append(delays, float64(sm.delay))
		} else {
			failCount++
		}
	}

	suspect := passive != nil && passive.IsSuspect(st.tag, now)

	if checkCount == 0 {
		if st.initialDelay > 0 {
			effectiveDelay = st.initialDelay
			score = float64(st.initialDelay)
			available = !suspect && (s.cfg.maxRttMs == 0 || effectiveDelay <= s.cfg.maxRttMs)
		} else if passive != nil && s.cfg.learnFromConnections {
			ttfbMs, recentSucc, consecFails, _ := passive.NodeStats(st.tag, now)
			if ttfbMs > 0 && recentSucc > 0 && consecFails < 2 && !suspect {
				effectiveDelay = uint16(math.Round(ttfbMs))
				score = ttfbMs
				available = s.cfg.maxRttMs == 0 || effectiveDelay <= s.cfg.maxRttMs
			}
		}
		return
	}

	okCount := len(delays)
	if okCount > 0 {
		sorted := append([]float64(nil), delays...)
		sort.Float64s(sorted)
		mid := len(sorted) / 2
		median := sorted[mid]
		if len(sorted)%2 == 0 {
			median = (sorted[mid-1] + sorted[mid]) / 2.0
		}

		// Jitter penalty based on mean absolute deviation around median
		var devSum float64
		for _, v := range delays {
			devSum += math.Abs(v - median)
		}
		mad := devSum / float64(len(delays))
		failRatio := float64(failCount) / float64(checkCount)
		score = median + 0.5*mad + failRatio*120.0

		if st.lastDelay > 0 {
			effectiveDelay = st.lastDelay
		} else {
			effectiveDelay = uint16(math.Round(median))
		}
	}

	if passive != nil && s.cfg.learnFromConnections {
		ttfbMs, _, consecFails, _ := passive.NodeStats(st.tag, now)
		if ttfbMs > 0 {
			if score > 0 {
				score = 0.7*score + 0.3*ttfbMs
			} else {
				score = ttfbMs
			}
		}
		if consecFails >= 2 && okCount > 0 {
			score += float64(consecFails) * 80.0
		}
	}

	activelyDead := st.consecutiveFail >= 2 || okCount == 0
	withinMaxRtt := effectiveDelay > 0 && (s.cfg.maxRttMs == 0 || effectiveDelay <= s.cfg.maxRttMs)
	available = !suspect && !activelyDead && withinMaxRtt
	return
}

func (s *scorer) evaluate(now time.Time, passive *passiveTracker) (string, string) {
	active, reason, _, _ := s.evaluateWithTransition(now, passive)
	return active, reason
}

func (s *scorer) evaluateWithTransition(now time.Time, passive *passiveTracker) (activeTag string, switchReason string, switched bool, isFailback bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.evaluateLocked(now, passive)
}

func (s *scorer) evaluateLocked(now time.Time, passive *passiveTracker) (string, string, bool, bool) {
	if len(s.order) == 0 {
		return "", "", false, false
	}
	if now.IsZero() {
		now = time.Now()
	}

	if s.pinnedTag != "" {
		if _, ok := s.members[s.pinnedTag]; ok {
			changed := s.activeTag != s.pinnedTag
			if changed {
				s.activeTag = s.pinnedTag
				s.lastSwitchAt = now
			}
			s.switchReason = "pinned"
			return s.activeTag, s.switchReason, changed, false
		}
	}

	items := make([]scoredEntryRef, 0, len(s.order))
	var current *scoredEntryRef
	for _, tag := range s.order {
		st := s.members[tag]
		score, delay, _, _, avail := s.memberScoreLocked(st, passive, now)
		item := scoredEntryRef{
			st:        st,
			score:     score,
			delay:     delay,
			available: avail,
			suspect:   passive != nil && passive.IsSuspect(tag, now),
		}
		items = append(items, item)
		if tag == s.activeTag {
			cp := item
			current = &cp
		}
	}

	if s.cfg.selectionMode == SelectionModePriority {
		return s.evaluatePriorityLocked(now, passive, items, current)
	}
	return s.evaluateLatencyLocked(now, items, current)
}

type scoredEntryRef struct {
	st        *memberState
	score     float64
	delay     uint16
	available bool
	suspect   bool
}

func (s *scorer) evaluatePriorityLocked(now time.Time, passive *passiveTracker, items []scoredEntryRef, current *scoredEntryRef) (string, string, bool, bool) {
	var firstHealthy *scoredEntryRef
	for i := range items {
		if items[i].available {
			firstHealthy = &items[i]
			break
		}
	}

	// Fallback if all unhealthy: non-suspect first, else priority 0
	if firstHealthy == nil {
		for i := range items {
			if !items[i].suspect {
				firstHealthy = &items[i]
				break
			}
		}
	}
	if firstHealthy == nil {
		firstHealthy = &items[0]
	}

	if current == nil || s.activeTag == "" {
		s.activeTag = firstHealthy.st.tag
		s.lastSwitchAt = now
		s.switchReason = "priority select"
		return s.activeTag, s.switchReason, true, false
	}

	// Same priority candidate
	if firstHealthy.st.tag == current.st.tag {
		return s.activeTag, s.switchReason, false, false
	}

	// Current node became unhealthy -> failover immediately
	if !current.available {
		s.activeTag = firstHealthy.st.tag
		s.lastSwitchAt = now
		s.switchReason = "priority failover"
		return s.activeTag, s.switchReason, true, false
	}

	// Current node is still healthy, higher-priority candidate wants to failback
	if firstHealthy.st.index < current.st.index {
		if s.canFailbackLocked(firstHealthy.st, passive, now) {
			s.activeTag = firstHealthy.st.tag
			s.lastSwitchAt = now
			s.switchReason = "priority failback"
			return s.activeTag, s.switchReason, true, true
		}
		return s.activeTag, s.switchReason, false, false
	}

	// Current node is healthy and already higher priority than candidate
	return s.activeTag, s.switchReason, false, false
}

func (s *scorer) canFailbackLocked(st *memberState, passive *passiveTracker, now time.Time) bool {
	if st.consecutiveOK < s.cfg.failbackChecks {
		return false
	}
	lastFail := st.lastActiveFailAt
	if passive != nil {
		pf := passive.LastFailureAt(st.tag)
		if pf.After(lastFail) {
			lastFail = pf
		}
	}
	if !lastFail.IsZero() && now.Sub(lastFail) < s.cfg.failbackDelay {
		return false
	}
	return true
}

func (s *scorer) evaluateLatencyLocked(now time.Time, items []scoredEntryRef, current *scoredEntryRef) (string, string, bool, bool) {
	healthy := make([]scoredEntryRef, 0, len(items))
	for _, it := range items {
		if it.available {
			healthy = append(healthy, it)
		}
	}

	var best scoredEntryRef
	if len(healthy) > 0 {
		sort.SliceStable(healthy, func(i, j int) bool {
			if healthy[i].score != healthy[j].score {
				return healthy[i].score < healthy[j].score
			}
			return healthy[i].st.index < healthy[j].st.index
		})
		best = healthy[0]
	} else {
		// Fallback: non-suspect with positive score, else lowest fail count, else index 0
		fallbacks := append([]scoredEntryRef(nil), items...)
		sort.SliceStable(fallbacks, func(i, j int) bool {
			if fallbacks[i].suspect != fallbacks[j].suspect {
				return !fallbacks[i].suspect
			}
			if (fallbacks[i].score > 0) != (fallbacks[j].score > 0) {
				return fallbacks[i].score > 0
			}
			if fallbacks[i].score > 0 && fallbacks[j].score > 0 && fallbacks[i].score != fallbacks[j].score {
				return fallbacks[i].score < fallbacks[j].score
			}
			return fallbacks[i].st.index < fallbacks[j].st.index
		})
		best = fallbacks[0]
	}

	if current == nil || s.activeTag == "" {
		s.activeTag = best.st.tag
		s.lastSwitchAt = now
		s.switchReason = "best available"
		return s.activeTag, s.switchReason, true, false
	}

	if best.st.tag == current.st.tag {
		return s.activeTag, s.switchReason, false, false
	}

	// If current is healthy AND best is not strictly better beyond tolerance, stay put!
	if current.available && current.score > 0 && best.available {
		if current.score-best.score <= float64(s.cfg.tolerance) {
			return s.activeTag, s.switchReason, false, false
		}
	}

	s.activeTag = best.st.tag
	s.lastSwitchAt = now
	if !current.available {
		s.switchReason = "failover"
	} else {
		s.switchReason = "best available"
	}
	return s.activeTag, s.switchReason, true, false
}

func (s *scorer) verifiedPoolLocked(now time.Time, passive *passiveTracker) []string {
	type ranked struct {
		tag   string
		index int
		score float64
		avail bool
	}
	var healthy []ranked
	for _, tag := range s.order {
		st := s.members[tag]
		score, _, _, _, avail := s.memberScoreLocked(st, passive, now)
		if avail {
			healthy = append(healthy, ranked{tag: tag, index: st.index, score: score, avail: avail})
		}
	}
	if len(healthy) == 0 {
		if s.activeTag != "" {
			return []string{s.activeTag}
		}
		if len(s.order) > 0 {
			return []string{s.order[0]}
		}
		return nil
	}

	if s.cfg.selectionMode == SelectionModePriority {
		sort.SliceStable(healthy, func(i, j int) bool {
			return healthy[i].index < healthy[j].index
		})
		capLimit := s.cfg.standbySize + 1
		if capLimit < 1 {
			capLimit = 1
		}
		if len(healthy) > capLimit {
			healthy = healthy[:capLimit]
		}
		out := make([]string, len(healthy))
		for i, h := range healthy {
			out[i] = h.tag
		}
		return out
	}

	sort.SliceStable(healthy, func(i, j int) bool {
		if healthy[i].score != healthy[j].score {
			return healthy[i].score < healthy[j].score
		}
		return healthy[i].index < healthy[j].index
	})
	bestScore := healthy[0].score
	spread := float64(s.cfg.tolerance)
	if spread < 50 {
		spread = 50
	}
	threshold := bestScore + spread
	var pool []string
	for _, h := range healthy {
		if h.score <= threshold || len(pool) == 0 {
			pool = append(pool, h.tag)
		}
		if len(pool) >= 8 {
			break
		}
	}
	return pool
}

func (s *scorer) advanceRotation(now time.Time, passive *passiveTracker) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if now.IsZero() {
		now = time.Now()
	}
	pool := s.verifiedPoolLocked(now, passive)
	if len(pool) == 0 {
		return s.activeTag
	}
	s.rotationIndex = (s.rotationIndex + 1) % len(pool)
	s.lastRotateAt = now
	chosen := pool[s.rotationIndex]
	if chosen != s.activeTag {
		s.activeTag = chosen
		s.lastSwitchAt = now
		s.switchReason = "rotation"
	}
	return chosen
}

func (s *scorer) pickConnectionCarrier(now time.Time, passive *passiveTracker) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.pickConnectionCarrierLocked(now, passive)
}

func (s *scorer) pickConnectionCarrierLocked(now time.Time, passive *passiveTracker) string {
	if s.pinnedTag != "" {
		return s.pinnedTag
	}
	if !s.cfg.balanceEnabled {
		if s.activeTag == "" {
			s.evaluateLocked(now, passive)
		}
		return s.activeTag
	}
	pool := s.verifiedPoolLocked(now, passive)
	if len(pool) == 0 {
		return s.activeTag
	}
	if s.cfg.balanceMode == BalanceModePerConnection {
		idx := (s.connCounter.Add(1) - 1) % uint64(len(pool))
		return pool[idx]
	}
	// timed_rotation
	if s.lastRotateAt.IsZero() {
		s.lastRotateAt = now
	} else if now.Sub(s.lastRotateAt) >= s.cfg.balanceInterval {
		s.rotationIndex = (s.rotationIndex + 1) % len(pool)
		s.lastRotateAt = now
		if pool[s.rotationIndex] != s.activeTag {
			s.activeTag = pool[s.rotationIndex]
			s.lastSwitchAt = now
			s.switchReason = "rotation"
		}
	}
	return pool[s.rotationIndex%len(pool)]
}

func (s *scorer) candidateOrder(siteKey string, now time.Time, passive *passiveTracker) []string {
	s.mu.Lock()
	defer s.mu.Unlock()

	if now.IsZero() {
		now = time.Now()
	}
	if s.pinnedTag != "" {
		if _, ok := s.members[s.pinnedTag]; ok {
			return []string{s.pinnedTag}
		}
	}

	if s.activeTag == "" {
		s.evaluateLocked(now, passive)
	}

	seen := make(map[string]bool, len(s.order))
	out := make([]string, 0, len(s.order))
	addUnique := func(tag string) {
		if tag != "" && !seen[tag] {
			if _, ok := s.members[tag]; ok {
				seen[tag] = true
				out = append(out, tag)
			}
		}
	}

	// Build scored slice for deterministic ordering
	type ranked struct {
		tag     string
		index   int
		score   float64
		avail   bool
		suspect bool
		avoid   bool
	}
	all := make([]ranked, 0, len(s.order))
	healthyMap := make(map[string]bool, len(s.order))
	for _, tag := range s.order {
		st := s.members[tag]
		score, _, _, _, avail := s.memberScoreLocked(st, passive, now)
		avoid := passive != nil && siteKey != "" && passive.ShouldAvoidSite(tag, siteKey, now)
		if avail {
			healthyMap[tag] = true
		}
		all = append(all, ranked{
			tag:     tag,
			index:   st.index,
			score:   score,
			avail:   avail,
			suspect: passive != nil && passive.IsSuspect(tag, now),
			avoid:   avoid,
		})
	}

	// 1. Site sticky preferred healthy node (not avoided for this site)
	if s.cfg.learnFromConnections && passive != nil && siteKey != "" {
		if pref := passive.PreferredSiteNode(siteKey, now); pref != "" && healthyMap[pref] && !passive.ShouldAvoidSite(pref, siteKey, now) {
			addUnique(pref)
		}
	}

	// 2. Current carrier (single active or balanced rotation/per-conn slot) if not site-avoided
	carrier := s.pickConnectionCarrierLocked(now, passive)
	if carrier != "" && (passive == nil || siteKey == "" || !passive.ShouldAvoidSite(carrier, siteKey, now)) {
		addUnique(carrier)
	}

	// Sort remaining nodes
	sort.SliceStable(all, func(i, j int) bool {
		if all[i].avail != all[j].avail {
			return all[i].avail
		}
		if all[i].avoid != all[j].avoid {
			return !all[i].avoid
		}
		if all[i].suspect != all[j].suspect {
			return !all[i].suspect
		}
		if s.cfg.selectionMode == SelectionModePriority {
			return all[i].index < all[j].index
		}
		if all[i].score > 0 && all[j].score > 0 && all[i].score != all[j].score {
			return all[i].score < all[j].score
		}
		if (all[i].score > 0) != (all[j].score > 0) {
			return all[i].score > 0
		}
		return all[i].index < all[j].index
	})

	for _, r := range all {
		addUnique(r.tag)
	}
	return out
}
