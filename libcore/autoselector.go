package libcore

import (
	"encoding/json"
	"libcore/protocol/autoselector"
)

// QueryAutoSelectorsJSON returns a JSON array describing all active auto-selector
// groups in this box instance. Passing withMembers=false strips the per-member
// breakdown for lightweight status polling.
func (b *BoxInstance) QueryAutoSelectorsJSON(withMembers bool) string {
	if b == nil || b.Box == nil {
		return "[]"
	}
	om := b.Outbound()
	if om == nil {
		return "[]"
	}
	var statuses []autoselector.AutoSelectorStatus
	for _, ob := range om.Outbounds() {
		grp, ok := ob.(autoselector.AutoSelectorGroup)
		if !ok {
			continue
		}
		st := grp.Status()
		if !withMembers {
			st.Members = nil
		}
		statuses = append(statuses, st)
	}
	if len(statuses) == 0 {
		return "[]"
	}
	data, err := json.Marshal(statuses)
	if err != nil {
		return "[]"
	}
	return string(data)
}

// AutoSelectorAction runs an imperative control command ("select" or "recheck")
// on the named auto-selector outbound group (or the first one if groupTag is empty).
// Returns an empty string on success or an error description on failure.
func (b *BoxInstance) AutoSelectorAction(groupTag string, action string, memberTag string) string {
	if b == nil || b.Box == nil {
		return "box not running"
	}
	om := b.Outbound()
	if om == nil {
		return "outbound manager unavailable"
	}
	var target autoselector.AutoSelectorGroup
	if groupTag != "" {
		if ob, ok := om.Outbound(groupTag); ok {
			target, _ = ob.(autoselector.AutoSelectorGroup)
		}
	}
	if target == nil {
		for _, ob := range om.Outbounds() {
			if grp, ok := ob.(autoselector.AutoSelectorGroup); ok {
				target = grp
				break
			}
		}
	}
	if target == nil {
		return "auto-selector group not found"
	}
	switch action {
	case "select":
		if !target.SelectOutbound(memberTag) {
			return "member not found: " + memberTag
		}
		return ""
	case "recheck":
		target.CheckOutbounds()
		return ""
	default:
		return "unknown action: " + action
	}
}
