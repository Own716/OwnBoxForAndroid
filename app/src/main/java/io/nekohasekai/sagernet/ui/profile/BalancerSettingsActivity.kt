package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.BalancerMemberResolver
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.OutboundPreference
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import java.util.Locale

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_balancer_settings) {

    companion object {
        private const val STATE_ADVANCED_EXPANDED = "balancer_advanced_expanded"
    }

    private data class PreviewOverrides(
        val country: String? = null,
        val exclude: String? = null,
        val include: String? = null,
        val selectionMode: String? = null,
        val runTopN: Int? = null,
        val rankTopN: Int? = null,
        val resultTrustMinutes: Int? = null,
        val skipFailedWithinTrust: Boolean? = null,
    )

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference
    private var refreshMatchSummaryFn: ((PreviewOverrides) -> Unit)? = null
    private var refreshExplicitSummaryFn: (() -> Unit)? = null
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

        val normalizedSelectionMode = if (isPriorityMode()) {
            BalancerBean.SELECTION_PRIORITY
        } else {
            BalancerBean.SELECTION_LATENCY
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
        DataStore.balancerRunTopN = runTopN
        DataStore.balancerRankTopN = rankTopN
        DataStore.balancerResultTrustMinutes = resultTrustMinutes
        DataStore.balancerSkipFailedWithinTrust = skipFailedWithinTrust

        DataStore.balancerStandbySize = standbySize
        DataStore.balancerIntensiveCheckCount = intensiveCheckCount
        DataStore.balancerInterval = interval
        DataStore.balancerFullScanIntervalSec = fullScanIntervalSec
        DataStore.balancerWatchIntervalSec = watchIntervalSec
        DataStore.balancerSampleCount = sampleCount

        DataStore.balancerTolerance = tolerance
        DataStore.balancerToleranceUnit = if (toleranceUnit.isNullOrBlank()) "ms" else toleranceUnit
        DataStore.balancerMaxRttMs = maxRttMs
        DataStore.balancerDialRetries = dialRetries
        DataStore.balancerInterruptOnSwitch = interruptOnSwitch
        DataStore.balancerFailbackChecks = failbackChecks
        DataStore.balancerFailbackDelaySec = failbackDelaySec
        DataStore.balancerInterruptOnFailback = interruptOnFailback
        DataStore.balancerLearnFromConnections = learnFromConnections
        DataStore.balancerSiteStickyMinutes = siteStickyMinutes

        DataStore.balancerTestUrl = testUrl ?: ""
        DataStore.balancerConnectivityUrl = connectivityUrl ?: ""
        DataStore.balancerUseFrontProxy = useFrontProxy
        DataStore.balancerUseLandingProxy = useLandingProxy
        DataStore.balancerFrontProxy = frontProxy
        DataStore.balancerFrontProxyTmp = if (frontProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerLandingProxy = landingProxy
        DataStore.balancerLandingProxyTmp = if (landingProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerNameExclude = nameExclude ?: ""
        DataStore.balancerNameInclude = nameInclude ?: ""
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        balancerType = DataStore.balancerType
        val gids = parseGroupIds(DataStore.balancerTargetGroups)
        targetGroupIds = ArrayList(gids)
        targetGroupId = gids.firstOrNull() ?: 0L

        selectionMode = if (DataStore.balancerSelectionMode == BalancerBean.SELECTION_PRIORITY) {
            BalancerBean.SELECTION_PRIORITY
        } else {
            BalancerBean.SELECTION_LATENCY
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
        runTopN = DataStore.balancerRunTopN
        rankTopN = DataStore.balancerRankTopN
        resultTrustMinutes = DataStore.balancerResultTrustMinutes
        skipFailedWithinTrust = DataStore.balancerSkipFailedWithinTrust

        standbySize = DataStore.balancerStandbySize
        intensiveCheckCount = DataStore.balancerIntensiveCheckCount
        interval = DataStore.balancerInterval
        fullScanIntervalSec = DataStore.balancerFullScanIntervalSec
        watchIntervalSec = DataStore.balancerWatchIntervalSec
        sampleCount = DataStore.balancerSampleCount

        tolerance = DataStore.balancerTolerance
        toleranceUnit = DataStore.balancerToleranceUnit
        maxRttMs = DataStore.balancerMaxRttMs
        dialRetries = DataStore.balancerDialRetries
        interruptOnSwitch = DataStore.balancerInterruptOnSwitch
        failbackChecks = DataStore.balancerFailbackChecks
        failbackDelaySec = DataStore.balancerFailbackDelaySec
        interruptOnFailback = DataStore.balancerInterruptOnFailback
        learnFromConnections = DataStore.balancerLearnFromConnections
        siteStickyMinutes = DataStore.balancerSiteStickyMinutes

        testUrl = DataStore.balancerTestUrl.trim()
        connectivityUrl = DataStore.balancerConnectivityUrl.trim()
        useFrontProxy = DataStore.balancerUseFrontProxy
        useLandingProxy = DataStore.balancerUseLandingProxy
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
        val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())

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

        val useFrontProxyPref = findPreference<Preference>("balancerUseFrontProxy")
        val useLandingProxyPref = findPreference<Preference>("balancerUseLandingProxy")
        val nameIncludePref = findPreference<EditTextPreference>("balancerNameInclude")
        val nameExcludePref = findPreference<EditTextPreference>("balancerNameExclude")
        val countryPref = findPreference<EditTextPreference>("balancerCountryFilter")
        val matchSummaryPref = findPreference<Preference>("balancerMatchSummary")

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
            val runTopN = overrides.runTopN ?: DataStore.balancerRunTopN
            val rankTopN = overrides.rankTopN ?: DataStore.balancerRankTopN
            val trustMin = overrides.resultTrustMinutes ?: DataStore.balancerResultTrustMinutes
            val skipFailed = overrides.skipFailedWithinTrust ?: DataStore.balancerSkipFailedWithinTrust
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
                        runTopN = runTopN,
                        rankTopN = rankTopN,
                        resultTrustMinutes = trustMin,
                        skipFailedWithinTrust = skipFailed,
                    )
                )
                val summaryText = formatPreviewSummary(res)
                onMainDispatcher {
                    matchSummaryPref?.summary = summaryText
                }
            }
        }
        refreshMatchSummaryFn = { ov -> refreshMatchSummary(ov) }

        fun updateTypeVisibility(type: Int) {
            val hasGroup = type == BalancerBean.TYPE_GROUP || type == BalancerBean.TYPE_MIXED
            val hasList = type == BalancerBean.TYPE_LIST || type == BalancerBean.TYPE_MIXED
            groupPref?.isVisible = hasGroup
            nameIncludePref?.isVisible = hasGroup
            nameExcludePref?.isVisible = hasGroup
            explicitNodesPref?.isVisible = hasList
            useFrontProxyPref?.isVisible = hasGroup
            useLandingProxyPref?.isVisible = hasGroup
            if (::configurationList.isInitialized) {
                configurationList.isVisible = hasList
            }
            if (::listCell.isInitialized) {
                listCell.isVisible = hasList
            }
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
        val advancedCategories = listOfNotNull(
            findPreference<PreferenceCategory>("balancerCatUseWhich"),
            findPreference<PreferenceCategory>("balancerCatHealth"),
            findPreference<PreferenceCategory>("balancerCatSwitching"),
            findPreference<PreferenceCategory>("balancerCatLoadBalance"),
            findPreference<PreferenceCategory>("balancerCatEndpoints"),
            findPreference<PreferenceCategory>("balancerCatChain"),
        )
        val advancedTogglePref = findPreference<Preference>("balancerAdvancedToggle")

        fun applyAdvancedExpanded(expanded: Boolean) {
            advancedExpanded = expanded
            advancedCategories.forEach { it.isVisible = expanded }
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

        // Numeric advanced fields (bound cleanly via one shared helper)
        bindIntPref("balancerRunTopN", 300, 0, R.string.balancer_unit_profiles, zeroAsUnlimited = true) { v ->
            refreshMatchSummary(PreviewOverrides(runTopN = v))
        }
        bindIntPref("balancerRankTopN", 1000, 0, R.string.balancer_unit_profiles, zeroAsUnlimited = true) { v ->
            refreshMatchSummary(PreviewOverrides(rankTopN = v))
        }
        bindIntPref("balancerResultTrustMinutes", 1440, 0, R.string.balancer_unit_minutes) { v ->
            refreshMatchSummary(PreviewOverrides(resultTrustMinutes = v))
        }
        findPreference<SwitchPreferenceCompat>("balancerSkipFailedWithinTrust")?.setOnPreferenceChangeListener { _, newValue ->
            refreshMatchSummary(PreviewOverrides(skipFailedWithinTrust = newValue as? Boolean ?: false))
            true
        }

        bindIntPref("balancerStandbySize", 3, 0, R.string.balancer_unit_profiles)
        bindIntPref("balancerIntensiveCheckCount", 8, 1, R.string.balancer_unit_profiles)
        bindIntPref("balancerInterval", 300, 5, R.string.balancer_unit_seconds)
        bindIntPref("balancerFullScanIntervalSec", 600, 10, R.string.balancer_unit_seconds)
        bindIntPref("balancerWatchIntervalSec", 15, 0, R.string.balancer_unit_seconds)
        bindIntPref("balancerSampleCount", 10, 1, null)

        val tolerancePref = findPreference<EditTextPreference>("balancerTolerance")
        val toleranceUnitPref = findPreference<SimpleMenuPreference>("balancerToleranceUnit")
        tolerancePref?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        fun updateToleranceSummary(unitOverride: String? = null) {
            val text = tolerancePref?.text?.trim()
            val unit = unitOverride ?: DataStore.balancerToleranceUnit
            val value = text?.toIntOrNull()
            tolerancePref?.summary = when {
                value != null && value >= 0 -> "$value $unit"
                unit == "s" -> "1 s"
                else -> "30 ms"
            }
        }
        tolerancePref?.setOnPreferenceChangeListener { _, newValue ->
            val parsed = (newValue as? String)?.trim()?.toIntOrNull()
            if (parsed != null && parsed >= 0) {
                DataStore.balancerTolerance = parsed
                tolerancePref.text = parsed.toString()
                updateToleranceSummary()
                true
            } else {
                false
            }
        }
        toleranceUnitPref?.value = DataStore.balancerToleranceUnit
        toleranceUnitPref?.setOnPreferenceChangeListener { _, newValue ->
            val unit = (newValue as? String) ?: "ms"
            DataStore.balancerToleranceUnit = unit
            updateToleranceSummary(unit)
            true
        }
        updateToleranceSummary()

        bindIntPref("balancerMaxRttMs", 0, 0, R.string.balancer_unit_ms, zeroAsUnlimited = true)
        bindIntPref("balancerDialRetries", 2, 0, null)

        val failbackChecksPref = bindIntPref("balancerFailbackChecks", 3, 1, R.string.balancer_unit_checks)
        val failbackDelayPref = bindIntPref("balancerFailbackDelaySec", 60, 0, R.string.balancer_unit_seconds)
        val interruptFailbackPref = findPreference<SwitchPreferenceCompat>("balancerInterruptOnFailback")
        val learnPref = findPreference<SwitchPreferenceCompat>("balancerLearnFromConnections")
        val siteStickyPref = bindIntPref("balancerSiteStickyMinutes", 60, 1, R.string.balancer_unit_minutes)

        val balanceSwitchPref = findPreference<SwitchPreferenceCompat>("balancerBalanceEnabled")
        val balanceModePref = findPreference<SimpleMenuPreference>("balancerBalanceMode")
        val balanceIntervalPref = bindIntPref("balancerBalanceIntervalSec", 30, 1, R.string.balancer_unit_seconds)
        val balanceHintPref = findPreference<Preference>("balancerBalanceHint")
        val selectionModePref = findPreference<SimpleMenuPreference>("balancerSelectionMode")

        fun updateSelectionAndBalanceUi(
            selectionOverride: String? = null,
            balanceEnabledOverride: Boolean? = null,
            balanceModeOverride: String? = null,
            learnOverride: Boolean? = null,
        ) {
            val mode = selectionOverride ?: DataStore.balancerSelectionMode
            val isPriority = mode == BalancerBean.SELECTION_PRIORITY
            selectionModePref?.value = mode
            selectionModePref?.setSummary(
                if (isPriority) R.string.balancer_selection_priority_sum else R.string.balancer_selection_latency_sum
            )

            failbackChecksPref?.isEnabled = isPriority
            failbackDelayPref?.isEnabled = isPriority
            interruptFailbackPref?.isEnabled = isPriority

            val learnOn = learnOverride ?: DataStore.balancerLearnFromConnections
            siteStickyPref?.isEnabled = learnOn

            val balanceOn = balanceEnabledOverride ?: DataStore.balancerBalanceEnabled
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

            balanceModePref?.isEnabled = balanceOn
            balanceIntervalPref?.isEnabled = balanceOn && balMode != BalancerBean.BALANCE_PER_CONNECTION
            balanceHintPref?.setSummary(if (balanceOn) R.string.balancer_balance_hint_on else R.string.balancer_balance_hint_off)
        }

        selectionModePref?.setOnPreferenceChangeListener { _, newValue ->
            val mode = (newValue as? String) ?: BalancerBean.SELECTION_LATENCY
            DataStore.balancerSelectionMode = mode
            DataStore.balancerStrategy = mode
            updateSelectionAndBalanceUi(selectionOverride = mode)
            refreshMatchSummary(PreviewOverrides(selectionMode = mode))
            true
        }

        learnPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: true
            updateSelectionAndBalanceUi(learnOverride = enabled)
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

        val connectivityUrlPref = findPreference<EditTextPreference>("balancerConnectivityUrl")
        connectivityUrlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val custom = pref.text?.trim().orEmpty()
            if (custom.isBlank()) {
                getString(R.string.balancer_connectivity_url_unset)
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

    private fun formatPreviewSummary(res: BalancerMemberResolver.ResolutionResult): String {
        val base = if (res.matchedCandidateCount <= 0) {
            getString(R.string.balancer_match_summary_empty)
        } else {
            getString(
                R.string.balancer_preview_summary,
                res.matchedCandidateCount,
                res.availableCount,
                res.totalCount,
                res.reusableRecentCount,
            )
        }
        return if (res.hasRegexError) {
            getString(R.string.balancer_match_summary_regex_error, base)
        } else {
            base
        }
    }

    private fun launchMultiSelectNodes() {
        replacing = 0
        val excludeList = mutableListOf<Long>()
        if (DataStore.editingId > 0L) excludeList.add(DataStore.editingId)
        if (DataStore.balancerFrontProxy > 0L) excludeList.add(DataStore.balancerFrontProxy)
        if (DataStore.balancerLandingProxy > 0L) excludeList.add(DataStore.balancerLandingProxy)

        selectProfileForAdd.launch(
            Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                putExtra(
                    ProfileSelectActivity.EXTRA_SELECTED_IDS,
                    proxyList.map { it.id }.toLongArray(),
                )
                if (excludeList.isNotEmpty()) {
                    putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeList.toLongArray())
                }
            }
        )
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager
    lateinit var listCell: View

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_EXPANDED, false) ?: false
        super.onCreate(savedInstanceState)

        supportActionBar?.setTitle(R.string.balancer_settings_autoselect)
        configurationList = findViewById(R.id.configuration_list)
        listCell = findViewById(R.id.list_cell)
        val hasList = DataStore.balancerType == BalancerBean.TYPE_LIST || DataStore.balancerType == BalancerBean.TYPE_MIXED
        configurationList.isVisible = hasList
        listCell.isVisible = hasList
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter
        configurationList.isNestedScrollingEnabled = false

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }

        findViewById<NestedScrollView>(R.id.nested_scroll_view)?.apply {
            clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val navBars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemBars()
                )
                v.updatePadding(bottom = navBars.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(this)
        }

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else {
                0
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else {
                0
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) {
                    false
                } else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition,
                        target.bindingAdapterPosition,
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }
        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view)?.apply {
            isNestedScrollingEnabled = false
            (layoutParams ?: ViewGroup.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        if (::configurationAdapter.isInitialized) {
            runOnDefaultDispatcher {
                configurationAdapter.reload()
            }
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
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
                notifyDataSetChanged()
                refreshExplicitSummaryFn?.invoke()
                refreshMatchSummaryFn?.invoke(PreviewOverrides())
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
            refreshExplicitSummaryFn?.invoke()
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
            refreshExplicitSummaryFn?.invoke()
            refreshMatchSummaryFn?.invoke(PreviewOverrides())
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
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

    var replacing = 0

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
                        configurationAdapter.notifyDataSetChanged()
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
                                    if (replacing != 0 && replacing <= proxyList.size) {
                                        proxyList[replacing - 1] = profile
                                        configurationAdapter.notifyItemChanged(replacing)
                                    } else if (proxyList.none { it.id == profile.id }) {
                                        proxyList.add(profile)
                                        configurationAdapter.notifyItemInserted(proxyList.size)
                                    }
                                    refreshExplicitSummaryFn?.invoke()
                                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                                }
                            }
                        }
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                launchMultiSelectNodes()
            }
        }
    }

    inner class ProfileHolder(val binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(proxyEntity: ProxyEntity) {
            val profile = proxyEntity.requireBean()

            binding.profileName.text = profile.displayName()
            binding.profileType.text = proxyEntity.displayType()
            binding.profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val serverAddress = profile.displayAddress().trim()
            val group = if (proxyEntity.groupId > 0L) {
                runCatching { SagerDatabase.groupDao.getById(proxyEntity.groupId) }.getOrNull()
            } else {
                null
            }
            val groupName = group?.takeIf { !it.ungrouped }?.name?.trim()?.takeIf {
                it.isNotBlank() && it != "null" && it != "[]"
            }

            val detailText = if (groupName != null) {
                if (serverAddress.isNotBlank()) "$serverAddress      [$groupName]" else "[$groupName]"
            } else {
                serverAddress
            }

            binding.profileAddress.text = detailText
            binding.profileAddress.isSelected = true
            (binding.profileAddress.parent as View).isVisible = detailText.isNotBlank()
            binding.trafficText.text = ""
            binding.trafficText.isGone = true

            binding.edit.setImageResource(R.drawable.ic_image_edit)
            binding.edit.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(
                    Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java)
                )
            }
            binding.remove.isVisible = true
            binding.remove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos > 0 && pos <= proxyList.size) {
                    configurationAdapter.remove(pos)
                }
            }
            binding.share.isVisible = false
        }
    }
}
