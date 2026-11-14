package urltest

import (
	"context"
	"maps"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/batch"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// ProbeMeta stores the latest probe outcome for a single strategy group member.
type ProbeMeta struct {
	Delay     uint16
	Error     string
	CheckedAt time.Time
}

// MemberSnapshot represents one member inside a strategy group for UI inspection.
type MemberSnapshot struct {
	Tag         string `json:"tag"`
	Delay       int    `json:"delay"`
	Error       string `json:"error,omitempty"`
	LastCheckMs int64  `json:"lastCheckMs"`
}

// GroupSnapshot represents the live state of a strategy group inside the Go core.
type GroupSnapshot struct {
	GroupTag     string           `json:"groupTag"`
	StrategyType string           `json:"strategyType"`
	ActiveTag    string           `json:"activeTag"`
	PinnedTag    string           `json:"pinnedTag"`
	Checking     bool             `json:"checking"`
	LastCheckMs  int64            `json:"lastCheckMs"`
	Members      []MemberSnapshot `json:"members"`
}

// StrategyGroupController is implemented by runtime strategy groups (URLTest / LoadBalance)
// so Android UI can inspect live state, trigger probes, and pin members directly without Clash API.
type StrategyGroupController interface {
	GroupSnapshot() GroupSnapshot
	PinOutbound(tag string) bool
	PinnedOutbound() string
	CheckAndSnapshot(ctx context.Context) GroupSnapshot
}

// BuildMemberSnapshots converts ordered member tags plus latest probe metadata and history
// into a consistent UI-ready member snapshot slice shared across URLTest and LoadBalance.
func BuildMemberSnapshots(
	ctx context.Context,
	outboundMgr adapter.OutboundManager,
	history *urltest.HistoryStorage,
	tags []string,
	meta map[string]ProbeMeta,
) []MemberSnapshot {
	if history == nil && ctx != nil {
		history = service.PtrFromContext[urltest.HistoryStorage](ctx)
	}
	members := make([]MemberSnapshot, 0, len(tags))
	for _, tag := range tags {
		entry := MemberSnapshot{Tag: tag}
		probeInfo, hasMeta := meta[tag]
		if hasMeta {
			entry.LastCheckMs = probeInfo.CheckedAt.UnixMilli()
			if probeInfo.Error != "" {
				entry.Error = probeInfo.Error
				entry.Delay = 0
				members = append(members, entry)
				continue
			}
			entry.Delay = int(probeInfo.Delay)
		}

		if history != nil {
			var hist *adapter.URLTestHistory
			if outboundMgr != nil {
				if ob, ok := outboundMgr.Outbound(tag); ok && ob != nil {
					if g, isGrp := ob.(adapter.OutboundGroup); isGrp {
						if realTag := ResolveGroupRealTag(g, N.NetworkTCP); realTag != "" {
							hist = history.LoadURLTestHistory(realTag)
						}
					}
				}
			}
			if hist == nil {
				hist = history.LoadURLTestHistory(tag)
			}
			if hist != nil {
				histMs := hist.Time.UnixMilli()
				if entry.Delay == 0 && entry.Error == "" && hist.Delay > 0 {
					entry.Delay = int(hist.Delay)
				}
				if entry.LastCheckMs == 0 {
					entry.LastCheckMs = histMs
				}
			}
		}
		members = append(members, entry)
	}
	return members
}

// ResolveGroupRealTag returns the leaf outbound tag currently chosen by a nested group.
func ResolveGroupRealTag(grp adapter.OutboundGroup, network string) string {
	visited := make(map[string]bool)
	var cur adapter.Outbound = grp
	for cur != nil {
		tag := cur.Tag()
		if visited[tag] {
			return tag
		}
		visited[tag] = true
		nested, isGrp := cur.(adapter.OutboundGroup)
		if !isGrp {
			return tag
		}
		selected := nested.Selected(network)
		if selected == nil {
			return tag
		}
		cur = selected
	}
	return ""
}

// URLTestOutboundsWithMeta probes outbounds and returns both delay map and detailed probe metadata.
func URLTestOutboundsWithMeta(
	ctx context.Context,
	outboundManager adapter.OutboundManager,
	history *urltest.HistoryStorage,
	logger log.Logger,
	outbounds []adapter.Outbound,
	link string,
	interval time.Duration,
	force bool,
) (map[string]uint16, map[string]ProbeMeta) {
	b, _ := batch.New(ctx, batch.WithConcurrencyNum[any](6))
	testBatch := &urlTestBatch{
		ctx:      ctx,
		outbound: outboundManager,
		history:  history,
		logger:   logger,
		batch:    b,
		checked:  make(map[string]bool),
		result:   make(map[string]uint16),
		meta:     make(map[string]ProbeMeta),
	}
	testBatch.test(outbounds, link, interval, force)
	b.Wait()
	for _, outboundGroup := range testBatch.groups {
		groupTag := outboundGroup.Tag()
		if g, isGrp := outboundGroup.(adapter.OutboundGroup); isGrp {
			if realTag := ResolveGroupRealTag(g, N.NetworkTCP); realTag != "" {
				groupHistory := history.LoadURLTestHistory(realTag)
				if groupHistory != nil {
					testBatch.result[groupTag] = groupHistory.Delay
					testBatch.access.Lock()
					if _, exists := testBatch.meta[groupTag]; !exists {
						testBatch.meta[groupTag] = ProbeMeta{
							Delay:     groupHistory.Delay,
							CheckedAt: groupHistory.Time,
						}
					}
					testBatch.access.Unlock()
				}
			}
		}
	}
	return testBatch.result, testBatch.meta
}

// CopyMetaLocked returns a shallow clone of a ProbeMeta map guarded by mu.
func CopyMetaLocked(mu *sync.RWMutex, src map[string]ProbeMeta) map[string]ProbeMeta {
	mu.RLock()
	defer mu.RUnlock()
	if len(src) == 0 {
		return nil
	}
	out := make(map[string]ProbeMeta, len(src))
	maps.Copy(out, src)
	return out
}

// MergeProbeMeta writes updated probe entries into dst guarded by mu.
func MergeProbeMeta(mu *sync.RWMutex, dst *map[string]ProbeMeta, src map[string]ProbeMeta) {
	if len(src) == 0 {
		return
	}
	mu.Lock()
	defer mu.Unlock()
	if *dst == nil {
		*dst = make(map[string]ProbeMeta, len(src))
	}
	for k, v := range src {
		(*dst)[k] = v
	}
}
