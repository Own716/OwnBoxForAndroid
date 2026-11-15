package autoselect

import (
	"context"
	"time"

	customUrltest "libcore/protocol/urltest"
)

const (
	RoleActive  = "active"
	RoleStandby = "standby"
	RoleReserve = "reserve"
	RoleFailed  = "failed"
)

func (g *AutoSelect) GroupSnapshot() customUrltest.GroupSnapshot {
	now := g.now()
	g.scorer.mu.RLock()
	activeTag := g.scorer.activeTag
	pinnedTag := g.scorer.pinnedTag
	lastSwitchAt := g.scorer.lastSwitchAt
	switchReason := g.scorer.switchReason
	hasTiers := g.scorer.hasTiers
	tierCount := g.scorer.tierCount
	order := append([]string(nil), g.scorer.order...)

	type memberView struct {
		tag           string
		delay         uint16
		checkCount    int
		failCount     int
		available     bool
		err           string
		lastCheckedAt time.Time
		tier          int
	}

	activeTier := 0
	views := make([]memberView, 0, len(order))
	availCount := 0
	for _, tag := range order {
		st := g.scorer.members[tag]
		if hasTiers && tag == activeTag {
			activeTier = st.tier + 1
		}
		if st.available {
			availCount++
		}
		errMsg := st.lastError
		if st.available {
			errMsg = ""
		}
		views = append(views, memberView{
			tag:           tag,
			delay:         st.lastDelay,
			checkCount:    st.checkCount,
			failCount:     st.failCount,
			available:     st.available,
			err:           errMsg,
			lastCheckedAt: st.lastCheckedAt,
			tier:          st.tier,
		})
	}
	g.scorer.mu.RUnlock()

	members := make([]customUrltest.MemberSnapshot, 0, len(views))
	for _, v := range views {
		role := RoleReserve
		if v.tag == activeTag {
			role = RoleActive
		} else if !v.available {
			if v.checkCount > 0 || v.err != "" {
				role = RoleFailed
			}
		} else {
			role = RoleStandby
		}

		var lastCheckMs int64
		if !v.lastCheckedAt.IsZero() {
			lastCheckMs = v.lastCheckedAt.UnixMilli()
		}

		displayDelay := int(v.delay)
		if !v.available && v.err != "" && v.checkCount > 0 {
			displayDelay = 0
		}

		var tierValue int
		if hasTiers {
			tierValue = v.tier + 1
		}

		members = append(members, customUrltest.MemberSnapshot{
			Tag:         v.tag,
			Delay:       displayDelay,
			Error:       v.err,
			LastCheckMs: lastCheckMs,
			Role:        role,
			Tier:        tierValue,
			CheckCount:  v.checkCount,
			FailCount:   v.failCount,
		})
	}

	var lastCheckMs int64
	if last := g.prober.LastCheckTime(); !last.IsZero() {
		lastCheckMs = last.UnixMilli()
	}
	var lastSwitchMs int64
	if !lastSwitchAt.IsZero() {
		lastSwitchMs = lastSwitchAt.UnixMilli()
	}

	learnedCount := 0
	if g.cfg.learnFromConnections && g.passive != nil {
		learnedCount = g.passive.LearnedSiteCount(now)
	}

	return customUrltest.GroupSnapshot{
		GroupTag:         g.Tag(),
		StrategyType:     TypeAutoSelect,
		ActiveTag:        activeTag,
		PinnedTag:        pinnedTag,
		Checking:         g.prober.IsChecking(),
		LastCheckMs:      lastCheckMs,
		SelectionMode:    g.cfg.selectionMode,
		ActiveTier:       activeTier,
		TierCount:        tierCount,
		BalanceEnabled:   g.cfg.balanceEnabled,
		AvailableCount:   availCount,
		TotalCount:       len(order),
		LastSwitchMs:     lastSwitchMs,
		SwitchReason:     switchReason,
		NextCheckSec:     g.prober.NextCheckRemainingSec(now),
		LearnedSiteCount: learnedCount,
		LearnEnabled:     g.cfg.learnFromConnections,
		LocalOffline:     g.prober.IsLocalOffline(),
		Members:          members,
	}
}

func (g *AutoSelect) CheckAndSnapshot(ctx context.Context) customUrltest.GroupSnapshot {
	g.prober.CheckAllNow(ctx)
	return g.GroupSnapshot()
}
