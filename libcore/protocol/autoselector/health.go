package autoselector

import (
	"errors"
	"math"
	"net"
	"os"
	"sort"
	"strings"
	"syscall"
	"time"
)

// Ring-slot sentinels. rttUntested marks a slot that has never been written (or
// one that was rolled back after being blamed on a local outage); rttFailed
// marks a probe that errored while the local network was believed to be up.
const (
	rttUntested = time.Duration(-1)
	rttFailed   = time.Duration(math.MaxInt64)
)

// Health tiers. Ordering between tiers dominates the per-tier comparators: an
// untested member outranks a known-dead one so the prober keeps exploring
// instead of settling on servers it has already proven broken.
const (
	tierHealthy = iota
	tierUntested
	tierDead
	tierCooldown
)

type memberStats struct {
	All       int
	Fail      int
	Average   time.Duration
	Deviation time.Duration
	Min       time.Duration
	Max       time.Duration
}

// Alive reports whether at least one probe succeeded inside the validity window.
func (s memberStats) Alive() bool {
	return s.All > s.Fail
}

func (s memberStats) FailRatio() float64 {
	if s.All == 0 {
		return 0
	}
	return float64(s.Fail) / float64(s.All)
}

type pingSample struct {
	at    time.Time
	value time.Duration
}

// memberHealth is the rolling health record of one group member: a ring of the
// last `capacity` probe results, plus passive signals from real dials.
type memberHealth struct {
	tag string

	capacity int
	validity time.Duration
	idx      int
	samples  []pingSample

	dialTotal   int
	dialFail    int
	lastDialErr string

	lastOK    time.Time
	lastProbe time.Time
	probes    int

	cooldownUntil time.Time
	cooldownAt    time.Time
	cooldownStep  int
}

func newMemberHealth(tag string, capacity int, validity time.Duration) *memberHealth {
	if capacity <= 0 {
		capacity = defaultAutoSampling
	}
	h := &memberHealth{
		tag:      tag,
		capacity: capacity,
		validity: validity,
		idx:      -1,
		samples:  make([]pingSample, capacity),
	}
	for i := range h.samples {
		h.samples[i].value = rttUntested
	}
	return h
}

func (h *memberHealth) put(value time.Duration, at time.Time) {
	h.idx = (h.idx + 1) % h.capacity
	h.samples[h.idx] = pingSample{at: at, value: value}
	h.probes++
	h.lastProbe = at
	if value != rttFailed && value != rttUntested {
		h.lastOK = at
	}
}

func (h *memberHealth) rollbackFailuresSince(since time.Time) int {
	rolled := 0
	for i := range h.samples {
		s := &h.samples[i]
		if s.value == rttFailed && !s.at.Before(since) {
			s.value = rttUntested
			rolled++
		}
	}
	return rolled
}

func rollbackSince(now time.Time, roundStartedAt time.Time) time.Time {
	window := now.Add(-outageRollbackWindow)
	if roundStartedAt.IsZero() || roundStartedAt.After(window) {
		return window
	}
	return roundStartedAt
}

func (h *memberHealth) stats(now time.Time) memberStats {
	stats := memberStats{Min: rttFailed}
	var (
		sum   time.Duration
		count int
		valid []time.Duration
	)
	for _, s := range h.samples {
		switch {
		case s.value == rttUntested || now.Sub(s.at) > h.validity:
			continue
		case s.value == rttFailed:
			stats.Fail++
			continue
		}
		count++
		sum += s.value
		valid = append(valid, s.value)
		if s.value > stats.Max {
			stats.Max = s.value
		}
		if s.value < stats.Min {
			stats.Min = s.value
		}
	}
	stats.All = count + stats.Fail
	if count == 0 {
		stats.Min = 0
		return stats
	}
	stats.Average = sum / time.Duration(count)
	if count < 2 {
		// Not enough data for a real deviation. Assume half the average so a
		// single lucky sample never outranks a well-measured member.
		stats.Deviation = stats.Average / 2
	} else {
		variance := float64(0)
		for _, v := range valid {
			variance += math.Pow(float64(v-stats.Average), 2)
		}
		stats.Deviation = time.Duration(math.Sqrt(variance / float64(count)))
	}
	return stats
}

func (h *memberHealth) inCooldown(now time.Time) bool {
	return now.Before(h.cooldownUntil)
}

