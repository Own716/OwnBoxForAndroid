package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.RemoteException
import android.os.SystemClock
import android.text.format.Formatter
import android.util.LruCache
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.LiveStatsBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.LiveSeries
import io.nekohasekai.sagernet.bg.LiveStatsRecorder
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.TrafficStatsDb
import io.nekohasekai.sagernet.databinding.ItemTrafficStatRowBinding
import io.nekohasekai.sagernet.databinding.LayoutLiveGraphsBinding
import io.nekohasekai.sagernet.databinding.LayoutLiveLegendRowBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.widget.DailyBarChartView
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.LiveChartView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Throne-style real-time traffic & core process graphs paired with a 3-dimension
 * persistent traffic statistics table (Daily / Profile / App).
 */
class LiveGraphsFragment : ToolbarFragment(R.layout.layout_live_graphs), Toolbar.OnMenuItemClickListener {

    private class Range(val seconds: Int, val tickSeconds: Int, @IdRes val button: Int)

    private class LegendRow(val binding: LayoutLiveLegendRowBinding, val row: LiveChartView.Row)

    private enum class StatsPeriod {
        SESSION,
        TODAY,
        LAST_7D,
        LAST_30D,
        ALL,
    }

    private enum class StatsDimension {
        DAILY,
        PROFILE,
        APP,
    }

    private enum class SortField {
        COL1,
        COL2,
        TOTAL,
    }

    private class AppVisual(val label: String, val icon: Drawable?)

    private data class DisplayRow(
        val key: String,
        val title: String,
        val subtitle: String,
        val icon: Drawable?,
        val showIcon: Boolean,
        val col1Bytes: Long,
        val col2Bytes: Long,
        val totalBytes: Long,
        val primaryBarBytes: Long,
        val secondaryBarBytes: Long,
        val dimension: StatsDimension,
        val profileId: Long = 0L,
        val packageName: String = "",
        val uid: Int = -1,
        val proxyRx: Long = 0L,
        val proxyTx: Long = 0L,
        val directRx: Long = 0L,
        val directTx: Long = 0L,
    )

    private data class LoadedStats(
        val summary: TrafficStatsDb.TrafficSummary,
        val rows: List<DisplayRow>,
        val trendBars: List<DailyBarChartView.BarItem>,
    )

    private data class MutableAppAgg(
        val appKey: String,
        var uid: Int,
        var packageName: String,
        var proxyRx: Long,
        var proxyTx: Long,
        var directRx: Long,
        var directTx: Long,
    )

    private data class MutableProfileAgg(
        val profileId: Long,
        var profileName: String,
        var profileType: String,
        var rx: Long,
        var tx: Long,
    )

    companion object {
        private const val POLL_MS = 500L
        private const val STATS_REFRESH_TICKS = 10 // ~5s
        private const val STALE_MS = 3000L
        private const val COLLAPSED_ROW_LIMIT = 15

        private const val ROW_PROXY = "proxy"
        private const val ROW_DIRECT = "direct"
        private const val COLUMN_DOWN = "d"
        private const val COLUMN_UP = "u"

        private val RANGES = arrayOf(
            Range(60, 15, R.id.live_range_1m),
            Range(120, 30, R.id.live_range_2m),
            Range(300, 60, R.id.live_range_5m),
            Range(600, 120, R.id.live_range_10m),
        )
    }

    private lateinit var binding: LayoutLiveGraphsBinding
    private lateinit var rateAxis: LiveChartView.Axis
    private val percentAxis = LiveChartView.percentAxis()
    private lateinit var bytesAxis: LiveChartView.Axis
    private val countAxis = LiveChartView.countAxis()

    private val legend = ArrayList<LegendRow>()
    private val hiddenRows = LinkedHashSet<String>()
    private val hiddenColumns = LinkedHashSet<String>()
    private val rowPool = ArrayList<ItemTrafficStatRowBinding>()
    private var range = 1
    private var smooth = true
    private var showMarkers = true

    private var statsPeriod = StatsPeriod.TODAY
    private var statsDimension = StatsDimension.DAILY
    private var sortField = SortField.TOTAL
    private var sortDescending = true
    private var statsExpanded = false
    private var proxyColor = 0
    private var directColor = 0
    private var statsTickCounter = 0

    private val appCache = LruCache<String, AppVisual>(128)
    private var lastLoadedStats = LoadedStats(TrafficStatsDb.TrafficSummary(), emptyList(), emptyList())

    private val charts
        get() = listOf(
            binding.liveTrafficChart,
            binding.liveCpuChart,
            binding.liveRamChart,
            binding.liveConnectionsChart,
        )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutLiveGraphsBinding.bind(view)
        toolbar.setTitle(R.string.menu_traffic_charts)
        toolbar.inflateMenu(R.menu.live_graphs_menu)
        toolbar.setOnMenuItemClickListener(this)

        val context = requireContext()
        val primaryTextColor = context.getColorAttr(android.R.attr.textColorPrimary)
        toolbar.menu?.findItem(R.id.action_live_clear)?.icon?.mutate()?.let {
            DrawableCompat.setTint(it, primaryTextColor)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.liveScroll, ListListener)

        legend.clear()
        rowPool.clear()
        hiddenRows.clear()
        hiddenColumns.clear()
        hiddenRows.addAll(DataStore.liveGraphHiddenRows)
        val dirs = DataStore.liveGraphHiddenDirs
        if (dirs and 1 != 0) hiddenColumns.add(COLUMN_DOWN)
        if (dirs and 2 != 0) hiddenColumns.add(COLUMN_UP)
        range = DataStore.liveGraphRange.coerceIn(0, RANGES.lastIndex)
        smooth = DataStore.liveGraphSmooth
        showMarkers = DataStore.liveGraphMarkers
        toolbar.menu?.findItem(R.id.action_live_smooth)?.isChecked = smooth
        toolbar.menu?.findItem(R.id.action_live_events)?.isChecked = showMarkers

        rateAxis = LiveChartView.rateAxis(context)
        bytesAxis = LiveChartView.bytesAxis(context)
        proxyColor = LiveChartView.proxyColor(context)
        directColor = LiveChartView.directColor(context)

