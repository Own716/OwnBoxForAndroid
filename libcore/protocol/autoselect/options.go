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
)

// AutoSelectOptions defines JSON configuration for the unified autoselect outbound.
type AutoSelectOptions struct {
	Outbounds                 []string            `json:"outbounds" reference:"outbound"`
	URL                       string              `json:"url,omitempty"`
	SelectionMode             string              `json:"selection_mode,omitempty"`
	BalanceEnabled            bool                `json:"balance_enabled,omitempty"`
	BalanceMode               string              `json:"balance_mode,omitempty"`
	BalanceInterval           badoption.Duration  `json:"balance_interval,omitempty"`
	RankTopN                  int                 `json:"rank_top_n,omitempty"`
	StandbySize               *int                `json:"standby_size,omitempty"`
	IntensiveCheckCount       *int                `json:"intensive_check_count,omitempty"`
	CheckInterval             badoption.Duration  `json:"check_interval,omitempty"`
	FullScanInterval          badoption.Duration  `json:"full_scan_interval,omitempty"`
	WatchInterval             *badoption.Duration `json:"watch_interval,omitempty"`
	SampleCount               *int                `json:"sample_count,omitempty"`
	Tolerance                 *uint16             `json:"tolerance,omitempty"`
	MaxRTT                    badoption.Duration  `json:"max_rtt,omitempty"`
	DialRetries               *int                `json:"dial_retries,omitempty"`
	InterruptExistConnections bool                `json:"interrupt_exist_connections,omitempty"`
	FailbackChecks            *int                `json:"failback_checks,omitempty"`
	FailbackDelay             *badoption.Duration `json:"failback_delay,omitempty"`
	InterruptOnFailback       bool                `json:"interrupt_on_failback,omitempty"`
	LearnFromConnections      *bool               `json:"learn_from_connections,omitempty"`
	SiteStickyTTL             *badoption.Duration `json:"site_sticky_ttl,omitempty"`
	ConnectivityURL           string              `json:"connectivity_url,omitempty"`
	InitialDelaysMs           map[string]uint16   `json:"initial_delays_ms,omitempty"`
}

type normalizedConfig struct {
	url                  string
	selectionMode        string
	balanceEnabled       bool
	balanceMode          string
	balanceInterval      time.Duration
	rankTopN             int
	standbySize          int
	intensiveCheckCount  int
	checkInterval        time.Duration
	fullScanInterval     time.Duration
	watchInterval        time.Duration
	sampleCount          int
	tolerance            uint16
	maxRttMs             uint16
	dialRetries          int
	interruptOnSwitch    bool
	failbackChecks       int
	failbackDelay        time.Duration
	interruptOnFailback  bool
	learnFromConnections bool
	siteStickyTTL        time.Duration
	connectivityURL      string
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

	standbySize := 3
	if o.StandbySize != nil {
		standbySize = *o.StandbySize
		if standbySize < 0 {
			standbySize = 0
		} else if standbySize > 50 {
			standbySize = 50
		}
	}

	intensiveCheckCount := 8
	if o.IntensiveCheckCount != nil {
		intensiveCheckCount = *o.IntensiveCheckCount
	}
	if intensiveCheckCount < 1 {
		intensiveCheckCount = 8
	} else if intensiveCheckCount > 200 {
		intensiveCheckCount = 200
	}

	checkInterval := o.CheckInterval.Build()
	if checkInterval <= 0 {
		checkInterval = 300 * time.Second
	} else if checkInterval < 5*time.Second {
		checkInterval = 5 * time.Second
	}

	fullScanInterval := o.FullScanInterval.Build()
	if fullScanInterval <= 0 {
		fullScanInterval = 600 * time.Second
	} else if fullScanInterval < 5*time.Second {
		fullScanInterval = 5 * time.Second
	}

	watchInterval := 15 * time.Second
	if o.WatchInterval != nil {
		d := o.WatchInterval.Build()
		if d <= 0 {
			watchInterval = 0
		} else if d < time.Second {
			watchInterval = time.Second
		} else {
			watchInterval = d
		}
	}

	sampleCount := 10
	if o.SampleCount != nil {
		sampleCount = *o.SampleCount
	}
	if sampleCount < 1 {
		sampleCount = 10
	} else if sampleCount > 50 {
		sampleCount = 50
	}

	var tolerance uint16 = 30
	if o.Tolerance != nil {
		tolerance = *o.Tolerance
	}

	var maxRttMs uint16
	if d := o.MaxRTT.Build(); d > 0 {
		ms := d.Milliseconds()
		if ms > 65535 {
			maxRttMs = 65535
		} else if ms > 0 {
			maxRttMs = uint16(ms)
		}
	}

	dialRetries := 2
	if o.DialRetries != nil {
		dialRetries = *o.DialRetries
		if dialRetries < 0 {
			dialRetries = 0
		} else if dialRetries > 10 {
			dialRetries = 10
		}
	}

	failbackChecks := 3
	if o.FailbackChecks != nil {
		failbackChecks = *o.FailbackChecks
		if failbackChecks < 1 {
			failbackChecks = 1
		} else if failbackChecks > 50 {
			failbackChecks = 50
		}
	}

	failbackDelay := 60 * time.Second
	if o.FailbackDelay != nil {
		d := o.FailbackDelay.Build()
		if d < 0 {
			failbackDelay = 0
		} else {
			failbackDelay = d
		}
	}

	learn := true
	if o.LearnFromConnections != nil {
		learn = *o.LearnFromConnections
	}

	siteStickyTTL := 60 * time.Minute
	if o.SiteStickyTTL != nil {
		d := o.SiteStickyTTL.Build()
		if d < 0 {
			siteStickyTTL = 0
		} else {
			siteStickyTTL = d
		}
	}

	rankTopN := o.RankTopN
	if rankTopN < 0 {
		rankTopN = 0
	}

	return normalizedConfig{
		url:                  o.URL,
		selectionMode:        selectionMode,
		balanceEnabled:       o.BalanceEnabled,
		balanceMode:          balanceMode,
		balanceInterval:      balanceInterval,
		rankTopN:             rankTopN,
		standbySize:          standbySize,
		intensiveCheckCount:  intensiveCheckCount,
		checkInterval:        checkInterval,
		fullScanInterval:     fullScanInterval,
		watchInterval:        watchInterval,
		sampleCount:          sampleCount,
		tolerance:            tolerance,
		maxRttMs:             maxRttMs,
		dialRetries:          dialRetries,
		interruptOnSwitch:    o.InterruptExistConnections,
		failbackChecks:       failbackChecks,
		failbackDelay:        failbackDelay,
		interruptOnFailback:  o.InterruptOnFailback,
		learnFromConnections: learn,
		siteStickyTTL:        siteStickyTTL,
		connectivityURL:      o.ConnectivityURL,
	}
}
