package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
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
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.AutoSelectorTexts
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_balancer_settings) {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profileId = it.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0) ?: 0L
            val profile = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
            DataStore.balancerFrontProxy = profile.id
            onMainDispatcher {
                if (::frontProxyPreference.isInitialized) {
                    frontProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
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
            onMainDispatcher {
                if (::landingProxyPreference.isInitialized) {
                    landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
            }
        }
    }

    override fun createEntity(): BalancerBean {
        val count = runCatching { SagerDatabase.proxyDao.getByType(ProxyEntity.TYPE_BALANCER).size }.getOrDefault(0) + 1
        val isAutoSelector = intent.getStringExtra("strategy") == BalancerBean.STRATEGY_AUTO_SELECTOR
        return BalancerBean().apply {
            if (isAutoSelector) {
                name = String.format("%s %02d", getString(R.string.autosel_title), count)
                strategy = BalancerBean.STRATEGY_AUTO_SELECTOR
                balancerType = BalancerBean.TYPE_GROUP
            } else {
                name = String.format("Balancer %02d", count)
            }
        }
    }

    val proxyList = ArrayList<ProxyEntity>()
    private var editingPinnedId: Long = -1L
    private var advancedExpanded: Boolean = false
    private var planJob: Job? = null
    private var onProxiesUpdatedCallback: (() -> Unit)? = null

    private fun parseGroupIdsFromStore(): List<Long> {
        val gids = DataStore.balancerTargetGroups.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .distinct()
        if (gids.isNotEmpty()) return gids
        val legacy = DataStore.balancerTargetGroup
        return if (legacy > 0L) listOf(legacy) else emptyList()
    }

    private fun readIntPref(key: String, fallback: Int): Int {
        return DataStore.profileCacheStore.getString(key, "")
            ?.trim()
            ?.toIntOrNull()
            ?: fallback
    }

    private fun currentSpecificNodeIds(): List<Long> {
        if (proxyList.isNotEmpty()) {
            return proxyList.map { it.id }.filter { it > 0L }.distinct()
        }
        return DataStore.serverProtocol.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .distinct()
    }

    private fun buildDraftBean(): BalancerBean {
        val gids = parseGroupIdsFromStore()
        return BalancerBean().apply {
            name = DataStore.profileName
            balancerType = DataStore.balancerType
            targetGroupIds = ArrayList(gids)
            targetGroupId = gids.firstOrNull() ?: 0L
            proxies = ArrayList(currentSpecificNodeIds())
            strategy = DataStore.balancerStrategy
            nameInclude = DataStore.balancerNameInclude
            nameExclude = if (strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) "" else DataStore.balancerNameExclude
            countryFilter = DataStore.balancerCountryFilter.trim()
            excludeUnavailable = DataStore.balancerExcludeUnavailable
            poolCap = DataStore.balancerPoolCap
            buildLimit = DataStore.balancerBuildLimit
            resultValidityMins = DataStore.balancerResultValidityMins
            connectivityUrl = DataStore.balancerConnectivityUrl.trim()
            interval = if (strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) {
                readIntPref("autoselCheckInterval", DataStore.balancerInterval)
            } else {
                DataStore.balancerInterval
            }
            benchIntervalSec = DataStore.balancerBenchIntervalSec
            watchIntervalSec = DataStore.balancerWatchIntervalSec
            activeSize = DataStore.balancerActiveSize
            sampling = DataStore.balancerSampling
            tolerance = if (strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) {
                readIntPref("autoselTolerance", DataStore.balancerTolerance)
            } else {
                DataStore.balancerTolerance
            }
            toleranceUnit = if (strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) "ms" else DataStore.balancerToleranceUnit
            maxRttMs = DataStore.balancerMaxRttMs
            expected = DataStore.balancerExpected
            dialRetries = DataStore.balancerDialRetries
            interruptOnSwitch = DataStore.balancerInterruptOnSwitch
            balance = DataStore.balancerBalance
            balanceMode = DataStore.balancerBalanceMode
            balanceIntervalSec = DataStore.balancerBalanceIntervalSec
            testUrl = if (strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) {
                DataStore.profileCacheStore.getString("autoselTestUrl", DataStore.balancerTestUrl).orEmpty().trim()
            } else {
                DataStore.balancerTestUrl
            }
            pinnedId = editingPinnedId
            normalizeAutoSelector()
        }
    }

    override suspend fun saveAndExit() {
        if (DataStore.balancerStrategy == BalancerBean.STRATEGY_AUTO_SELECTOR) {
            if (DataStore.profileName.isBlank()) {
                onMainDispatcher {
                    Toast.makeText(this@BalancerSettingsActivity, R.string.autosel_name_empty, Toast.LENGTH_SHORT).show()
                }
                return
            }
            if (parseGroupIdsFromStore().isEmpty() && currentSpecificNodeIds().isEmpty()) {
                onMainDispatcher {
                    Toast.makeText(this@BalancerSettingsActivity, R.string.autosel_select_group_or_node, Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
        super.saveAndExit()
    }

    override fun BalancerBean.init() {
        DataStore.profileName = name
        DataStore.balancerType = balancerType
        val gids = if (targetGroupIds != null && targetGroupIds.isNotEmpty()) {
            targetGroupIds
        } else if (targetGroupId > 0L) {
            listOf(targetGroupId)
        } else emptyList()
        DataStore.balancerTargetGroups = gids.joinToString(",")
        DataStore.balancerTargetGroup = gids.firstOrNull() ?: 0L
        DataStore.balancerStrategy = when (strategy) {
            "round_robin" -> BalancerBean.STRATEGY_ROUND_ROBIN
            "failover", "stable" -> BalancerBean.STRATEGY_LEAST_PING
            "consistent_hash", "consistentHash" -> BalancerBean.STRATEGY_CONSISTENT_HASH_CAMEL
            null, "" -> BalancerBean.STRATEGY_LEAST_PING
            else -> strategy
        }
        DataStore.balancerTestUrl = testUrl ?: ""
        DataStore.balancerInterval = interval.coerceAtLeast(10)
        val effectiveTolerance = if (DataStore.balancerStrategy == BalancerBean.STRATEGY_AUTO_SELECTOR) {
            calculateToleranceMs().toInt().coerceAtLeast(0)
        } else {
            tolerance.coerceAtLeast(0)
        }
        DataStore.balancerTolerance = effectiveTolerance
        DataStore.balancerToleranceUnit = if (DataStore.balancerStrategy == BalancerBean.STRATEGY_AUTO_SELECTOR) {
            "ms"
        } else if (toleranceUnit.isNullOrBlank()) {
            "ms"
        } else {
            toleranceUnit
        }
        DataStore.balancerUseFrontProxy = useFrontProxy
        DataStore.balancerUseLandingProxy = useLandingProxy
        DataStore.balancerFrontProxy = frontProxy
        DataStore.balancerFrontProxyTmp = if (frontProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerLandingProxy = landingProxy
        DataStore.balancerLandingProxyTmp = if (landingProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerNameExclude = nameExclude ?: ""
        DataStore.balancerNameInclude = nameInclude ?: ""
        DataStore.serverProtocol = proxies.joinToString(",")

        // v6 AutoSelector fields
        DataStore.balancerCountryFilter = countryFilter ?: ""
        DataStore.balancerExcludeUnavailable = excludeUnavailable
        DataStore.balancerPoolCap = poolCap.coerceIn(1, BalancerBean.MAX_POOL_CAP)
        DataStore.balancerBuildLimit = buildLimit.coerceIn(1, BalancerBean.MAX_BUILD_LIMIT)
        DataStore.balancerResultValidityMins = resultValidityMins.coerceAtLeast(0)
        DataStore.balancerConnectivityUrl = connectivityUrl ?: ""
        DataStore.balancerBenchIntervalSec = benchIntervalSec.coerceAtLeast(DataStore.balancerInterval)
        DataStore.balancerWatchIntervalSec = watchIntervalSec.coerceAtLeast(5)
        DataStore.balancerActiveSize = activeSize.coerceAtLeast(1)
        DataStore.balancerSampling = sampling.coerceIn(2, 60)
        DataStore.balancerMaxRttMs = maxRttMs.coerceAtLeast(0)
        DataStore.balancerExpected = expected.coerceAtLeast(1)
        DataStore.balancerDialRetries = dialRetries.coerceIn(0, 5)
        DataStore.balancerInterruptOnSwitch = interruptOnSwitch
        DataStore.balancerBalance = balance
        DataStore.balancerBalanceMode = if (balanceMode == "connection") "connection" else "rotate"
        DataStore.balancerBalanceIntervalSec = balanceIntervalSec.coerceAtLeast(5)
        editingPinnedId = pinnedId

        // Seed dedicated keys inside AutoSelector advanced categories so they never collide with legacy prefs
        DataStore.profileCacheStore.putString("autoselCheckInterval", DataStore.balancerInterval.toString())
        DataStore.profileCacheStore.putString("autoselTolerance", effectiveTolerance.toString())
        DataStore.profileCacheStore.putString("autoselTestUrl", testUrl ?: "")
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        balancerType = DataStore.balancerType
        val gids = parseGroupIdsFromStore()
        targetGroupIds = ArrayList(gids)
        targetGroupId = gids.firstOrNull() ?: 0L
        strategy = DataStore.balancerStrategy
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
        nameInclude = DataStore.balancerNameInclude
        nameExclude = if (strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) "" else DataStore.balancerNameExclude
        proxies = ArrayList(currentSpecificNodeIds())

        countryFilter = DataStore.balancerCountryFilter.trim()
        excludeUnavailable = DataStore.balancerExcludeUnavailable
        poolCap = DataStore.balancerPoolCap
        buildLimit = DataStore.balancerBuildLimit
        resultValidityMins = DataStore.balancerResultValidityMins
        connectivityUrl = DataStore.balancerConnectivityUrl.trim()
        benchIntervalSec = DataStore.balancerBenchIntervalSec
        watchIntervalSec = DataStore.balancerWatchIntervalSec
        activeSize = DataStore.balancerActiveSize
        sampling = DataStore.balancerSampling
        maxRttMs = DataStore.balancerMaxRttMs
        expected = DataStore.balancerExpected
        dialRetries = DataStore.balancerDialRetries
        interruptOnSwitch = DataStore.balancerInterruptOnSwitch
        balance = DataStore.balancerBalance
        balanceMode = DataStore.balancerBalanceMode
        balanceIntervalSec = DataStore.balancerBalanceIntervalSec
        pinnedId = editingPinnedId

        if (strategy == BalancerBean.STRATEGY_AUTO_SELECTOR) {
            interval = readIntPref("autoselCheckInterval", DataStore.balancerInterval)
            tolerance = readIntPref("autoselTolerance", DataStore.balancerTolerance)
            toleranceUnit = "ms"
            testUrl = DataStore.profileCacheStore.getString("autoselTestUrl", DataStore.balancerTestUrl).orEmpty().trim()
            balancerType = BalancerBean.TYPE_GROUP
        } else {
            testUrl = DataStore.balancerTestUrl
            interval = DataStore.balancerInterval
            tolerance = DataStore.balancerTolerance
            toleranceUnit = DataStore.balancerToleranceUnit
        }

        normalizeAutoSelector()
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.balancer_preferences)

        findPreference<OutboundPreference>("balancerFrontProxy")?.apply {
            frontProxyPreference = this
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerFrontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(
                            this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.balancerFrontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }

        findPreference<OutboundPreference>("balancerLandingProxy")?.apply {
            landingProxyPreference = this
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerLandingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(
                            this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.balancerLandingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupPref = findPreference<Preference>("balancerTargetGroup")
        val specificNodesPref = findPreference<Preference>("autoselSpecificNodes")
        val frontPref = findPreference<Preference>("balancerFrontProxy")
        val landingPref = findPreference<Preference>("balancerLandingProxy")
        val useFrontProxyPref = findPreference<Preference>("balancerUseFrontProxy")
        val useLandingProxyPref = findPreference<Preference>("balancerUseLandingProxy")
        val nameExcludePref = findPreference<EditTextPreference>("balancerNameExclude")
        val nameIncludePref = findPreference<EditTextPreference>("balancerNameInclude")
        val balancePref = findPreference<SwitchPreferenceCompat>("balancerBalance")
        val pinnedPref = findPreference<Preference>("autoselPinned")
        val useAutomaticPref = findPreference<Preference>("autoselUseAutomatic")
        val planPref = findPreference<Preference>("autoselPlan")
        val advancedPref = findPreference<Preference>("autoselAdvanced")

        val urlPref = findPreference<EditTextPreference>("balancerTestUrl")
        val intervalPref = findPreference<EditTextPreference>("balancerInterval")
        val tolerancePref = findPreference<EditTextPreference>("balancerTolerance")
        val toleranceUnitPref = findPreference<SimpleMenuPreference>("balancerToleranceUnit")
        val typePref = findPreference<SimpleMenuPreference>("balancerType")
        val strategyPref = findPreference<SimpleMenuPreference>("balancerStrategy")

        val catMembership = findPreference<PreferenceCategory>("autoselCatMembership")
        val catHealth = findPreference<PreferenceCategory>("autoselCatHealth")
        val catSwitching = findPreference<PreferenceCategory>("autoselCatSwitching")
        val catBalance = findPreference<PreferenceCategory>("autoselCatBalance")
        val catEndpoints = findPreference<PreferenceCategory>("autoselCatEndpoints")

        val countryFilterPref = findPreference<EditTextPreference>("balancerCountryFilter")
        val buildLimitPref = findPreference<EditTextPreference>("balancerBuildLimit")
        val poolCapPref = findPreference<EditTextPreference>("balancerPoolCap")
        val resultValidityPref = findPreference<EditTextPreference>("balancerResultValidityMins")
        val excludeUnavailablePref = findPreference<SwitchPreferenceCompat>("balancerExcludeUnavailable")

        val expectedPref = findPreference<EditTextPreference>("balancerExpected")
        val activeSizePref = findPreference<EditTextPreference>("balancerActiveSize")
        val autoselIntervalPref = findPreference<EditTextPreference>("autoselCheckInterval")
        val benchIntervalPref = findPreference<EditTextPreference>("balancerBenchIntervalSec")
        val watchIntervalPref = findPreference<EditTextPreference>("balancerWatchIntervalSec")
        val samplingPref = findPreference<EditTextPreference>("balancerSampling")

        val autoselTolerancePref = findPreference<EditTextPreference>("autoselTolerance")
        val maxRttPref = findPreference<EditTextPreference>("balancerMaxRttMs")
        val dialRetriesPref = findPreference<EditTextPreference>("balancerDialRetries")
        val interruptPref = findPreference<SwitchPreferenceCompat>("balancerInterruptOnSwitch")

        val balanceModePref = findPreference<SimpleMenuPreference>("balancerBalanceMode")
        val balanceIntervalPref = findPreference<EditTextPreference>("balancerBalanceIntervalSec")
        val balanceHintPref = findPreference<Preference>("autoselBalanceHint")

        val autoselTestUrlPref = findPreference<EditTextPreference>("autoselTestUrl")
        val connectivityUrlPref = findPreference<EditTextPreference>("balancerConnectivityUrl")

        val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())

        fun isAutoSelector(): Boolean = DataStore.balancerStrategy == BalancerBean.STRATEGY_AUTO_SELECTOR

        fun schedulePlanRefresh() {
            if (!isAutoSelector() || planPref == null) return
            planPref.setSummary(R.string.autosel_plan_calculating)
            planJob?.cancel()
            planJob = lifecycleScope.launch {
                delay(120L)
                val draft = withContext(Dispatchers.IO) { buildDraftBean() }
                val plan = withContext(Dispatchers.IO) {
                    AutoSelectorProfiles.buildPlan(draft, excludeProfileId = DataStore.editingId)
                }
                planPref.summary = AutoSelectorTexts.planSummary(
                    this@BalancerSettingsActivity,
                    plan,
                    draft.poolCap,
                )
            }
        }

        fun updateGroupSummary() {
            val selectedGids = parseGroupIdsFromStore()
            val groupMap = allGroups.associateBy { it.id }
            val parts = selectedGids.map { gid ->
                groupMap[gid]?.displayName() ?: getString(R.string.autosel_group_missing, gid)
            }
            groupPref?.summary = when {
                parts.isEmpty() -> {
                    if (isAutoSelector()) getString(R.string.autosel_groups_none)
                    else getString(androidx.preference.R.string.not_set)
                }
                parts.size <= 2 -> parts.joinToString(", ")
                else -> "${parts.size} 个分组: " + parts.take(2).joinToString(", ") + "..."
            }
        }

        fun updateSpecificNodesSummary() {
            if (specificNodesPref == null) return
            if (proxyList.isNotEmpty()) {
                val preview = proxyList.take(3).joinToString(", ") { it.displayName() } +
                    if (proxyList.size > 3) "..." else ""
                specificNodesPref.summary = getString(R.string.autosel_custom_nodes_summary, proxyList.size, preview)
                return
            }
            val pendingIds = currentSpecificNodeIds()
            if (pendingIds.isEmpty()) {
                specificNodesPref.setSummary(R.string.autosel_custom_nodes_summary_none)
            } else {
                val preview = pendingIds.take(3).joinToString(", ") { "#$it" } +
                    if (pendingIds.size > 3) "..." else ""
                specificNodesPref.summary = getString(R.string.autosel_custom_nodes_summary, pendingIds.size, preview)
            }
        }

        fun updatePinnedPrefs() {
            val showPin = isAutoSelector() && editingPinnedId > 0L
            pinnedPref?.isVisible = showPin
            useAutomaticPref?.isVisible = showPin
            if (showPin) {
                lifecycleScope.launch {
                    val name = withContext(Dispatchers.IO) {
                        ProfileManager.getProfile(editingPinnedId)?.displayName()
                    } ?: getString(R.string.autosel_pinned_missing)
                    pinnedPref?.summary = getString(R.string.autosel_pinned_summary, name)
                }
            }
        }

        fun updateBalanceCategoryState() {
            val enabled = DataStore.balancerBalance
            balanceModePref?.isEnabled = enabled
            balanceIntervalPref?.isEnabled = enabled
            val hintRes = when {
                !enabled -> R.string.autosel_balance_hint_off
                DataStore.balancerBalanceMode == "connection" -> R.string.autosel_balance_hint_connection
                else -> R.string.autosel_balance_hint_rotate
            }
            balanceHintPref?.setSummary(hintRes)
        }

        fun updateNameFilterTitleAndSummary() {
            if (isAutoSelector()) {
                nameIncludePref?.setTitle(R.string.autosel_name_filter)
                nameIncludePref?.setDialogMessage(R.string.autosel_name_filter_tip)
                groupPref?.setTitle(R.string.autosel_group)
            } else {
                nameIncludePref?.setTitle(R.string.balancer_name_include)
                nameIncludePref?.dialogMessage = null
                groupPref?.setTitle(R.string.balancer_select_group)
            }
        }

        fun updateAllVisibility() {
            val strategy = DataStore.balancerStrategy
            val isAuto = strategy == BalancerBean.STRATEGY_AUTO_SELECTOR
            val isGroup = DataStore.balancerType == BalancerBean.TYPE_GROUP
            val isLeastPing = !isAuto && strategy == BalancerBean.STRATEGY_LEAST_PING

            supportActionBar?.setTitle(if (isAuto) R.string.autosel_title else R.string.balancer_settings)
            updateNameFilterTitleAndSummary()

            typePref?.isVisible = !isAuto
            groupPref?.isVisible = isAuto || isGroup
            specificNodesPref?.isVisible = isAuto
            frontPref?.isVisible = !isAuto && isGroup
            landingPref?.isVisible = !isAuto && isGroup
            useFrontProxyPref?.isVisible = !isAuto && isGroup
            useLandingProxyPref?.isVisible = !isAuto && isGroup
            nameExcludePref?.isVisible = !isAuto && isGroup
            nameIncludePref?.isVisible = isAuto || isGroup

            balancePref?.isVisible = isAuto
            planPref?.isVisible = isAuto
            advancedPref?.isVisible = isAuto

            tolerancePref?.isVisible = isLeastPing
            toleranceUnitPref?.isVisible = isLeastPing
            urlPref?.isVisible = isLeastPing
            intervalPref?.isVisible = isLeastPing

            if (::configurationList.isInitialized) {
                configurationList.isVisible = !isAuto && !isGroup
            }
            if (::listCell.isInitialized) {
                listCell.isVisible = !isAuto && !isGroup
            }

            if (isAuto) {
                advancedPref?.setIcon(
                    if (advancedExpanded) R.drawable.ic_baseline_expand_less_24
                    else R.drawable.ic_baseline_expand_more_24
                )
                advancedPref?.setSummary(
                    if (advancedExpanded) R.string.autosel_advanced_hide
                    else R.string.autosel_advanced_show
                )
            }
            val showAdvanced = isAuto && advancedExpanded
            catMembership?.isVisible = showAdvanced
            catHealth?.isVisible = showAdvanced
            catSwitching?.isVisible = showAdvanced
            catBalance?.isVisible = showAdvanced
            catEndpoints?.isVisible = showAdvanced

            updateGroupSummary()
            updateSpecificNodesSummary()
            updatePinnedPrefs()
            updateBalanceCategoryState()
            if (isAuto) {
                schedulePlanRefresh()
            }
        }

        onProxiesUpdatedCallback = {
            updateSpecificNodesSummary()
            schedulePlanRefresh()
        }

        updateGroupSummary()

        groupPref?.setOnPreferenceClickListener {
            val currentSelected = parseGroupIdsFromStore().toMutableSet()
            val groupNames = allGroups.map { g ->
                val count = runCatching { SagerDatabase.proxyDao.getByGroup(g.id).size }.getOrDefault(0)
                "${g.displayName()} ($count)"
            }.toTypedArray()
            val checkedStates = allGroups.map { currentSelected.contains(it.id) }.toBooleanArray()

            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                .setTitle(if (isAutoSelector()) R.string.autosel_select_group_for_nodes else R.string.balancer_select_group)
                .setMultiChoiceItems(groupNames, checkedStates) { _, which, isChecked ->
                    checkedStates[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newGids = allGroups.filterIndexed { index, _ -> checkedStates[index] }.map { it.id }
                    DataStore.balancerTargetGroups = newGids.joinToString(",")
                    DataStore.balancerTargetGroup = newGids.firstOrNull() ?: 0L
                    DataStore.dirty = true
                    updateGroupSummary()
                    schedulePlanRefresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        fun launchSpecificNodesPicker() {
            replacing = 0
            val excludeList = mutableListOf<Long>()
            if (DataStore.editingId > 0L) excludeList.add(DataStore.editingId)
            selectProfileForAdd.launch(
                Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                    putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED_IDS, currentSpecificNodeIds().toLongArray())
                    if (excludeList.isNotEmpty()) {
                        putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeList.toLongArray())
                    }
                }
            )
        }

        specificNodesPref?.setOnPreferenceClickListener {
            if (proxyList.isEmpty() && currentSpecificNodeIds().isEmpty()) {
                launchSpecificNodesPicker()
            } else {
                val options = arrayOf(
                    getString(R.string.autosel_picker_title),
                    getString(R.string.autosel_custom_nodes_clear),
                )
                MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                    .setTitle(R.string.autosel_custom_nodes)
                    .setItems(options) { _, which ->
                        if (which == 0) {
                            launchSpecificNodesPicker()
                        } else {
                            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                .setTitle(R.string.autosel_custom_nodes_clear)
                                .setMessage(R.string.autosel_custom_nodes_clear_confirm)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    proxyList.clear()
                                    DataStore.serverProtocol = ""
                                    DataStore.dirty = true
                                    if (::configurationAdapter.isInitialized) {
                                        configurationAdapter.notifyDataSetChanged()
                                    }
                                    updateSpecificNodesSummary()
                                    schedulePlanRefresh()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                    .show()
            }
            true
        }

        typePref?.setOnPreferenceChangeListener { _, newValue ->
            val type = (newValue as? String)?.toIntOrNull() ?: 0
            DataStore.balancerType = type
            updateAllVisibility()
            true
        }

        urlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) {
                getString(androidx.preference.R.string.not_set)
            } else {
                pref.text
            }
        }

        intervalPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val text = pref.text?.trim()
            val value = text?.toIntOrNull()
            if (value != null && value > 0) {
                value.toString()
            } else {
                "300"
            }
        }

        fun updateToleranceSummary() {
            val text = tolerancePref?.text?.trim()
            val unit = DataStore.balancerToleranceUnit
            val value = text?.toIntOrNull()
            tolerancePref?.summary = when {
                value != null && value >= 0 -> "$value $unit"
                unit == "s" -> "1 s"
                else -> "300 ms"
            }
        }
        updateToleranceSummary()

        strategyPref?.value = DataStore.balancerStrategy
        strategyPref?.setOnPreferenceChangeListener { _, newValue ->
            val strategy = (newValue as? String) ?: BalancerBean.STRATEGY_LEAST_PING
            DataStore.balancerStrategy = strategy
            updateAllVisibility()
            true
        }

        tolerancePref?.setOnPreferenceChangeListener { _, newValue ->
            val str = (newValue as? String)?.trim().orEmpty()
            val v = str.toIntOrNull()
            if (v != null && v >= 0) {
                DataStore.balancerTolerance = v
                tolerancePref.text = v.toString()
                DataStore.profileCacheStore.putString("autoselTolerance", v.toString())
                autoselTolerancePref?.text = v.toString()
                updateToleranceSummary()
            }
            true
        }

        toleranceUnitPref?.setOnPreferenceChangeListener { _, newValue ->
            val unit = (newValue as? String) ?: "ms"
            DataStore.balancerToleranceUnit = unit
            updateToleranceSummary()
            true
        }

        nameExcludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(androidx.preference.R.string.not_set) else pref.text
        }
        nameIncludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val txt = pref.text
            when {
                !txt.isNullOrBlank() -> txt
                isAutoSelector() -> getString(R.string.autosel_name_filter_empty)
                else -> getString(androidx.preference.R.string.not_set)
            }
        }
        nameIncludePref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.balancerNameInclude = (newValue as? String).orEmpty()
            schedulePlanRefresh()
            true
        }

        useAutomaticPref?.setOnPreferenceClickListener {
            editingPinnedId = -1L
            DataStore.dirty = true
            updatePinnedPrefs()
            schedulePlanRefresh()
            true
        }

        planPref?.setOnPreferenceClickListener {
            schedulePlanRefresh()
            true
        }

        advancedPref?.setOnPreferenceClickListener {
            advancedExpanded = !advancedExpanded
            updateAllVisibility()
            true
        }

        balancePref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.balancerBalance = (newValue as? Boolean) ?: false
            updateBalanceCategoryState()
            true
        }

        countryFilterPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val txt = pref.text?.trim()
            if (txt.isNullOrEmpty()) getString(R.string.autosel_country_filter_empty) else txt
        }
        countryFilterPref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.balancerCountryFilter = (newValue as? String).orEmpty().trim()
            schedulePlanRefresh()
            true
        }

        excludeUnavailablePref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.balancerExcludeUnavailable = (newValue as? Boolean) ?: true
            schedulePlanRefresh()
            true
        }

        fun bindNumericPref(
            pref: EditTextPreference?,
            minVal: Int,
            maxVal: Int,
            defaultVal: Int,
            formatter: (Int) -> String,
            afterChange: ((Int) -> Unit)? = null,
        ) {
            if (pref == null) return
            pref.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text?.length ?: 0)
            }
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                val v = p.text?.trim()?.toIntOrNull()?.coerceIn(minVal, maxVal) ?: defaultVal
                formatter(v)
            }
            pref.setOnPreferenceChangeListener { _, newValue ->
                val parsed = (newValue as? String)?.trim()?.toIntOrNull()
                val clamped = (parsed ?: defaultVal).coerceIn(minVal, maxVal)
                val normalized = clamped.toString()
                pref.text = normalized
                DataStore.profileCacheStore.putString(pref.key, normalized)
                afterChange?.invoke(clamped)
                false
            }
        }

        bindNumericPref(
            buildLimitPref, 1, BalancerBean.MAX_BUILD_LIMIT, 300,
            formatter = { resources.getQuantityString(R.plurals.autosel_profiles, it, it) },
            afterChange = { schedulePlanRefresh() },
        )
        bindNumericPref(
            poolCapPref, 1, BalancerBean.MAX_POOL_CAP, 1000,
            formatter = { resources.getQuantityString(R.plurals.autosel_profiles, it, it) },
            afterChange = { schedulePlanRefresh() },
        )
        bindNumericPref(
            resultValidityPref, 0, 1_000_000, 1440,
            formatter = {
                if (it <= 0) getString(R.string.autosel_always_retest)
                else resources.getQuantityString(R.plurals.autosel_minutes, it, it)
            },
            afterChange = { schedulePlanRefresh() },
        )
        bindNumericPref(
            expectedPref, 1, BalancerBean.MAX_BUILD_LIMIT, 3,
            formatter = { resources.getQuantityString(R.plurals.autosel_profiles, it, it) },
        )
        bindNumericPref(
            activeSizePref, 1, BalancerBean.MAX_BUILD_LIMIT, 8,
            formatter = { resources.getQuantityString(R.plurals.autosel_profiles, it, it) },
        )
        bindNumericPref(
            autoselIntervalPref, 10, 86_400, 300,
            formatter = { resources.getQuantityString(R.plurals.autosel_seconds, it, it) },
            afterChange = { v ->
                DataStore.balancerInterval = v
                intervalPref?.text = v.toString()
            },
        )
        bindNumericPref(
            benchIntervalPref, 10, 86_400, 600,
            formatter = { resources.getQuantityString(R.plurals.autosel_seconds, it, it) },
        )
        bindNumericPref(
            watchIntervalPref, 5, 3600, 15,
            formatter = { resources.getQuantityString(R.plurals.autosel_seconds, it, it) },
        )
        bindNumericPref(
            samplingPref, 2, 60, 10,
            formatter = { it.toString() },
        )
        bindNumericPref(
            autoselTolerancePref, 0, 60_000, 50,
            formatter = { resources.getQuantityString(R.plurals.autosel_milliseconds, it, it) },
            afterChange = { v ->
                DataStore.balancerTolerance = v
                DataStore.balancerToleranceUnit = "ms"
                tolerancePref?.text = v.toString()
                toleranceUnitPref?.value = "ms"
                updateToleranceSummary()
            },
        )
        bindNumericPref(
            maxRttPref, 0, 60_000, 0,
            formatter = {
                if (it <= 0) getString(R.string.autosel_no_limit)
                else resources.getQuantityString(R.plurals.autosel_milliseconds, it, it)
            },
            afterChange = { schedulePlanRefresh() },
        )
        bindNumericPref(
            dialRetriesPref, 0, 5, 2,
            formatter = { it.toString() },
        )
        bindNumericPref(
            balanceIntervalPref, 5, 86_400, 30,
            formatter = { resources.getQuantityString(R.plurals.autosel_seconds, it, it) },
        )

        balanceModePref?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.balancerBalanceMode = (newValue as? String) ?: "rotate"
            updateBalanceCategoryState()
            true
        }

        autoselTestUrlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val txt = pref.text?.trim()
            if (txt.isNullOrEmpty()) getString(R.string.autosel_test_url_empty, DataStore.connectionTestURL)
            else txt
        }
        autoselTestUrlPref?.setOnPreferenceChangeListener { _, newValue ->
            val v = (newValue as? String).orEmpty().trim()
            DataStore.balancerTestUrl = v
            urlPref?.text = v
            true
        }

        connectivityUrlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val txt = pref.text?.trim()
            if (txt.isNullOrEmpty()) getString(R.string.autosel_connectivity_url_none) else txt
        }

        updateAllVisibility()
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager
    lateinit var listCell: View

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        advancedExpanded = savedInstanceState?.getBoolean("autosel_advanced") ?: false
        super.onCreate(savedInstanceState)

        val isAuto = DataStore.balancerStrategy == BalancerBean.STRATEGY_AUTO_SELECTOR
        supportActionBar?.setTitle(if (isAuto) R.string.autosel_title else R.string.balancer_settings)
        configurationList = findViewById(R.id.configuration_list)
        listCell = findViewById(R.id.list_cell)
        val isGroup = DataStore.balancerType == BalancerBean.TYPE_GROUP
        configurationList.isVisible = !isAuto && !isGroup
        listCell.isVisible = !isAuto && !isGroup
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
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("autosel_advanced", advancedExpanded)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        val isAuto = DataStore.balancerStrategy == BalancerBean.STRATEGY_AUTO_SELECTOR
        val isGroup = DataStore.balancerType == BalancerBean.TYPE_GROUP
        supportActionBar?.setTitle(if (isAuto) R.string.autosel_title else R.string.balancer_settings)
        if (::configurationList.isInitialized) {
            configurationList.isVisible = !isAuto && !isGroup
        }
        if (::listCell.isInitialized) {
            listCell.isVisible = !isAuto && !isGroup
        }
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
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLongOrNull() }
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
                onProxiesUpdatedCallback?.invoke()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
            DataStore.dirty = true
            onProxiesUpdatedCallback?.invoke()
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
            DataStore.dirty = true
            onProxiesUpdatedCallback?.invoke()
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
                        DataStore.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
                        configurationAdapter.notifyDataSetChanged()
                        onProxiesUpdatedCallback?.invoke()
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
                                    } else {
                                        if (proxyList.none { it.id == profile.id }) {
                                            proxyList.add(profile)
                                            configurationAdapter.notifyItemInserted(proxyList.size)
                                        }
                                    }
                                    DataStore.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
                                    onProxiesUpdatedCallback?.invoke()
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
                replacing = 0
                val excludeList = mutableListOf<Long>()
                if (DataStore.editingId > 0L) excludeList.add(DataStore.editingId)
                if (DataStore.balancerFrontProxy > 0L) excludeList.add(DataStore.balancerFrontProxy)
                if (DataStore.balancerLandingProxy > 0L) excludeList.add(DataStore.balancerLandingProxy)

                selectProfileForAdd.launch(
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    ).apply {
                        putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                        putExtra(
                            ProfileSelectActivity.EXTRA_SELECTED_IDS,
                            proxyList.map { it.id }.toLongArray()
                        )
                        if (excludeList.isNotEmpty()) {
                            putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeList.toLongArray())
                        }
                    }
                )
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
            } else null
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
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    )
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