        val rows = listOf(
            LiveChartView.Row(
                ROW_PROXY,
                getString(R.string.live_graph_proxy),
                proxyColor,
                true,
                intArrayOf(LiveStatsBatch.PROXY_DOWN, LiveStatsBatch.PROXY_UP),
            ),
            LiveChartView.Row(
                ROW_DIRECT,
                getString(R.string.live_graph_direct),
                directColor,
                false,
                intArrayOf(LiveStatsBatch.DIRECT_DOWN, LiveStatsBatch.DIRECT_UP),
            ),
        )
        binding.liveTrafficChart.setup(
            rows,
            listOf(
                LiveChartView.Column(COLUMN_DOWN, "↓", dashed = false, wash = true),
                LiveChartView.Column(COLUMN_UP, "↑", dashed = true, wash = false),
            ),
            rateAxis,
            events = true,
        )
        val core = getString(R.string.live_graph_core)
        val coreColor = LiveChartView.coreColor(context)
        val valueCol = listOf(LiveChartView.Column("v", "", dashed = false, wash = true))
        binding.liveCpuChart.setup(
            listOf(LiveChartView.Row("core", core, coreColor, true, intArrayOf(LiveStatsBatch.CPU))),
            valueCol,
            percentAxis,
            compact = true,
        )
        binding.liveRamChart.setup(
            listOf(LiveChartView.Row("core", core, coreColor, true, intArrayOf(LiveStatsBatch.MEMORY))),
            valueCol,
            bytesAxis,
            compact = true,
        )
        binding.liveConnectionsChart.setup(
            listOf(LiveChartView.Row("core", core, coreColor, true, intArrayOf(LiveStatsBatch.CONNECTIONS))),
            valueCol,
            countAxis,
            compact = true,
        )
        for (chart in charts) chart.data = LiveHistory.series

        for (row in rows) {
            val item = LayoutLiveLegendRowBinding.inflate(layoutInflater, binding.liveLegend, true)
            item.liveLegendName.text = row.label
            item.root.setOnClickListener { toggleRow(row.key) }
            item.root.setOnLongClickListener {
                soloRow(row.key)
                true
            }
            legend.add(LegendRow(item, row))
        }
        val secondary = MaterialColors.getColor(context, android.R.attr.textColorSecondary, 0xff808080.toInt())
        binding.liveColumnDown.text = "↓ " + getString(R.string.live_graph_down)
        binding.liveColumnUp.text = "↑ " + getString(R.string.live_graph_up)
        binding.liveColumnDown.setCompoundDrawablesRelativeWithIntrinsicBounds(lineKey(secondary, false), null, null, null)
        binding.liveColumnUp.setCompoundDrawablesRelativeWithIntrinsicBounds(lineKey(secondary, true), null, null, null)
        binding.liveColumnDown.setOnClickListener { toggleColumn(COLUMN_DOWN) }
        binding.liveColumnUp.setOnClickListener { toggleColumn(COLUMN_UP) }

