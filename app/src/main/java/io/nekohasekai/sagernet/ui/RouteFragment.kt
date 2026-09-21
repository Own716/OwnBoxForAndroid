package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.format.Formatter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import moe.matsuri.nb4a.utils.ConnectionUidResolver
import io.nekohasekai.sagernet.utils.PackageCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutEmptyRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    companion object {
        val CN_APPS = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.eg.android.AlipayGphone",
            "com.taobao.taobao",
            "com.jingdong.app.mall",
            "tv.danmaku.bili",
            "com.ss.android.ugc.aweme",
            "com.netease.cloudmusic",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.xunmeng.pinduoduo",
            "com.sankuai.meituan",
            "com.zhihu.android",
            "com.sina.weibo",
            "com.coolapk.market",
        )

        val FOREIGN_APPS = setOf(
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.google.android.youtube",
            "com.twitter.android",
            "com.android.chrome",
            "com.google.android.gms",
            "com.android.vending",
            "com.discord",
            "com.whatsapp",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.openai.chatgpt",
            "com.netflix.mediaclient",
            "com.spotify.music",
        )
    }

    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        ruleListView = view.findViewById(R.id.route_list)
        updateBottomPadding()
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                ruleAdapter.remove(index)
                undoManager.remove(index to (viewHolder as RuleAdapter.RuleHolder).rule)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target is RuleAdapter.DocumentHolder) {
                    false
                } else {
                    ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    true
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        }).attachToRecyclerView(ruleListView)
    }

    fun updateBottomPadding() {
        if (!::ruleListView.isInitialized) return
        ruleListView.clipToPadding = false
        ruleListView.updatePadding(bottom = dp2px(if (DataStore.showBottomBar) 80 else 4))
    }

    data class RouteLiveStats(
        val connCount: Int = 0,
        val upRate: Long = 0L,
        val downRate: Long = 0L,
        val exitNode: String = "",
        val matched: Boolean = false
    )

    private var liveMonitorJob: Job? = null
    private val liveStatsMap = ConcurrentHashMap<Long, RouteLiveStats>()
    private val lastTrafficMap = mutableMapOf<String, Pair<Long, Long>>() // connId -> Pair(up, down)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    override fun onResume() {
        super.onResume()
        startLiveMonitor()
    }

    override fun onPause() {
        super.onPause()
        stopLiveMonitor()
    }

    private fun startLiveMonitor() {
        liveMonitorJob?.cancel()
        liveMonitorJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (DataStore.serviceState.connected) {
                    pollConnections()
                } else {
                    if (liveStatsMap.isNotEmpty()) {
                        liveStatsMap.clear()
                        withContext(Dispatchers.Main) {
                            if (::ruleAdapter.isInitialized) {
                                ruleAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    private fun stopLiveMonitor() {
        liveMonitorJob?.cancel()
        liveMonitorJob = null
        liveStatsMap.clear()
        lastTrafficMap.clear()
    }

    private suspend fun pollConnections() {
        try {
            val req = Request.Builder().url("http://127.0.0.1:9090/connections").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val json = JSONObject(body)
                val connectionsArr = json.optJSONArray("connections") ?: return

                if (!::ruleAdapter.isInitialized) return
                val currentRules = ruleAdapter.ruleList.toList()
                val newStats = mutableMapOf<Long, RouteLiveStats>()

                class Accumulator(
                    var count: Int = 0,
                    var upRate: Long = 0L,
                    var downRate: Long = 0L,
                    val exits: MutableList<String> = mutableListOf()
                )
                val accumulators = mutableMapOf<Long, Accumulator>()

                val rulePackageUids: Map<Long, Set<Int>> = currentRules.associate { r ->
                    r.id to r.packages.mapNotNull { pkg -> PackageCache[pkg] }.toSet()
                }

                val currentConnIds = mutableSetOf<String>()

                for (i in 0 until connectionsArr.length()) {
                    val conn = connectionsArr.optJSONObject(i) ?: continue
                    val connId = conn.optString("id")
                    currentConnIds.add(connId)
                    val meta = conn.optJSONObject("metadata")
                    val network = meta?.optString("network") ?: "TCP"
                    val host = meta?.optString("destinationHost") ?: ""
                    val destIP = meta?.optString("destinationIP") ?: ""
                    val destPort = meta?.optString("destinationPort")?.toIntOrNull() ?: 0
                    val sourceIP = meta?.optString("sourceIP") ?: ""
                    val sourcePort = meta?.optString("sourcePort")?.toIntOrNull() ?: 0
                    val upload = conn.optLong("upload", 0L)
                    val download = conn.optLong("download", 0L)

                    val last = lastTrafficMap[connId]
                    val upRate = if (last != null) (upload - last.first).coerceAtLeast(0L) / 2 else 0L
                    val downRate = if (last != null) (download - last.second).coerceAtLeast(0L) / 2 else 0L
                    lastTrafficMap[connId] = Pair(upload, download)

                    val chainsArr = conn.optJSONArray("chains")
                    val chainsList = mutableListOf<String>()
                    if (chainsArr != null) {
                        for (j in 0 until chainsArr.length()) {
                            chainsList.add(chainsArr.getString(j))
                        }
                    }

                    val exit = when {
                        chainsList.isEmpty() -> "直连"
                        chainsList.size == 1 -> chainsList[0]
                        else -> chainsList.joinToString(" → ")
                    }

                    val connUid = if (context != null) {
                        ConnectionUidResolver.resolveUid(requireContext(), network, sourceIP, sourcePort, destIP, destPort)
                    } else -1

                    for (rule in currentRules) {
                        if (!rule.enabled) continue
                        var matched = false
                        val uids = rulePackageUids[rule.id]
                        if (connUid > 0 && uids != null && uids.contains(connUid)) {
                            matched = true
                        } else if (host.isNotEmpty() && rule.domains.isNotBlank()) {
                            val dl = rule.domains.split("\n", ",")
                            if (dl.any { d -> host.equals(d.trim(), ignoreCase = true) || host.endsWith("." + d.trim().removePrefix("domain:").removePrefix("full:"), ignoreCase = true) }) {
                                matched = true
                            }
                        } else if (destIP.isNotEmpty() && rule.ip.isNotBlank()) {
                            val il = rule.ip.split("\n", ",")
                            if (il.any { ip -> destIP.startsWith(ip.trim().split("/")[0]) }) {
                                matched = true
                            }
                        }

                        if (matched) {
                            val acc = accumulators.getOrPut(rule.id) { Accumulator() }
                            acc.count++
                            acc.upRate += upRate
                            acc.downRate += downRate
                            if (!acc.exits.contains(exit)) {
                                acc.exits.add(exit)
                            }
                            break
                        }
                    }
                }

                lastTrafficMap.keys.retainAll(currentConnIds)

                for (rule in currentRules) {
                    val acc = accumulators[rule.id]
                    if (acc != null && acc.count > 0) {
                        newStats[rule.id] = RouteLiveStats(
                            connCount = acc.count,
                            upRate = acc.upRate,
                            downRate = acc.downRate,
                            exitNode = acc.exits.joinToString(", "),
                            matched = true
                        )
                    } else {
                        newStats[rule.id] = RouteLiveStats(
                            connCount = 0,
                            matched = false
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    liveStatsMap.clear()
                    liveStatsMap.putAll(newStats)
                    ruleAdapter.notifyDataSetChanged()
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        stopLiveMonitor()
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            SagerDatabase.rulesDao.reset()
                            DataStore.rulesFirstCreate = false
                            ruleAdapter.reload()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
            R.id.action_manage_assets -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
            R.id.action_preset_routes -> {
                val presets = arrayOf(
                    getString(R.string.preset_bypass_cn_apps),
                    getString(R.string.preset_proxy_foreign_apps),
                    getString(R.string.route_opt_block_ads)
                )
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.route_preset_title)
                    .setItems(presets) { _, which ->
                        runOnDefaultDispatcher {
                            when (which) {
                                0 -> {
                                    val rule = RuleEntity(
                                        name = getString(R.string.preset_bypass_cn_apps),
                                        packages = CN_APPS,
                                        outbound = -1L,
                                        enabled = true
                                    )
                                    ProfileManager.createRule(rule)
                                }
                                1 -> {
                                    val rule = RuleEntity(
                                        name = getString(R.string.preset_proxy_foreign_apps),
                                        packages = FOREIGN_APPS,
                                        outbound = 0L,
                                        enabled = true
                                    )
                                    ProfileManager.createRule(rule)
                                }
                                2 -> {
                                    val rule = RuleEntity(
                                        name = getString(R.string.route_opt_block_ads),
                                        domains = "geosite:category-ads-all",
                                        outbound = -2L,
                                        enabled = true
                                    )
                                    ProfileManager.createRule(rule)
                                }
                            }
                            onMainDispatcher {
                                snackbar(R.string.preset_applied_toast).show()
                                ruleAdapter.reload()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return true
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        suspend fun reload() {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                ruleList.clear()
                ruleList.addAll(rules)
                ruleAdapter.notifyDataSetChanged()
            }
        }

        init {
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                DocumentHolder(LayoutEmptyRouteBinding.inflate(layoutInflater, parent, false))
            } else {
                RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return 0
            return 1
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DocumentHolder) {
                holder.bind()
            } else if (holder is RuleHolder) {
                holder.bind(ruleList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return ruleList.size + 1
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return 0L
            return ruleList[position - 1].id
        }

        private val updated = HashSet<RuleEntity>()
        fun move(from: Int, to: Int) {
            val first = ruleList[from - 1]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from - 1 until to - 1) else Pair(-1, to downTo from - 1)
            for (i in range) {
                val next = ruleList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                ruleList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            ruleList[to - 1] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            if (updated.isNotEmpty()) {
                SagerDatabase.rulesDao.updateRules(updated.toList())
                updated.clear()
                needReload()
            }
        }

        fun remove(index: Int) {
            ruleList.removeAt(index - 1)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((index, item) in actions) {
                ruleList.add(index - 1, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            runOnDefaultDispatcher {
                ProfileManager.deleteRules(rules)
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            ruleListView.post {
                ruleList.add(rule)
                ruleAdapter.notifyItemInserted(ruleList.size)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            val index = ruleList.indexOfFirst { it.id == rule.id }
            if (index == -1) return
            ruleListView.post {
                ruleList[index] = rule
                ruleAdapter.notifyItemChanged(index + 1)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            val index = ruleList.indexOfFirst { it.id == ruleId }
            if (index == -1) {
                onMainDispatcher {
                    needReload()
                }
            } else ruleListView.post {
                ruleList.removeAt(index)
                ruleAdapter.notifyItemRemoved(index + 1)
                needReload()
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                ruleList.clear()
                ruleAdapter.notifyDataSetChanged()
                needReload()
            }
        }

        inner class DocumentHolder(binding: LayoutEmptyRouteBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind() {
                itemView.setOnClickListener {
                    it.context.launchCustomTab("https://t.me/OwnBoxs")
                }
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
            val editButton = binding.edit
            val shareLayout = binding.share
            val enableSwitch = binding.enable
            val liveStatus = binding.routeLiveStatus

            fun bind(ruleEntity: RuleEntity) {
                rule = ruleEntity
                profileName.text = rule.displayName()
                profileType.text = rule.mkSummary()
                routeOutbound.text = rule.displayOutbound()

                // 根据路由类型设置文字颜色
                val colorRes = when (rule.outbound) {
                    -2L -> R.color.color_route_block   // 屏蔽：红色
                    -1L -> R.color.color_route_direct  // 直连：绿色
                    0L -> R.color.color_route_proxy    // 代理：蓝色
                    else -> R.color.color_route_config // 配置：紫色
                }
                routeOutbound.setTextColor(ContextCompat.getColor(itemView.context, colorRes))

                // 实际路由出口状态展示
                if (!DataStore.serviceState.connected) {
                    liveStatus.visibility = View.GONE
                } else {
                    val stats = liveStatsMap[rule.id]
                    liveStatus.visibility = View.VISIBLE
                    if (stats == null || stats.connCount == 0) {
                        liveStatus.text = "暂无活动连接"
                        liveStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.material_grey_500))
                    } else {
                        val upRateStr = Formatter.formatFileSize(itemView.context, stats.upRate) + "/s"
                        val downRateStr = Formatter.formatFileSize(itemView.context, stats.downRate) + "/s"
                        val rateText = if (stats.upRate > 0 || stats.downRate > 0) " | ↑ $upRateStr | ↓ $downRateStr" else ""
                        liveStatus.text = "实际出口: ${stats.exitNode} (${stats.connCount}条连接$rateText)"
                        liveStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.color_route_direct))
                    }
                }
                liveStatus.setOnClickListener {
                    startActivity(Intent(it.context, TrafficChartActivity::class.java))
                }

                itemView.setOnClickListener(null)
                itemView.isClickable = false
                itemView.isFocusable = false
                enableSwitch.setOnCheckedChangeListener(null)
                enableSwitch.isChecked = rule.enabled
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    runOnDefaultDispatcher {
                        rule.enabled = isChecked
                        SagerDatabase.rulesDao.updateRule(rule)
                        onMainDispatcher {
                            needReload()
                        }
                    }
                }
                editButton.setOnClickListener {
                    startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
                    })
                }
            }
        }

    }

}
