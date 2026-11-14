package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.StrategyGroupSnapshot
import io.nekohasekai.sagernet.bg.StrategyMemberSnapshot
import io.nekohasekai.sagernet.bg.StrategySnapshot
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityStrategyStatsBinding
import io.nekohasekai.sagernet.databinding.ItemStrategyMemberBinding
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.profile.BalancerSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StrategyStatsActivity : ThemedActivity(), SagerConnection.Callback {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_GROUP_ID = "group_id"
        private const val PAYLOAD_TIME = "time"
    }

    private lateinit var binding: ActivityStrategyStatsBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
    private lateinit var memberAdapter: StrategyMemberAdapter

    private var currentSnapshot: StrategyGroupSnapshot? = null
    private var tickerJob: Job? = null
    private var checkingNow = false
    private var pinUpdating = false
    private var activityVisible = false
    private var fallbackEditProfileId = 0L
    private var fallbackEditGroupId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStrategyStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fallbackEditProfileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        fallbackEditGroupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerStrategyMembers) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = navBars.bottom + dp2px(16))
            insets
        }

        memberAdapter = StrategyMemberAdapter(::onMemberClicked)
        binding.recyclerStrategyMembers.apply {
            layoutManager = LinearLayoutManager(this@StrategyStatsActivity)
            adapter = memberAdapter
            itemAnimator = null
        }

        binding.btnCheckAll.setOnClickListener { triggerCheckAll() }
        binding.btnRestoreAuto.setOnClickListener { triggerRestoreAuto() }

        connection.connect(this, this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.strategy_stats_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val editItem = menu.findItem(R.id.action_edit_strategy)
        val snap = currentSnapshot
        val canEdit = (snap?.canEdit == true) || fallbackEditProfileId > 0L || fallbackEditGroupId > 0L
        editItem?.isVisible = canEdit
        editItem?.isEnabled = canEdit
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_edit_strategy) {
            openStrategyEditor()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openStrategyEditor() {
        val snap = currentSnapshot
        val profileId = snap?.editProfileId?.takeIf { it > 0L } ?: fallbackEditProfileId
        val groupId = snap?.editGroupId?.takeIf { it > 0L } ?: fallbackEditGroupId
        when {
            profileId > 0L -> startActivity(
                Intent(this, BalancerSettingsActivity::class.java)
                    .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, profileId)
            )

            groupId > 0L -> startActivity(
                Intent(this, GroupSettingsActivity::class.java)
                    .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, groupId)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        refreshSnapshot()
        startTicker()
    }

    override fun onPause() {
        activityVisible = false
        tickerJob?.cancel()
        tickerJob = null
        super.onPause()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        tickerJob = null
        connection.disconnect(this)
        super.onDestroy()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive && activityVisible) {
                delay(3000L)
                if (!checkingNow && !pinUpdating) {
                    refreshSnapshot()
                }
            }
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        refreshSnapshot()
    }

    override fun onServiceDisconnected() {
        renderEmpty()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        if (state.started) {
            refreshSnapshot()
        } else {
            renderEmpty()
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        refreshSnapshot()
    }

    private fun refreshSnapshot() {
        val service = connection.service
        if (service == null || !DataStore.serviceState.started) {
            renderEmpty()
            return
        }
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { service.getActiveStrategySnapshotJson() }.getOrNull().orEmpty()
            }
            val snap = StrategySnapshot.parse(raw)
            if (snap == null) {
                renderEmpty()
            } else {
                renderSnapshot(snap)
            }
        }
    }

    private fun triggerCheckAll() {
        val service = connection.service ?: return
        if (checkingNow || !DataStore.serviceState.started) return
        checkingNow = true
        updateCheckButtonState(coreChecking = true)
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    runCatching { service.checkActiveStrategyNowJson() }.getOrNull().orEmpty()
                }
                val snap = StrategySnapshot.parse(raw)
                if (snap != null) {
                    renderSnapshot(snap)
                } else {
                    refreshSnapshot()
                }
            } finally {
                checkingNow = false
                updateCheckButtonState(coreChecking = currentSnapshot?.checking == true)
            }
        }
    }

    private fun onMemberClicked(member: StrategyMemberSnapshot) {
        val service = connection.service ?: return
        if (pinUpdating || !DataStore.serviceState.started) return
        if (member.profileId <= 0L) {
            Toast.makeText(this, R.string.strategy_pin_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pinUpdating = true
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    runCatching { service.pinActiveStrategyMember(member.profileId) }.getOrNull().orEmpty()
                }
                val snap = StrategySnapshot.parse(raw)
                if (snap != null && snap.pinnedProfileId == member.profileId) {
                    renderSnapshot(snap)
                    Toast.makeText(
                        this@StrategyStatsActivity,
                        getString(R.string.strategy_pin_applied, member.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    if (snap != null) {
                        renderSnapshot(snap)
                    }
                    Toast.makeText(
                        this@StrategyStatsActivity,
                        R.string.strategy_pin_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                pinUpdating = false
            }
        }
    }

    private fun triggerRestoreAuto() {
        val service = connection.service ?: return
        if (pinUpdating || !DataStore.serviceState.started) return
        pinUpdating = true
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    runCatching { service.pinActiveStrategyMember(0L) }.getOrNull().orEmpty()
                }
                val snap = StrategySnapshot.parse(raw)
                if (snap != null) {
                    renderSnapshot(snap)
                } else {
                    refreshSnapshot()
                }
                Toast.makeText(
                    this@StrategyStatsActivity,
                    R.string.strategy_pin_restored,
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                pinUpdating = false
            }
        }
    }

    private fun renderEmpty() {
        currentSnapshot = null
        supportActionBar?.subtitle = null
        binding.textStrategyHeadline.setText(R.string.strategy_not_running)
        binding.textStrategySubtitle.text = ""
        binding.textStrategySubtitle.visibility = View.GONE
        binding.btnRestoreAuto.visibility = View.GONE
        binding.btnCheckAll.isEnabled = false
        binding.btnCheckAll.setText(R.string.strategy_check_all)
        binding.textStrategyEmpty.visibility = View.VISIBLE
        binding.recyclerStrategyMembers.visibility = View.GONE
        memberAdapter.submitList(emptyList())
        invalidateOptionsMenu()
    }

    private fun renderSnapshot(snap: StrategyGroupSnapshot) {
        currentSnapshot = snap
        supportActionBar?.subtitle = snap.groupName.ifBlank { null }

        val modeCode = snap.strategyType.ifBlank { "leastPing" }
        val modeLabel = StrategySnapshot.formatStrategyLabel(this, modeCode)
        val activeDisplay = snap.pinnedName.takeIf { snap.isPinned && it.isNotBlank() } ?: snap.activeName

        binding.textStrategyHeadline.text = if (snap.isPinned) {
            getString(R.string.strategy_overview_pinned, activeDisplay.ifBlank { snap.pinnedTag })
        } else {
            getString(R.string.strategy_overview_running, modeLabel, modeCode)
        }

        val lastCheckText = StrategySnapshot.formatRelativeTime(this, snap.lastCheckMs)
        binding.textStrategySubtitle.visibility = View.VISIBLE
        binding.textStrategySubtitle.text = if (activeDisplay.isNotBlank()) {
            getString(R.string.strategy_overview_meta_active, lastCheckText, snap.members.size, activeDisplay)
        } else {
            getString(R.string.strategy_overview_meta, lastCheckText, snap.members.size)
        }

        binding.btnRestoreAuto.visibility = if (snap.isPinned) View.VISIBLE else View.GONE
        updateCheckButtonState(coreChecking = snap.checking)

        val hasMembers = snap.members.isNotEmpty()
        binding.textStrategyEmpty.visibility = if (hasMembers) View.GONE else View.VISIBLE
        binding.recyclerStrategyMembers.visibility = if (hasMembers) View.VISIBLE else View.GONE

        val previousMembers = memberAdapter.currentList
        memberAdapter.submitList(snap.members)
        if (hasMembers && previousMembers == snap.members) {
            memberAdapter.notifyItemRangeChanged(0, snap.members.size, PAYLOAD_TIME)
        }
        invalidateOptionsMenu()
    }

    private fun updateCheckButtonState(coreChecking: Boolean) {
        val busy = checkingNow || coreChecking
        binding.btnCheckAll.isEnabled = DataStore.serviceState.started && currentSnapshot != null && !busy
        binding.btnCheckAll.setText(if (busy) R.string.strategy_checking else R.string.strategy_check_all)
    }

    private class StrategyMemberAdapter(
        private val onClick: (StrategyMemberSnapshot) -> Unit,
    ) : ListAdapter<StrategyMemberSnapshot, StrategyMemberAdapter.ViewHolder>(DIFF_CALLBACK) {

        companion object {
            private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StrategyMemberSnapshot>() {
                override fun areItemsTheSame(
                    oldItem: StrategyMemberSnapshot,
                    newItem: StrategyMemberSnapshot,
                ): Boolean = oldItem.tag == newItem.tag && oldItem.profileId == newItem.profileId

                override fun areContentsTheSame(
                    oldItem: StrategyMemberSnapshot,
                    newItem: StrategyMemberSnapshot,
                ): Boolean = oldItem == newItem
            }
        }

        inner class ViewHolder(
            val binding: ItemStrategyMemberBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: StrategyMemberSnapshot, position: Int) {
                val context = binding.root.context
                val primaryColor = context.getColorAttr(R.attr.colorPrimary)
                val textPrimaryColor = context.getColorAttr(android.R.attr.textColorPrimary)
                val textSecondaryColor = context.getColorAttr(android.R.attr.textColorSecondary)

                binding.textMemberIndex.text = (position + 1).toString()
                binding.textMemberName.text = item.name
                binding.textMemberName.setTextColor(
                    if (item.pinned || item.active) primaryColor else textPrimaryColor
                )

                binding.imgMemberPin.visibility = if (item.pinned) View.VISIBLE else View.GONE

                when {
                    item.pinned -> {
                        binding.textMemberBadge.visibility = View.VISIBLE
                        binding.textMemberBadge.setText(R.string.strategy_badge_pinned)
                        applyBadgeBackground(binding.textMemberBadge, primaryColor)
                    }

                    item.active -> {
                        binding.textMemberBadge.visibility = View.VISIBLE
                        binding.textMemberBadge.setText(R.string.strategy_badge_active)
                        applyBadgeBackground(binding.textMemberBadge, primaryColor)
                    }

                    else -> {
                        binding.textMemberBadge.visibility = View.GONE
                    }
                }

                if (item.error.isNotBlank()) {
                    binding.textMemberError.visibility = View.VISIBLE
                    binding.textMemberError.text = item.error
                } else {
                    binding.textMemberError.visibility = View.GONE
                }

                when {
                    item.isUnavailable -> {
                        binding.textMemberDelay.setText(R.string.unavailable)
                        binding.textMemberDelay.setTextColor(textSecondaryColor)
                    }

                    item.delay > 0 -> {
                        binding.textMemberDelay.text = context.getString(R.string.strategy_latency_ms, item.delay)
                        binding.textMemberDelay.setTextColor(
                            StrategySnapshot.resolveLatencyColor(context, item.delay)
                        )
                    }

                    else -> {
                        binding.textMemberDelay.setText(R.string.strategy_untested)
                        binding.textMemberDelay.setTextColor(textSecondaryColor)
                    }
                }

                bindTimeOnly(item)
                binding.rowRoot.setOnClickListener { onClick(item) }
            }

            fun bindTimeOnly(item: StrategyMemberSnapshot) {
                val context = binding.root.context
                binding.textMemberTime.text = StrategySnapshot.formatRelativeTime(context, item.lastCheckMs)
            }

            private fun applyBadgeBackground(view: View, primaryColor: Int) {
                view.background = GradientDrawable().apply {
                    setColor(ColorUtils.setAlphaComponent(primaryColor, 28))
                    cornerRadius = dp2px(6).toFloat()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemStrategyMemberBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position), position)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_TIME) && payloads.size == 1) {
                holder.bindTimeOnly(getItem(position))
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}
