package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.system.OsConstants
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.*
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

class DashboardActivity : ThemedActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var connBinding: LayoutDashboardConnectionsBinding
    private lateinit var rulesBinding: LayoutDashboardRulesBinding
    private lateinit var logsBinding: LayoutDashboardLogsBinding

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var isForeground = false
    private var isPaused = false
    private var pollingJob: Job? = null
    private var logWebSocket: WebSocket? = null

    // Preferences & Settings
    private lateinit var prefs: SharedPreferences
    private var pollIntervalMs = 1500L
    private var maxHistoryCount = 100
    private var currentLogLevel = "info"
    private var isLogPaused = false

    // App Info Caches
    data class AppDisplayInfo(val name: String, val icon: Drawable?)
    private val appInfoCache = LruCache<Int, AppDisplayInfo>(128)
    private val portToUidCache = LruCache<Int, Int>(256)

    // Data Models
    data class ConnectionModel(
        val id: String,
        val destination: String,
        val host: String,
        val network: String,
        val inbound: String,
        val upload: Long,
        val download: Long,
        val speedUp: Long,
        val speedDown: Long,
        val rule: String,
        val chains: String,
        val startTimeMs: Long,
        val closedTimeMs: Long = 0L,
        val isClosed: Boolean = false,
        val appName: String = "",
        val appIcon: Drawable? = null
    )

    data class RuleModel(
        val index: Int,
        val type: String,
        val payload: String,
        val proxy: String
    )

    data class LogModel(
        val time: String,
        val level: String,
        val message: String
    )

    // Connections State
    private val previousActiveMap = ConcurrentHashMap<String, ConnectionModel>()
    private val closedHistory = ArrayDeque<ConnectionModel>()
    private var latestActiveList = listOf<ConnectionModel>()

    enum class ConnStatusTab { ACTIVE, CLOSED, ALL }
    private var currentStatusTab = ConnStatusTab.ACTIVE

    enum class SortMode {
        TIME_DESC, TIME_ASC,
        SPEED_DESC, SPEED_ASC,
        UPLOAD_DESC, UPLOAD_ASC,
        DOWNLOAD_DESC, DOWNLOAD_ASC,
        HOST_ASC, HOST_DESC
    }
    private var currentSortMode = SortMode.TIME_DESC

    private var currentProtocolFilter = "ALL" // ALL, TCP, UDP
    private var currentOutboundFilter = "ALL" // ALL or specific outbound/proxy
    private var currentSearchQuery = ""

    // Adapters
    private lateinit var connectionAdapter: ConnectionAdapter
    private lateinit var rulesAdapter: RulesAdapter
    private lateinit var logsAdapter: LogsAdapter
    private val logsList = mutableListOf<LogModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connBinding = LayoutDashboardConnectionsBinding.bind(binding.viewConnections.root)
        rulesBinding = LayoutDashboardRulesBinding.bind(binding.viewRules.root)
        logsBinding = LayoutDashboardLogsBinding.bind(binding.viewLogs.root)

        prefs = getSharedPreferences("singbox_dashboard", Context.MODE_PRIVATE)
        pollIntervalMs = prefs.getLong("poll_interval_ms", 1500L)
        maxHistoryCount = prefs.getInt("max_history_count", 100)

        setupWindowInsets()
        setupToolbarAndControls()
        setupBottomNavigation()
        setupConnectionsTab()
        setupRulesTab()
        setupLogsTab()
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        startMonitoring()
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
        stopMonitoring()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinatorLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.dashboardBottomNav.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupToolbarAndControls() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Pause/Resume
        binding.btnPauseResume.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                binding.btnPauseResume.setImageResource(R.drawable.ic_dashboard_play)
                binding.btnPauseResume.contentDescription = getString(R.string.dashboard_resume)
                Snackbar.make(binding.root, R.string.dashboard_pause, Snackbar.LENGTH_SHORT).show()
            } else {
                binding.btnPauseResume.setImageResource(R.drawable.ic_dashboard_pause)
                binding.btnPauseResume.contentDescription = getString(R.string.dashboard_pause)
                Snackbar.make(binding.root, R.string.dashboard_resume, Snackbar.LENGTH_SHORT).show()
                fetchConnectionsManual()
            }
        }

        // Manual Refresh
        binding.btnManualRefresh.setOnClickListener {
            binding.btnManualRefresh.animate().rotationBy(360f).setDuration(500).start()
            fetchConnectionsManual()
            if (binding.viewRules.root.visibility == View.VISIBLE) {
                fetchRules()
            }
        }

        // Clear Closed
        binding.btnClearClosed.setOnClickListener {
            val count = closedHistory.size
            closedHistory.clear()
            applyFilterAndSubmit()
            Snackbar.make(
                binding.root,
                getString(R.string.dashboard_clear_closed_done) + " ($count)",
                Snackbar.LENGTH_SHORT
            ).show()
        }

        // Batch Close
        binding.btnBatchClose.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dashboard_batch_close)
                .setMessage(R.string.dashboard_batch_close_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    closeAllFilteredConnections()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Settings Dialog
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Retry connect button in error card
        connBinding.btnRetryConnect.setOnClickListener {
            startMonitoring()
        }
    }

    private fun setupBottomNavigation() {
        binding.dashboardBottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dash_connections -> {
                    binding.viewConnections.root.visibility = View.VISIBLE
                    binding.viewRules.root.visibility = View.GONE
                    binding.viewLogs.root.visibility = View.GONE
                    binding.cardTopStats.visibility = View.VISIBLE
                    true
                }
                R.id.nav_dash_rules -> {
                    binding.viewConnections.root.visibility = View.GONE
                    binding.viewRules.root.visibility = View.VISIBLE
                    binding.viewLogs.root.visibility = View.GONE
                    binding.cardTopStats.visibility = View.GONE
                    fetchRules()
                    true
                }
                R.id.nav_dash_logs -> {
                    binding.viewConnections.root.visibility = View.GONE
                    binding.viewRules.root.visibility = View.GONE
                    binding.viewLogs.root.visibility = View.VISIBLE
                    binding.cardTopStats.visibility = View.GONE
                    connectLogsWebSocket()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupConnectionsTab() {
        connectionAdapter = ConnectionAdapter { connId ->
            closeSingleConnection(connId)
        }
        connBinding.rvConnections.layoutManager = LinearLayoutManager(this)
        connBinding.rvConnections.adapter = connectionAdapter

        // Status Tabs: Active | Closed | All
        connBinding.tabLayoutStatus.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentStatusTab = when (tab?.position) {
                    0 -> ConnStatusTab.ACTIVE
                    1 -> ConnStatusTab.CLOSED
                    2 -> ConnStatusTab.ALL
                    else -> ConnStatusTab.ACTIVE
                }
                applyFilterAndSubmit()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Search text watcher
        connBinding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applyFilterAndSubmit()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Protocol Chip
        connBinding.chipProtocol.setOnClickListener {
            val options = arrayOf(
                getString(R.string.dashboard_filter_all_proto),
                "TCP",
                "UDP"
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dashboard_filter_all_proto)
                .setItems(options) { _, which ->
                    currentProtocolFilter = when (which) {
                        1 -> "TCP"
                        2 -> "UDP"
                        else -> "ALL"
                    }
                    connBinding.chipProtocol.text = options[which]
                    applyFilterAndSubmit()
                }
                .show()
        }

        // Outbound Strategy Chip
        connBinding.chipOutbound.setOnClickListener {
            val outbounds = mutableSetOf<String>()
            latestActiveList.forEach {
                if (it.chains.isNotEmpty()) outbounds.add(it.chains)
            }
            closedHistory.forEach {
                if (it.chains.isNotEmpty()) outbounds.add(it.chains)
            }
            val list = mutableListOf(getString(R.string.dashboard_filter_all_outbounds))
            list.addAll(outbounds.sorted())

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dashboard_filter_all_outbounds)
                .setItems(list.toTypedArray()) { _, which ->
                    currentOutboundFilter = if (which == 0) "ALL" else list[which]
                    connBinding.chipOutbound.text = list[which]
                    applyFilterAndSubmit()
                }
                .show()
        }

        // Sort Chip
        connBinding.chipSort.setOnClickListener {
            val sortOptions = arrayOf(
                getString(R.string.dashboard_sort_time_desc),
                getString(R.string.dashboard_sort_time_asc),
                getString(R.string.dashboard_sort_speed_desc),
                getString(R.string.dashboard_sort_speed_asc),
                getString(R.string.dashboard_sort_upload_desc),
                getString(R.string.dashboard_sort_upload_asc),
                getString(R.string.dashboard_sort_download_desc),
                getString(R.string.dashboard_sort_download_asc),
                getString(R.string.dashboard_sort_host_asc),
                getString(R.string.dashboard_sort_host_desc)
            )

            MaterialAlertDialogBuilder(this)
                .setTitle("选择排序方式")
                .setItems(sortOptions) { _, which ->
                    currentSortMode = SortMode.values()[which]
                    connBinding.chipSort.text = sortOptions[which]
                    applyFilterAndSubmit()
                }
                .show()
        }
    }

    private fun setupRulesTab() {
        rulesAdapter = RulesAdapter()
        rulesBinding.rvRules.layoutManager = LinearLayoutManager(this)
        rulesBinding.rvRules.adapter = rulesAdapter

        rulesBinding.etSearchRules.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterRules(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupLogsTab() {
        logsAdapter = LogsAdapter()
        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        logsBinding.rvLogs.layoutManager = lm
        logsBinding.rvLogs.adapter = logsAdapter

        logsBinding.chipGroupLogLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            currentLogLevel = when (checkedIds.firstOrNull()) {
                R.id.chipLogWarn -> "warning"
                R.id.chipLogError -> "error"
                R.id.chipLogDebug -> "debug"
                else -> "info"
            }
            connectLogsWebSocket()
        }

        logsBinding.btnPauseLogs.setOnClickListener {
            isLogPaused = !isLogPaused
            if (isLogPaused) {
                logsBinding.btnPauseLogs.setImageResource(R.drawable.ic_dashboard_play)
            } else {
                logsBinding.btnPauseLogs.setImageResource(R.drawable.ic_dashboard_pause)
            }
        }

        logsBinding.btnClearLogs.setOnClickListener {
            logsList.clear()
            logsAdapter.submitList(emptyList())
            logsBinding.layoutEmptyLogs.visibility = View.VISIBLE
            logsBinding.rvLogs.visibility = View.GONE
        }
    }

    private fun startMonitoring() {
        if (!DataStore.serviceState.connected) {
            connBinding.cardNotConnected.visibility = View.VISIBLE
            binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange)
            binding.tvPortStatus.text = getString(R.string.dashboard_port_disconnected) + " (VPN 未连接)"
            return
        }

        connBinding.cardNotConnected.visibility = View.GONE
        binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_green)
        binding.tvPortStatus.text = getString(R.string.dashboard_port_connected)

        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isForeground) {
                if (!isPaused) {
                    fetchConnections()
                }
                delay(pollIntervalMs)
            }
        }
    }

    private fun stopMonitoring() {
        pollingJob?.cancel()
        pollingJob = null
        logWebSocket?.cancel()
        logWebSocket = null
    }

    private fun fetchConnectionsManual() {
        lifecycleScope.launch(Dispatchers.IO) {
            fetchConnections()
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun fetchConnections() {
        try {
            val req = Request.Builder().url("http://127.0.0.1:9090/connections").build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                resp.close()
                withContext(Dispatchers.Main) {
                    binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange)
                    binding.tvPortStatus.text = getString(R.string.dashboard_port_disconnected)
                }
                return
            }

            val bodyStr = resp.body?.string() ?: return
            resp.close()

            val root = JSONObject(bodyStr)
            val uploadTotal = root.optLong("uploadTotal", 0L)
            val downloadTotal = root.optLong("downloadTotal", 0L)
            val connArray = root.optJSONArray("connections")

            val now = System.currentTimeMillis()
            val activeItems = mutableListOf<ConnectionModel>()
            var sumSpeedUp = 0L
            var sumSpeedDown = 0L

            if (connArray != null) {
                for (i in 0 until connArray.length()) {
                    val c = connArray.getJSONObject(i)
                    val id = c.optString("id")
                    val meta = c.optJSONObject("metadata")
                    val network = meta?.optString("network")?.uppercase() ?: "TCP"
                    val host = meta?.optString("destinationHost")?.takeIf { it.isNotEmpty() }
                        ?: meta?.optString("destinationIP") ?: "Unknown"
                    val destPort = meta?.optString("destinationPort") ?: ""
                    val fullDest = if (destPort.isNotEmpty()) "$host:$destPort" else host

                    val sourceIP = meta?.optString("sourceIP") ?: ""
                    val sourcePort = meta?.optString("sourcePort")?.toIntOrNull() ?: 0
                    val destIP = meta?.optString("destinationIP") ?: ""
                    val processPath = meta?.optString("processPath") ?: ""
                    val inbound = meta?.optString("inboundName")?.takeIf { it.isNotEmpty() }
                        ?: meta?.optString("type") ?: "tun"

                    val upload = c.optLong("upload", 0L)
                    val download = c.optLong("download", 0L)
                    val rule = c.optString("rule", "MATCH")
                    val chainsArr = c.optJSONArray("chains")
                    val chainsList = mutableListOf<String>()
                    if (chainsArr != null) {
                        for (j in 0 until chainsArr.length()) {
                            chainsList.add(chainsArr.getString(j))
                        }
                    }

                    // Start time
                    val startStr = c.optString("start")
                    val startTimeMs = parseIsoTime(startStr)

                    // Calculate speed
                    val prev = previousActiveMap[id]
                    val dt = if (prev != null) max((now - prev.closedTimeMs) / 1000.0, 1.0) else (pollIntervalMs / 1000.0)
                    val sUp = if (prev != null) max(0L, ((upload - prev.upload) / dt).toLong()) else 0L
                    val sDown = if (prev != null) max(0L, ((download - prev.download) / dt).toLong()) else 0L
                    sumSpeedUp += sUp
                    sumSpeedDown += sDown

                    // Resolve App Identity & Icon
                    val uid = resolveUid(network, sourceIP, sourcePort, destIP, destPort.toIntOrNull() ?: 0)
                    val appDisplay = getAppDisplayInfo(uid, processPath, destPort)

                    val connModel = ConnectionModel(
                        id = id,
                        destination = fullDest,
                        host = host,
                        network = network,
                        inbound = inbound,
                        upload = upload,
                        download = download,
                        speedUp = sUp,
                        speedDown = sDown,
                        rule = rule,
                        chains = chainsList.joinToString(" » "),
                        startTimeMs = startTimeMs,
                        closedTimeMs = now,
                        isClosed = false,
                        appName = appDisplay.name,
                        appIcon = appDisplay.icon
                    )
                    activeItems.add(connModel)
                }
            }

            // Snapshot Diffing: Find Closed Connections
            val currentActiveIds = activeItems.map { it.id }.toSet()
            for ((prevId, prevModel) in previousActiveMap) {
                if (prevId !in currentActiveIds) {
                    val closedModel = prevModel.copy(
                        isClosed = true,
                        closedTimeMs = now,
                        speedUp = 0L,
                        speedDown = 0L
                    )
                    closedHistory.addFirst(closedModel)
                }
            }

            // Evict oldest history if exceeding max bounds
            while (closedHistory.size > maxHistoryCount) {
                closedHistory.removeLast()
            }

            // Update active map for next iteration
            previousActiveMap.clear()
            for (item in activeItems) {
                previousActiveMap[item.id] = item
            }
            latestActiveList = activeItems

            withContext(Dispatchers.Main) {
                connBinding.cardNotConnected.visibility = View.GONE
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_green)
                binding.tvPortStatus.text = getString(R.string.dashboard_port_connected)

                binding.tvSpeedUp.text = "↑ " + Formatter.formatFileSize(this@DashboardActivity, sumSpeedUp) + "/s"
                binding.tvSpeedDown.text = "↓ " + Formatter.formatFileSize(this@DashboardActivity, sumSpeedDown) + "/s"
                binding.tvTotalTraffic.text = "总计: ↑ " + Formatter.formatFileSize(this@DashboardActivity, uploadTotal) +
                        " | ↓ " + Formatter.formatFileSize(this@DashboardActivity, downloadTotal)

                // Update tab titles with badges
                connBinding.tabLayoutStatus.getTabAt(0)?.text = "${getString(R.string.dashboard_tab_active)} (${activeItems.size})"
                connBinding.tabLayoutStatus.getTabAt(1)?.text = "${getString(R.string.dashboard_tab_closed)} (${closedHistory.size})"
                connBinding.tabLayoutStatus.getTabAt(2)?.text = "${getString(R.string.dashboard_tab_all)} (${activeItems.size + closedHistory.size})"

                applyFilterAndSubmit()
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_orange)
                binding.tvPortStatus.text = getString(R.string.dashboard_port_disconnected)
            }
        }
    }

    private fun applyFilterAndSubmit() {
        val baseList = when (currentStatusTab) {
            ConnStatusTab.ACTIVE -> latestActiveList
            ConnStatusTab.CLOSED -> closedHistory.toList()
            ConnStatusTab.ALL -> (latestActiveList + closedHistory.toList())
        }

        var filtered = baseList

        // Protocol filter
        if (currentProtocolFilter != "ALL") {
            filtered = filtered.filter { it.network.equals(currentProtocolFilter, ignoreCase = true) }
        }

        // Outbound filter
        if (currentOutboundFilter != "ALL" && currentOutboundFilter != getString(R.string.dashboard_filter_all_outbounds)) {
            filtered = filtered.filter { it.chains.contains(currentOutboundFilter, ignoreCase = true) }
        }

        // Search filter: Supports Text & Regex
        if (currentSearchQuery.isNotEmpty()) {
            val regex = try {
                Regex(currentSearchQuery, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                null
            }

            filtered = filtered.filter { item ->
                if (regex != null) {
                    regex.containsMatchIn(item.destination) ||
                            regex.containsMatchIn(item.appName) ||
                            regex.containsMatchIn(item.rule) ||
                            regex.containsMatchIn(item.chains)
                } else {
                    item.destination.contains(currentSearchQuery, ignoreCase = true) ||
                            item.appName.contains(currentSearchQuery, ignoreCase = true) ||
                            item.rule.contains(currentSearchQuery, ignoreCase = true) ||
                            item.chains.contains(currentSearchQuery, ignoreCase = true)
                }
            }
        }

        // Multi-dimensional Sort
        val sorted = when (currentSortMode) {
            SortMode.TIME_DESC -> filtered.sortedByDescending { it.startTimeMs }
            SortMode.TIME_ASC -> filtered.sortedBy { it.startTimeMs }
            SortMode.SPEED_DESC -> filtered.sortedByDescending { it.speedUp + it.speedDown }
            SortMode.SPEED_ASC -> filtered.sortedBy { it.speedUp + it.speedDown }
            SortMode.UPLOAD_DESC -> filtered.sortedByDescending { it.upload }
            SortMode.UPLOAD_ASC -> filtered.sortedBy { it.upload }
            SortMode.DOWNLOAD_DESC -> filtered.sortedByDescending { it.download }
            SortMode.DOWNLOAD_ASC -> filtered.sortedBy { it.download }
            SortMode.HOST_ASC -> filtered.sortedBy { it.host.lowercase() }
            SortMode.HOST_DESC -> filtered.sortedByDescending { it.host.lowercase() }
        }

        if (sorted.isEmpty()) {
            connBinding.layoutEmptyState.visibility = View.VISIBLE
            connBinding.rvConnections.visibility = View.GONE
        } else {
            connBinding.layoutEmptyState.visibility = View.GONE
            connBinding.rvConnections.visibility = View.VISIBLE
            connectionAdapter.submitList(sorted)
        }
    }

    private fun closeSingleConnection(id: String) {
        runOnDefaultDispatcher {
            try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:9090/connections/$id")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().close()
                delay(150)
                fetchConnections()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeAllFilteredConnections() {
        runOnDefaultDispatcher {
            try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:9090/connections")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().close()
                delay(200)
                fetchConnections()
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, R.string.dashboard_batch_close_done, Snackbar.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showSettingsDialog() {
        val intervals = arrayOf("1000", "1500", "2000", "3000", "5000")
        val intervalLabels = arrayOf("1 秒", "1.5 秒 (默认)", "2 秒", "3 秒", "5 秒")
        val currentIntervalIndex = intervals.indexOf(pollIntervalMs.toString()).coerceAtLeast(1)

        val historyLimits = arrayOf(50, 100, 200, 500)
        val historyLabels = arrayOf("50 条", "100 条 (默认)", "200 条", "500 条")
        val currentLimitIndex = historyLimits.indexOf(maxHistoryCount).coerceAtLeast(1)

        var selectedIntervalIndex = currentIntervalIndex
        var selectedLimitIndex = currentLimitIndex

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dashboard_settings)
            .setSingleChoiceItems(intervalLabels, currentIntervalIndex) { _, which ->
                selectedIntervalIndex = which
            }
            .setPositiveButton("下一步 (历史容量)") { _, _ ->
                pollIntervalMs = intervals[selectedIntervalIndex].toLong()
                prefs.edit().putLong("poll_interval_ms", pollIntervalMs).apply()

                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dashboard_setting_max_history)
                    .setSingleChoiceItems(historyLabels, currentLimitIndex) { _, which ->
                        selectedLimitIndex = which
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        maxHistoryCount = historyLimits[selectedLimitIndex]
                        prefs.edit().putInt("max_history_count", maxHistoryCount).apply()
                        while (closedHistory.size > maxHistoryCount) {
                            closedHistory.removeLast()
                        }
                        applyFilterAndSubmit()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Rules Tab Logic
    private var allRulesList = listOf<RuleModel>()

    private fun fetchRules() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("http://127.0.0.1:9090/rules").build()
                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    return@launch
                }
                val bodyStr = resp.body?.string() ?: return@launch
                resp.close()

                val root = JSONObject(bodyStr)
                val rulesArr = root.optJSONArray("rules")
                val parsedRules = mutableListOf<RuleModel>()
                if (rulesArr != null) {
                    for (i in 0 until rulesArr.length()) {
                        val r = rulesArr.getJSONObject(i)
                        parsedRules.add(
                            RuleModel(
                                index = i + 1,
                                type = r.optString("type"),
                                payload = r.optString("payload").takeIf { it.isNotEmpty() } ?: "(无匹配载荷)",
                                proxy = r.optString("proxy")
                            )
                        )
                    }
                }
                allRulesList = parsedRules

                withContext(Dispatchers.Main) {
                    rulesBinding.tvRulesTotal.text = getString(R.string.dashboard_rules_total, allRulesList.size)
                    filterRules(rulesBinding.etSearchRules.text?.toString()?.trim() ?: "")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun filterRules(query: String) {
        val filtered = if (query.isEmpty()) {
            allRulesList
        } else {
            allRulesList.filter {
                it.type.contains(query, ignoreCase = true) ||
                        it.payload.contains(query, ignoreCase = true) ||
                        it.proxy.contains(query, ignoreCase = true)
            }
        }

        if (filtered.isEmpty()) {
            rulesBinding.layoutEmptyRules.visibility = View.VISIBLE
            rulesBinding.rvRules.visibility = View.GONE
        } else {
            rulesBinding.layoutEmptyRules.visibility = View.GONE
            rulesBinding.rvRules.visibility = View.VISIBLE
            rulesAdapter.submitList(filtered)
        }
    }

    // Logs Tab Logic
    private fun connectLogsWebSocket() {
        logWebSocket?.cancel()
        logWebSocket = null

        val req = Request.Builder()
            .url("ws://127.0.0.1:9090/logs?level=$currentLogLevel")
            .build()

        logWebSocket = wsClient.newWebSocket(req, object : WebSocketListener() {
            private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isForeground || isLogPaused) return
                try {
                    val obj = JSONObject(text)
                    val level = obj.optString("type", "info").uppercase()
                    val payload = obj.optString("payload")
                    val timeStr = timeFormat.format(Date())

                    runOnUiThread {
                        val item = LogModel(timeStr, level, payload)
                        logsList.add(item)
                        if (logsList.size > 300) {
                            logsList.removeAt(0)
                        }
                        logsAdapter.submitList(logsList.toList()) {
                            logsBinding.rvLogs.scrollToPosition(logsList.size - 1)
                        }
                        logsBinding.layoutEmptyLogs.visibility = View.GONE
                        logsBinding.rvLogs.visibility = View.VISIBLE
                    }
                } catch (_: Exception) {
                }
            }
        })
    }

    // App Resolution
    private fun resolveUid(network: String, sourceIP: String, sourcePort: Int, destIP: String, destPort: Int): Int {
        if (sourcePort <= 0) return -1
        portToUidCache.get(sourcePort)?.let { return it }

        var resolvedUid = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null && sourceIP.isNotEmpty() && destIP.isNotEmpty() && destPort > 0) {
                    val proto = if (network.equals("UDP", ignoreCase = true)) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
                    val srcAddr = InetSocketAddress(InetAddress.getByName(sourceIP), sourcePort)
                    val dstAddr = InetSocketAddress(InetAddress.getByName(destIP), destPort)
                    val ownerUid = cm.getConnectionOwnerUid(proto, srcAddr, dstAddr)
                    if (ownerUid > 0) {
                        resolvedUid = ownerUid
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (resolvedUid <= 0) {
            resolvedUid = findUidFromProcNet(sourcePort)
        }

        if (resolvedUid > 0) {
            portToUidCache.put(sourcePort, resolvedUid)
        }
        return resolvedUid
    }

    private fun findUidFromProcNet(port: Int): Int {
        val hexPort = String.format("%04X", port)
        val procFiles = listOf("/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6")
        for (filePath in procFiles) {
            try {
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) continue
                for (line in file.readLines()) {
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size > 7) {
                        val localAddr = tokens[1]
                        if (localAddr.endsWith(":$hexPort", ignoreCase = true)) {
                            val uid = tokens[7].toIntOrNull()
                            if (uid != null && uid > 0) {
                                return uid
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return -1
    }

    private fun getAppDisplayInfo(uid: Int, processPath: String, destPort: String): AppDisplayInfo {
        if (uid > 0) {
            appInfoCache.get(uid)?.let { return it }
            try {
                val packages = packageManager.getPackagesForUid(uid)
                val pkg = packages?.firstOrNull()
                if (pkg != null) {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    val name = packageManager.getApplicationLabel(appInfo).toString()
                    val icon = packageManager.getApplicationIcon(appInfo)
                    val info = AppDisplayInfo(name, icon)
                    appInfoCache.put(uid, info)
                    return info
                }
            } catch (_: Throwable) {
            }
        }

        if (processPath.isNotBlank()) {
            val cleanPkg = processPath.substringAfterLast("/").substringBefore(" ")
            try {
                val appInfo = packageManager.getApplicationInfo(cleanPkg, 0)
                val name = packageManager.getApplicationLabel(appInfo).toString()
                val icon = packageManager.getApplicationIcon(appInfo)
                val info = AppDisplayInfo(name, icon)
                if (uid > 0) appInfoCache.put(uid, info)
                return info
            } catch (_: Throwable) {
            }
        }

        val defaultIcon = AppCompatResources.getDrawable(this, R.drawable.ic_navigation_apps)
        val defaultName = if (destPort == "53") "系统 DNS 解析" else if (uid == 0) "系统核心服务" else "网络应用"
        return AppDisplayInfo(defaultName, defaultIcon)
    }

    private fun parseIsoTime(str: String): Long {
        if (str.isEmpty()) return System.currentTimeMillis()
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(str.substringBefore("."))?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // Connection List Adapter
    class ConnectionAdapter(
        private val onClose: (String) -> Unit
    ) : ListAdapter<ConnectionModel, ConnectionAdapter.VH>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<ConnectionModel>() {
            override fun areItemsTheSame(oldItem: ConnectionModel, newItem: ConnectionModel): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ConnectionModel, newItem: ConnectionModel): Boolean {
                return oldItem == newItem
            }
        }

        inner class VH(val binding: ItemDashboardConnectionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemDashboardConnectionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(binding)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            val ctx = holder.itemView.context

            holder.binding.tvDestination.text = item.destination
            holder.binding.tvAppName.text = item.appName
            if (item.appIcon != null) {
                holder.binding.ivAppIcon.setImageDrawable(item.appIcon)
            } else {
                holder.binding.ivAppIcon.setImageResource(R.drawable.ic_navigation_apps)
            }

            // Status & Time
            if (item.isClosed) {
                val durationSec = max(1L, (item.closedTimeMs - item.startTimeMs) / 1000)
                holder.binding.tvTimeAndStatus.text = "已关闭 · ${durationSec}s"
                holder.binding.tvTimeAndStatus.setTextColor(Color.parseColor("#9CA3AF"))
                holder.binding.btnCloseSingle.visibility = View.INVISIBLE
            } else {
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    item.startTimeMs,
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                holder.binding.tvTimeAndStatus.text = "● $relativeTime"
                holder.binding.tvTimeAndStatus.setTextColor(Color.parseColor("#10B981"))
                holder.binding.btnCloseSingle.visibility = View.VISIBLE
            }

            // Inbound & Protocol
            holder.binding.tvInboundAndProto.text = "${item.inbound} | ${item.network}"

            // Traffic
            holder.binding.tvTrafficUp.text = "↑ " + Formatter.formatFileSize(ctx, item.upload)
            holder.binding.tvTrafficDown.text = "↓ " + Formatter.formatFileSize(ctx, item.download)

            // Rule & Chains
            val chainDisplay = if (item.chains.isNotEmpty()) item.chains else "直连"
            holder.binding.tvRuleAndChain.text = "${item.rule} ⊙ [$chainDisplay]"

            // Realtime Speed
            if (!item.isClosed && (item.speedUp > 0 || item.speedDown > 0)) {
                holder.binding.tvRealtimeSpeed.visibility = View.VISIBLE
                val upStr = if (item.speedUp > 0) "↑ " + Formatter.formatFileSize(ctx, item.speedUp) + "/s" else ""
                val downStr = if (item.speedDown > 0) "↓ " + Formatter.formatFileSize(ctx, item.speedDown) + "/s" else ""
                holder.binding.tvRealtimeSpeed.text = listOf(upStr, downStr).filter { it.isNotEmpty() }.joinToString("  ")
            } else {
                holder.binding.tvRealtimeSpeed.visibility = View.GONE
            }

            // Close Button
            holder.binding.btnCloseSingle.setOnClickListener {
                onClose(item.id)
            }
        }
    }

    // Rules Adapter
    class RulesAdapter : ListAdapter<RuleModel, RulesAdapter.VH>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<RuleModel>() {
            override fun areItemsTheSame(oldItem: RuleModel, newItem: RuleModel): Boolean =
                oldItem.index == newItem.index

            override fun areContentsTheSame(oldItem: RuleModel, newItem: RuleModel): Boolean =
                oldItem == newItem
        }

        inner class VH(val binding: ItemDashboardRuleBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemDashboardRuleBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.tvRuleIndex.text = "#${item.index}"
            holder.binding.tvRuleType.text = item.type
            holder.binding.tvRulePayload.text = item.payload
            holder.binding.tvRuleProxy.text = item.proxy

            val proxyLower = item.proxy.lowercase()
            when {
                proxyLower.contains("direct") -> holder.binding.tvRuleProxy.setTextColor(Color.parseColor("#3B82F6"))
                proxyLower.contains("block") || proxyLower.contains("reject") -> holder.binding.tvRuleProxy.setTextColor(Color.parseColor("#EF4444"))
                else -> holder.binding.tvRuleProxy.setTextColor(Color.parseColor("#10B981"))
            }
        }
    }

    // Logs Adapter
    class LogsAdapter : ListAdapter<LogModel, LogsAdapter.VH>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<LogModel>() {
            override fun areItemsTheSame(oldItem: LogModel, newItem: LogModel): Boolean =
                oldItem === newItem

            override fun areContentsTheSame(oldItem: LogModel, newItem: LogModel): Boolean =
                oldItem == newItem
        }

        inner class VH(val binding: ItemDashboardLogBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemDashboardLogBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.tvLogTime.text = item.time
            holder.binding.tvLogLevel.text = item.level
            holder.binding.tvLogMessage.text = item.message

            when (item.level.uppercase()) {
                "ERROR" -> holder.binding.tvLogLevel.setTextColor(Color.parseColor("#EF4444"))
                "WARN", "WARNING" -> holder.binding.tvLogLevel.setTextColor(Color.parseColor("#F59E0B"))
                "DEBUG" -> holder.binding.tvLogLevel.setTextColor(Color.parseColor("#9CA3AF"))
                else -> holder.binding.tvLogLevel.setTextColor(Color.parseColor("#3B82F6"))
            }
        }
    }
}
