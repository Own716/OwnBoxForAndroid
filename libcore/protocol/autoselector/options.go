package autoselector

import (
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/json/badoption"
)

const TypeAutoSelector = "auto-selector"

// WarmEntry carries the UI's saved health summary for one member so a
// recreated core instance starts with an informed ranking instead of cold-probing.
type WarmEntry struct {
	Tag string `json:"tag"`
	RTT int    `json:"rtt"`
	Age int64  `json:"age,omitempty"`
}

// AutoSelectorOptions mirrors Throne's option.AutoSelectorOutboundOptions cleanly
// inside libcore without requiring a sing-box fork.
type AutoSelectorOptions struct {
	Outbounds                 []string             `json:"outbounds" reference:"outbound"`
	URL                       string               `json:"url,omitempty"`
	ConnectivityURL           string               `json:"connectivity_url,omitempty"`
	Interval                  badoption.Duration   `json:"interval,omitempty"`
	BenchInterval             badoption.Duration   `json:"bench_interval,omitempty"`
	WatchInterval             badoption.Duration   `json:"watch_interval,omitempty"`
	ActiveSize                int                  `json:"active_size,omitempty"`
	Sampling                  int                  `json:"sampling,omitempty"`
	Timeout                   badoption.Duration   `json:"timeout,omitempty"`
	Concurrency               int                  `json:"concurrency,omitempty"`
	Tolerance                 *int                 `json:"tolerance,omitempty"`
	MaxRTT                    badoption.Duration   `json:"max_rtt,omitempty"`
	FailTolerance             float64              `json:"fail_tolerance,omitempty"`
	Baselines                 []badoption.Duration `json:"baselines,omitempty"`
	Expected                  int                  `json:"expected,omitempty"`
	Balance                   bool                 `json:"balance,omitempty"`
	BalanceMode               string               `json:"balance_mode,omitempty"`
	BalanceInterval           badoption.Duration   `json:"balance_interval,omitempty"`
	DialRetries               *int                 `json:"dial_retries,omitempty"`
	InterruptExistConnections bool                 `json:"interrupt_exist_connections,omitempty"`
	Pinned                    string               `json:"pinned,omitempty"`
	Warm                      []WarmEntry          `json:"warm,omitempty"`
}

// AutoSelectorMemberStatus is one member's externally visible health.
type AutoSelectorMemberStatus struct {
	Tag             string `json:"tag"`
	Rank            int    `json:"rank"`
	State           string `json:"state"`
	Selected        bool   `json:"selected"`
	SelectedUDP     bool   `json:"selected_udp"`
	Qualified       bool   `json:"qualified"`
	Active          bool   `json:"active"`
	AverageMs       int    `json:"average_ms"`
	DeviationMs     int    `json:"deviation_ms"`
	MinMs           int    `json:"min_ms"`
	MaxMs           int    `json:"max_ms"`
	Samples         int    `json:"samples"`
	Failures        int    `json:"failures"`
	Probes          int    `json:"probes"`
	DialTotal       int    `json:"dial_total"`
	DialFail        int    `json:"dial_fail"`
	LastOKMs        int64  `json:"last_ok_ms"`
	LastProbeMs     int64  `json:"last_probe_ms"`
	CooldownUntilMs int64  `json:"cooldown_until_ms"`
	LastError       string `json:"last_error"`
}

// AutoSelectorStatus is a point-in-time snapshot of one auto-selector group.
type AutoSelectorStatus struct {
	Tag              string                     `json:"tag"`
	Phase            string                     `json:"phase"`
	Selected         string                     `json:"selected"`
	SelectedUDP      string                     `json:"selected_udp"`
	Pinned           string                     `json:"pinned"`
	Balance          bool                       `json:"balance"`
	BalanceMode      string                     `json:"balance_mode"`
	Suspended        bool                       `json:"suspended"`
	SuspendedSinceMs int64                      `json:"suspended_since_ms"`
	MembersTotal     int                        `json:"members_total"`
	MembersProbed    int                        `json:"members_probed"`
	MembersAlive     int                        `json:"members_alive"`
	MembersQualified int                        `json:"members_qualified"`
	MembersCooldown  int                        `json:"members_cooldown"`
	ProbesInFlight   int                        `json:"probes_in_flight"`
	RoundsCompleted  int                        `json:"rounds_completed"`
	LastRoundMs      int64                      `json:"last_round_ms"`
	NextRoundMs      int64                      `json:"next_round_ms"`
	LastSwitchMs     int64                      `json:"last_switch_ms"`
	LastSwitchReason string                     `json:"last_switch_reason"`
	Members          []AutoSelectorMemberStatus `json:"members"`
}

// AutoSelectorGroup is the contract callers outside this package use for inspection and control.
type AutoSelectorGroup interface {
	adapter.OutboundGroup
	Status() AutoSelectorStatus
	CheckOutbounds()
	SelectOutbound(tag string) bool
}
