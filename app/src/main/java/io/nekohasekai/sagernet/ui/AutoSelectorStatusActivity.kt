package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorClient
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorHealthStore
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorMemberStatus
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAutoSelectorMemberBinding
import io.nekohasekai.sagernet.databinding.LayoutAutoSelectorStatusBinding
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.profile.BalancerSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live & offline statistics and manual pin/recheck screen for an AutoSelector strategy group
 * (matching Screenshot 3: "自动选择统计").
 */
class AutoSelectorStatusActivity : ThemedActivity(), SagerConnection.Callback, ProfileManager.Listener {

    companion object {
        const val EXTRA_SELECTOR_ID = "selector_id"

        fun intent(context: Context, selectorId: Long = 0L): Intent {
            return Intent(context, AutoSelectorStatusActivity::class.java)
                .putExtra(EXTRA_SELECTOR_ID, selectorId)
        }
    }

    data class MemberRow(
        val member: AutoSelectorMemberStatus,
        val displayName: String,
        val exists: Boolean,
        val testedAtSec: Long,
        val lastTestError: String?,
        val pinned: Boolean,
        val selected: Boolean,
        val running: Boolean,
    )

    private data class SnapshotBundle(
        val targetSelectorId: Long,
        val selectorName: String,
        val selectorBean: BalancerBean?,
        val entitiesById: Map<Long, ProxyEntity>,
        val healthMap: Map<Long, AutoSelectorHealthStore.Entry>,
        val offlinePoolIds: List<Long>,
    )

