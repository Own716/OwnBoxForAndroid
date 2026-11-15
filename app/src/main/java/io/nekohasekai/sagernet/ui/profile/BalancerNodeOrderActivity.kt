package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.ActivityBalancerNodeOrderBinding
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.BalancerMemberResolver
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.BalancerNodeListPreference
import moe.matsuri.nb4a.Protocols.getProtocolColor
import java.util.Collections

class BalancerNodeOrderActivity : ThemedActivity() {

    companion object {
        const val EXTRA_NODE_IDS = "node_ids"
        const val EXTRA_TIERS_JSON = "tiers_json"
        const val EXTRA_EDITING_ID = "editing_id"
        const val EXTRA_EXCLUDE_IDS = "exclude_ids"
        const val EXTRA_RESULT_NODE_IDS = "result_node_ids"

        private const val STATE_NODE_IDS = "state_node_ids"
        private const val STATE_HAS_CHANGES = "state_has_changes"
    }

    private lateinit var binding: ActivityBalancerNodeOrderBinding
    private val nodeList = ArrayList<ProxyEntity>()
    private val groupNames = HashMap<Long, String>()
    private val tiersList = ArrayList<BalancerBean.PriorityTier>()
    private var tierIndexById: Map<Long, Int> = emptyMap()
    private val excludeIdSet = HashSet<Long>()
    private var editingId = 0L
    private var hasChanges = false
    private var replacingIndex = -1

    private lateinit var nodeAdapter: OrderAdapter
    private var itemTouchHelper: ItemTouchHelper? = null