func (h *memberHealth) penalize(now time.Time, backoff []time.Duration, err error) {
	h.dialTotal++
	h.dialFail++
	if err != nil {
		h.lastDialErr = err.Error()
	}
	step := h.cooldownStep
	if step >= len(backoff) {
		step = len(backoff) - 1
	}
	h.cooldownUntil = now.Add(backoff[step])
	h.cooldownAt = now
	if h.cooldownStep < len(backoff)-1 {
		h.cooldownStep++
	}
}

func (h *memberHealth) reward(now time.Time) {
	h.dialTotal++
	h.dialFail = 0
	h.cooldownStep = 0
	h.cooldownUntil = time.Time{}
	h.cooldownAt = time.Time{}
	h.lastDialErr = ""
	h.lastOK = now
}

func (h *memberHealth) clearCooldown() {
	h.cooldownUntil = time.Time{}
	h.cooldownAt = time.Time{}
	h.cooldownStep = 0
	h.dialFail = 0
}

func (h *memberHealth) clearCooldownSince(since time.Time) bool {
	if h.cooldownAt.IsZero() || h.cooldownAt.Before(since) {
		return false
	}
	h.clearCooldown()
	return true
}

type rankedNode struct {
	tag   string
	tier  int
	stats memberStats
}

func (n *rankedNode) state() string {
	switch n.tier {
	case tierHealthy:
		if n.stats.Fail > 0 {
			return "degraded"
		}
		return "ok"
	case tierUntested:
		return "untested"
	case tierDead:
		return "dead"
	default:
		return "cooldown"
	}
}

func stableByPrior(nodes []*rankedNode, prior map[string]int) {
	sort.SliceStable(nodes, func(i, j int) bool {
		li, liOK := prior[nodes[i].tag]
		lj, ljOK := prior[nodes[j].tag]
		if liOK != ljOK {
			return liOK
		}
		return li < lj
	})
}

func sortNodes(nodes []*rankedNode) {
	sort.SliceStable(nodes, func(i, j int) bool {
		l, r := nodes[i], nodes[j]
		if l.tier != r.tier {
			return l.tier < r.tier
		}
		if l.tier != tierHealthy {
			return false
		}
		if l.stats.Deviation != r.stats.Deviation {
			return l.stats.Deviation < r.stats.Deviation
		}
		if l.stats.Average != r.stats.Average {
			return l.stats.Average < r.stats.Average
		}
		if l.stats.Fail != r.stats.Fail {
			return l.stats.Fail < r.stats.Fail
		}
		if l.stats.All != r.stats.All {
			return l.stats.All > r.stats.All
		}
		return false
	})
}

func applyBaselines(nodes []*rankedNode, baselines []time.Duration, expected int) []*rankedNode {
	if len(nodes) == 0 {
		return nil
	}
	if expected <= 0 {
		expected = 1
	}
	if expected >= len(nodes) {
		return nodes
	}
	if len(baselines) == 0 {
		return nodes[:expected]
	}
	count := 0
	for _, baseline := range baselines {
		for i := count; i < len(nodes); i++ {
			if nodes[i].stats.Deviation >= baseline {
				break
			}
			count = i + 1
		}
		if count >= expected {
			break
		}
	}
	if count < expected {
		count = expected
	}
	return nodes[:count]
}

func isLocalNetworkError(err error) bool {
	if err == nil {
		return false
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		switch errno {
		case syscall.ENETUNREACH, syscall.ENETDOWN, syscall.EHOSTUNREACH, syscall.ENETRESET:
			return true
		}
		switch uintptr(errno) {
		case 10050, // WSAENETDOWN
			10051, // WSAENETUNREACH
			10052, // WSAENETRESET
			10065: // WSAEHOSTUNREACH
			return true
		}
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) && !dnsErr.IsNotFound {
		return true
	}
	if errors.Is(err, syscall.ECONNABORTED) || errors.Is(err, os.ErrDeadlineExceeded) {
		return false
	}
	msg := strings.ToLower(err.Error())
	for _, marker := range []string{
		"network is unreachable",
		"network is down",
		"no route to host",
		"host is unreachable",
		"can not resolve",
		"cannot assign requested address",
	} {
		if strings.Contains(msg, marker) {
			return true
		}
	}
	return false
}

func unixMillisOrZero(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}