    private lateinit var binding: LayoutAutoSelectorStatusBinding
    private lateinit var adapter: MemberAdapter
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_AUTO_SELECTOR, true)

    private var requestedSelectorId: Long = 0L
    private var onlyProblems: Boolean = false
    private var snapshot: SnapshotBundle? = null
    private var latestStatus: AutoSelectorStatus = AutoSelectorStatus.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedSelectorId = intent.getLongExtra(EXTRA_SELECTOR_ID, 0L)

        binding = LayoutAutoSelectorStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.autosel_stats_title)
            setDisplayHomeAsUpEnabled(true)
        }

        adapter = MemberAdapter { row -> showMemberDialog(row) }
        binding.memberList.layoutManager = LinearLayoutManager(this)
        binding.memberList.adapter = adapter

        val footerBaseBottom = binding.statusFooter.paddingBottom
        val listBaseBottom = binding.memberList.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.statusFooter.updatePadding(bottom = footerBaseBottom + bars.bottom)
            binding.memberList.updatePadding(
                bottom = listBaseBottom + if (binding.statusFooter.isVisible) 0 else bars.bottom
            )
            insets
        }

        binding.useAutomaticButton.setOnClickListener {
            unpinSelection()
        }
        binding.checkAllButton.setOnClickListener {
            triggerRecheckAll()
        }

        ProfileManager.addListener(this)
        connection.connect(this, this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutoSelectorClient.status.collect { status ->
                    latestStatus = status
                    render()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(1_000L)
                    if (latestStatus.running) {
                        render()
                    }
                }
            }
        }

        refreshSnapshot()
    }

    override fun onResume() {
        super.onResume()
        refreshSnapshot()
        lifecycleScope.launch(Dispatchers.IO) {
            val statusJson = runCatching { connection.service?.getAutoSelectorStatus(true) }.getOrNull()
            if (statusJson != null) {
                AutoSelectorClient.onStatusJson(statusJson)
            }
        }
    }

    override fun onDestroy() {
        ProfileManager.removeListener(this)
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_selector_status_menu, menu)
        menu.findItem(R.id.action_only_problems)?.isChecked = onlyProblems
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val targetId = resolveTargetSelectorId()
        menu.findItem(R.id.action_edit_selector)?.isVisible = targetId > 0L
        menu.findItem(R.id.action_only_problems)?.isChecked = onlyProblems
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_edit_selector -> {
                val targetId = resolveTargetSelectorId()
                if (targetId > 0L) {
                    startActivity(
                        Intent(this, BalancerSettingsActivity::class.java)
                            .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, targetId)
                    )
                }
                true
            }
            R.id.action_only_problems -> {
                onlyProblems = !onlyProblems
                item.isChecked = onlyProblems
                render()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        if (state == BaseService.State.Stopped || state == BaseService.State.Idle) {
            latestStatus = AutoSelectorStatus.IDLE
            refreshSnapshot()
        } else if (state == BaseService.State.Connected) {
            lifecycleScope.launch(Dispatchers.IO) {
                val json = runCatching { connection.service?.getAutoSelectorStatus(true) }.getOrNull()
                if (json != null) AutoSelectorClient.onStatusJson(json)
            }
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        lifecycleScope.launch(Dispatchers.IO) {
            val json = runCatching { service.getAutoSelectorStatus(true) }.getOrNull()
            if (json != null) AutoSelectorClient.onStatusJson(json)
        }
    }

    override suspend fun onAdd(profile: ProxyEntity) = refreshSnapshotAsync()
    override suspend fun onUpdated(data: List<io.nekohasekai.sagernet.aidl.TrafficData>) {}
    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = refreshSnapshotAsync()
    override suspend fun onRemoved(groupId: Long, profileId: Long) = refreshSnapshotAsync()

    private fun refreshSnapshot() {
        lifecycleScope.launch {
            refreshSnapshotAsync()
        }
    }

    private suspend fun refreshSnapshotAsync() {
        val loaded = withContext(Dispatchers.IO) {
            loadSnapshotBundle()
        }
        snapshot = loaded
        invalidateOptionsMenu()
        render()
    }

    private fun loadSnapshotBundle(): SnapshotBundle {
        val currentStatus = latestStatus
        var selectorPair = if (requestedSelectorId > 0L) {
            AutoSelectorProfiles.findAutoSelector(requestedSelectorId)
        } else null

        if (selectorPair == null && currentStatus.running && currentStatus.selectorId > 0L) {
            selectorPair = AutoSelectorProfiles.findAutoSelector(currentStatus.selectorId)
        }
        if (selectorPair == null && DataStore.selectedProxy > 0L) {
            selectorPair = AutoSelectorProfiles.findAutoSelector(DataStore.selectedProxy)
        }
        if (selectorPair == null) {
            selectorPair = AutoSelectorProfiles.loadAllAutoSelectors().firstOrNull()
        }

        val targetId = selectorPair?.first?.id ?: requestedSelectorId
        val selectorName = selectorPair?.first?.displayName().orEmpty()
        val bean = selectorPair?.second

        val allEntities = runCatching { SagerDatabase.proxyDao.getAll() }.getOrDefault(emptyList())
        val entitiesById = HashMap<Long, ProxyEntity>(allEntities.size)
        for (e in allEntities) {
            entitiesById[e.id] = e
        }
        val healthMap = AutoSelectorHealthStore.snapshot()

        val savedPool = bean?.pool?.filter { it > 0L }.orEmpty()
        val offlinePoolIds = if (savedPool.isNotEmpty()) {
            savedPool
        } else if (bean != null) {
            AutoSelectorProfiles.buildPlan(bean, excludeProfileId = targetId).pool.map { it.id }
        } else {
            emptyList()
        }

        return SnapshotBundle(
            targetSelectorId = targetId,
            selectorName = selectorName,
            selectorBean = bean,
            entitiesById = entitiesById,
            healthMap = healthMap,
            offlinePoolIds = offlinePoolIds,
        )
    }

    private fun resolveTargetSelectorId(): Long {
        val snapId = snapshot?.targetSelectorId ?: 0L
        if (snapId > 0L) return snapId
        if (requestedSelectorId > 0L) return requestedSelectorId
        return if (latestStatus.running) latestStatus.selectorId else 0L
    }

    private fun render() {
        val snap = snapshot ?: return
        val status = latestStatus
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L

        val runningMatches = status.running &&
            (snap.targetSelectorId <= 0L || status.selectorId == snap.targetSelectorId)

        val subtitle = when {
            snap.selectorName.isNotBlank() -> snap.selectorName
            runningMatches && status.selectorName.isNotBlank() -> status.selectorName
            else -> null
        }
        supportActionBar?.subtitle = subtitle

        val nameResolver: (Long, String) -> String = { id, tag ->
            resolveMemberName(snap, id, tag)
        }

        val effectivePinnedId = when {
            runningMatches && status.pinnedId > 0L -> status.pinnedId
            snap.selectorBean != null && snap.selectorBean.pinnedId > 0L -> snap.selectorBean.pinnedId
            else -> -1L
        }
        val hasPin = (runningMatches && (status.pinnedId > 0L || status.pinnedTag.isNotBlank())) ||
            effectivePinnedId > 0L

        binding.useAutomaticButton.isVisible = hasPin
        binding.checkAllButton.isEnabled = runningMatches

        val rows: List<MemberRow>
        if (runningMatches) {
            binding.statusHeadline.text = AutoSelectorTexts.statusHeadline(this, status, nowMs, nameResolver)
            val detail = AutoSelectorTexts.statusDetail(this, status)
            binding.statusDetail.text = detail
            binding.statusDetail.isVisible = detail.isNotBlank()

            val footer = AutoSelectorTexts.statusFooter(this, status, nowMs, nameResolver)
            binding.statusFooter.text = footer
            binding.statusFooter.isVisible = footer.isNotBlank()

            val ordered = if (status.members.any { it.rank > 0 }) {
                status.members.sortedWith(compareBy<AutoSelectorMemberStatus> {
                    if (it.rank > 0) it.rank else Int.MAX_VALUE
                }.thenBy { it.tag })
            } else {
                status.members
            }

            rows = ordered.mapIndexed { idx, m ->
                val displayRank = if (m.rank > 0) m.rank else idx + 1
                val entity = if (m.profileId > 0L) snap.entitiesById[m.profileId] else null
                val exists = entity != null
                val name = resolveMemberName(snap, m.profileId, m.tag)
                val cached = if (m.profileId > 0L) snap.healthMap[m.profileId] else null
                val resolvedHealth = entity?.let { AutoSelectorHealthStore.resolveMemberHealth(it, snap.healthMap) }
                val pinned = (status.pinnedId > 0L && status.pinnedId == m.profileId) ||
                    (status.pinnedTag.isNotBlank() && status.pinnedTag == m.tag)
                val selected = m.selected ||
                    (status.selectedId > 0L && status.selectedId == m.profileId) ||
                    (status.selectedTag.isNotBlank() && status.selectedTag == m.tag)
                MemberRow(
                    member = m.copy(rank = displayRank),
                    displayName = name,
                    exists = exists,
                    testedAtSec = resolvedHealth?.at ?: cached?.at ?: 0L,
                    lastTestError = resolvedHealth?.error ?: cached?.error,
                    pinned = pinned,
                    selected = selected,
                    running = true,
                )
            }
        } else {
            val bean = snap.selectorBean
            binding.statusHeadline.setText(R.string.autosel_stats_not_running)
            val builtCount = bean?.lastBuilt?.size?.takeIf { it > 0 } ?: snap.offlinePoolIds.size
            if (bean != null && bean.lastBuiltAt > 0L && builtCount > 0) {
                binding.statusDetail.text = getString(
                    R.string.autosel_stats_last_build,
                    AutoSelectorTexts.agoSeconds(this, bean.lastBuiltAt, nowMs),
                    builtCount,
                )
            } else {
                binding.statusDetail.setText(R.string.autosel_stats_never_built)
            }
            binding.statusDetail.isVisible = true

            val footerLines = ArrayList<String>()
            if (effectivePinnedId > 0L) {
                val pinnedName = resolveMemberName(snap, effectivePinnedId, "")
                footerLines.add(getString(R.string.autosel_footer_pinned_other, pinnedName))
            }
            val footer = footerLines.joinToString("\n")
            binding.statusFooter.text = footer
            binding.statusFooter.isVisible = footer.isNotBlank()

            rows = snap.offlinePoolIds.mapIndexed { idx, pid ->
                val entity = snap.entitiesById[pid]
                val exists = entity != null
                val name = resolveMemberName(snap, pid, "#$pid")
                val cached = snap.healthMap[pid]
                val resolved = entity?.let { AutoSelectorHealthStore.resolveMemberHealth(it, snap.healthMap) }
                val latency = resolved?.latency ?: cached?.latency ?: 0
                val atSec = resolved?.at ?: cached?.at ?: 0L
                val err = resolved?.error ?: cached?.error
                val offlineState = when {
                    !exists -> "dead"
                    latency > 0 && err.isNullOrBlank() -> "ok"
                    !err.isNullOrBlank() -> "dead"
                    entity?.status in 2..3 -> "dead"
                    else -> "untested"
                }
                val isPinned = effectivePinnedId > 0L && effectivePinnedId == pid
                val memberStatus = AutoSelectorMemberStatus(
                    tag = "proxy-$pid",
                    profileId = pid,
                    rank = idx + 1,
                    state = offlineState,
                    averageMs = latency,
                    lastOkMs = if (offlineState == "ok" && atSec > 0L) atSec * 1000L else 0L,
                    lastProbeMs = if (atSec > 0L) atSec * 1000L else 0L,
                    lastError = err.orEmpty(),
                )
                MemberRow(
                    member = memberStatus,
                    displayName = name,
                    exists = exists,
                    testedAtSec = atSec,
                    lastTestError = err,
                    pinned = isPinned,
                    selected = false,
                    running = false,
                )
            }
        }

        val filtered = if (onlyProblems) {
            rows.filter { isProblemRow(it) }
        } else {
            rows
        }

        adapter.submitList(filtered)
        if (filtered.isEmpty()) {
            binding.memberList.isVisible = false
            binding.emptyView.isVisible = true
            binding.emptyView.setText(
                if (onlyProblems && rows.isNotEmpty()) {
                    R.string.autosel_stats_no_problems
                } else {
                    R.string.autosel_stats_empty
                }
            )
        } else {
            binding.memberList.isVisible = true
            binding.emptyView.isVisible = false
        }
    }

    private fun isProblemRow(row: MemberRow): Boolean {
        if (!row.exists) return true
        val m = row.member
        if (m.state in setOf("dead", "cooldown", "degraded")) return true
        if (m.lastError.isNotBlank() || !row.lastTestError.isNullOrBlank()) return true
        if (row.running && m.failures > 0) return true
        return false
    }

    private fun resolveMemberName(snap: SnapshotBundle, profileId: Long, tag: String): String {
        if (profileId > 0L) {
            snap.entitiesById[profileId]?.let { entity ->
                return runCatching { entity.displayName() }.getOrDefault("#$profileId")
            }
            return getString(R.string.autosel_pinned_missing)
        }
        return tag.ifBlank { getString(R.string.autosel_pinned_missing) }
    }

    private fun showMemberDialog(row: MemberRow) {
        val m = row.member
        val lines = ArrayList<String>()
        val nowMs = System.currentTimeMillis()
        lines.add(getString(R.string.autosel_row_state, AutoSelectorTexts.stateLabel(this, m.state)))

        if (m.samples > 0 && m.averageMs > 0) {
            val min = if (m.minMs > 0) m.minMs else m.averageMs
            val max = if (m.maxMs > 0) m.maxMs else m.averageMs
            lines.add(getString(R.string.autosel_row_latency, m.averageMs, min, max))
            if (m.deviationMs > 0) {
                lines.add(getString(R.string.autosel_row_jitter, m.deviationMs))
            }
        }
        if (m.probes > 0) {
            val okChecks = (m.probes - m.failures).coerceAtLeast(0)
            lines.add(getString(R.string.autosel_row_checks, okChecks, m.probes))
        }
        if (m.dialTotal > 0) {
            val okDials = (m.dialTotal - m.dialFail).coerceAtLeast(0)
            lines.add(getString(R.string.autosel_row_connects, okDials, m.dialTotal))
        }
        if (m.lastOkMs > 0L) {
            lines.add(getString(R.string.autosel_row_last_ok, AutoSelectorTexts.ago(this, m.lastOkMs, nowMs)))
        }
        val err = m.lastError.ifBlank { row.lastTestError.orEmpty() }.trim()
        if (err.isNotBlank()) {
            lines.add(getString(R.string.autosel_row_error, err))
        }

        if (!row.running) {
            if (row.testedAtSec > 0L) {
                val resText = if (m.averageMs > 0 && err.isBlank()) {
                    getString(R.string.autosel_ms, m.averageMs)
                } else {
                    getString(R.string.unavailable)
                }
                lines.add(
                    getString(
                        R.string.autosel_row_tested,
                        resText,
                        AutoSelectorTexts.agoSeconds(this, row.testedAtSec, nowMs),
                    )
                )
            } else if (m.probes == 0) {
                lines.add(getString(R.string.autosel_row_untested))
            }
        }

        if (row.exists && m.profileId > 0L) {
            lines.add("")
            lines.add(getString(R.string.autosel_use_this_tip))
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(row.displayName)
            .setMessage(lines.joinToString("\n"))
            .setNegativeButton(android.R.string.cancel, null)

        if (row.exists && m.profileId > 0L) {
            if (row.pinned) {
                builder.setPositiveButton(R.string.autosel_back_to_automatic) { _, _ ->
                    unpinSelection()
                }
            } else {
                builder.setPositiveButton(R.string.autosel_use_this) { _, _ ->
                    pinMember(m.profileId)
                }
            }
        }
        builder.show()
    }

    private fun pinMember(memberProfileId: Long) {
        val targetId = resolveTargetSelectorId()
        if (targetId <= 0L) return
        val status = latestStatus
        val runningMatches = status.running && status.selectorId == targetId
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (runningMatches && connection.service != null) {
                    runCatching { connection.service?.autoSelectorSelect(memberProfileId) }
                        .onFailure { AutoSelectorProfiles.setPinned(targetId, memberProfileId) }
                } else {
                    AutoSelectorProfiles.setPinned(targetId, memberProfileId)
                }
            }
            if (runningMatches) delay(200L)
            refreshSnapshotAsync()
        }
    }

    private fun unpinSelection() {
        pinMember(-1L)
    }

    private fun triggerRecheckAll() {
        val targetId = resolveTargetSelectorId()
        val status = latestStatus
        if (!status.running || (targetId > 0L && status.selectorId != targetId)) return
        binding.checkAllButton.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { connection.service?.autoSelectorRecheck() }
            }
            delay(400L)
            render()
        }
    }

    private inner class MemberAdapter(
        private val onClick: (MemberRow) -> Unit,
    ) : ListAdapter<MemberRow, MemberViewHolder>(RowDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
            val itemBinding = LayoutAutoSelectorMemberBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return MemberViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
            holder.bind(getItem(position), onClick)
        }
    }

    private object RowDiff : DiffUtil.ItemCallback<MemberRow>() {
        override fun areItemsTheSame(oldItem: MemberRow, newItem: MemberRow): Boolean {
            return if (oldItem.member.profileId > 0L && newItem.member.profileId > 0L) {
                oldItem.member.profileId == newItem.member.profileId
            } else {
                oldItem.member.tag == newItem.member.tag
            }
        }

        override fun areContentsTheSame(oldItem: MemberRow, newItem: MemberRow): Boolean {
            return oldItem == newItem
        }
    }

    private class MemberViewHolder(
        private val binding: LayoutAutoSelectorMemberBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: MemberRow, onClick: (MemberRow) -> Unit) {
            val ctx = binding.root.context
            val m = row.member
            val nowMs = System.currentTimeMillis()
            val status = AutoSelectorStatus(
                running = row.running,
                selectedId = if (row.selected) m.profileId else 0L,
                selectedTag = if (row.selected) m.tag else "",
                pinnedId = if (row.pinned) m.profileId else 0L,
                pinnedTag = if (row.pinned) m.tag else "",
            )

            binding.memberRank.text = if (m.rank > 0) m.rank.toString() else "-"
            binding.memberName.text = row.displayName

            val accentColor = ctx.getColorAttr(R.attr.primaryOrTextPrimary)
            val normalNameColor = ctx.getColorAttr(android.R.attr.textColorPrimary)
            binding.memberName.setTextColor(if (row.selected) accentColor else normalNameColor)

            binding.memberBadgeSelected.isVisible = row.selected
            binding.memberBadgePinned.isVisible = row.pinned
            binding.memberBadgeSelected.setColorFilter(accentColor)
            binding.memberBadgePinned.setColorFilter(accentColor)

            val subtitle = AutoSelectorTexts.memberSubtitle(
                context = ctx,
                member = m,
                status = status,
                exists = row.exists,
                testedAtSec = row.testedAtSec,
                lastTestError = row.lastTestError,
                nowMs = nowMs,
            )
            binding.memberSubtitle.text = subtitle
            binding.memberSubtitle.isVisible = subtitle.isNotBlank()

            val latencyText: String
            val latencyColor: Int
            when {
                m.averageMs > 0 && m.state != "dead" && m.state != "cooldown" -> {
                    latencyText = ctx.getString(R.string.autosel_ms, m.averageMs)
                    latencyColor = when {
                        m.averageMs < 300 -> ContextCompat.getColor(ctx, R.color.material_green_600)
                        m.averageMs <= 800 -> ContextCompat.getColor(ctx, R.color.material_deep_orange_500)
                        else -> ContextCompat.getColor(ctx, R.color.material_red_500)
                    }
                }
                m.state == "dead" || m.state == "cooldown" -> {
                    latencyText = ctx.getString(R.string.unavailable)
                    latencyColor = ContextCompat.getColor(ctx, R.color.material_red_500)
                }
                else -> {
                    latencyText = AutoSelectorTexts.stateLabel(ctx, m.state)
                    latencyColor = ctx.getColorAttr(android.R.attr.textColorSecondary)
                }
            }
            binding.memberLatency.text = latencyText
            binding.memberLatency.setTextColor(latencyColor)

            val metricText = when {
                !row.running && row.testedAtSec > 0L -> AutoSelectorTexts.agoSeconds(ctx, row.testedAtSec, nowMs)
                !row.running -> ""
                m.lastOkMs > 0L && (m.state == "ok" || m.state == "degraded") ->
                    ctx.getString(R.string.autosel_last_ok_short, AutoSelectorTexts.ago(ctx, m.lastOkMs, nowMs))
                m.lastProbeMs > 0L -> AutoSelectorTexts.ago(ctx, m.lastProbeMs, nowMs)
                else -> ""
            }
            binding.memberMetric.text = metricText
            binding.memberMetric.isVisible = metricText.isNotBlank()

            binding.root.setOnClickListener { onClick(row) }
        }
    }
}
