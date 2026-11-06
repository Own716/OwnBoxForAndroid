package io.nekohasekai.sagernet.ui.route

import android.text.InputType
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.route.RouteRuleEditor
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import moe.matsuri.nb4a.utils.listByLineOrComma

/** JSON-backed preferences leave Room and the legacy binary backup contract untouched. */
class RouteRulePreferences(private val fragment: PreferenceFragmentCompat) {
    private var expanded = false
    private val context get() = fragment.requireContext()
    private fun pref(key: String) = fragment.findPreference<Preference>(key)!!

    companion object {
        val NETWORK_TYPES = listOf("wifi", "cellular", "ethernet", "other")
        private val ADVANCED_JSON_FIELDS = setOf(
            "domain_keyword", "domain_regex", "domain", "ip_is_private", "source_ip_is_private",
            "ip_version", "inbound", "invert", "override_address", "override_port",
            "no_drop", "network_is_expensive", "sniffer"
        )
    }

    fun bind() {
        for (key in listOf(
            Key.ROUTE_DOMAIN, Key.ROUTE_IP, Key.ROUTE_SOURCE, Key.ROUTE_PORT,
            Key.ROUTE_SOURCE_PORT, Key.ROUTE_NETWORK, Key.ROUTE_PROTOCOL
        )) {
            (pref(key) as? EditTextPreference)?.setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                edit.isSingleLine = false
                edit.minLines = 3
                edit.maxLines = 10
            }
        }
        for (field in RouteRuleEditor.fields) {
            val preference = fragment.findPreference<Preference>("rr_$field") ?: continue
            preference.isPersistent = false
            if (preference is EditTextPreference) preference.setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                edit.isSingleLine = false
                edit.minLines = if (field in RouteRuleEditor.lists) 3 else 1
            }
            if (field == "network_type") continue
            preference.setOnPreferenceChangeListener { _, value ->
                try {
                    DataStore.serverConfig = RouteRuleEditor.change(DataStore.serverConfig, field, value)
                    DataStore.dirty = true
                    preferenceScreenState()
                    true
                } catch (_: Exception) {
                    val reason = if (runCatching { RouteRuleEditor.json(DataStore.serverConfig) }.isFailure)
                        R.string.rr_json_error else R.string.rr_value_error
                    MaterialAlertDialogBuilder(context).setTitle(R.string.rr_invalid)
                        .setMessage(context.getString(R.string.rr_problem, preference.title, context.getString(reason)))
                        .setPositiveButton(android.R.string.ok, null).show()
                    false
                }
            }
        }
        pref(Key.ROUTE_RULESET).setOnPreferenceClickListener {
            val currentList = DataStore.routeRuleset.listByLineOrComma()
            (fragment.activity as? RouteSettingsActivity)?.selectRuleSets?.launch(currentList)
            true
        }
        pref("rr_network_type").setOnPreferenceClickListener {
            pickNetworkTypes()
            true
        }
        pref("rr_ruleset_auto_update").setOnPreferenceClickListener {
            RuleSetAutoUpdateDialog.show(context) {
                refresh()
            }
            true
        }
        pref("rr_toggle").setOnPreferenceClickListener {
            expanded = !expanded
            preferenceScreenState()
            true
        }
        refresh()
    }

    fun refresh() {
        val obj = runCatching { RouteRuleEditor.json(DataStore.serverConfig) }.getOrNull() ?: return
        for (field in RouteRuleEditor.fields) {
            val preference = fragment.findPreference<Preference>("rr_$field") ?: continue
            when (preference) {
                is SwitchPreferenceCompat -> preference.isChecked = obj.optBoolean(field)
                is ListPreference -> preference.value = RouteRuleEditor.text(DataStore.serverConfig, field)
                is EditTextPreference -> preference.text = RouteRuleEditor.text(DataStore.serverConfig, field)
            }
        }
        updateRuleSetSummary()
        updateNetworkTypeSummary()
        pref("rr_ruleset_auto_update").summary = RuleSets.formatUpdateInterval(context, DataStore.rulesUpdateInterval)
        preferenceScreenState()
    }

    private fun updateRuleSetSummary() {
        val items = DataStore.routeRuleset.listByLineOrComma().map { RuleSets.shortName(it) }
        pref(Key.ROUTE_RULESET).summary = if (items.isEmpty()) {
            context.getString(androidx.preference.R.string.not_set)
        } else {
            val shown = items.take(3).joinToString("\n")
            if (items.size > 3) "$shown\n…" else shown
        }
    }

    private fun updateNetworkTypeSummary() {
        val obj = runCatching { RouteRuleEditor.json(DataStore.serverConfig) }.getOrNull()
        val values = if (obj != null) RouteRuleEditor.values(obj, "network_type") else emptyList()
        val labels = context.resources.getStringArray(R.array.route_rule_network_type_entries)
        val names = values.map { v ->
            val idx = NETWORK_TYPES.indexOf(v)
            if (idx >= 0 && idx < labels.size) labels[idx] else v
        }
        pref("rr_network_type").summary = if (names.isEmpty()) {
            context.getString(androidx.preference.R.string.not_set)
        } else {
            names.joinToString(", ")
        }
    }

    private fun pickNetworkTypes() {
        val obj = runCatching { RouteRuleEditor.json(DataStore.serverConfig) }.getOrNull()
        val current = if (obj != null) RouteRuleEditor.values(obj, "network_type") else emptyList()
        val checked = BooleanArray(NETWORK_TYPES.size) { NETWORK_TYPES[it] in current }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.route_rule_network_type)
            .setMultiChoiceItems(R.array.route_rule_network_type_entries, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = NETWORK_TYPES.filterIndexed { i, _ -> checked[i] }.joinToString("\n")
                DataStore.serverConfig = RouteRuleEditor.change(DataStore.serverConfig, "network_type", selected)
                DataStore.dirty = true
                updateNetworkTypeSummary()
                preferenceScreenState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun countAdvancedSet(): Int {
        var count = 0
        for (legacyKey in listOf(
            DataStore.routeSource, DataStore.routePort, DataStore.routeSourcePort,
            DataStore.routeNetwork, DataStore.routeProtocol
        )) {
            if (legacyKey.isNotBlank()) count++
        }
        val obj = runCatching { RouteRuleEditor.json(DataStore.serverConfig) }.getOrNull() ?: return count
        for (field in ADVANCED_JSON_FIELDS) {
            if (field in RouteRuleEditor.booleans) {
                if (obj.optBoolean(field)) count++
            } else if (field in RouteRuleEditor.lists) {
                if (RouteRuleEditor.values(obj, field).isNotEmpty()) count++
            } else if (obj.optString(field).isNotBlank()) {
                count++
            }
        }
        return count
    }

    private fun preferenceScreenState() {
        pref("rr_advanced").isVisible = expanded
        val advancedCount = countAdvancedSet()
        pref("rr_toggle").apply {
            title = if (advancedCount > 0) {
                context.getString(R.string.route_rule_advanced_count, advancedCount)
            } else {
                context.getString(R.string.rr_advanced)
            }
            setSummary(if (expanded) R.string.rr_hide_advanced else R.string.rr_show_advanced)
            setIcon(if (expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        }
        val act = runCatching { RouteRuleEditor.json(DataStore.serverConfig).optString("action").ifBlank { "route" } }.getOrDefault("route")
        pref(Key.ROUTE_OUTBOUND).isEnabled = act == "route"
        // Preserve previously entered action options so changing actions never silently loses data.
        for ((field, applicable) in mapOf(
            "method" to (act == "reject"),
            "no_drop" to (act == "reject"),
            "strategy" to (act == "resolve"),
            "sniffer" to (act == "sniff"),
            "override_address" to (act in setOf("route", "route-options")),
            "override_port" to (act in setOf("route", "route-options"))
        )) {
            pref("rr_$field").isVisible = applicable || runCatching { RouteRuleEditor.json(DataStore.serverConfig).has(field) }.getOrDefault(false)
        }
    }
}