    private val pickProfileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            replacingIndex = -1
            return@registerForActivityResult
        }
        val data = result.data ?: run {
            replacingIndex = -1
            return@registerForActivityResult
        }
        val targetIndex = replacingIndex
        replacingIndex = -1
        runOnDefaultDispatcher {
            val multiIds = data.getLongArrayExtra(ProfileSelectActivity.EXTRA_PROFILE_IDS)
            if (multiIds != null) {
                val profiles = ProfileManager.getProfiles(multiIds.toList()).associateBy { it.id }
                var circularFound = false
                val updated = ArrayList<ProxyEntity>()
                val seen = HashSet<Long>()
                for (id in multiIds) {
                    if (seen.add(id)) {
                        val profile = profiles[id] ?: continue
                        if (!isProfileAllowed(profile)) {
                            circularFound = true
                            continue
                        }
                        updated.add(profile)
                    }
                }
                onMainDispatcher {
                    if (circularFound) {
                        showCircularReferenceDialog()
                    }
                    nodeList.clear()
                    nodeList.addAll(updated)
                    markChangedAndRefresh()
                }
                return@runOnDefaultDispatcher
            }

            val singleId = data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
            if (singleId <= 0L) return@runOnDefaultDispatcher
            val profile = ProfileManager.getProfile(singleId) ?: return@runOnDefaultDispatcher
            if (!isProfileAllowed(profile)) {
                onMainDispatcher { showCircularReferenceDialog() }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                if (targetIndex in nodeList.indices) {
                    val existingIdx = nodeList.indexOfFirst { it.id == profile.id }
                    if (existingIdx >= 0 && existingIdx != targetIndex) {
                        BalancerNodeListPreference.moveItem(nodeList, existingIdx, targetIndex)
                        nodeAdapter.notifyItemMoved(existingIdx, targetIndex)
                        recomputeTiers()
                        refreshVisibleBadges()
                        markChanged()
                    } else {
                        nodeList[targetIndex] = profile
                        recomputeTiers()
                        nodeAdapter.notifyItemChanged(targetIndex)
                        markChanged()
                    }
                } else if (nodeList.none { it.id == profile.id }) {
                    nodeList.add(profile)
                    markChangedAndRefresh()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBalancerNodeOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        editingId = intent.getLongExtra(EXTRA_EDITING_ID, DataStore.editingId)
        intent.getLongArrayExtra(EXTRA_EXCLUDE_IDS)?.forEach { excludeIdSet.add(it) }
        if (editingId > 0L) excludeIdSet.add(editingId)
        val tiersJson = intent.getStringExtra(EXTRA_TIERS_JSON).orEmpty()
        tiersList.addAll(BalancerBean.parseTiersJson(tiersJson))

        hasChanges = savedInstanceState?.getBoolean(STATE_HAS_CHANGES, false) ?: false
        val initialIds = savedInstanceState?.getLongArray(STATE_NODE_IDS)
            ?: intent.getLongArrayExtra(EXTRA_NODE_IDS)
            ?: LongArray(0)

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerNodeOrder) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = navBars.bottom + dp2px(16))
            insets
        }

        nodeAdapter = OrderAdapter()
        binding.recyclerNodeOrder.apply {
            layoutManager = LinearLayoutManager(this@BalancerNodeOrderActivity)
            adapter = nodeAdapter
            itemAnimator = null
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (from == to || from !in nodeList.indices || to !in nodeList.indices) return false
                Collections.swap(nodeList, from, to)
                nodeAdapter.notifyItemMoved(from, to)
                refreshVisibleBadges()
                markChanged()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                refreshVisibleBadges()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(binding.recyclerNodeOrder)
        }

        if (hasChanges) {
            publishResult(initialIds)
        }

        loadInitialNodes(initialIds)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(STATE_NODE_IDS, nodeList.map { it.id }.toLongArray())
        outState.putBoolean(STATE_HAS_CHANGES, hasChanges)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_balancer_node_order, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sortItem = menu.findItem(R.id.action_sort_by_latency)
        sortItem?.isEnabled = nodeList.size > 1
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_nodes -> {
                launchAddOrPickNodes()
                true
            }
            R.id.action_sort_by_latency -> {
                sortNodesByCurrentLatency()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadInitialNodes(initialIds: LongArray) {
        runOnDefaultDispatcher {
            val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())
            val groupMap = HashMap<Long, String>()
            allGroups.filter { !it.ungrouped }.forEach { groupMap[it.id] = it.displayName() }

            val loaded = if (initialIds.isNotEmpty()) {
                val map = ProfileManager.getProfiles(initialIds.toList()).associateBy { it.id }
                val seen = HashSet<Long>()
                initialIds.toList().mapNotNull { id ->
                    if (seen.add(id)) map[id] else null
                }
            } else {
                emptyList()
            }

            onMainDispatcher {
                groupNames.clear()
                groupNames.putAll(groupMap)
                nodeList.clear()
                nodeList.addAll(loaded)
                recomputeTiers()
                nodeAdapter.notifyDataSetChanged()
                updateEmptyState()
                invalidateOptionsMenu()
            }
        }
    }

    private fun launchAddOrPickNodes() {
        replacingIndex = -1
        val excludeIds = excludeIdSet.toLongArray()
        pickProfileLauncher.launch(
            Intent(this, ProfileSelectActivity::class.java).apply {
                putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                putExtra(ProfileSelectActivity.EXTRA_SELECTED_IDS, nodeList.map { it.id }.toLongArray())
                if (excludeIds.isNotEmpty()) {
                    putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeIds)
                }
            },
        )
    }

    private fun launchReplaceNode(position: Int, entity: ProxyEntity) {
        replacingIndex = position
        val excludeIds = excludeIdSet.toLongArray()
        pickProfileLauncher.launch(
            Intent(this, ProfileSelectActivity::class.java).apply {
                putExtra(ProfileSelectActivity.EXTRA_SELECTED, entity)
                if (excludeIds.isNotEmpty()) {
                    putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeIds)
                }
            },
        )
    }

    private fun sortNodesByCurrentLatency() {
        if (nodeList.size <= 1) return
        recomputeTiers()
        val hasTiers = tiersList.isNotEmpty()
        val sorted = nodeList.sortedWith(
            compareBy<ProxyEntity>(
                { entity -> if (hasTiers) (tierIndexById[entity.id] ?: tiersList.size) else 0 },
                { entity ->
                    when {
                        entity.status == 1 && entity.ping > 0 -> 0
                        entity.status == 2 || entity.status == 3 -> 2
                        else -> 1
                    }
                },
                { entity ->
                    if (entity.status == 1 && entity.ping > 0) entity.ping else Int.MAX_VALUE
                },
            ),
        )
        nodeList.clear()
        nodeList.addAll(sorted)
        markChangedAndRefresh()
        Toast.makeText(this, R.string.balancer_node_order_sorted_by_latency, Toast.LENGTH_SHORT).show()
    }

    private fun isProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id in excludeIdSet) return false
        if (editingId > 0L && profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.balancerBean ?: return true
            if (bean.proxies.contains(editingId)) return false
            if (bean.frontProxy == editingId || bean.landingProxy == editingId) return false
        }
        return true
    }

    private fun showCircularReferenceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.circular_reference)
            .setMessage(R.string.circular_reference_sum)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun recomputeTiers() {
        tierIndexById = if (tiersList.isNotEmpty() && nodeList.isNotEmpty()) {
            BalancerMemberResolver.assignTierIndices(nodeList, tiersList).first
        } else {
            emptyMap()
        }
    }

    private fun markChanged() {
        hasChanges = true
        publishResult(nodeList.map { it.id }.toLongArray())
    }

    private fun markChangedAndRefresh() {
        recomputeTiers()
        nodeAdapter.notifyDataSetChanged()
        updateEmptyState()
        invalidateOptionsMenu()
        markChanged()
    }

    private fun publishResult(ids: LongArray) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_NODE_IDS, ids),
        )
    }

    private fun updateEmptyState() {
        val empty = nodeList.isEmpty()
        binding.textEmpty.isVisible = empty
        binding.recyclerNodeOrder.isVisible = !empty
    }

    private fun refreshVisibleBadges() {
        val count = binding.recyclerNodeOrder.childCount
        for (i in 0 until count) {
            val child = binding.recyclerNodeOrder.getChildAt(i) ?: continue
            val holder = binding.recyclerNodeOrder.getChildViewHolder(child) as? OrderViewHolder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                holder.bindIndex(pos)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class OrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val priorityIndex: TextView = itemView.findViewById(R.id.priority_index)
        private val nodeName: TextView = itemView.findViewById(R.id.node_name)
        private val tierBadge: TextView = itemView.findViewById(R.id.tier_badge)
        private val nodeProtocol: TextView = itemView.findViewById(R.id.node_protocol)
        private val nodeGroupMeta: TextView = itemView.findViewById(R.id.node_group_meta)
        private val nodeStatus: TextView = itemView.findViewById(R.id.node_status)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_button)
        private val dragHandle: ImageView = itemView.findViewById(R.id.drag_handle)

        init {
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(this)
                }
                false
            }
            removeButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in nodeList.indices) {
                    nodeList.removeAt(pos)
                    recomputeTiers()
                    nodeAdapter.notifyItemRemoved(pos)
                    refreshVisibleBadges()
                    updateEmptyState()
                    invalidateOptionsMenu()
                    markChanged()
                }
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                val entity = nodeList.getOrNull(pos) ?: return@setOnClickListener
                launchReplaceNode(pos, entity)
            }
        }

        fun bindIndex(position: Int) {
            priorityIndex.text = (position + 1).toString()
        }

        fun bind(entity: ProxyEntity, position: Int) {
            val ctx = itemView.context
            bindIndex(position)
            nodeName.text = entity.displayName()
            nodeProtocol.text = entity.displayType()
            nodeProtocol.setTextColor(ctx.getProtocolColor(entity.type))

            if (tiersList.isNotEmpty()) {
                val tierIdx = tierIndexById[entity.id] ?: tiersList.size
                tierBadge.isVisible = true
                if (tierIdx >= tiersList.size) {
                    tierBadge.setText(R.string.balancer_tier_badge_fallback)
                    val fallbackColor = Color.parseColor("#FFA000")
                    tierBadge.setTextColor(fallbackColor)
                    tierBadge.background = createPillBackground(fallbackColor)
                } else {
                    tierBadge.text = ctx.getString(R.string.balancer_tier_badge, tierIdx + 1)
                    val primaryColor = ctx.getColorAttr(R.attr.colorPrimary)
                    tierBadge.setTextColor(primaryColor)
                    tierBadge.background = createPillBackground(primaryColor)
                }
            } else {
                tierBadge.isVisible = false
            }

            val groupName = groupNames[entity.groupId]?.takeIf { it.isNotBlank() }
            nodeGroupMeta.isVisible = groupName != null
            nodeGroupMeta.text = if (groupName != null) " · $groupName" else ""

            when (entity.status) {
                1 -> {
                    val ping = entity.ping
                    if (ping > 0) {
                        val color = when {
                            ping < 300 -> ctx.getColour(R.color.material_green_500)
                            ping < 800 -> Color.parseColor("#FFA000")
                            else -> ctx.getColour(R.color.material_red_500)
                        }
                        nodeStatus.isVisible = true
                        nodeStatus.text = "$ping ms"
                        nodeStatus.setTextColor(color)
                    } else {
                        nodeStatus.isVisible = false
                    }
                }
                2, 3 -> {
                    nodeStatus.isVisible = true
                    nodeStatus.setText(R.string.unavailable)
                    nodeStatus.setTextColor(ctx.getColour(R.color.material_red_500))
                }
                else -> {
                    nodeStatus.isVisible = false
                }
            }
        }

        private fun createPillBackground(color: Int): GradientDrawable {
            return GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(color, 28))
                cornerRadius = dp2px(6).toFloat()
            }
        }
    }

    private inner class OrderAdapter : RecyclerView.Adapter<OrderViewHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = nodeList[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                R.layout.layout_balancer_node_item,
                parent,
                false,
            )
            return OrderViewHolder(view)
        }

        override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
            holder.bind(nodeList[position], position)
        }

        override fun getItemCount(): Int = nodeList.size
    }
}
