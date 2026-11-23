package autoselect

import (
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

type memberState struct {
	tag              string
	index            int
	tier             int
	checkCount       int
	failCount        int
	consecutiveFails int
	available        bool
	lastCheckedAt    time.Time
	lastDelay        uint16
	lastError        string
}

type scorer struct {
	mu           sync.RWMutex
	order        []string
	members      map[string]*memberState
	cfg          normalizedConfig
	hasTiers     bool
	tierCount    int
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
	tagTier := make(map[string]int, len(order))
	hasTiers := len(cfg.tiers) > 0
	tierCount := 0
	if hasTiers {
		tierCount = len(cfg.tiers) + 1
		for tierIdx, tierTags := range cfg.tiers {
			for _, t := range tierTags {
				if t == "" {
					continue
				}
				if _, exists := tagTier[t]; !exists {
					tagTier[t] = tierIdx
				}
			}
		}
	}
	for _, tag := range order {
		if tag == "" {
			continue
		}
		if _, exists := members[tag]; exists {
			continue
		}
		idx := len(cleanOrder)
		cleanOrder = append(cleanOrder, tag)
		tier := 0
		if hasTiers {
			if t, ok := tagTier[tag]; ok {
				tier = t
			} else {
				tier = len(cfg.tiers)
			}
		}
		members[tag] = &memberState{
			tag:   tag,
			index: idx,
			tier:  tier,
		}
	}
	return &scorer{
		order:     cleanOrder,
		members:   members,
		cfg:       cfg,
		hasTiers:  hasTiers,
		tierCount: tierCount,
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

	okProbe := err == nil && delay > 0
	st.checkCount++
	st.lastCheckedAt = now
	if okProbe {
		st.consecutiveFails = 0
		st.available = true
		st.lastDelay = delay
		st.lastError = ""
		return
	}

	st.failCount++
	st.consecutiveFails++
	if err != nil {
		st.lastError = err.Error()
	} else {
		st.lastError = "probe failed"
	}

	if s.cfg.selectionMode == SelectionModeFallback {
		maxFails := s.cfg.fallbackMaxFails
		if maxFails <= 0 {
			maxFails = 2
		}
		if st.consecutiveFails >= maxFails || !st.available {
			st.available = false
			st.lastDelay = 0
		}
		return
	}

	// Single probe failure marks the node unavailable immediately in latency/priority modes.
	st.available = false
	st.lastDelay = 0
}

func (s *scorer) sampleCounts(tag string) (total, fails int, lastDelay uint16) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	st, ok := s.members[tag]
	if !ok {
		return 0, 0, 0
	}
	return st.checkCount, st.failCount, st.lastDelay
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

func (s *scorer) evaluate(now time.Time, passive *passiveTracker) (string, string) {
	active, reason, _, _ := s.evaluateWithTransition(now, passive)
	return active, reason
}

func (s *scorer) evaluateWithTransition(now time.Time, _ *passiveTracker) (activeTag string, switchReason string, switched bool, isFailback bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.evaluateLocked(now)
}

type scoredEntryRef struct {
	st        *memberState
	delay     uint16
	available bool
}

func (s *scorer) evaluateLocked(now time.Time) (string, string, bool, bool) {
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
		item := scoredEntryRef{
			st:        st,
			delay:     st.lastDelay,
			available: st.available,
		}
		items = append(items, item)
		if tag == s.activeTag {
			cp := item
			current = &cp
		}
	}

	if s.cfg.selectionMode == SelectionModeFallback {
		return s.evaluateFallbackLocked(now, items, current)
	}
	if s.cfg.selectionMode == SelectionModePriority {
		return s.evaluatePriorityLocked(now, items, current)
	}
	return s.evaluateLatencyLocked(now, items, current)
}

func (s *scorer) evaluateFallbackLocked(now time.Time, items []scoredEntryRef, current *scoredEntryRef) (string, string, bool, bool) {
	var firstHealthy *scoredEntryRef
	for i := range items {
		if items[i].available {
			firstHealthy = &items[i]
			break
		}
	}
	if firstHealthy == nil {
		firstHealthy = &items[0]
	}

	if current == nil || s.activeTag == "" {
		s.activeTag = firstHealthy.st.tag
		s.lastSwitchAt = now
		s.switchReason = "fallback select"
		return s.activeTag, s.switchReason, true, false
	}

	if firstHealthy.st.tag == current.st.tag {
		return s.activeTag, s.switchReason, false, false
	}

	if !current.available {
		s.activeTag = firstHealthy.st.tag
		s.lastSwitchAt = now
		s.switchReason = "fallback failover"
		return s.activeTag, s.switchReason, true, false
	}

	if s.cfg.fallbackAutoRevert && firstHealthy.st.index < current.st.index {
		s.activeTag = firstHealthy.st.tag
		s.lastSwitchAt = now
		s.switchReason = "fallback auto-revert"
		return s.activeTag, s.switchReason, true, true
	}

	return s.activeTag, s.switchReason, false, false
}

func (s *scorer) evaluatePriorityLocked(now time.Time, items []scoredEntryRef, current *scoredEntryRef) (string, string, bool, bool) {
	if s.hasTiers {
		return s.evaluatePriorityTieredLocked(now, items, current)
	}
	var firstHealthy *scoredEntryRef
	for i := range items {
		if items[i].available {
			firstHealthy = &items[i]
			break
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

	if firstHealthy.st.tag == current.st.tag {
		return s.activeTag, s.switchReason, false, false
	}

	if !current.available {
		s.activeTag = firstHealthy.st.tag
		s.lastSwitchAt = now
		s.switchReason = "priority failover"
		return s.activeTag, s.switchReason, true, false
	}

	// Higher-priority member is healthy: fail back immediately.
	if firstHealthy.st.index < current.st.index {
		s.activeTag = firstHealthy.st.tag
		s.lastSwitchAt = now
		s.switchReason = "priority failback"
		return s.activeTag, s.switchReason, true, true
	}

	return s.activeTag, s.switchReason, false, false
}

func betterTieredFallback(a, b *scoredEntryRef) bool {
	if a.st.tier != b.st.tier {
		return a.st.tier < b.st.tier
	}
	if (a.delay > 0) != (b.delay > 0) {
		return a.delay > 0
	}
	if a.delay > 0 && b.delay > 0 && a.delay != b.delay {
		return a.delay < b.delay
	}
	return a.st.index < b.st.index
}

func (s *scorer) evaluatePriorityTieredLocked(now time.Time, items []scoredEntryRef, current *scoredEntryRef) (string, string, bool, bool) {
	bestTier := -1
	var bestInTier *scoredEntryRef
	for i := range items {
		it := &items[i]
		if !it.available {
			continue
		}
		t := it.st.tier
		if bestTier == -1 || t < bestTier ||
			(t == bestTier && (it.delay < bestInTier.delay || (it.delay == bestInTier.delay && it.st.index < bestInTier.st.index))) {
			bestTier = t
			bestInTier = it
		}
	}

	if bestInTier == nil {
		var fallback *scoredEntryRef
		for i := range items {
			cand := &items[i]
			if fallback == nil || betterTieredFallback(cand, fallback) {
				fallback = cand
			}
		}
		if current == nil || s.activeTag == "" {
			s.activeTag = fallback.st.tag
			s.lastSwitchAt = now
			s.switchReason = "priority select"
			return s.activeTag, s.switchReason, true, false
		}
		if fallback.st.tag == current.st.tag {
			return s.activeTag, s.switchReason, false, false
		}
		if !current.available && betterTieredFallback(fallback, current) {
			s.activeTag = fallback.st.tag
			s.lastSwitchAt = now
			s.switchReason = "priority failover"
			return s.activeTag, s.switchReason, true, false
		}
		return s.activeTag, s.switchReason, false, false
	}

	if current == nil || s.activeTag == "" {
		s.activeTag = bestInTier.st.tag
		s.lastSwitchAt = now
		s.switchReason = "priority select"
		return s.activeTag, s.switchReason, true, false
	}

	if bestInTier.st.tag == current.st.tag {
		return s.activeTag, s.switchReason, false, false
	}

	if !current.available {
		s.activeTag = bestInTier.st.tag
		s.lastSwitchAt = now
		s.switchReason = "priority failover"
		return s.activeTag, s.switchReason, true, false
	}

	// Higher tier has a healthy node: fail back immediately.
	if bestTier < current.st.tier {
		s.activeTag = bestInTier.st.tag
		s.lastSwitchAt = now
		s.switchReason = "priority failback"
		return s.activeTag, s.switchReason, true, true
	}

	if bestTier == current.st.tier {
		if current.delay > 0 && int(current.delay)-int(bestInTier.delay) <= int(s.cfg.tolerance) {
			return s.activeTag, s.switchReason, false, false
		}
		s.activeTag = bestInTier.st.tag
		s.lastSwitchAt = now
		s.switchReason = "tier best available"
		return s.activeTag, s.switchReason, true, false
	}

	return s.activeTag, s.switchReason, false, false
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
			if healthy[i].delay != healthy[j].delay {
				return healthy[i].delay < healthy[j].delay
			}
			return healthy[i].st.index < healthy[j].st.index
		})
		best = healthy[0]
	} else {
		fallbacks := append([]scoredEntryRef(nil), items...)
		sort.SliceStable(fallbacks, func(i, j int) bool {
			if (fallbacks[i].delay > 0) != (fallbacks[j].delay > 0) {
				return fallbacks[i].delay > 0
			}
			if fallbacks[i].delay > 0 && fallbacks[j].delay > 0 && fallbacks[i].delay != fallbacks[j].delay {
				return fallbacks[i].delay < fallbacks[j].delay
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

	// Strictly enforce user tolerance when current node is healthy.
	if current.available && current.delay > 0 && best.available {
		if int(current.delay)-int(best.delay) <= int(s.cfg.tolerance) {
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

// switchFromConnectionFailure rotates away from a node experiencing consecutive real connection failures
// without corrupting its pure active-probe delay or global availability state.
func (s *scorer) switchFromConnectionFailure(failedTag, reason string, now time.Time, passive *passiveTracker) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	if len(s.order) <= 1 || s.pinnedTag != "" || failedTag == "" || s.activeTag != failedTag {
		return false
	}
	if now.IsZero() {
		now = time.Now()
	}

	type cand struct {
		st          *memberState
		avail       bool
		consecFails int
	}
	candidates := make([]cand, 0, len(s.order)-1)
	for _, tag := range s.order {
		if tag == failedTag {
			continue
		}
		st := s.members[tag]
		consecFails := 0
		if passive != nil {
			_, _, consecFails = passive.NodeStats(tag, now)
		}
		candidates = append(candidates, cand{
			st:          st,
			avail:       st.available,
			consecFails: consecFails,
		})
	}
	if len(candidates) == 0 {
		return false
	}

	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].avail != candidates[j].avail {
			return candidates[i].avail
		}
		if (candidates[i].consecFails >= 2) != (candidates[j].consecFails >= 2) {
			return candidates[i].consecFails < 2
		}
		if s.cfg.selectionMode == SelectionModePriority || s.cfg.selectionMode == SelectionModeFallback {
			if s.cfg.selectionMode == SelectionModePriority && s.hasTiers {
				if candidates[i].st.tier != candidates[j].st.tier {
					return candidates[i].st.tier < candidates[j].st.tier
				}
			} else {
				return candidates[i].st.index < candidates[j].st.index
			}
		}
		if candidates[i].st.lastDelay != candidates[j].st.lastDelay {
			if (candidates[i].st.lastDelay > 0) != (candidates[j].st.lastDelay > 0) {
				return candidates[i].st.lastDelay > 0
			}
			return candidates[i].st.lastDelay < candidates[j].st.lastDelay
		}
		return candidates[i].st.index < candidates[j].st.index
	})

	chosen := candidates[0].st.tag
	if chosen == "" || chosen == s.activeTag {
		return false
	}
	s.activeTag = chosen
	s.lastSwitchAt = now
	s.switchReason = reason
	return true
}

func (s *scorer) verifiedPoolLocked(now time.Time, _ *passiveTracker) []string {
	type ranked struct {
		tag   string
		index int
		tier  int
		delay uint16
	}
	var healthy []ranked
	for _, tag := range s.order {
		st := s.members[tag]
		if st.available && st.lastDelay > 0 {
			healthy = append(healthy, ranked{
				tag:   tag,
				index: st.index,
				tier:  st.tier,
				delay: st.lastDelay,
			})
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

	filterTopSpread := func(items []ranked) []string {
		sort.SliceStable(items, func(i, j int) bool {
			if items[i].delay != items[j].delay {
				return items[i].delay < items[j].delay
			}
			return items[i].index < items[j].index
		})
		threshold := int(items[0].delay) + int(s.cfg.tolerance)
		var pool []string
		for _, h := range items {
			if int(h.delay) <= threshold || len(pool) == 0 {
				pool = append(pool, h.tag)
			}
		}
		return pool
	}

	if s.cfg.selectionMode == SelectionModePriority {
		if s.hasTiers {
			minTier := healthy[0].tier
			for _, h := range healthy[1:] {
				if h.tier < minTier {
					minTier = h.tier
				}
			}
			tierHealthy := make([]ranked, 0, len(healthy))
			for _, h := range healthy {
				if h.tier == minTier {
					tierHealthy = append(tierHealthy, h)
				}
			}
			return filterTopSpread(tierHealthy)
		}
		sort.SliceStable(healthy, func(i, j int) bool {
			return healthy[i].index < healthy[j].index
		})
		out := make([]string, len(healthy))
		for i, h := range healthy {
			out[i] = h.tag
		}
		return out
	}

	return filterTopSpread(healthy)
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
			s.evaluateLocked(now)
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
		s.evaluateLocked(now)
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

	type ranked struct {
		tag   string
		index int
		tier  int
		delay uint16
		avail bool
		avoid bool
	}
	all := make([]ranked, 0, len(s.order))
	healthyMap := make(map[string]bool, len(s.order))
	for _, tag := range s.order {
		st := s.members[tag]
		avoid := passive != nil && siteKey != "" && passive.ShouldAvoidSite(tag, siteKey, now)
		if st.available {
			healthyMap[tag] = true
		}
		all = append(all, ranked{
			tag:   tag,
			index: st.index,
			tier:  st.tier,
			delay: st.lastDelay,
			avail: st.available,
			avoid: avoid,
		})
	}

	// 1. Site sticky preferred healthy node (only when load-balancing is disabled)
	if !s.cfg.balanceEnabled && s.cfg.learnFromConnections && passive != nil && siteKey != "" {
		if pref := passive.PreferredSiteNode(siteKey, now); pref != "" && healthyMap[pref] && !passive.ShouldAvoidSite(pref, siteKey, now) {
			addUnique(pref)
		}
	}

	// 2. Current carrier (single active or balanced rotation/per-conn slot) if not site-avoided
	carrier := s.pickConnectionCarrierLocked(now, passive)
	if carrier != "" &&
		(passive == nil || siteKey == "" || !passive.ShouldAvoidSite(carrier, siteKey, now)) {
		if len(healthyMap) == 0 || healthyMap[carrier] {
			addUnique(carrier)
		}
	}

	// Sort remaining candidates deterministically
	sort.SliceStable(all, func(i, j int) bool {
		if all[i].avail != all[j].avail {
			return all[i].avail
		}
		if all[i].avoid != all[j].avoid {
			return !all[i].avoid
		}
		if s.cfg.selectionMode == SelectionModePriority || s.cfg.selectionMode == SelectionModeFallback {
			if s.cfg.selectionMode == SelectionModePriority && s.hasTiers {
				if all[i].tier != all[j].tier {
					return all[i].tier < all[j].tier
				}
			} else {
				return all[i].index < all[j].index
			}
		}
		if all[i].delay > 0 && all[j].delay > 0 && all[i].delay != all[j].delay {
			return all[i].delay < all[j].delay
		}
		if (all[i].delay > 0) != (all[j].delay > 0) {
			return all[i].delay > 0
		}
		return all[i].index < all[j].index
	})

	for _, r := range all {
		addUnique(r.tag)
	}
	return out
}
