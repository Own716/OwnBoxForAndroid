package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatSpinner
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.BalancerMemberResolver
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.OutboundPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import java.util.Locale

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_balancer_settings) {

    companion object {
        private const val STATE_ADVANCED_EXPANDED = "balancer_advanced_expanded"
        private const val ORDER_TIER_BASE = 6001
    }

    private data class PreviewOverrides(
        val country: String? = null,
        val exclude: String? = null,
        val include: String? = null,
        val selectionMode: String? = null,
    )

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference
    private val groupNameMap = HashMap<Long, String>()
    private val tiersList = ArrayList<BalancerBean.PriorityTier>()
    private val tierPrefs = ArrayList<Preference>()
    private var lastTierCounts: List<Int> = emptyList()
    private var refreshMatchSummaryFn: ((PreviewOverrides) -> Unit)? = null
    private var refreshExplicitSummaryFn: (() -> Unit)? = null
    private var refreshNodeListFn: (() -> Unit)? = null
    private var rebuildTierPrefsFn: (() -> Unit)? = null
    private var advancedExpanded = false

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profileId = it.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0) ?: 0L
            val profile = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
            DataStore.balancerFrontProxy = profile.id
            DataStore.dirty = true
            onMainDispatcher {
                if (::frontProxyPreference.isInitialized) {
                    frontProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
                refreshMatchSummaryFn?.invoke(PreviewOverrides())
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profileId = it.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0) ?: 0L
            val profile = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
            DataStore.balancerLandingProxy = profile.id
            DataStore.dirty = true
            onMainDispatcher {
                if (::landingProxyPreference.isInitialized) {
                    landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
                refreshMatchSummaryFn?.invoke(PreviewOverrides())
            }
        }
    }

    override fun createEntity(): BalancerBean {
        val count = runCatching { SagerDatabase.proxyDao.getByType(ProxyEntity.TYPE_BALANCER).size }.getOrDefault(0) + 1
        return BalancerBean().apply {
            name = String.format(Locale.ROOT, "Auto-Select %02d", count)
            balancerType = BalancerBean.TYPE_MIXED
        }
    }

    val proxyList = ArrayList<ProxyEntity>()

    override fun BalancerBean.init() {
        DataStore.profileName = name
        DataStore.balancerType = balancerType
        val gids = if (targetGroupIds != null && targetGroupIds.isNotEmpty()) {
            targetGroupIds
        } else if (targetGroupId > 0L) {
            listOf(targetGroupId)
        } else {
            emptyList()
        }
        DataStore.balancerTargetGroups = gids.joinToString(",")
        DataStore.balancerTargetGroup = gids.firstOrNull() ?: 0L

        val normalizedSelectionMode = when {
            isFallbackMode() -> BalancerBean.SELECTION_FALLBACK
            isPriorityMode() -> BalancerBean.SELECTION_PRIORITY
            else -> BalancerBean.SELECTION_LATENCY
        }
        DataStore.balancerSelectionMode = normalizedSelectionMode
        DataStore.balancerStrategy = normalizedSelectionMode

        DataStore.balancerBalanceEnabled = balanceEnabled
        DataStore.balancerBalanceMode = if (balanceMode == BalancerBean.BALANCE_PER_CONNECTION) {
            BalancerBean.BALANCE_PER_CONNECTION
        } else {
            BalancerBean.BALANCE_TIMED_ROTATION
        }
        DataStore.balancerBalanceIntervalSec = balanceIntervalSec.takeIf { it > 0 } ?: 30

        DataStore.balancerCountryFilter = countryFilter ?: ""
        DataStore.balancerInterval = interval

        val normalizedTolerance = if (toleranceUnit == "s") {
            tolerance.coerceAtLeast(0) * 1000
        } else {
            tolerance
        }
        DataStore.balancerTolerance = normalizedTolerance
        DataStore.balancerToleranceUnit = "ms"
        DataStore.balancerInterruptOnSwitch = interruptOnSwitch
        DataStore.balancerLearnFromConnections = learnFromConnections

        DataStore.balancerTestUrl = testUrl ?: ""
        DataStore.balancerFrontProxy = frontProxy
        DataStore.balancerFrontProxyTmp = if (frontProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerLandingProxy = landingProxy
        DataStore.balancerLandingProxyTmp = if (landingProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerNameExclude = nameExclude ?: ""
        DataStore.balancerNameInclude = nameInclude ?: ""
        DataStore.balancerTiers = BalancerBean.tiersToJson(tiers)
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        balancerType = DataStore.balancerType
        val gids = parseGroupIds(DataStore.balancerTargetGroups)
        targetGroupIds = ArrayList(gids)
        targetGroupId = gids.firstOrNull() ?: 0L

        selectionMode = when (DataStore.balancerSelectionMode) {
            BalancerBean.SELECTION_FALLBACK -> BalancerBean.SELECTION_FALLBACK
            BalancerBean.SELECTION_PRIORITY -> BalancerBean.SELECTION_PRIORITY
            else -> BalancerBean.SELECTION_LATENCY
        }
        strategy = selectionMode

        balanceEnabled = DataStore.balancerBalanceEnabled
        balanceMode = if (DataStore.balancerBalanceMode == BalancerBean.BALANCE_PER_CONNECTION) {
            BalancerBean.BALANCE_PER_CONNECTION
        } else {
            BalancerBean.BALANCE_TIMED_ROTATION
        }
        balanceIntervalSec = DataStore.balancerBalanceIntervalSec

        countryFilter = DataStore.balancerCountryFilter.trim()
        interval = DataStore.balancerInterval
        tolerance = DataStore.balancerTolerance
        toleranceUnit = "ms"
        interruptOnSwitch = DataStore.balancerInterruptOnSwitch
        learnFromConnections = DataStore.balancerLearnFromConnections

        testUrl = DataStore.balancerTestUrl.trim()
        frontProxy = if (DataStore.balancerFrontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerFrontProxy
        } else {
            -1L
        }
        landingProxy = if (DataStore.balancerLandingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerLandingProxy
        } else {
            -1L
        }
        nameExclude = DataStore.balancerNameExclude
        nameInclude = DataStore.balancerNameInclude
        tiers = BalancerBean.parseTiersJson(DataStore.balancerTiers)
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED_EXPANDED, advancedExpanded)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.balancer_preferences)

        val frontPref = findPreference<OutboundPreference>("balancerFrontProxy")
        frontPref?.apply {
            frontProxyPreference = this
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerFrontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                            ProfileManager.getProfile(DataStore.balancerFrontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    runOnMainDispatcher { refreshMatchSummaryFn?.invoke(PreviewOverrides()) }
                    true
                }
            }
        }

        val landingPref = findPreference<OutboundPreference>("balancerLandingProxy")
        landingPref?.apply {
            landingProxyPreference = this
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerLandingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                            ProfileManager.getProfile(DataStore.balancerLandingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    runOnMainDispatcher { refreshMatchSummaryFn?.invoke(PreviewOverrides()) }
                    true
                }
            }
        }

        val groupPref = findPreference<Preference>("balancerTargetGroup")
        val explicitNodesPref = findPreference<Preference>("balancerExplicitNodes")
        val localNodeListPref = findPreference<Preference>("balancerNodeList")
        val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())
        groupNameMap.clear()
        allGroups.filter { !it.ungrouped }.forEach { groupNameMap[it.id] = it.displayName() }

        fun updateGroupSummary() {
            val selectedGids = parseGroupIds(DataStore.balancerTargetGroups).toSet()
            val matchedGroups = allGroups.filter { selectedGids.contains(it.id) }
            groupPref?.summary = when {
                matchedGroups.isEmpty() -> getString(R.string.balancer_no_group_selected)
                matchedGroups.size <= 2 -> matchedGroups.joinToString(", ") { it.displayName() }
                else -> "${matchedGroups.size}: " + matchedGroups.take(2).joinToString(", ") { it.displayName() } + "..."
            }
        }
        updateGroupSummary()

        fun updateExplicitNodesSummary() {
            explicitNodesPref?.summary = if (proxyList.isEmpty()) {
                getString(R.string.balancer_explicit_nodes_empty)
            } else {
                val previewNames = proxyList.take(3).joinToString(", ") { it.displayName() }
                val suffix = if (proxyList.size > 3) "..." else ""
                getString(R.string.balancer_explicit_nodes_summary, proxyList.size, previewNames + suffix)
            }
        }
        refreshExplicitSummaryFn = { updateExplicitNodesSummary() }
        updateExplicitNodesSummary()

        groupPref?.setOnPreferenceClickListener {
            val currentSelected = parseGroupIds(DataStore.balancerTargetGroups).toMutableSet()
            if (currentSelected.isEmpty() && DataStore.balancerTargetGroup > 0L) {
                currentSelected.add(DataStore.balancerTargetGroup)
            }
            val groupNames = allGroups.map { g ->
                val count = runCatching { SagerDatabase.proxyDao.getByGroup(g.id).size }.getOrDefault(0)
                "${g.displayName()} ($count)"
            }.toTypedArray()
            val checkedStates = allGroups.map { currentSelected.contains(it.id) }.toBooleanArray()

            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                .setTitle(R.string.balancer_select_group)
                .setMultiChoiceItems(groupNames, checkedStates) { _, which, isChecked ->
                    checkedStates[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newGids = allGroups.filterIndexed { index, _ -> checkedStates[index] }.map { it.id }
                    DataStore.balancerTargetGroups = newGids.joinToString(",")
                    DataStore.balancerTargetGroup = newGids.firstOrNull() ?: 0L
                    DataStore.dirty = true
                    updateGroupSummary()
                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        explicitNodesPref?.setOnPreferenceClickListener {
            launchMultiSelectNodes()
            true
        }

        val nameIncludePref = findPreference<EditTextPreference>("balancerNameInclude")
        val nameExcludePref = findPreference<EditTextPreference>("balancerNameExclude")
        val countryPref = findPreference<EditTextPreference>("balancerCountryFilter")
        val matchSummaryPref = findPreference<Preference>("balancerMatchSummary")
        val addTierPref = findPreference<Preference>("balancerAddTier")

        tiersList.clear()
        tiersList.addAll(BalancerBean.parseTiersJson(DataStore.balancerTiers))

        fun syncTiersDataStore() {
            DataStore.balancerTiers = BalancerBean.tiersToJson(tiersList)
        }

        fun formatTierSummary(tier: BalancerBean.PriorityTier, matchedCount: Int): String {
            val groupLabel = if (tier.groupId > 0L) {
                groupNameMap[tier.groupId] ?: getString(R.string.balancer_tier_missing_group, tier.groupId)
            } else {
                ""
            }
            val filterLabel = tier.nameFilter.orEmpty().trim()
            return when {
                groupLabel.isNotEmpty() && filterLabel.isNotEmpty() ->
                    getString(R.string.balancer_tier_sum_both, groupLabel, filterLabel, matchedCount)
                groupLabel.isNotEmpty() ->
                    getString(R.string.balancer_tier_sum_group_only, groupLabel, matchedCount)
                filterLabel.isNotEmpty() ->
                    getString(R.string.balancer_tier_sum_regex_only, filterLabel, matchedCount)
                else -> getString(R.string.balancer_tier_sum_all, matchedCount)
            }
        }

        fun updateAddTierSummary() {
            addTierPref?.summary = when {
                tiersList.isEmpty() -> getString(R.string.balancer_add_tier_empty_hint)
                else -> {
                    val base = getString(R.string.balancer_add_tier_sum)
                    val fallbackCount = lastTierCounts.getOrNull(tiersList.size) ?: 0
                    if (fallbackCount > 0) {
                        base + "\n" + getString(R.string.balancer_tier_fallback_note, fallbackCount)
                    } else {
                        base
                    }
                }
            }
        }

        var showTierDialog: ((Int?) -> Unit)? = null

        fun rebuildTierPreferences() {
            val screen = preferenceScreen ?: return
            screen.isOrderingAsAdded = false
            tierPrefs.forEach { screen.removePreference(it) }
            tierPrefs.clear()
            val isPriority = DataStore.balancerSelectionMode == BalancerBean.SELECTION_PRIORITY
            addTierPref?.isVisible = isPriority
            if (!isPriority) return

            tiersList.forEachIndexed { index, tier ->
                val pref = Preference(this@BalancerSettingsActivity).apply {
                    key = "balancerTier_$index"
                    order = ORDER_TIER_BASE + index
                    title = getString(R.string.balancer_tier_title, index + 1)
                    setIcon(R.drawable.ic_baseline_layers_24)
                    summary = formatTierSummary(tier, lastTierCounts.getOrNull(index) ?: 0)
                    isVisible = true
                    setOnPreferenceClickListener {
                        showTierDialog?.invoke(index)
                        true
                    }
                }
                screen.addPreference(pref)
                tierPrefs.add(pref)
            }
            updateAddTierSummary()
        }
        rebuildTierPrefsFn = { rebuildTierPreferences() }

        showTierDialog = { editIndex ->
            val existing = editIndex?.let { tiersList.getOrNull(it) }
            val dialogView = LayoutInflater.from(this@BalancerSettingsActivity)
                .inflate(R.layout.layout_balancer_tier_dialog, null)
            val groupLabel = dialogView.findViewById<TextView>(R.id.tier_group_label)
            val groupSpinner = dialogView.findViewById<AppCompatSpinner>(R.id.tier_group_spinner)
            val filterLayout = dialogView.findViewById<TextInputLayout>(R.id.tier_filter_layout)
            val filterEdit = dialogView.findViewById<TextInputEditText>(R.id.tier_filter_edit)
            val moveActions = dialogView.findViewById<View>(R.id.tier_move_actions)
            val moveUpBtn = dialogView.findViewById<MaterialButton>(R.id.tier_move_up)
            val moveDownBtn = dialogView.findViewById<MaterialButton>(R.id.tier_move_down)

            val selectedGids = parseGroupIds(DataStore.balancerTargetGroups).ifEmpty {
                if (DataStore.balancerTargetGroup > 0L) listOf(DataStore.balancerTargetGroup) else emptyList()
            }.toSet()
            val selectedGroups = allGroups.filter { selectedGids.contains(it.id) }
            val spinnerItems = ArrayList<Pair<Long, String>>().apply {
                add(-1L to getString(R.string.balancer_tier_any_group))
                selectedGroups.forEach { add(it.id to it.displayName()) }
                val existingGid = existing?.groupId ?: -1L
                if (existingGid > 0L && none { it.first == existingGid }) {
                    add(existingGid to (groupNameMap[existingGid] ?: getString(R.string.balancer_tier_missing_group, existingGid)))
                }
            }
            val showGroupSelector = spinnerItems.size > 1
            groupLabel.visibility = if (showGroupSelector) View.VISIBLE else View.GONE
            groupSpinner.visibility = if (showGroupSelector) View.VISIBLE else View.GONE
            if (showGroupSelector) {
                val adapter = ArrayAdapter(
                    this@BalancerSettingsActivity,
                    android.R.layout.simple_spinner_item,
                    spinnerItems.map { it.second }
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                groupSpinner.adapter = adapter
                val initialPos = spinnerItems.indexOfFirst { it.first == (existing?.groupId ?: -1L) }
                groupSpinner.setSelection(if (initialPos >= 0) initialPos else 0)
            }

            filterEdit.setText(existing?.nameFilter.orEmpty())
            filterEdit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    filterLayout.error = null
                }
            })

            fun buildValidatedTier(): BalancerBean.PriorityTier? {
                val pattern = filterEdit.text?.toString()?.trim().orEmpty()
                if (pattern.isNotEmpty() && runCatching { Regex(pattern) }.isFailure) {
                    filterLayout.error = getString(R.string.balancer_tier_regex_invalid)
                    return null
                }
                val chosenGid = if (showGroupSelector) {
                    spinnerItems.getOrNull(groupSpinner.selectedItemPosition)?.first ?: -1L
                } else {
                    -1L
                }
                return BalancerBean.PriorityTier(chosenGid, pattern)
            }

            val builder = MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                .setTitle(
                    if (editIndex == null) getString(R.string.balancer_tier_dialog_add)
                    else getString(R.string.balancer_tier_dialog_edit, editIndex + 1)
                )
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)

            if (editIndex != null) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    if (editIndex in tiersList.indices) {
                        tiersList.removeAt(editIndex)
                        syncTiersDataStore()
                        DataStore.dirty = true
                        rebuildTierPreferences()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                    }
                }
            }

            val dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val updated = buildValidatedTier() ?: return@setOnClickListener
                    if (editIndex == null) {
                        tiersList.add(updated)
                    } else if (editIndex in tiersList.indices) {
                        tiersList[editIndex] = updated
                    }
                    syncTiersDataStore()
                    DataStore.dirty = true
                    rebuildTierPreferences()
                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                    dialog.dismiss()
                }
            }

            if (editIndex != null && tiersList.size > 1) {
                moveActions.visibility = View.VISIBLE
                moveUpBtn.isEnabled = editIndex > 0
                moveDownBtn.isEnabled = editIndex < tiersList.lastIndex
                moveUpBtn.setOnClickListener {
                    val updated = buildValidatedTier() ?: return@setOnClickListener
                    if (editIndex in 1 until tiersList.size) {
                        tiersList[editIndex] = updated
                        val swapped = tiersList[editIndex - 1]
                        tiersList[editIndex - 1] = tiersList[editIndex]
                        tiersList[editIndex] = swapped
                        syncTiersDataStore()
                        DataStore.dirty = true
                        rebuildTierPreferences()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                        dialog.dismiss()
                    }
                }
                moveDownBtn.setOnClickListener {
                    val updated = buildValidatedTier() ?: return@setOnClickListener
                    if (editIndex in 0 until tiersList.lastIndex) {
                        tiersList[editIndex] = updated
                        val swapped = tiersList[editIndex + 1]
                        tiersList[editIndex + 1] = tiersList[editIndex]
                        tiersList[editIndex] = swapped
                        syncTiersDataStore()
                        DataStore.dirty = true
                        rebuildTierPreferences()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                        dialog.dismiss()
                    }
                }
            } else {
                moveActions.visibility = View.GONE
            }

            dialog.show()
        }

        addTierPref?.setOnPreferenceClickListener {
            showTierDialog?.invoke(null)
            true
        }

        fun refreshMatchSummary(overrides: PreviewOverrides = PreviewOverrides()) {
            val type = DataStore.balancerType
            val gids = parseGroupIds(DataStore.balancerTargetGroups).ifEmpty {
                if (DataStore.balancerTargetGroup > 0L) listOf(DataStore.balancerTargetGroup) else emptyList()
            }
            val extraIds = proxyList.map { it.id }
            val countryPattern = overrides.country ?: DataStore.balancerCountryFilter
            val excludePattern = overrides.exclude ?: DataStore.balancerNameExclude
            val includePattern = overrides.include ?: DataStore.balancerNameInclude
            val selectionMode = overrides.selectionMode ?: DataStore.balancerSelectionMode
            val tiersSnapshot = tiersList.map { it.copy() }
            val selfId = DataStore.editingId
            val frontId = if (DataStore.balancerFrontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.balancerFrontProxy
            } else {
                0L
            }
            val landingId = if (DataStore.balancerLandingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.balancerLandingProxy
            } else {
                0L
            }

            runOnDefaultDispatcher {
                val res = BalancerMemberResolver.resolve(
                    BalancerMemberResolver.FilterSpec(
                        balancerType = type,
                        targetGroupIds = gids,
                        explicitProxyIds = extraIds,
                        nameExclude = excludePattern,
                        nameInclude = includePattern,
                        selfId = selfId,
                        frontProxyId = frontId,
                        landingProxyId = landingId,
                        countryFilter = countryPattern,
                        selectionMode = selectionMode,
                        tiers = tiersSnapshot,
                    )
                )
                val summaryText = formatPreviewSummary(res, type)
                onMainDispatcher {
                    lastTierCounts = res.tierCounts
                    tierPrefs.forEachIndexed { idx, pref ->
                        tiersList.getOrNull(idx)?.let { tier ->
                            pref.summary = formatTierSummary(tier, lastTierCounts.getOrNull(idx) ?: 0)
                        }
                    }
                    updateAddTierSummary()
                    matchSummaryPref?.summary = summaryText
                }
            }
        }
        refreshMatchSummaryFn = { ov -> refreshMatchSummary(ov) }

        fun formatNodeOrderSummary(): String {
            if (proxyList.isEmpty()) return getString(R.string.balancer_node_order_summary_empty)
            val previewNames = proxyList.take(3).joinToString(", ") { it.displayName() }
            val suffix = if (proxyList.size > 3) "..." else ""
            return getString(R.string.balancer_node_order_summary, proxyList.size, previewNames + suffix)
        }

        fun syncNodeListUi(
            type: Int = DataStore.balancerType,
            selectionMode: String = DataStore.balancerSelectionMode,
        ) {
            val isFallback = selectionMode == BalancerBean.SELECTION_FALLBACK
            localNodeListPref?.isVisible = shouldShowNodeList(type, selectionMode)
            if (isFallback) {
                localNodeListPref?.setTitle(R.string.balancer_fallback_order_title)
            } else {
                localNodeListPref?.setTitle(R.string.balancer_node_order_title)
            }
            localNodeListPref?.summary = formatNodeOrderSummary()
        }
        refreshNodeListFn = { syncNodeListUi() }

        localNodeListPref?.setOnPreferenceClickListener {
            val excludeIds = buildExcludeIds()
            editNodeOrderLauncher.launch(
                Intent(this@BalancerSettingsActivity, BalancerNodeOrderActivity::class.java).apply {
                    putExtra(BalancerNodeOrderActivity.EXTRA_NODE_IDS, proxyList.map { it.id }.toLongArray())
                    putExtra(BalancerNodeOrderActivity.EXTRA_TIERS_JSON, BalancerBean.tiersToJson(tiersList))
                    putExtra(BalancerNodeOrderActivity.EXTRA_EDITING_ID, DataStore.editingId)
                    if (excludeIds.isNotEmpty()) {
                        putExtra(BalancerNodeOrderActivity.EXTRA_EXCLUDE_IDS, excludeIds)
                    }
                },
            )
            true
        }

        fun updateTypeVisibility(type: Int) {
            val hasGroup = type == BalancerBean.TYPE_GROUP || type == BalancerBean.TYPE_MIXED
            val hasList = hasExplicitListType(type)
            groupPref?.isVisible = hasGroup
            nameIncludePref?.isVisible = hasGroup
            nameExcludePref?.isVisible = hasGroup
            explicitNodesPref?.isVisible = hasList
            syncNodeListUi(type, DataStore.balancerSelectionMode)
            refreshMatchSummary()
        }

        val typePref = findPreference<SimpleMenuPreference>("balancerType")
        typePref?.value = DataStore.balancerType.toString()
        typePref?.setOnPreferenceChangeListener { _, newValue ->
            val type = (newValue as? String)?.toIntOrNull() ?: BalancerBean.TYPE_MIXED
            DataStore.balancerType = type
            updateTypeVisibility(type)
            true
        }

        nameIncludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(R.string.balancer_name_include_all) else pref.text
        }
        nameExcludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(androidx.preference.R.string.not_set) else pref.text
        }
        countryPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(R.string.balancer_country_filter_any) else pref.text
        }

        nameIncludePref?.setOnPreferenceChangeListener { _, newValue ->
            refreshMatchSummary(PreviewOverrides(include = newValue as? String))
            true
        }
        nameExcludePref?.setOnPreferenceChangeListener { _, newValue ->
            refreshMatchSummary(PreviewOverrides(exclude = newValue as? String))
            true
        }
        countryPref?.setOnPreferenceChangeListener { _, newValue ->
            refreshMatchSummary(PreviewOverrides(country = newValue as? String))
            true
        }

        // Advanced collapsible section
        val catUseWhich = findPreference<PreferenceCategory>("balancerCatUseWhich")
        val catHealth = findPreference<PreferenceCategory>("balancerCatHealth")
        val catLoadBalance = findPreference<PreferenceCategory>("balancerCatLoadBalance")
        val catChain = findPreference<PreferenceCategory>("balancerCatChain")
        val advancedTogglePref = findPreference<Preference>("balancerAdvancedToggle")

        bindIntPref("balancerInterval", 300, 5, R.string.balancer_unit_seconds)
        bindIntPref("balancerTolerance", 30, 0, R.string.balancer_unit_ms)

        val balanceSwitchPref = findPreference<SwitchPreferenceCompat>("balancerBalanceEnabled")
        val balanceModePref = findPreference<SimpleMenuPreference>("balancerBalanceMode")
        val balanceIntervalPref = bindIntPref("balancerBalanceIntervalSec", 30, 1, R.string.balancer_unit_seconds)
        val selectionModePref = findPreference<SimpleMenuPreference>("balancerSelectionMode")
        val tolerancePref = findPreference<Preference>("balancerTolerance")
        val fallbackAutoRevertPref = findPreference<Preference>("balancerFallbackAutoRevert")
        val fallbackMaxFailsPref = findPreference<Preference>("balancerFallbackMaxFails")

        fun applyCategoryVisibility(balanceOn: Boolean) {
            catUseWhich?.isVisible = advancedExpanded
            catHealth?.isVisible = advancedExpanded
            catChain?.isVisible = advancedExpanded
            catLoadBalance?.isVisible = advancedExpanded && balanceOn
        }

        fun applyAdvancedExpanded(expanded: Boolean, balanceEnabledOverride: Boolean? = null) {
            advancedExpanded = expanded
            applyCategoryVisibility(balanceEnabledOverride ?: DataStore.balancerBalanceEnabled)
            advancedTogglePref?.setSummary(if (expanded) R.string.balancer_advanced_hide else R.string.balancer_advanced_show)
            advancedTogglePref?.setIcon(
                if (expanded) R.drawable.ic_baseline_keyboard_arrow_up_24 else R.drawable.ic_baseline_keyboard_arrow_down_24
            )
        }
        applyAdvancedExpanded(advancedExpanded)
        advancedTogglePref?.setOnPreferenceClickListener {
            applyAdvancedExpanded(!advancedExpanded)
            true
        }

        fun updateSelectionAndBalanceUi(
            selectionOverride: String? = null,
            balanceEnabledOverride: Boolean? = null,
            balanceModeOverride: String? = null,
        ) {
            val mode = selectionOverride ?: DataStore.balancerSelectionMode
            val isFallback = mode == BalancerBean.SELECTION_FALLBACK
            val isPriority = mode == BalancerBean.SELECTION_PRIORITY
            selectionModePref?.value = mode
            selectionModePref?.setSummary(
                when {
                    isFallback -> R.string.balancer_selection_fallback_sum
                    isPriority -> R.string.balancer_selection_priority_sum
                    else -> R.string.balancer_selection_latency_sum
                }
            )

            fallbackAutoRevertPref?.isVisible = isFallback
            fallbackMaxFailsPref?.isVisible = isFallback
            balanceSwitchPref?.isVisible = !isFallback

            tolerancePref?.setTitle(
                if (isFallback) R.string.balancer_fallback_timeout_title else R.string.balancer_tolerance_title
            )
            tolerancePref?.setSummary(
                if (isFallback) getString(R.string.balancer_fallback_timeout_summary) else null
            )

            rebuildTierPreferences()
            syncNodeListUi(DataStore.balancerType, mode)

            val balanceOn = if (isFallback) false else (balanceEnabledOverride ?: DataStore.balancerBalanceEnabled)
            val balMode = balanceModeOverride ?: DataStore.balancerBalanceMode
            balanceModePref?.value = balMode
            val balModeLabel = getString(
                if (balMode == BalancerBean.BALANCE_PER_CONNECTION) {
                    R.string.balancer_balance_mode_per_conn
                } else {
                    R.string.balancer_balance_mode_timed
                }
            )
            balanceSwitchPref?.summary = if (balanceOn) {
                getString(R.string.balancer_balance_enabled_on_sum, balModeLabel)
            } else {
                getString(R.string.balancer_balance_enabled_off_sum)
            }

            applyCategoryVisibility(balanceOn && !isFallback)
            balanceModePref?.isEnabled = balanceOn && !isFallback
            balanceIntervalPref?.isVisible = balanceOn && !isFallback && balMode != BalancerBean.BALANCE_PER_CONNECTION
        }

        selectionModePref?.setOnPreferenceChangeListener { _, newValue ->
            val mode = (newValue as? String) ?: BalancerBean.SELECTION_LATENCY
            DataStore.balancerSelectionMode = mode
            DataStore.balancerStrategy = mode
            updateSelectionAndBalanceUi(selectionOverride = mode)
            refreshMatchSummary(PreviewOverrides(selectionMode = mode))
            true
        }

        balanceSwitchPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            updateSelectionAndBalanceUi(balanceEnabledOverride = enabled)
            true
        }

        balanceModePref?.setOnPreferenceChangeListener { _, newValue ->
            val mode = (newValue as? String) ?: BalancerBean.BALANCE_TIMED_ROTATION
            updateSelectionAndBalanceUi(balanceModeOverride = mode)
            true
        }

        val testUrlPref = findPreference<EditTextPreference>("balancerTestUrl")
        testUrlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val custom = pref.text?.trim().orEmpty()
            if (custom.isBlank()) {
                getString(R.string.balancer_test_url_default, DataStore.connectionTestURL)
            } else {
                custom
            }
        }

        updateSelectionAndBalanceUi()
        updateTypeVisibility(DataStore.balancerType)
    }

    private fun PreferenceFragmentCompat.bindIntPref(
        key: String,
        defaultValue: Int,
        minValue: Int,
        unitResId: Int?,
        zeroAsUnlimited: Boolean = false,
        onValidChange: ((Int) -> Unit)? = null,
    ): EditTextPreference? {
        val pref = findPreference<EditTextPreference>(key) ?: return null
        pref.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { current ->
            val parsed = current.text?.trim()?.toIntOrNull()
            val effective = if (parsed != null && parsed >= minValue) parsed else defaultValue
            when {
                zeroAsUnlimited && effective <= 0 -> getString(R.string.balancer_unlimited)
                unitResId != null -> getString(unitResId, effective)
                else -> effective.toString()
            }
        }
        pref.setOnPreferenceChangeListener { _, newValue ->
            val parsed = (newValue as? String)?.trim()?.toIntOrNull()
            if (parsed == null || parsed < minValue) {
                false
            } else {
                onValidChange?.invoke(parsed)
                true
            }
        }
        return pref
    }

    private fun parseGroupIds(raw: String): List<Long> {
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
    }

    private fun shouldShowNodeList(
        type: Int = DataStore.balancerType,
        selectionMode: String = DataStore.balancerSelectionMode,
    ): Boolean =
        (selectionMode == BalancerBean.SELECTION_PRIORITY || selectionMode == BalancerBean.SELECTION_FALLBACK) &&
            hasExplicitListType(type) &&
            proxyList.isNotEmpty()

    private fun hasExplicitListType(type: Int): Boolean =
        type == BalancerBean.TYPE_LIST || type == BalancerBean.TYPE_MIXED

    private fun syncServerProtocol() {
        DataStore.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
    }

    private fun buildExcludeIds(): LongArray {
        val excludeList = mutableListOf<Long>()
        if (DataStore.editingId > 0L) excludeList.add(DataStore.editingId)
        if (DataStore.balancerFrontProxy > 0L) excludeList.add(DataStore.balancerFrontProxy)
        if (DataStore.balancerLandingProxy > 0L) excludeList.add(DataStore.balancerLandingProxy)
        return excludeList.toLongArray()
    }

    private fun formatPreviewSummary(
        res: BalancerMemberResolver.ResolutionResult,
        type: Int = DataStore.balancerType,
    ): String {
        val resId = when (type) {
            BalancerBean.TYPE_LIST -> R.string.balancer_preview_summary_list
            BalancerBean.TYPE_MIXED -> R.string.balancer_preview_summary_mixed
            else -> R.string.balancer_preview_summary
        }
        val base = if (res.matchedCandidateCount <= 0) {
            getString(R.string.balancer_match_summary_empty)
        } else {
            getString(
                resId,
                res.matchedCandidateCount,
                res.availableCount,
                res.totalCount,
            )
        }
        return if (res.hasRegexError) {
            getString(R.string.balancer_match_summary_regex_error, base)
        } else {
            base
        }
    }

    private fun launchMultiSelectNodes() {
        val excludeIds = buildExcludeIds()

        selectProfileForAdd.launch(
            Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                putExtra(
                    ProfileSelectActivity.EXTRA_SELECTED_IDS,
                    proxyList.map { it.id }.toLongArray(),
                )
                if (excludeIds.isNotEmpty()) {
                    putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeIds)
                }
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_EXPANDED, false) ?: false
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.balancer_settings_autoselect)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        runOnDefaultDispatcher {
            reloadExplicitNodes()
        }
    }

    private suspend fun reloadExplicitNodes() {
        val idList = DataStore.serverProtocol.split(",")
            .mapNotNull { it.takeIf { s -> s.isNotBlank() }?.toLongOrNull() }
        val loaded = ArrayList<ProxyEntity>()
        if (idList.isNotEmpty()) {
            val profiles = ProfileManager.getProfiles(idList).associateBy { it.id }
            for (id in idList) {
                profiles[id]?.let { loaded.add(it) }
            }
        }
        onMainDispatcher {
            proxyList.clear()
            proxyList.addAll(loaded)
            refreshNodeListFn?.invoke()
            refreshExplicitSummaryFn?.invoke()
            refreshMatchSummaryFn?.invoke(PreviewOverrides())
        }
    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false
        if (profile.id == DataStore.balancerFrontProxy || profile.id == DataStore.balancerLandingProxy) return false
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.balancerBean ?: return true
            if (bean.proxies.contains(DataStore.editingId)) return false
            if (bean.frontProxy == DataStore.editingId || bean.landingProxy == DataStore.editingId) return false
        }
        return true
    }

    val editNodeOrderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                val data = result.data ?: return@runOnDefaultDispatcher
                val resultIds = data.getLongArrayExtra(BalancerNodeOrderActivity.EXTRA_RESULT_NODE_IDS)
                    ?: return@runOnDefaultDispatcher
                val profileMap = if (resultIds.isNotEmpty()) {
                    ProfileManager.getProfiles(resultIds.toList()).associateBy { it.id }
                } else {
                    emptyMap()
                }
                val reordered = ArrayList<ProxyEntity>()
                val seen = HashSet<Long>()
                for (id in resultIds) {
                    if (seen.add(id)) {
                        profileMap[id]?.let { reordered.add(it) }
                    }
                }
                onMainDispatcher {
                    proxyList.clear()
                    proxyList.addAll(reordered)
                    syncServerProtocol()
                    DataStore.dirty = true
                    refreshNodeListFn?.invoke()
                    refreshExplicitSummaryFn?.invoke()
                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                }
            }
        }

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true
                val data = result.data ?: return@runOnDefaultDispatcher
                val multiIds = data.getLongArrayExtra(ProfileSelectActivity.EXTRA_PROFILE_IDS)
                if (multiIds != null) {
                    val profiles = ProfileManager.getProfiles(multiIds.toList()).associateBy { it.id }
                    var circularFound = false
                    val newProxyList = ArrayList<ProxyEntity>()
                    val seen = HashSet<Long>()
                    for (id in multiIds) {
                        if (seen.add(id)) {
                            val p = profiles[id] ?: continue
                            if (!testProfileAllowed(p)) {
                                circularFound = true
                                continue
                            }
                            newProxyList.add(p)
                        }
                    }
                    onMainDispatcher {
                        if (circularFound) {
                            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                .setTitle(R.string.circular_reference)
                                .setMessage(R.string.circular_reference_sum)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        proxyList.clear()
                        proxyList.addAll(newProxyList)
                        syncServerProtocol()
                        refreshNodeListFn?.invoke()
                        refreshExplicitSummaryFn?.invoke()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                    }
                } else {
                    val singleId = data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
                    if (singleId > 0L) {
                        val profile = ProfileManager.getProfile(singleId)
                        if (profile != null) {
                            if (!testProfileAllowed(profile)) {
                                onMainDispatcher {
                                    MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                        .setTitle(R.string.circular_reference)
                                        .setMessage(R.string.circular_reference_sum)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            } else {
                                onMainDispatcher {
                                    if (proxyList.none { it.id == profile.id }) {
                                        proxyList.add(profile)
                                        refreshNodeListFn?.invoke()
                                    }
                                    syncServerProtocol()
                                    refreshExplicitSummaryFn?.invoke()
                                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                                }
                            }
                        }
                    }
                }
            }
        }
}
