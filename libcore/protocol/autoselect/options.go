package autoselect

import (
	"time"

	"github.com/sagernet/sing/common/json/badoption"
)

const (
	TypeAutoSelect = "autoselect"

	SelectionModeLatency  = "latency"
	SelectionModePriority = "priority"

	BalanceModeTimedRotation = "timed_rotation"
	BalanceModePerConnection = "per_connection"

	ClearLearnedControlTag = ":clear_learned"

	defaultSiteStickyTTL       = 60 * time.Minute
	defaultMaxDialRetries      = 2
	defaultFastFailoverTimeout = 2 * time.Second
)

// AutoSelectOptions defines JSON configuration for the unified autoselect outbound.
type AutoSelectOptions struct {
	Outbounds                 []string           `json:"outbounds" reference:"outbound"`
	URL                       string             `json:"url,omitempty"`
	SelectionMode             string             `json:"selection_mode,omitempty"`
	BalanceEnabled            bool               `json:"balance_enabled,omitempty"`
	BalanceMode               string             `json:"balance_mode,omitempty"`
	BalanceInterval           badoption.Duration `json:"balance_interval,omitempty"`
	CheckInterval             badoption.Duration `json:"check_interval,omitempty"`
	Tolerance                 *uint16            `json:"tolerance,omitempty"`
	InterruptExistConnections bool               `json:"interrupt_exist_connections,omitempty"`
	LearnFromConnections      *bool              `json:"learn_from_connections,omitempty"`
	Tiers                     [][]string         `json:"tiers,omitempty"`
	InitialDelays             map[string]uint16  `json:"initial_delays,omitempty"`
}

type normalizedConfig struct {
	url                  string
	selectionMode        string
	balanceEnabled       bool
	balanceMode          string
	balanceInterval      time.Duration
	checkInterval        time.Duration
	tolerance            uint16
	interruptOnSwitch    bool
	learnFromConnections bool
	tiers                [][]string
}

func (o AutoSelectOptions) normalized() normalizedConfig {
	selectionMode := SelectionModeLatency
	if o.SelectionMode == SelectionModePriority {
		selectionMode = SelectionModePriority
	}

	balanceMode := BalanceModeTimedRotation
	if o.BalanceMode == BalanceModePerConnection {
		balanceMode = BalanceModePerConnection
	}

	balanceInterval := o.BalanceInterval.Build()
	if balanceInterval <= 0 {
		balanceInterval = 30 * time.Second
	} else if balanceInterval < time.Second {
		balanceInterval = time.Second
	}

	checkInterval := o.CheckInterval.Build()
	if checkInterval <= 0 {
		checkInterval = 300 * time.Second
	} else if checkInterval < 5*time.Second {
		checkInterval = 5 * time.Second
	}

	var tolerance uint16 = 30
	if o.Tolerance != nil {
		tolerance = *o.Tolerance
	}

	learn := true
	if o.LearnFromConnections != nil {
		learn = *o.LearnFromConnections
	}

	var tiers [][]string
	if len(o.Tiers) > 0 {
		tiers = make([][]string, len(o.Tiers))
		for i, tierTags := range o.Tiers {
			if len(tierTags) > 0 {
				tiers[i] = append([]string(nil), tierTags...)
			}
		}
	}

	return normalizedConfig{
		url:                  o.URL,
		selectionMode:        selectionMode,
		balanceEnabled:       o.BalanceEnabled,
		balanceMode:          balanceMode,
		balanceInterval:      balanceInterval,
		checkInterval:        checkInterval,
		tolerance:            tolerance,
		interruptOnSwitch:    o.InterruptExistConnections,
		learnFromConnections: learn,
		tiers:                tiers,
	}
}