        binding.liveRange.check(RANGES[range].button)
        binding.liveRange.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val index = RANGES.indexOfFirst { it.button == id }
            if (index < 0 || index == range) return@addOnButtonCheckedListener
            range = index
            DataStore.liveGraphRange = index
            applyState()
        }

        binding.liveConnectionsHeader.setOnClickListener {
            if (DataStore.enableConnectionsMonitor) {
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_connections)
            }
        }

        setupStatsCard()
        applyState()
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                statsTickCounter = STATS_REFRESH_TICKS
                while (true) {
                    pull()
                    render()
                    statsTickCounter++
                    if (statsTickCounter >= STATS_REFRESH_TICKS) {
                        statsTickCounter = 0
                        refreshStatsTable(forceFlush = false)
                    }
                    delay(POLL_MS)
                }
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_live_smooth -> {
                smooth = !smooth
                item.isChecked = smooth
                DataStore.liveGraphSmooth = smooth
            }

            R.id.action_live_events -> {
                showMarkers = !showMarkers
                item.isChecked = showMarkers
                DataStore.liveGraphMarkers = showMarkers
            }

            R.id.action_live_show_all -> {
                if (hiddenRows.isEmpty() && hiddenColumns.isEmpty()) return true
                hiddenRows.clear()
                hiddenColumns.clear()
                saveHidden()
            }

            R.id.action_live_clear -> {
                LiveHistory.clear()
                for (chart in charts) chart.resetView()
                render()
            }

            else -> return false
        }
        applyState()
        return true
    }

    // ------------------------------------------------------------------------------------------------ stats card

    private fun setupStatsCard() {
        statsPeriod = StatsPeriod.values().getOrElse(DataStore.trafficStatsPeriod) { StatsPeriod.TODAY }
        statsDimension = StatsDimension.values().getOrElse(DataStore.trafficStatsDimension) { StatsDimension.DAILY }
        sortField = SortField.values().getOrElse(DataStore.trafficStatsSortField) { SortField.TOTAL }
        sortDescending = DataStore.trafficStatsSortDesc

        binding.dotSummaryProxy.background = dot(proxyColor, filled = true)
        binding.dotSummaryDirect.background = dot(directColor, filled = true)
        binding.summaryBarProxy.setBackgroundColor(proxyColor)
        binding.summaryBarDirect.setBackgroundColor(directColor)

        binding.btnStatsRefresh.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                refreshStatsTable(forceFlush = true)
            }
        }

        binding.btnStatsClear.setOnClickListener {
            confirmClearStats()
        }

        val initialPeriodId = when (statsPeriod) {
            StatsPeriod.SESSION -> R.id.chip_period_session
            StatsPeriod.TODAY -> R.id.chip_period_today
            StatsPeriod.LAST_7D -> R.id.chip_period_7d
            StatsPeriod.LAST_30D -> R.id.chip_period_30d
            StatsPeriod.ALL -> R.id.chip_period_all
        }
        binding.statsPeriodChips.check(initialPeriodId)
        binding.statsPeriodChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val nextPeriod = when (checkedId) {
                R.id.chip_period_session -> StatsPeriod.SESSION
                R.id.chip_period_today -> StatsPeriod.TODAY
                R.id.chip_period_7d -> StatsPeriod.LAST_7D
                R.id.chip_period_30d -> StatsPeriod.LAST_30D
                R.id.chip_period_all -> StatsPeriod.ALL
                else -> StatsPeriod.TODAY
            }
            if (nextPeriod != statsPeriod) {
                statsPeriod = nextPeriod
                DataStore.trafficStatsPeriod = nextPeriod.ordinal
                statsExpanded = false
                viewLifecycleOwner.lifecycleScope.launch {
                    refreshStatsTable(forceFlush = false)
                }
            }
        }

        val initialDimId = when (statsDimension) {
            StatsDimension.DAILY -> R.id.btn_dim_daily
            StatsDimension.PROFILE -> R.id.btn_dim_profile
            StatsDimension.APP -> R.id.btn_dim_app
        }
        binding.statsDimensionGroup.check(initialDimId)
        binding.statsDimensionGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val nextDim = when (checkedId) {
                R.id.btn_dim_daily -> StatsDimension.DAILY
                R.id.btn_dim_profile -> StatsDimension.PROFILE
                R.id.btn_dim_app -> StatsDimension.APP
                else -> StatsDimension.DAILY
            }
            if (nextDim != statsDimension) {
                statsDimension = nextDim
                DataStore.trafficStatsDimension = nextDim.ordinal
                statsExpanded = false
                sortField = SortField.TOTAL
                sortDescending = true
                DataStore.trafficStatsSortField = sortField.ordinal
                DataStore.trafficStatsSortDesc = sortDescending
                updateTableHeaders()
                viewLifecycleOwner.lifecycleScope.launch {
                    refreshStatsTable(forceFlush = false)
                }
            }
        }

        binding.dailyBarChart.onBarSelected = { bar ->
            val match = lastLoadedStats.rows.firstOrNull { it.key == bar.day }
            if (match != null) {
                showRowDetailDialog(match)
            } else if (bar.totalBytes > 0L) {
                showRowDetailDialog(
                    DisplayRow(
                        key = bar.day,
                        title = formatDayLabel(bar.day),
                        subtitle = "",
                        icon = null,
                        showIcon = false,
                        col1Bytes = bar.proxyBytes,
                        col2Bytes = bar.directBytes,
                        totalBytes = bar.totalBytes,
                        primaryBarBytes = bar.proxyBytes,
                        secondaryBarBytes = bar.directBytes,
                        dimension = StatsDimension.DAILY,
                        proxyRx = bar.proxyBytes,
                        directRx = bar.directBytes,
                    )
                )
            }
        }

        binding.tvTableCol1.setOnClickListener { onSortHeaderClicked(SortField.COL1) }
        binding.tvTableCol2.setOnClickListener { onSortHeaderClicked(SortField.COL2) }
        binding.tvTableColTotal.setOnClickListener { onSortHeaderClicked(SortField.TOTAL) }

        binding.btnStatsExpand.setOnClickListener {
            statsExpanded = !statsExpanded
            renderStatsRows(lastLoadedStats)
        }

        updateTableHeaders()
    }

    private fun onSortHeaderClicked(target: SortField) {
        if (sortField == target) {
            sortDescending = !sortDescending
        } else {
            sortField = target
            sortDescending = true
        }
        DataStore.trafficStatsSortField = sortField.ordinal
        DataStore.trafficStatsSortDesc = sortDescending
        updateTableHeaders()
        renderStatsRows(lastLoadedStats)
    }

    private fun updateTableHeaders() {
        val nameLabel: String
        val col1Label: String
        val col2Label: String
        when (statsDimension) {
            StatsDimension.DAILY -> {
                nameLabel = getString(R.string.traffic_stats_col_date)
                col1Label = getString(R.string.live_graph_proxy)
                col2Label = getString(R.string.live_graph_direct)
            }
            StatsDimension.PROFILE -> {
                nameLabel = getString(R.string.traffic_stats_col_profile)
                col1Label = "↓ " + getString(R.string.live_graph_down)
                col2Label = "↑ " + getString(R.string.live_graph_up)
            }
            StatsDimension.APP -> {
                nameLabel = getString(R.string.traffic_stats_col_app)
                col1Label = getString(R.string.live_graph_proxy)
                col2Label = getString(R.string.live_graph_direct)
            }
        }
        val arrow = if (sortDescending) " ▼" else " ▲"
        val totalLabel = getString(R.string.live_graph_total)
        val primaryColor = requireContext().getColorAttr(android.R.attr.textColorPrimary)
        val secondaryColor = requireContext().getColorAttr(android.R.attr.textColorSecondary)

        binding.tvTableColName.text = nameLabel
        binding.tvTableCol1.text = if (sortField == SortField.COL1) col1Label + arrow else col1Label
        binding.tvTableCol2.text = if (sortField == SortField.COL2) col2Label + arrow else col2Label
        binding.tvTableColTotal.text = if (sortField == SortField.TOTAL) totalLabel + arrow else totalLabel

        binding.tvTableCol1.setTextColor(if (sortField == SortField.COL1) primaryColor else secondaryColor)
        binding.tvTableCol2.setTextColor(if (sortField == SortField.COL2) primaryColor else secondaryColor)
        binding.tvTableColTotal.setTextColor(if (sortField == SortField.TOTAL) primaryColor else secondaryColor)
    }

    private suspend fun refreshStatsTable(forceFlush: Boolean) {
        val period = statsPeriod
        val dimension = statsDimension
        val service = (activity as? MainActivity)?.connection?.service

        val loaded = withContext(Dispatchers.IO) {
            loadStats(period, dimension, service, forceFlush)
        }
        if (!isAdded) return
        lastLoadedStats = loaded
        renderStatsSummary(loaded.summary)
        renderTrendChart(loaded.trendBars)
        renderStatsRows(loaded)
    }

    private fun loadStats(
        period: StatsPeriod,
        dimension: StatsDimension,
        service: ISagerNetService?,
        forceFlush: Boolean,
    ): LoadedStats {
        if (period == StatsPeriod.SESSION) {
            return loadSessionStats(dimension, service)
        }

        var pendingRoot: JSONObject? = null
        if (DataStore.serviceState.connected && service != null) {
            if (forceFlush) {
                runCatching { service.flushTrafficStats() }
            } else {
                val pendingRaw = runCatching { service.pendingTrafficStatsJson }.getOrNull().orEmpty()
                if (pendingRaw.isNotBlank()) {
                    pendingRoot = runCatching { JSONObject(pendingRaw) }.getOrNull()
                }
            }
        }

        val sinceDay = when (period) {
            StatsPeriod.TODAY -> TrafficStatsDb.formatDay()
            StatsPeriod.LAST_7D -> TrafficStatsDb.dayDaysAgo(6)
            StatsPeriod.LAST_30D -> TrafficStatsDb.dayDaysAgo(29)
            StatsPeriod.ALL, StatsPeriod.SESSION -> null
        }

        val pendingDay = pendingRoot?.optString("day", TrafficStatsDb.formatDay()).orEmpty()
        val includePending = pendingRoot != null && (sinceDay == null || pendingDay >= sinceDay)

        val dbSummary = TrafficStatsDb.querySummary(sinceDay)
        val sumObj = if (includePending) pendingRoot?.optJSONObject("summary") else null
        val summary = TrafficStatsDb.TrafficSummary(
            proxyRx = dbSummary.proxyRx + (sumObj?.optLong("proxyRx", 0L) ?: 0L),
            proxyTx = dbSummary.proxyTx + (sumObj?.optLong("proxyTx", 0L) ?: 0L),
            directRx = dbSummary.directRx + (sumObj?.optLong("directRx", 0L) ?: 0L),
            directTx = dbSummary.directTx + (sumObj?.optLong("directTx", 0L) ?: 0L),
        )

        var trendBars: List<DailyBarChartView.BarItem> = emptyList()
        val rows: List<DisplayRow> = when (dimension) {
            StatsDimension.DAILY -> {
                val dailyMap = LinkedHashMap<String, TrafficStatsDb.DailyRow>()
                for (r in TrafficStatsDb.queryDailyRows(sinceDay)) {
                    dailyMap[r.day] = r
                }
                if (includePending && sumObj != null && pendingDay.isNotBlank()) {
                    val pRx = sumObj.optLong("proxyRx", 0L)
                    val pTx = sumObj.optLong("proxyTx", 0L)
                    val dRx = sumObj.optLong("directRx", 0L)
                    val dTx = sumObj.optLong("directTx", 0L)
                    if (pRx + pTx + dRx + dTx > 0L) {
                        val existing = dailyMap[pendingDay]
                        dailyMap[pendingDay] = TrafficStatsDb.DailyRow(
                            day = pendingDay,
                            proxyRx = (existing?.proxyRx ?: 0L) + pRx,
                            proxyTx = (existing?.proxyTx ?: 0L) + pTx,
                            directRx = (existing?.directRx ?: 0L) + dRx,
                            directTx = (existing?.directTx ?: 0L) + dTx,
                        )
                    }
                }
                val mergedDaily = dailyMap.values.filter { it.totalAll > 0L }.sortedByDescending { it.day }
                trendBars = buildTrendBars(period, dailyMap)
                mergedDaily.map { r ->
                    DisplayRow(
                        key = r.day,
                        title = formatDayLabel(r.day),
                        subtitle = if (r.day == TrafficStatsDb.formatDay() || r.day == TrafficStatsDb.dayDaysAgo(1)) r.day else "",
                        icon = null,
                        showIcon = false,
                        col1Bytes = r.totalProxy,
                        col2Bytes = r.totalDirect,
                        totalBytes = r.totalAll,
                        primaryBarBytes = r.totalProxy,
                        secondaryBarBytes = r.totalDirect,
                        dimension = StatsDimension.DAILY,
                        proxyRx = r.proxyRx,
                        proxyTx = r.proxyTx,
                        directRx = r.directRx,
                        directTx = r.directTx,
                    )
                }
            }

            StatsDimension.PROFILE -> {
                val profileMap = LinkedHashMap<Long, MutableProfileAgg>()
                for (r in TrafficStatsDb.queryProfileRows(sinceDay)) {
                    profileMap[r.profileId] = MutableProfileAgg(
                        r.profileId, r.profileName, r.profileType, r.rx, r.tx
                    )
                }
                if (includePending) {
                    val pArr = pendingRoot?.optJSONArray("profiles")
                    if (pArr != null) {
                        for (i in 0 until pArr.length()) {
                            val o = pArr.optJSONObject(i) ?: continue
                            val id = o.optLong("profileId", 0L)
                            val rx = o.optLong("rx", 0L)
                            val tx = o.optLong("tx", 0L)
                            if (id <= 0L || rx + tx <= 0L) continue
                            val name = o.optString("profileName", "")
                            val type = o.optString("profileType", "")
                            val cur = profileMap[id]
                            if (cur == null) {
                                profileMap[id] = MutableProfileAgg(id, name, type, rx, tx)
                            } else {
                                if (name.isNotBlank()) cur.profileName = name
                                if (type.isNotBlank()) cur.profileType = type
                                cur.rx += rx
                                cur.tx += tx
                            }
                        }
                    }
                }
                profileMap.values
                    .filter { it.rx + it.tx > 0L }
                    .map { r ->
                        DisplayRow(
                            key = "profile:${r.profileId}",
                            title = r.profileName.ifBlank { getString(R.string.traffic_stats_unknown_profile) },
                            subtitle = r.profileType,
                            icon = null,
                            showIcon = false,
                            col1Bytes = r.rx,
                            col2Bytes = r.tx,
                            totalBytes = r.rx + r.tx,
                            primaryBarBytes = r.rx,
                            secondaryBarBytes = r.tx,
                            dimension = StatsDimension.PROFILE,
                            profileId = r.profileId,
                            proxyRx = r.rx,
                            proxyTx = r.tx,
                        )
                    }
            }

            StatsDimension.APP -> {
                val appMap = LinkedHashMap<String, MutableAppAgg>()
                for (r in TrafficStatsDb.queryAppRows(sinceDay)) {
                    appMap[r.appKey] = MutableAppAgg(
                        r.appKey, r.uid, r.packageName, r.proxyRx, r.proxyTx, r.directRx, r.directTx
                    )
                }
                if (includePending) {
                    val aArr = pendingRoot?.optJSONArray("apps")
                    if (aArr != null) {
                        for (i in 0 until aArr.length()) {
                            val o = aArr.optJSONObject(i) ?: continue
                            val appKey = o.optString("appKey", "")
                            if (appKey.isBlank()) continue
                            val uid = o.optInt("uid", -1)
                            val pkg = o.optString("packageName", "")
                            val pRx = o.optLong("proxyRx", 0L)
                            val pTx = o.optLong("proxyTx", 0L)
                            val dRx = o.optLong("directRx", 0L)
                            val dTx = o.optLong("directTx", 0L)
                            if (pRx + pTx + dRx + dTx <= 0L) continue
                            val cur = appMap[appKey]
                            if (cur == null) {
                                appMap[appKey] = MutableAppAgg(appKey, uid, pkg, pRx, pTx, dRx, dTx)
                            } else {
                                if (uid >= 0) cur.uid = uid
                                if (pkg.isNotBlank()) cur.packageName = pkg
                                cur.proxyRx += pRx
                                cur.proxyTx += pTx
                                cur.directRx += dRx
                                cur.directTx += dTx
                            }
                        }
                    }
                }
                appMap.values
                    .filter { it.proxyRx + it.proxyTx + it.directRx + it.directTx > 0L }
                    .map { r ->
                        val pTotal = r.proxyRx + r.proxyTx
                        val dTotal = r.directRx + r.directTx
                        val visual = resolveAppVisual(r.appKey, r.packageName, r.uid)
                        val sub = if (r.packageName.isNotBlank() && r.packageName != visual.label) {
                            r.packageName
                        } else {
                            ""
                        }
                        DisplayRow(
                            key = "app:${r.appKey}",
                            title = visual.label,
                            subtitle = sub,
                            icon = visual.icon,
                            showIcon = true,
                            col1Bytes = pTotal,
                            col2Bytes = dTotal,
                            totalBytes = pTotal + dTotal,
                            primaryBarBytes = pTotal,
                            secondaryBarBytes = dTotal,
                            dimension = StatsDimension.APP,
                            packageName = r.packageName,
                            uid = r.uid,
                            proxyRx = r.proxyRx,
                            proxyTx = r.proxyTx,
                            directRx = r.directRx,
                            directTx = r.directTx,
                        )
                    }
            }
        }
        return LoadedStats(summary, rows, trendBars)
    }

    private fun buildTrendBars(
        period: StatsPeriod,
        dailyMap: Map<String, TrafficStatsDb.DailyRow>,
    ): List<DailyBarChartView.BarItem> {
        if (period == StatsPeriod.SESSION || period == StatsPeriod.TODAY) {
            return emptyList()
        }
        val windowDays = when (period) {
            StatsPeriod.LAST_7D -> 7
            StatsPeriod.LAST_30D -> 30
            StatsPeriod.ALL -> 30
            else -> return emptyList()
        }
        val today = TrafficStatsDb.formatDay()
        if (period == StatsPeriod.ALL) {
            val recent = dailyMap.values.filter { it.totalAll > 0L }.sortedBy { it.day }.takeLast(30)
            if (recent.size < 2) return emptyList()
            return recent.map { r ->
                DailyBarChartView.BarItem(
                    day = r.day,
                    shortLabel = if (r.day == today) getString(R.string.traffic_stats_period_today) else r.day.substringAfter('-'),
                    proxyBytes = r.totalProxy,
                    directBytes = r.totalDirect,
                )
            }
        }

        var hasAnyData = false
        val list = ArrayList<DailyBarChartView.BarItem>(windowDays)
        for (ago in (windowDays - 1) downTo 0) {
            val day = TrafficStatsDb.dayDaysAgo(ago)
            val r = dailyMap[day]
            val pBytes = r?.totalProxy ?: 0L
            val dBytes = r?.totalDirect ?: 0L
            if (pBytes + dBytes > 0L) hasAnyData = true
            list.add(
                DailyBarChartView.BarItem(
                    day = day,
                    shortLabel = if (day == today) getString(R.string.traffic_stats_period_today) else day.substringAfter('-'),
                    proxyBytes = pBytes,
                    directBytes = dBytes,
                )
            )
        }
        return if (hasAnyData) list else emptyList()
    }

    private fun loadSessionStats(
        dimension: StatsDimension,
        service: ISagerNetService?,
    ): LoadedStats {
        if (!DataStore.serviceState.connected || service == null) {
            return LoadedStats(TrafficStatsDb.TrafficSummary(), emptyList(), emptyList())
        }
        val raw = runCatching { service.sessionTrafficStatsJson }.getOrNull().orEmpty()
        if (raw.isBlank()) {
            return LoadedStats(TrafficStatsDb.TrafficSummary(), emptyList(), emptyList())
        }
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return LoadedStats(TrafficStatsDb.TrafficSummary(), emptyList(), emptyList())
        val sumObj = root.optJSONObject("summary")
        val summary = TrafficStatsDb.TrafficSummary(
            proxyRx = sumObj?.optLong("proxyRx", 0L) ?: 0L,
            proxyTx = sumObj?.optLong("proxyTx", 0L) ?: 0L,
            directRx = sumObj?.optLong("directRx", 0L) ?: 0L,
            directTx = sumObj?.optLong("directTx", 0L) ?: 0L,
        )
        val sessionDay = root.optString("day", TrafficStatsDb.formatDay())

        val rows: List<DisplayRow> = when (dimension) {
            StatsDimension.DAILY -> {
                if (summary.totalAll <= 0L) emptyList()
                else listOf(
                    DisplayRow(
                        key = "session:$sessionDay",
                        title = getString(R.string.traffic_stats_period_session),
                        subtitle = sessionDay,
                        icon = null,
                        showIcon = false,
                        col1Bytes = summary.totalProxy,
                        col2Bytes = summary.totalDirect,
                        totalBytes = summary.totalAll,
                        primaryBarBytes = summary.totalProxy,
                        secondaryBarBytes = summary.totalDirect,
                        dimension = StatsDimension.DAILY,
                        proxyRx = summary.proxyRx,
                        proxyTx = summary.proxyTx,
                        directRx = summary.directRx,
                        directTx = summary.directTx,
                    )
                )
            }

            StatsDimension.PROFILE -> {
                val arr = root.optJSONArray("profiles")
                if (arr == null) emptyList() else buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val rx = o.optLong("rx", 0L)
                        val tx = o.optLong("tx", 0L)
                        if (rx + tx <= 0L) continue
                        val pid = o.optLong("profileId", 0L)
                        val name = o.optString("profileName", "")
                        add(
                            DisplayRow(
                                key = "profile:$pid",
                                title = name.ifBlank { getString(R.string.traffic_stats_unknown_profile) },
                                subtitle = o.optString("profileType", ""),
                                icon = null,
                                showIcon = false,
                                col1Bytes = rx,
                                col2Bytes = tx,
                                totalBytes = rx + tx,
                                primaryBarBytes = rx,
                                secondaryBarBytes = tx,
                                dimension = StatsDimension.PROFILE,
                                profileId = pid,
                                proxyRx = rx,
                                proxyTx = tx,
                            )
                        )
                    }
                }
            }

            StatsDimension.APP -> {
                val arr = root.optJSONArray("apps")
                if (arr == null) emptyList() else buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val pRx = o.optLong("proxyRx", 0L)
                        val pTx = o.optLong("proxyTx", 0L)
                        val dRx = o.optLong("directRx", 0L)
                        val dTx = o.optLong("directTx", 0L)
                        val pTotal = pRx + pTx
                        val dTotal = dRx + dTx
                        val all = pTotal + dTotal
                        if (all <= 0L) continue
                        val appKey = o.optString("appKey", "")
                        val pkg = o.optString("packageName", "")
                        val uid = o.optInt("uid", -1)
                        val visual = resolveAppVisual(appKey, pkg, uid)
                        add(
                            DisplayRow(
                                key = "app:$appKey",
                                title = visual.label,
                                subtitle = if (pkg.isNotBlank() && pkg != visual.label) pkg else "",
                                icon = visual.icon,
                                showIcon = true,
                                col1Bytes = pTotal,
                                col2Bytes = dTotal,
                                totalBytes = all,
                                primaryBarBytes = pTotal,
                                secondaryBarBytes = dTotal,
                                dimension = StatsDimension.APP,
                                packageName = pkg,
                                uid = uid,
                                proxyRx = pRx,
                                proxyTx = pTx,
                                directRx = dRx,
                                directTx = dTx,
                            )
                        )
                    }
                }
            }
        }
        return LoadedStats(summary, rows, emptyList())
    }

    private fun renderStatsSummary(summary: TrafficStatsDb.TrafficSummary) {
        val ctx = requireContext()
        binding.tvSummaryTotal.text = Formatter.formatFileSize(ctx, summary.totalAll)
        binding.tvSummaryProxy.text = Formatter.formatFileSize(ctx, summary.totalProxy)
        binding.tvSummaryDirect.text = Formatter.formatFileSize(ctx, summary.totalDirect)
        binding.tvSummaryProxyDetail.text =
            "↓ ${Formatter.formatShortFileSize(ctx, summary.proxyRx)}  ↑ ${Formatter.formatShortFileSize(ctx, summary.proxyTx)}"
        binding.tvSummaryDirectDetail.text =
            "↓ ${Formatter.formatShortFileSize(ctx, summary.directRx)}  ↑ ${Formatter.formatShortFileSize(ctx, summary.directTx)}"

        val total = summary.totalAll.toFloat().coerceAtLeast(1f)
        val pWeight = if (summary.totalAll > 0L) summary.totalProxy / total else 0f
        val dWeight = if (summary.totalAll > 0L) summary.totalDirect / total else 0f
        val emptyWeight = if (summary.totalAll > 0L) 0f else 1f
        setWeight(binding.summaryBarProxy, pWeight)
        setWeight(binding.summaryBarDirect, dWeight)
        setWeight(binding.summaryBarEmpty, emptyWeight)
    }

    private fun renderTrendChart(trendBars: List<DailyBarChartView.BarItem>) {
        val showTrend = statsDimension == StatsDimension.DAILY && trendBars.isNotEmpty()
        binding.dailyBarChart.isVisible = showTrend
        if (showTrend) {
            binding.dailyBarChart.setData(trendBars, proxyColor, directColor)
        }
    }

    private fun renderStatsRows(loaded: LoadedStats) {
        val sorted = when (sortField) {
            SortField.COL1 -> if (sortDescending) {
                loaded.rows.sortedByDescending { it.col1Bytes }
            } else {
                loaded.rows.sortedBy { it.col1Bytes }
            }
            SortField.COL2 -> if (sortDescending) {
                loaded.rows.sortedByDescending { it.col2Bytes }
            } else {
                loaded.rows.sortedBy { it.col2Bytes }
            }
            SortField.TOTAL -> if (sortDescending) {
                loaded.rows.sortedByDescending { it.totalBytes }
            } else {
                loaded.rows.sortedBy { it.totalBytes }
            }
        }

        if (sorted.isEmpty()) {
            for (rb in rowPool) rb.root.isVisible = false
            binding.tvStatsEmpty.isVisible = true
            binding.btnStatsExpand.isVisible = false
            return
        }
        binding.tvStatsEmpty.isVisible = false

        val visibleCount = if (statsExpanded || sorted.size <= COLLAPSED_ROW_LIMIT) {
            sorted.size
        } else {
            COLLAPSED_ROW_LIMIT
        }
        val maxTotal = sorted.maxOf { it.totalBytes }.coerceAtLeast(1L).toFloat()
        val ctx = requireContext()

        for (i in 0 until visibleCount) {
            val rowBinding = if (i < rowPool.size) {
                rowPool[i]
            } else {
                ItemTrafficStatRowBinding.inflate(layoutInflater, binding.statsTableRows, true).also {
                    rowPool.add(it)
                }
            }
            rowBinding.root.isVisible = true
            val item = sorted[i]
            if (item.showIcon) {
                rowBinding.statIcon.isVisible = true
                rowBinding.statIcon.setImageDrawable(item.icon)
            } else {
                rowBinding.statIcon.isVisible = false
            }
            rowBinding.statTitle.text = item.title
            rowBinding.statSubtitle.isVisible = item.subtitle.isNotBlank()
            rowBinding.statSubtitle.text = item.subtitle

            rowBinding.statCol1.text = formatCellBytes(ctx, item.col1Bytes)
            rowBinding.statCol2.text = formatCellBytes(ctx, item.col2Bytes)
            rowBinding.statTotal.text = formatCellBytes(ctx, item.totalBytes)

            val pWeight = item.primaryBarBytes / maxTotal
            val sWeight = item.secondaryBarBytes / maxTotal
            val restWeight = (1f - pWeight - sWeight).coerceAtLeast(0f)
            rowBinding.statBarPrimary.setBackgroundColor(proxyColor)
            rowBinding.statBarSecondary.setBackgroundColor(directColor)
            setWeight(rowBinding.statBarPrimary, pWeight)
            setWeight(rowBinding.statBarSecondary, sWeight)
            setWeight(rowBinding.statBarRest, restWeight)

            rowBinding.root.setOnClickListener { showRowDetailDialog(item) }
        }

        for (i in visibleCount until rowPool.size) {
            rowPool[i].root.isVisible = false
        }

        if (sorted.size > COLLAPSED_ROW_LIMIT) {
            binding.btnStatsExpand.isVisible = true
            binding.btnStatsExpand.text = if (statsExpanded) {
                getString(R.string.traffic_stats_collapse)
            } else {
                getString(R.string.traffic_stats_show_all_rows, sorted.size)
            }
        } else {
            binding.btnStatsExpand.isVisible = false
        }
    }

    private fun showRowDetailDialog(item: DisplayRow) {
        val ctx = requireContext()
        val message = buildString {
            if (item.subtitle.isNotBlank()) {
                append(item.subtitle).append("\n\n")
            }
            append(getString(R.string.traffic_stats_summary_total)).append(": ")
            append(Formatter.formatFileSize(ctx, item.totalBytes)).append('\n')

            when (item.dimension) {
                StatsDimension.DAILY, StatsDimension.APP -> {
                    val pTotal = item.proxyRx + item.proxyTx
                    val dTotal = item.directRx + item.directTx
                    val pct = if (item.totalBytes > 0L) pTotal * 100.0 / item.totalBytes else 0.0
                    append('\n')
                    append(getString(R.string.live_graph_proxy)).append(": ")
                    append(Formatter.formatFileSize(ctx, pTotal))
                    append(" (↓ ").append(Formatter.formatFileSize(ctx, item.proxyRx))
                    append("  ↑ ").append(Formatter.formatFileSize(ctx, item.proxyTx)).append(")\n")

                    append(getString(R.string.live_graph_direct)).append(": ")
                    append(Formatter.formatFileSize(ctx, dTotal))
                    append(" (↓ ").append(Formatter.formatFileSize(ctx, item.directRx))
                    append("  ↑ ").append(Formatter.formatFileSize(ctx, item.directTx)).append(")\n")

                    append(getString(R.string.traffic_stats_detail_share)).append(": ")
                    append(String.format(Locale.US, "%.1f%%", pct))
                }

                StatsDimension.PROFILE -> {
                    val totalProxy = lastLoadedStats.summary.totalProxy
                    val pct = if (totalProxy > 0L) item.totalBytes * 100.0 / totalProxy else 0.0
                    append("↓ ").append(getString(R.string.live_graph_down)).append(": ")
                    append(Formatter.formatFileSize(ctx, item.proxyRx)).append('\n')
                    append("↑ ").append(getString(R.string.live_graph_up)).append(": ")
                    append(Formatter.formatFileSize(ctx, item.proxyTx)).append('\n')
                    append(getString(R.string.traffic_stats_detail_share)).append(": ")
                    append(String.format(Locale.US, "%.1f%%", pct))
                }
            }
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(item.title)
            .setMessage(message)

        if (item.showIcon && item.icon != null) {
            builder.setIcon(item.icon)
        }

        when (item.dimension) {
            StatsDimension.APP -> {
                val pkgQuery = item.packageName.takeIf { it.isNotBlank() && !it.contains('/') }
                val connQuery = pkgQuery ?: if (item.uid > 0) item.uid.toString() else ""
                if (connQuery.isNotBlank() && DataStore.enableConnectionsMonitor && DataStore.serviceState.connected) {
                    builder.setPositiveButton(R.string.traffic_stats_action_connections) { _, _ ->
                        (activity as? MainActivity)?.openConnectionsWithSearch(connQuery)
                    }
                }
                if (pkgQuery != null) {
                    builder.setNeutralButton(R.string.traffic_stats_action_per_app) { _, _ ->
                        startActivity(
                            Intent(ctx, AppManagerActivity::class.java)
                                .putExtra(AppManagerActivity.EXTRA_SEARCH_QUERY, pkgQuery)
                        )
                    }
                }
                builder.setNegativeButton(android.R.string.ok, null)
            }

            StatsDimension.PROFILE -> {
                if (item.profileId > 0L) {
                    builder.setNeutralButton(R.string.traffic_stats_action_clear_node) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            val service = (activity as? MainActivity)?.connection?.service
                            withContext(Dispatchers.IO) {
                                runCatching { service?.deleteProfileTrafficStats(item.profileId) }
                                TrafficStatsDb.deleteProfileStats(item.profileId)
                            }
                            if (isAdded) {
                                refreshStatsTable(forceFlush = false)
                            }
                        }
                    }
                }
                builder.setPositiveButton(android.R.string.ok, null)
            }

            StatsDimension.DAILY -> {
                builder.setPositiveButton(android.R.string.ok, null)
            }
        }
        builder.show()
    }

    private fun confirmClearStats() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.traffic_stats_clear_title)
            .setMessage(R.string.traffic_stats_clear_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val service = (activity as? MainActivity)?.connection?.service
                    withContext(Dispatchers.IO) {
                        runCatching { service?.clearSessionTrafficStats() }
                        TrafficStatsDb.clearAll()
                    }
                    if (isAdded) {
                        refreshStatsTable(forceFlush = false)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatCellBytes(context: android.content.Context, bytes: Long): String {
        return if (bytes <= 0L) "0 B" else Formatter.formatShortFileSize(context, bytes)
    }

    private fun formatDayLabel(day: String): String {
        return when (day) {
            TrafficStatsDb.formatDay() -> getString(R.string.traffic_stats_period_today)
            TrafficStatsDb.dayDaysAgo(1) -> getString(R.string.traffic_stats_yesterday)
            else -> day
        }
    }

    private fun resolveAppVisual(appKey: String, packageName: String, uid: Int): AppVisual {
        val cacheKey = "$appKey#$uid"
        appCache.get(cacheKey)?.let { return it }

        val pm = SagerNet.application.packageManager
        val resolvedPkg = packageName.takeIf { it.isNotBlank() && !it.contains('/') }
            ?: if (uid >= 0) runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() else null

        val visual = if (!resolvedPkg.isNullOrBlank()) {
            val info = runCatching {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(resolvedPkg, 0)
            }.getOrNull()
            val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?: resolvedPkg
            val icon = info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
                ?: pm.defaultActivityIcon
            AppVisual(label, icon)
        } else {
            val fallbackLabel = when {
                appKey == "system" -> getString(R.string.traffic_stats_system_app)
                packageName.isNotBlank() -> packageName.substringAfterLast('/')
                uid == 0 -> "Root (0)"
                uid == 1000 -> "Android System (1000)"
                uid > 0 -> "UID $uid"
                else -> getString(R.string.traffic_stats_system_app)
            }
            AppVisual(fallbackLabel, pm.defaultActivityIcon)
        }

        appCache.put(cacheKey, visual)
        return visual
    }

    private fun setWeight(view: View, weight: Float) {
        val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.weight = weight.coerceAtLeast(0f)
        view.layoutParams = lp
    }

    // ------------------------------------------------------------------------------------------------ live chart state

    private fun toggleRow(key: String) {
        if (!hiddenRows.remove(key)) hiddenRows.add(key)
        saveHidden()
        applyState()
    }

    private fun soloRow(key: String) {
        val keys = legend.map { it.row.key }
        val already = keys.all { (it in hiddenRows) == (it != key) }
        for (k in keys) {
            hiddenRows.remove(k)
            if (!already && k != key) hiddenRows.add(k)
        }
        saveHidden()
        applyState()
    }

    private fun toggleColumn(key: String) {
        if (!hiddenColumns.remove(key)) hiddenColumns.add(key)
        saveHidden()
        applyState()
    }

    private fun saveHidden() {
        DataStore.liveGraphHiddenRows = hiddenRows.toSet()
        DataStore.liveGraphHiddenDirs = (if (COLUMN_DOWN in hiddenColumns) 1 else 0) or
            (if (COLUMN_UP in hiddenColumns) 2 else 0)
    }

    private fun applyState() {
        val selected = RANGES[range]
        val windowMs = selected.seconds * 1000L
        val tickMs = selected.tickSeconds * 1000L
        binding.liveTrafficChart.apply {
            this.windowMs = windowMs
            this.tickMs = tickMs
            smooth = this@LiveGraphsFragment.smooth
            showMarkers = this@LiveGraphsFragment.showMarkers
            hiddenRows = this@LiveGraphsFragment.hiddenRows.toSet()
            hiddenColumns = this@LiveGraphsFragment.hiddenColumns.toSet()
            invalidate()
        }
        for (spark in listOf(binding.liveCpuChart, binding.liveRamChart, binding.liveConnectionsChart)) {
            spark.windowMs = windowMs
            spark.smooth = smooth
            spark.invalidate()
        }
        val downOn = COLUMN_DOWN !in hiddenColumns
        val upOn = COLUMN_UP !in hiddenColumns
        for (item in legend) {
            val on = item.row.key !in hiddenRows
            item.binding.liveLegendDot.background = dot(item.row.color, on)
            item.binding.liveLegendName.alpha = if (on) 1f else 0.5f
            item.binding.liveLegendDown.alpha = if (on && downOn) 1f else 0.5f
            item.binding.liveLegendUp.alpha = if (on && upOn) 1f else 0.5f
        }
        binding.liveColumnDown.alpha = if (downOn) 1f else 0.5f
        binding.liveColumnUp.alpha = if (upOn) 1f else 0.5f
    }

    private fun dot(color: Int, filled: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (filled) {
            setColor(color)
        } else {
            setStroke(resources.displayMetrics.density.times(1.5f).toInt().coerceAtLeast(1), color)
        }
    }

    private fun lineKey(color: Int, dashed: Boolean) = GradientDrawable().apply {
        val density = resources.displayMetrics.density
        shape = GradientDrawable.LINE
        if (dashed) {
            setStroke((1.5f * density).toInt().coerceAtLeast(1), color, 4f * density, 3f * density)
        } else {
            setStroke((2f * density).toInt().coerceAtLeast(1), color)
        }
        setSize((14 * density).toInt(), (8 * density).toInt())
    }

    private suspend fun pull() {
        val service = (activity as? MainActivity)?.connection?.service ?: return
        val session = LiveHistory.session
        val serial = LiveHistory.serial
        val batch = withContext(Dispatchers.IO) {
            try {
                service.liveStats(session, serial)
            } catch (_: RemoteException) {
                null
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
        } ?: return
        LiveHistory.merge(batch)
    }

    private fun render() {
        val running = DataStore.serviceState == BaseService.State.Connected
        val markers = LiveHistory.markers
        for (chart in charts) {
            chart.running = running
            chart.markers = markers
            chart.invalidate()
        }
        val series = LiveHistory.series
        val last = if (series.size > 0 && SystemClock.elapsedRealtime() - series.latestTime <= STALE_MS) {
            series.size - 1
        } else {
            -1
        }
        fun show(view: TextView, field: Int, axis: LiveChartView.Axis) {
            val v = if (last < 0) Float.NaN else series.value(field, last)
            view.text = if (v.isNaN()) "—" else axis.format(v.toDouble())
        }
        for (item in legend) {
            show(item.binding.liveLegendDown, item.row.fields[0], rateAxis)
            show(item.binding.liveLegendUp, item.row.fields[1], rateAxis)
        }
        show(binding.liveCpuValue, LiveStatsBatch.CPU, percentAxis)
        show(binding.liveRamValue, LiveStatsBatch.MEMORY, bytesAxis)
        show(binding.liveConnectionsValue, LiveStatsBatch.CONNECTIONS, countAxis)
    }
}

/** UI-process cache of the service's live history, pulling only incremental points on revisit. */
private object LiveHistory {

    val series = LiveSeries(LiveStatsRecorder.CAPACITY, LiveStatsBatch.FIELDS)

    var markers: List<LiveChartView.Marker> = emptyList()
        private set
    var session = 0L
        private set
    var serial = 0L
        private set

    private var clearedAt = 0L

    fun merge(batch: LiveStatsBatch) {
        if (batch.session != session) {
            series.clear()
            session = batch.session
            clearedAt = 0L
        }
        serial = batch.serial
        for (i in batch.times.indices) {
            series.push(batch.times[i], batch.values, i * LiveStatsBatch.FIELDS)
        }
        markers = batch.markerTimes.indices
            .filter { batch.markerTimes[it] > clearedAt }
            .map {
                LiveChartView.Marker(
                    batch.markerTimes[it],
                    batch.markerKinds[it],
                    batch.markerTexts.getOrElse(it) { "" },
                )
            }
    }

    fun clear() {
        series.clear()
        markers = emptyList()
        clearedAt = SystemClock.elapsedRealtime()
    }
}
