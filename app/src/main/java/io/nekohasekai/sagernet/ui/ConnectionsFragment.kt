package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections) {

    enum class SortField(val labelRes: Int) {
        TIME(R.string.connections_sort_time),
        DOWNLOAD_SPEED(R.string.connections_sort_download_speed),
        UPLOAD_SPEED(R.string.connections_sort_upload_speed),
        DOWNLOAD(R.string.connections_sort_download),
        UPLOAD(R.string.connections_sort_upload),
        HOST(R.string.connections_sort_host),
    }

    data class ConnectionItem(
        val id: String,
        val active: Boolean,
        val appName: String,
        val packageName: String,
        val processPath: String,
        val uid: Int,
        val icon: Drawable?,
        val network: String,
        val protocol: String,
        val inbound: String,
        val inboundType: String,
        val sourceIp: String,
        val sourcePort: Int,
        val host: String,
        val rawHost: String,
        val port: Int,
        val destinationIp: String,
        val ipVersion: String,
        val rule: String,
        val upload: Long,
        val download: Long,
        val uploadSpeed: Long,
        val downloadSpeed: Long,
        val chainsList: List<String>,
        val chainsDisplay: String,
        val outbound: String,
        val outboundType: String,
        val startMs: Long,
        val closedMs: Long,
        val rawJson: String,
    )

    companion object {
        const val ARG_INITIAL_SEARCH = "initial_search"
        private const val FILTER_ALL = "ALL"
        private const val FILTER_PROXY = "PROXY"
        private const val FILTER_DIRECT = "DIRECT"
        private const val FILTER_TCP = "TCP"
        private const val FILTER_UDP = "UDP"
        private const val FILTER_OUTBOUND_PREFIX = "OUTBOUND:"

        private val COLOR_GREEN = Color.parseColor("#10B981")
        private val COLOR_BLUE = Color.parseColor("#3B82F6")

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ConnectionItem>() {
            override fun areItemsTheSame(oldItem: ConnectionItem, newItem: ConnectionItem): Boolean {
                return oldItem.id == newItem.id && oldItem.active == newItem.active
            }

            override fun areContentsTheSame(oldItem: ConnectionItem, newItem: ConnectionItem): Boolean {
                return oldItem.appName == newItem.appName &&
                    oldItem.network == newItem.network &&
                    oldItem.protocol == newItem.protocol &&
                    oldItem.inboundType == newItem.inboundType &&
                    oldItem.host == newItem.host &&
                    oldItem.destinationIp == newItem.destinationIp &&
                    oldItem.rule == newItem.rule &&
                    oldItem.upload == newItem.upload &&
                    oldItem.download == newItem.download &&
                    oldItem.uploadSpeed == newItem.uploadSpeed &&
                    oldItem.downloadSpeed == newItem.downloadSpeed &&
                    oldItem.chainsDisplay == newItem.chainsDisplay &&
                    oldItem.outbound == newItem.outbound &&
                    oldItem.closedMs == newItem.closedMs
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private lateinit var btnTabActive: Chip
    private lateinit var btnTabClosed: Chip
    private lateinit var btnPauseResume: ImageView
    private lateinit var btnCloseAll: MaterialButton
    private lateinit var tvTotalTraffic: TextView
    private lateinit var tvPausedBadge: TextView
    private lateinit var tvTotalSpeed: TextView
    private lateinit var tvSortSelector: TextView
    private lateinit var btnSortOrder: ImageView
    private lateinit var etSearch: EditText
    private lateinit var btnRegexToggle: TextView
    private lateinit var btnClearSearch: ImageView
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var recyclerConnections: RecyclerView
    private lateinit var tvEmpty: TextView

    private val adapter = ConnectionsAdapter()
    private val appInfoCache = ConcurrentHashMap<String, Pair<String, Drawable?>>()

    @Volatile
    private var prevTrafficSnapshot: Map<String, Triple<Long, Long, Long>> = emptyMap()

    private var activeConnections = listOf<ConnectionItem>()
    private var closedConnections = listOf<ConnectionItem>()
    private var currentFiltered = listOf<ConnectionItem>()
    private var totalUpload = 0L
    private var totalDownload = 0L
    private var totalUploadSpeed = 0L
    private var totalDownloadSpeed = 0L

    private var pollJob: Job? = null
    private var showClosed = false
    private var isPaused = false
    private var sortField = SortField.TIME
    private var sortDescending = true
    private var filterKeyword = ""
    private var isRegexEnabled = false
    private var selectedFilterKey = FILTER_ALL
    private var currentFilterChipKeys = listOf<Pair<String, String>>()

    private var openDetailDialog: BottomSheetDialog? = null
    private var openDetailId: String? = null
    private var openDetailUpdater: ((ConnectionItem) -> Unit)? = null

    private fun viewScope() = viewLifecycleOwnerLiveData.value?.lifecycleScope

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isToolbarInitialized) {
            toolbar.setTitle(R.string.connections_title)
        }

        btnTabActive = view.findViewById(R.id.btn_tab_active)
        btnTabClosed = view.findViewById(R.id.btn_tab_closed)
        btnPauseResume = view.findViewById(R.id.btn_pause_resume)
        btnCloseAll = view.findViewById(R.id.btn_close_all)
        tvTotalTraffic = view.findViewById(R.id.tv_total_traffic)
        tvPausedBadge = view.findViewById(R.id.tv_paused_badge)
        tvTotalSpeed = view.findViewById(R.id.tv_total_speed)
        tvSortSelector = view.findViewById(R.id.tv_sort_selector)
        btnSortOrder = view.findViewById(R.id.btn_sort_order)
        etSearch = view.findViewById(R.id.et_search)
        btnRegexToggle = view.findViewById(R.id.btn_regex_toggle)
        btnClearSearch = view.findViewById(R.id.btn_clear_search)
        chipGroupFilter = view.findViewById(R.id.chip_group_filter)
        recyclerConnections = view.findViewById(R.id.recycler_connections)
        tvEmpty = view.findViewById(R.id.tv_empty)

        recyclerConnections.layoutManager = LinearLayoutManager(requireContext())
        recyclerConnections.adapter = adapter
        recyclerConnections.itemAnimator = null
        ViewCompat.setOnApplyWindowInsetsListener(recyclerConnections, ListListener)

        btnTabActive.text = getString(R.string.connections_tab_active, 0)
        btnTabClosed.text = getString(R.string.connections_tab_closed, 0)
        tvSortSelector.setText(sortField.labelRes)
        updateSortOrderIcon()
        updatePauseUi()
        updateRegexButtonUi()

        btnTabActive.setOnClickListener {
            if (showClosed) {
                showClosed = false
                updateTabActionUi()
                applyFilter()
            }
        }
        btnTabClosed.setOnClickListener {
            if (!showClosed) {
                showClosed = true
                updateTabActionUi()
                applyFilter()
            }
        }

        btnPauseResume.setOnClickListener {
            isPaused = !isPaused
            updatePauseUi()
            if (isPaused) {
                stopPolling()
            } else {
                startPolling()
            }
        }

        btnCloseAll.setOnClickListener {
            handleBulkAction()
        }

        tvSortSelector.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            SortField.values().forEachIndexed { idx, field ->
                popup.menu.add(0, idx, idx, field.labelRes)
            }
            popup.setOnMenuItemClickListener { menuItem ->
                val chosen = SortField.values().getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener false
                sortField = chosen
                tvSortSelector.setText(chosen.labelRes)
                applyFilter()
                true
            }
            popup.show()
        }

        btnSortOrder.setOnClickListener {
            sortDescending = !sortDescending
            updateSortOrderIcon()
            applyFilter()
        }

        btnRegexToggle.setOnClickListener {
            isRegexEnabled = !isRegexEnabled
            updateRegexButtonUi()
            applyFilter()
        }

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterKeyword = s?.toString()?.trim().orEmpty()
                btnClearSearch.isVisible = filterKeyword.isNotEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        rebuildFilterChipsIfNeeded()
        updateTabActionUi()
        arguments?.getString(ARG_INITIAL_SEARCH)?.takeIf { it.isNotBlank() }?.let { initialQuery ->
            etSearch.setText(initialQuery)
            arguments?.remove(ARG_INITIAL_SEARCH)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isPaused) {
            startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroyView() {
        runCatching { openDetailDialog?.dismiss() }
        openDetailDialog = null
        openDetailId = null
        openDetailUpdater = null
        stopPolling()
        super.onDestroyView()
    }

    private fun currentService(): ISagerNetService? =
        (activity as? MainActivity)?.connection?.service

    private fun buildClashRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        val secret = DataStore.clashApiSecret
        if (secret.isNotBlank()) {
            builder.header("Authorization", "Bearer $secret")
        }
        return builder
    }

    private fun startPolling() {
        stopPolling()
        if (!DataStore.enableConnectionsMonitor || isPaused) return
        val scope = viewScope() ?: return
        pollJob = scope.launch {
            while (isActive && DataStore.enableConnectionsMonitor && !isPaused) {
                fetchConnections()
                delay(1500)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun updatePauseUi() {
        btnPauseResume.setImageResource(
            if (isPaused) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24
        )
        btnPauseResume.contentDescription = getString(
            if (isPaused) R.string.connections_resume else R.string.connections_pause
        )
        tvPausedBadge.isVisible = isPaused
    }

    private fun updateSortOrderIcon() {
        btnSortOrder.setImageResource(
            if (sortDescending) R.drawable.ic_baseline_keyboard_arrow_down_24
            else R.drawable.ic_baseline_keyboard_arrow_up_24
        )
    }

    private fun resolvePrimaryAttrColor(): Int {
        val ctx = context ?: return COLOR_BLUE
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) {
            tv.data
        } else {
            COLOR_BLUE
        }
    }

    private fun resolveSecondaryTextColor(): Int {
        val ctx = context ?: return Color.GRAY
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)) {
            tv.data
        } else {
            Color.GRAY
        }
    }

    private fun updateRegexButtonUi() {
        btnRegexToggle.setTextColor(
            if (isRegexEnabled) resolvePrimaryAttrColor() else resolveSecondaryTextColor()
        )
        btnRegexToggle.alpha = if (isRegexEnabled) 1.0f else 0.7f
    }

    private fun updateTabActionUi() {
        btnTabActive.text = getString(R.string.connections_tab_active, activeConnections.size)
        btnTabClosed.text = getString(R.string.connections_tab_closed, closedConnections.size)
        if (showClosed) {
            btnTabClosed.isChecked = true
            btnCloseAll.setText(R.string.connections_clear_history)
            btnCloseAll.isEnabled = closedConnections.isNotEmpty()
        } else {
            btnTabActive.isChecked = true
            btnCloseAll.setText(R.string.connections_close_all)
            btnCloseAll.isEnabled = activeConnections.isNotEmpty()
        }
    }

    private fun handleBulkAction() {
        val ctx = context ?: return
        val service = currentService()
        if (showClosed) {
            if (closedConnections.isEmpty()) return
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.connections_clear_history)
                .setMessage("确定要清空当前已记录的 ${closedConnections.size} 条历史连接吗？")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewScope()?.launch(Dispatchers.IO) {
                        runCatching { service?.clearClosedConnections() }
                        withContext(Dispatchers.Main) {
                            closedConnections = emptyList()
                            updateTabActionUi()
                            applyFilter()
                        }
                        fetchConnections()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val allActive = activeConnections
        if (allActive.isEmpty()) return
        val filteredIds = currentFiltered.filter { it.active }.map { it.id }

        if (filteredIds.isNotEmpty() && filteredIds.size < allActive.size) {
            val options = arrayOf(
                "仅断开当前筛选的 ${filteredIds.size} 条连接",
                "断开全部 ${allActive.size} 条活跃连接",
            )
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.connections_close_all)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> closeConnectionsByIds(filteredIds)
                        1 -> closeAllActive(service)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.connections_close_all)
                .setMessage("确定要断开当前全部 ${allActive.size} 条活跃连接吗？")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    closeAllActive(service)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun closeAllActive(service: ISagerNetService?) {
        viewScope()?.launch(Dispatchers.IO) {
            if (service != null) {
                runCatching { service.closeAllActiveConnections() }
            } else if (DataStore.enableClashAPI || DataStore.allowAccess) {
                runCatching {
                    val req = buildClashRequest("http://127.0.0.1:9090/connections")
                        .delete()
                        .build()
                    httpClient.newCall(req).execute().close()
                }
            }
            fetchConnections()
        }
    }

    private fun closeConnectionsByIds(ids: List<String>) {
        val service = currentService()
        viewScope()?.launch(Dispatchers.IO) {
            for (id in ids) {
                closeSingleInternal(service, id)
            }
            fetchConnections()
        }
    }

    private fun closeSingleConnection(id: String) {
        val service = currentService()
        viewScope()?.launch(Dispatchers.IO) {
            closeSingleInternal(service, id)
            fetchConnections()
        }
    }

    private fun closeSingleInternal(service: ISagerNetService?, id: String) {
        if (service != null) {
            runCatching { service.closeActiveConnection(id) }
        } else if (DataStore.enableClashAPI || DataStore.allowAccess) {
            runCatching {
                val req = buildClashRequest("http://127.0.0.1:9090/connections/$id")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().close()
            }
        }
    }

    private fun resolveAppDisplay(
        pm: PackageManager,
        rawPackageName: String,
        uid: Int,
    ): Triple<String, String, Drawable?> {
        var pkg = rawPackageName.trim()
        if (pkg.isEmpty() && uid >= 0) {
            pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull().orEmpty()
        }
        if (pkg.isEmpty()) {
            val fallbackName = if (uid >= 0) "UID $uid" else "系统 / 本机内核"
            return Triple(fallbackName, "", null)
        }
        appInfoCache[pkg]?.let { cached ->
            return Triple(cached.first, pkg, cached.second)
        }
        val label = runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
        val icon = runCatching {
            pm.getApplicationIcon(pkg)
        }.getOrNull()
        appInfoCache[pkg] = label to icon
        return Triple(label, pkg, icon)
    }

    private fun parseChainsList(arr: JSONArray?, reverse: Boolean, fallback: String): List<String> {
        val list = ArrayList<String>()
        if (arr != null && arr.length() > 0) {
            if (reverse) {
                for (i in arr.length() - 1 downTo 0) {
                    val tag = arr.optString(i).trim()
                    if (tag.isNotEmpty()) list.add(tag)
                }
            } else {
                for (i in 0 until arr.length()) {
                    val tag = arr.optString(i).trim()
                    if (tag.isNotEmpty()) list.add(tag)
                }
            }
        }
        if (list.isEmpty()) {
            val fb = fallback.takeIf { it.isNotBlank() } ?: "direct"
            list.add(fb)
        }
        return list
    }

    private fun stripProcessSuffix(rawPath: String): Pair<String, Int> {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return "" to -1
        val idx = trimmed.lastIndexOf(" (")
        if (idx > 0 && trimmed.endsWith(")")) {
            val head = trimmed.substring(0, idx).trim()
            val inside = trimmed.substring(idx + 2, trimmed.length - 1).trim()
            val uid = inside.toIntOrNull() ?: -1
            return (if (head.all { it.isDigit() }) "" else head) to uid
        }
        val numericUid = trimmed.toIntOrNull()
        return if (numericUid != null) "" to numericUid else trimmed to -1
    }

    private fun parseIsoTimeMs(iso: String): Long {
        if (iso.isBlank()) return 0L
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
    }

    private fun parseNativeItems(
        arr: JSONArray?,
        defaultActive: Boolean,
        nowMs: Long,
        pm: PackageManager,
        prevTrafficMap: Map<String, Triple<Long, Long, Long>>,
        nextTrafficMap: MutableMap<String, Triple<Long, Long, Long>>,
    ): List<ConnectionItem> {
        if (arr == null || arr.length() == 0) return emptyList()
        val result = ArrayList<ConnectionItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val isActive = obj.optBoolean("active", defaultActive)
            val network = obj.optString("network").ifBlank { "TCP" }.uppercase()
            val protocol = obj.optString("protocol").trim()
            val rawHost = obj.optString("host").ifBlank { "未知目标" }
            val port = obj.optInt("port", 0)
            val fullHost = if (port > 0) {
                if (rawHost.contains(":") && !rawHost.startsWith("[")) "[$rawHost]:$port" else "$rawHost:$port"
            } else {
                rawHost
            }
            val inbound = obj.optString("inbound").trim()
            val inboundType = obj.optString("inboundType").trim().ifBlank { inbound.ifBlank { "tun" } }
            val sourceIp = obj.optString("sourceIP").trim()
            val sourcePort = obj.optInt("sourcePort", 0)
            val destIp = obj.optString("destinationIP").trim()
            val ipVersion = obj.optString("ipVersion").trim().ifBlank {
                when {
                    destIp.contains(":") -> "IPv6"
                    destIp.isNotBlank() -> "IPv4"
                    else -> ""
                }
            }
            val rawPkg = obj.optString("packageName", "")
            val uid = obj.optInt("uid", -1)
            val (appName, pkg, icon) = resolveAppDisplay(pm, rawPkg, uid)
            val processPath = obj.optString("processPath").trim().ifBlank {
                when {
                    pkg.isNotBlank() && uid >= 0 -> "$pkg ($uid)"
                    pkg.isNotBlank() -> pkg
                    uid >= 0 -> uid.toString()
                    else -> ""
                }
            }

            val rawRule = obj.optString("rule").trim().ifBlank { "final" }
            val outbound = obj.optString("outbound").trim()
            val outboundType = obj.optString("outboundType").trim()
            val chainsList = parseChainsList(obj.optJSONArray("chains"), reverse = false, fallback = outbound)
            val chainsDisplay = chainsList.joinToString(" » ")
            val up = obj.optLong("upload", 0L)
            val down = obj.optLong("download", 0L)

            var upSpeed = 0L
            var downSpeed = 0L
            if (isActive) {
                val prev = prevTrafficMap[id]
                if (prev != null && nowMs > prev.third) {
                    val dt = (nowMs - prev.third).coerceAtLeast(250L)
                    upSpeed = ((up - prev.first).coerceAtLeast(0L) * 1000L) / dt
                    downSpeed = ((down - prev.second).coerceAtLeast(0L) * 1000L) / dt
                }
                nextTrafficMap[id] = Triple(up, down, nowMs)
            }

            val startMs = obj.optLong("startMs", 0L)
            val closedMs = obj.optLong("closedMs", 0L)

            result.add(
                ConnectionItem(
                    id = id,
                    active = isActive,
                    appName = appName,
                    packageName = pkg,
                    processPath = processPath,
                    uid = uid,
                    icon = icon,
                    network = network,
                    protocol = protocol,
                    inbound = inbound,
                    inboundType = inboundType,
                    sourceIp = sourceIp,
                    sourcePort = sourcePort,
                    host = fullHost,
                    rawHost = rawHost,
                    port = port,
                    destinationIp = destIp,
                    ipVersion = ipVersion,
                    rule = rawRule,
                    upload = up,
                    download = down,
                    uploadSpeed = upSpeed,
                    downloadSpeed = downSpeed,
                    chainsList = chainsList,
                    chainsDisplay = chainsDisplay,
                    outbound = outbound.ifBlank { chainsList.lastOrNull().orEmpty() },
                    outboundType = outboundType,
                    startMs = startMs,
                    closedMs = closedMs,
                    rawJson = runCatching { obj.toString(2) }.getOrDefault(obj.toString()),
                )
            )
        }
        return result
    }

    private suspend fun fetchConnections() {
        if (!DataStore.serviceState.started) {
            withContext(Dispatchers.Main) {
                if (!isAdded || view == null) return@withContext
                activeConnections = emptyList()
                closedConnections = emptyList()
                prevTrafficSnapshot = emptyMap()
                totalUpload = 0L
                totalDownload = 0L
                totalUploadSpeed = 0L
                totalDownloadSpeed = 0L
                updateTabActionUi()
                updateTotalStatsUi()
                applyFilter()
            }
            return
        }

        val pm = context?.packageManager ?: return
        val service = currentService()
        val nowMs = System.currentTimeMillis()
        val prevTraffic = prevTrafficSnapshot

        data class Snapshot(
            val active: List<ConnectionItem>,
            val closed: List<ConnectionItem>,
            val upTotal: Long,
            val downTotal: Long,
            val upSpeed: Long,
            val downSpeed: Long,
            val nextMap: Map<String, Triple<Long, Long, Long>>,
        )

        val snap = withContext(Dispatchers.IO) {
            val nextMap = HashMap<String, Triple<Long, Long, Long>>()
            var activeList = emptyList<ConnectionItem>()
            var closedList = emptyList<ConnectionItem>()
            var upTotal = 0L
            var downTotal = 0L
            var upSpeedTotal = 0L
            var downSpeedTotal = 0L

            try {
                val rawNative = runCatching { service?.getActiveConnectionsJson() }.getOrNull().orEmpty()
                if (rawNative.isNotBlank()) {
                    val json = JSONObject(rawNative)
                    upTotal = json.optLong("uploadTotal", 0L)
                    downTotal = json.optLong("downloadTotal", 0L)
                    activeList = parseNativeItems(
                        json.optJSONArray("connections"),
                        defaultActive = true,
                        nowMs = nowMs,
                        pm = pm,
                        prevTrafficMap = prevTraffic,
                        nextTrafficMap = nextMap,
                    )
                    closedList = parseNativeItems(
                        json.optJSONArray("closedConnections"),
                        defaultActive = false,
                        nowMs = nowMs,
                        pm = pm,
                        prevTrafficMap = prevTraffic,
                        nextTrafficMap = nextMap,
                    )
                } else if (DataStore.enableClashAPI || DataStore.allowAccess) {
                    val req = buildClashRequest("http://127.0.0.1:9090/connections").build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string() ?: return@use
                        val json = JSONObject(body)
                        upTotal = json.optLong("uploadTotal", 0L)
                        downTotal = json.optLong("downloadTotal", 0L)
                        val connArr = json.optJSONArray("connections") ?: return@use
                        val parsed = ArrayList<ConnectionItem>(connArr.length())

                        for (i in 0 until connArr.length()) {
                            val obj = connArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val meta = obj.optJSONObject("metadata")
                            val network = meta?.optString("network")?.uppercase()?.ifBlank { "TCP" } ?: "TCP"
                            val inboundType = meta?.optString("type")?.trim().orEmpty().ifBlank { "tun" }
                            val rawHost = meta?.optString("host")?.takeIf { it.isNotBlank() }
                                ?: meta?.optString("destinationIP")?.takeIf { it.isNotBlank() }
                                ?: "未知目标"
                            val port = meta?.optString("destinationPort")?.toIntOrNull() ?: 0
                            val fullHost = if (port > 0) {
                                if (rawHost.contains(":") && !rawHost.startsWith("[")) "[$rawHost]:$port" else "$rawHost:$port"
                            } else {
                                rawHost
                            }
                            val sourceIp = meta?.optString("sourceIP")?.trim().orEmpty()
                            val sourcePort = meta?.optString("sourcePort")?.toIntOrNull() ?: 0
                            val destIp = meta?.optString("destinationIP")?.trim().orEmpty()
                            val ipVersion = when {
                                destIp.contains(":") -> "IPv6"
                                destIp.isNotBlank() -> "IPv4"
                                else -> ""
                            }

                            val rawProcess = meta?.optString("processPath")?.takeIf { it.isNotBlank() }
                                ?: meta?.optString("process").orEmpty()
                            val (rawPkg, parsedUid) = stripProcessSuffix(rawProcess)
                            val (appName, pkg, icon) = resolveAppDisplay(pm, rawPkg, parsedUid)

                            val rule = obj.optString("rule").trim()
                            val payload = obj.optString("rulePayload").trim()
                            val ruleDesc = when {
                                payload.isNotBlank() -> "$rule($payload)"
                                rule.isNotBlank() -> rule
                                else -> "final"
                            }

                            val up = obj.optLong("upload", 0L)
                            val down = obj.optLong("download", 0L)
                            val prev = prevTraffic[id]
                            var itemUpSpeed = 0L
                            var itemDownSpeed = 0L
                            if (prev != null && nowMs > prev.third) {
                                val dt = (nowMs - prev.third).coerceAtLeast(250L)
                                itemUpSpeed = ((up - prev.first).coerceAtLeast(0L) * 1000L) / dt
                                itemDownSpeed = ((down - prev.second).coerceAtLeast(0L) * 1000L) / dt
                            }
                            nextMap[id] = Triple(up, down, nowMs)

                            val chainsList = parseChainsList(obj.optJSONArray("chains"), reverse = true, fallback = "direct")
                            val startMs = parseIsoTimeMs(obj.optString("start"))

                            parsed.add(
                                ConnectionItem(
                                    id = id,
                                    active = true,
                                    appName = appName,
                                    packageName = pkg,
                                    processPath = rawProcess,
                                    uid = parsedUid,
                                    icon = icon,
                                    network = network,
                                    protocol = "",
                                    inbound = inboundType.substringAfter("/", inboundType),
                                    inboundType = inboundType,
                                    sourceIp = sourceIp,
                                    sourcePort = sourcePort,
                                    host = fullHost,
                                    rawHost = rawHost,
                                    port = port,
                                    destinationIp = destIp,
                                    ipVersion = ipVersion,
                                    rule = ruleDesc,
                                    upload = up,
                                    download = down,
                                    uploadSpeed = itemUpSpeed,
                                    downloadSpeed = itemDownSpeed,
                                    chainsList = chainsList,
                                    chainsDisplay = chainsList.joinToString(" » "),
                                    outbound = chainsList.lastOrNull().orEmpty(),
                                    outboundType = "",
                                    startMs = startMs,
                                    closedMs = 0L,
                                    rawJson = runCatching { obj.toString(2) }.getOrDefault(obj.toString()),
                                )
                            )
                        }
                        activeList = parsed
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
            }

            for (item in activeList) {
                upSpeedTotal += item.uploadSpeed
                downSpeedTotal += item.downloadSpeed
            }
            if (upTotal == 0L && downTotal == 0L) {
                for (item in activeList) {
                    upTotal += item.upload
                    downTotal += item.download
                }
            }

            Snapshot(activeList, closedList, upTotal, downTotal, upSpeedTotal, downSpeedTotal, nextMap)
        }

        withContext(Dispatchers.Main) {
            if (!isAdded || view == null) return@withContext
            prevTrafficSnapshot = snap.nextMap
            activeConnections = snap.active
            closedConnections = snap.closed
            totalUpload = snap.upTotal
            totalDownload = snap.downTotal
            totalUploadSpeed = snap.upSpeed
            totalDownloadSpeed = snap.downSpeed

            updateTabActionUi()
            updateTotalStatsUi()
            rebuildFilterChipsIfNeeded()
            applyFilter()

            val detailId = openDetailId
            val updater = openDetailUpdater
            if (detailId != null && updater != null) {
                val updated = activeConnections.firstOrNull { it.id == detailId }
                    ?: closedConnections.firstOrNull { it.id == detailId }
                if (updated != null) {
                    updater(updated)
                }
            }
        }
    }

    private fun updateTotalStatsUi() {
        val ctx = context ?: return
        val downStr = Formatter.formatFileSize(ctx, totalDownload)
        val upStr = Formatter.formatFileSize(ctx, totalUpload)
        tvTotalTraffic.text = "累计 ▼ $downStr  ▲ $upStr"

        val downSpd = "${Formatter.formatFileSize(ctx, totalDownloadSpeed)}/s"
        val upSpd = "${Formatter.formatFileSize(ctx, totalUploadSpeed)}/s"
        val sb = SpannableStringBuilder()
        val s1 = sb.length
        sb.append("▼ $downSpd")
        sb.setSpan(ForegroundColorSpan(COLOR_GREEN), s1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ")
        val s2 = sb.length
        sb.append("▲ $upSpd")
        sb.setSpan(ForegroundColorSpan(COLOR_BLUE), s2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvTotalSpeed.text = sb
    }

    private fun isDirectItem(item: ConnectionItem): Boolean {
        val head = item.chainsList.firstOrNull()?.lowercase().orEmpty()
        val tail = item.outbound.lowercase()
        return head in setOf("direct", "bypass", "block", "reject") ||
            tail in setOf("direct", "bypass", "block", "reject") ||
            item.outboundType.equals("direct", ignoreCase = true)
    }

    private fun rebuildFilterChipsIfNeeded() {
        val ctx = context ?: return
        val source = if (showClosed) activeConnections + closedConnections else activeConnections
        val dynamicOutbounds = source
            .mapNotNull { item ->
                val head = item.chainsList.firstOrNull()?.trim().orEmpty()
                if (head.isEmpty() || head.lowercase() in setOf("direct", "bypass", "block", "reject")) null else head
            }
            .distinct()
            .sorted()

        val desired = ArrayList<Pair<String, String>>(5 + dynamicOutbounds.size).apply {
            add(FILTER_ALL to getString(R.string.connections_filter_all))
            add(FILTER_PROXY to getString(R.string.connections_filter_proxy))
            add(FILTER_DIRECT to getString(R.string.connections_filter_direct))
            add(FILTER_TCP to "TCP")
            add(FILTER_UDP to "UDP")
            for (tag in dynamicOutbounds) {
                add("$FILTER_OUTBOUND_PREFIX$tag" to tag)
            }
        }

        if (desired == currentFilterChipKeys) return
        currentFilterChipKeys = desired
        if (desired.none { it.first == selectedFilterKey }) {
            selectedFilterKey = FILTER_ALL
        }

        val inflater = LayoutInflater.from(ctx)
        chipGroupFilter.setOnCheckedStateChangeListener(null)
        chipGroupFilter.removeAllViews()
        var checkedChipId = View.NO_ID

        for ((key, label) in desired) {
            val chip = inflater.inflate(R.layout.item_doc_category_chip, chipGroupFilter, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = key
            chip.text = label
            chip.textSize = 12f
            val checked = key == selectedFilterKey
            chip.isChecked = checked
            if (checked) checkedChipId = chip.id
            chipGroupFilter.addView(chip)
        }
        if (checkedChipId != View.NO_ID) {
            chipGroupFilter.check(checkedChipId)
        }
        chipGroupFilter.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id) ?: return@setOnCheckedStateChangeListener
            val newKey = chip.tag as? String ?: FILTER_ALL
            if (newKey != selectedFilterKey) {
                selectedFilterKey = newKey
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val base = if (showClosed) closedConnections else activeConnections

        val byCategory = when {
            selectedFilterKey == FILTER_ALL -> base
            selectedFilterKey == FILTER_PROXY -> base.filter { !isDirectItem(it) }
            selectedFilterKey == FILTER_DIRECT -> base.filter { isDirectItem(it) }
            selectedFilterKey == FILTER_TCP -> base.filter { it.network.equals("TCP", ignoreCase = true) }
            selectedFilterKey == FILTER_UDP -> base.filter { it.network.equals("UDP", ignoreCase = true) }
            selectedFilterKey.startsWith(FILTER_OUTBOUND_PREFIX) -> {
                val targetTag = selectedFilterKey.removePrefix(FILTER_OUTBOUND_PREFIX)
                base.filter { item ->
                    item.chainsList.any { it == targetTag } || item.outbound == targetTag
                }
            }
            else -> base
        }

        val regex = if (isRegexEnabled && filterKeyword.isNotEmpty()) {
            runCatching { Regex(filterKeyword, RegexOption.IGNORE_CASE) }.getOrNull()
        } else {
            null
        }

        val bySearch = if (filterKeyword.isEmpty()) {
            byCategory
        } else if (regex != null) {
            byCategory.filter { item ->
                regex.containsMatchIn(item.host) ||
                    regex.containsMatchIn(item.destinationIp) ||
                    regex.containsMatchIn(item.appName) ||
                    regex.containsMatchIn(item.packageName) ||
                    regex.containsMatchIn(item.rule) ||
                    regex.containsMatchIn(item.chainsDisplay)
            }
        } else {
            byCategory.filter { item ->
                item.host.contains(filterKeyword, ignoreCase = true) ||
                    item.destinationIp.contains(filterKeyword, ignoreCase = true) ||
                    item.appName.contains(filterKeyword, ignoreCase = true) ||
                    item.packageName.contains(filterKeyword, ignoreCase = true) ||
                    item.rule.contains(filterKeyword, ignoreCase = true) ||
                    item.chainsDisplay.contains(filterKeyword, ignoreCase = true)
            }
        }

        val comparator = when (sortField) {
            SortField.TIME -> compareBy<ConnectionItem>(
                { if (it.active) it.startMs else (if (it.closedMs > 0L) it.closedMs else it.startMs) },
                { it.id }
            )
            SortField.DOWNLOAD_SPEED -> compareBy<ConnectionItem>({ it.downloadSpeed }, { it.download }, { it.startMs })
            SortField.UPLOAD_SPEED -> compareBy<ConnectionItem>({ it.uploadSpeed }, { it.upload }, { it.startMs })
            SortField.DOWNLOAD -> compareBy<ConnectionItem>({ it.download }, { it.startMs })
            SortField.UPLOAD -> compareBy<ConnectionItem>({ it.upload }, { it.startMs })
            SortField.HOST -> compareBy<ConnectionItem>({ it.host.lowercase() }, { it.startMs })
        }
        val sorted = if (sortDescending) bySearch.sortedWith(comparator.reversed()) else bySearch.sortedWith(comparator)
        currentFiltered = sorted

        if (!DataStore.serviceState.started) {
            tvEmpty.text = "VPN 未连接，连接后即可实时查看分流连接"
        } else if (showClosed) {
            tvEmpty.setText(R.string.connections_history_empty)
        } else {
            tvEmpty.setText(R.string.connections_empty)
        }
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(sorted)
    }

    private fun formatRelativeTime(item: ConnectionItem): String {
        val now = System.currentTimeMillis()
        if (!item.active) {
            val durSec = if (item.closedMs > item.startMs && item.startMs > 0L) {
                (item.closedMs - item.startMs) / 1000L
            } else {
                0L
            }
            return if (durSec > 0L) "已关闭 · ${durSec}s" else "已关闭"
        }
        if (item.startMs <= 0L) return "刚刚"
        val diffSec = ((now - item.startMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 5L -> "刚刚"
            diffSec < 60L -> "${diffSec} 秒前"
            diffSec < 3600L -> "${diffSec / 60L} 分钟前"
            else -> "${diffSec / 3600L} 小时前"
        }
    }

    private fun showConnectionDetailDialog(initialItem: ConnectionItem) {
        val ctx = context ?: return
        runCatching { openDetailDialog?.dismiss() }
        val dialog = BottomSheetDialog(ctx)
        openDetailDialog = dialog
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_connection_detail, null)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.layoutParams = sheet.layoutParams.apply {
                    height = (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt()
                }
                BottomSheetBehavior.from(sheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }

        val btnDetailClose = view.findViewById<ImageView>(R.id.btn_detail_close)
        val detailTabs = view.findViewById<TabLayout>(R.id.detail_tabs)
        val layoutOverview = view.findViewById<View>(R.id.layout_tab_overview)
        val layoutProxy = view.findViewById<View>(R.id.layout_tab_proxy)
        val layoutRaw = view.findViewById<View>(R.id.layout_tab_raw)

        val detailId = view.findViewById<TextView>(R.id.detail_id)
        val detailStatus = view.findViewById<TextView>(R.id.detail_status)
        val detailType = view.findViewById<TextView>(R.id.detail_type)
        val detailTime = view.findViewById<TextView>(R.id.detail_time)
        val detailRule = view.findViewById<TextView>(R.id.detail_rule)
        val detailAppIcon = view.findViewById<ImageView>(R.id.detail_app_icon)
        val detailProcess = view.findViewById<TextView>(R.id.detail_process)

        val detailSourceIp = view.findViewById<TextView>(R.id.detail_source_ip)
        val detailSourcePort = view.findViewById<TextView>(R.id.detail_source_port)
        val detailHost = view.findViewById<TextView>(R.id.detail_host)
        val detailDestIp = view.findViewById<TextView>(R.id.detail_dest_ip)
        val detailIpVersion = view.findViewById<TextView>(R.id.detail_ip_version)

        val detailGeoIpAsn = view.findViewById<TextView>(R.id.detail_geo_ip_asn)
        val detailGeoLocation = view.findViewById<TextView>(R.id.detail_geo_location)
        val detailGeoOrg = view.findViewById<TextView>(R.id.detail_geo_org)

        val detailDownload = view.findViewById<TextView>(R.id.detail_download)
        val detailUpload = view.findViewById<TextView>(R.id.detail_upload)
        val detailDownloadSpeed = view.findViewById<TextView>(R.id.detail_download_speed)
        val detailUploadSpeed = view.findViewById<TextView>(R.id.detail_upload_speed)

        val detailChains = view.findViewById<TextView>(R.id.detail_chains)
        val detailOutbound = view.findViewById<TextView>(R.id.detail_outbound)

        val detailProxyChips = view.findViewById<ChipGroup>(R.id.detail_proxy_chips)
        val detailProxyHopsContainer = view.findViewById<LinearLayout>(R.id.detail_proxy_hops_container)
        val detailProxyTrafficSummary = view.findViewById<TextView>(R.id.detail_proxy_traffic_summary)

        val btnCopyRawJson = view.findViewById<MaterialButton>(R.id.btn_copy_raw_json)
        val detailRawJson = view.findViewById<TextView>(R.id.detail_raw_json)

        val btnCreateRule = view.findViewById<MaterialButton>(R.id.btn_detail_create_rule)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btn_detail_copy)
        val btnCloseConn = view.findViewById<MaterialButton>(R.id.btn_detail_close_conn)

        var currentItem = initialItem
        var geoLoadedForIp = ""

        detailTabs.addTab(detailTabs.newTab().setText(R.string.connections_detail_overview))
        detailTabs.addTab(detailTabs.newTab().setText(R.string.connections_detail_proxy))
        detailTabs.addTab(detailTabs.newTab().setText(R.string.connections_detail_raw))
        detailTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position ?: 0
                layoutOverview.isVisible = pos == 0
                layoutProxy.isVisible = pos == 1
                layoutRaw.isVisible = pos == 2
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        fun renderDetail(item: ConnectionItem) {
            currentItem = item
            val safeCtx = context ?: return

            detailId.text = item.id
            detailStatus.text = if (item.active) "活跃连接" else "已关闭"
            detailStatus.setTextColor(if (item.active) COLOR_GREEN else resolveSecondaryTextColor())

            val typeLabel = buildString {
                append(item.inboundType.ifBlank { "tun" })
                append(" | ")
                append(item.network.lowercase())
                if (item.protocol.isNotBlank()) {
                    append(" (").append(item.protocol).append(")")
                }
            }
            detailType.text = typeLabel
            detailTime.text = formatRelativeTime(item)
            detailRule.text = item.rule

            if (item.icon != null) {
                detailAppIcon.setImageDrawable(item.icon)
            } else {
                detailAppIcon.setImageResource(R.drawable.ic_navigation_apps)
            }
            detailProcess.text = buildString {
                append(item.appName)
                if (item.processPath.isNotBlank() && item.processPath != item.appName) {
                    append(" · ").append(item.processPath)
                }
            }

            detailSourceIp.text = item.sourceIp.ifBlank { "-" }
            detailSourcePort.text = if (item.sourcePort > 0) item.sourcePort.toString() else "-"
            detailHost.text = item.host
            detailDestIp.text = item.destinationIp.ifBlank { "-" }
            detailIpVersion.text = item.ipVersion.ifBlank { "-" }

            detailDownload.text = Formatter.formatFileSize(safeCtx, item.download)
            detailUpload.text = Formatter.formatFileSize(safeCtx, item.upload)
            detailDownloadSpeed.text = "${Formatter.formatFileSize(safeCtx, item.downloadSpeed)}/s"
            detailUploadSpeed.text = "${Formatter.formatFileSize(safeCtx, item.uploadSpeed)}/s"

            detailChains.text = item.chainsDisplay
            detailOutbound.text = if (item.outboundType.isNotBlank()) {
                "${item.outbound} (${item.outboundType.uppercase()})"
            } else {
                item.outbound.ifBlank { "-" }
            }

            // Tab 2: Proxy Chain view
            val chipInflater = LayoutInflater.from(safeCtx)
            detailProxyChips.removeAllViews()
            item.chainsList.forEachIndexed { idx, hop ->
                val chip = chipInflater.inflate(R.layout.item_doc_category_chip, detailProxyChips, false) as Chip
                chip.isCheckable = false
                chip.isClickable = false
                chip.isChecked = idx == item.chainsList.lastIndex
                chip.text = hop
                chip.textSize = 12f
                detailProxyChips.addView(chip)
            }

            detailProxyHopsContainer.removeAllViews()
            val padH = dp2px(12)
            val padV = dp2px(10)
            val marginB = dp2px(8)
            fun addHopCard(title: String, subtitle: String, isLast: Boolean) {
                val card = MaterialCardView(safeCtx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = marginB }
                    radius = resources.getDimension(R.dimen.card_corner_radius)
                    cardElevation = 0f
                    strokeWidth = dp2px(1)
                    strokeColor = ContextCompat.getColor(safeCtx, R.color.card_stroke)
                }
                val inner = LinearLayout(safeCtx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padH, padV, padH, padV)
                }
                val tvTitle = TextView(safeCtx).apply {
                    text = title
                    textSize = 13.5f
                    if (isLast) {
                        setTextColor(resolvePrimaryAttrColor())
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                }
                val tvSub = TextView(safeCtx).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(resolveSecondaryTextColor())
                }
                inner.addView(tvTitle)
                inner.addView(tvSub)
                card.addView(inner)
                detailProxyHopsContainer.addView(card)
            }

            addHopCard("入站：${item.inboundType.ifBlank { "tun" }}", "协议：${item.network}${if (item.protocol.isNotBlank()) " / ${item.protocol}" else ""}", false)
            item.chainsList.forEachIndexed { idx, hop ->
                val isLast = idx == item.chainsList.lastIndex
                val sub = if (isLast && item.outboundType.isNotBlank()) {
                    "最终出站 · 协议类型：${item.outboundType.uppercase()}"
                } else if (isLast) {
                    "最终出站"
                } else {
                    "策略组 / 中继路由第 ${idx + 1} 跳"
                }
                addHopCard("↳ $hop", sub, isLast)
            }

            detailProxyTrafficSummary.text =
                "实时速度：▼ ${Formatter.formatFileSize(safeCtx, item.downloadSpeed)}/s  " +
                    "▲ ${Formatter.formatFileSize(safeCtx, item.uploadSpeed)}/s  ·  " +
                    "累计：▼ ${Formatter.formatFileSize(safeCtx, item.download)}  " +
                    "▲ ${Formatter.formatFileSize(safeCtx, item.upload)}"

            // Tab 3: Raw JSON
            detailRawJson.text = item.rawJson

            btnCloseConn.isVisible = item.active

            // Trigger on-demand geo lookup once per destination IP
            val destIp = item.destinationIp
            if (destIp.isBlank()) {
                detailGeoIpAsn.text = "暂无解析 IP 归属信息"
                detailGeoLocation.isVisible = false
                detailGeoOrg.isVisible = false
            } else if (geoLoadedForIp != destIp) {
                geoLoadedForIp = destIp
                detailGeoIpAsn.text = "$destIp（正在查询归属信息...）"
                detailGeoLocation.isVisible = false
                detailGeoOrg.isVisible = false
                viewScope()?.launch {
                    val geo = LandingIpManager.lookupIpGeo(destIp)
                    if (!isAdded || !dialog.isShowing || currentItem.destinationIp != destIp) return@launch
                    if (geo == null) {
                        detailGeoIpAsn.text = destIp
                        detailGeoLocation.text = "归属查询超时或暂不可用"
                        detailGeoLocation.isVisible = true
                        detailGeoOrg.isVisible = false
                    } else {
                        detailGeoIpAsn.text = if (geo.asn.isNotBlank()) "${geo.ip} (${geo.asn})" else geo.ip
                        detailGeoLocation.text = geo.location
                        detailGeoLocation.isVisible = geo.location.isNotBlank()
                        detailGeoOrg.text = geo.org
                        detailGeoOrg.isVisible = geo.org.isNotBlank()
                    }
                }
            }
        }

        renderDetail(initialItem)
        openDetailId = initialItem.id
        openDetailUpdater = { updated -> renderDetail(updated) }
        dialog.setOnDismissListener {
            if (openDetailDialog === dialog) {
                openDetailDialog = null
            }
            openDetailId = null
            openDetailUpdater = null
        }

        btnDetailClose.setOnClickListener { dialog.dismiss() }
        detailId.setOnClickListener {
            if (SagerNet.trySetPrimaryClip(currentItem.id)) {
                Toast.makeText(ctx, R.string.copy_success, Toast.LENGTH_SHORT).show()
            }
        }
        btnCopyRawJson.setOnClickListener {
            if (SagerNet.trySetPrimaryClip(currentItem.rawJson)) {
                Toast.makeText(ctx, R.string.copy_success, Toast.LENGTH_SHORT).show()
            }
        }
        btnCloseConn.setOnClickListener {
            closeSingleConnection(currentItem.id)
            dialog.dismiss()
        }
        btnCopy.setOnClickListener {
            val copyItems = linkedMapOf<String, String>()
            if (currentItem.rawHost.isNotBlank() && currentItem.rawHost != "未知目标") {
                copyItems["主机地址 (${currentItem.host})"] = currentItem.rawHost
            }
            if (currentItem.destinationIp.isNotBlank()) {
                copyItems["目标 IP (${currentItem.destinationIp})"] = currentItem.destinationIp
            }
            if (currentItem.packageName.isNotBlank()) {
                copyItems["应用包名 (${currentItem.packageName})"] = currentItem.packageName
            }
            if (currentItem.rule.isNotBlank()) {
                copyItems["命中规则"] = currentItem.rule
            }
            if (currentItem.chainsDisplay.isNotBlank()) {
                copyItems["代理链路"] = currentItem.chainsDisplay
            }
            copyItems["连接 ID"] = currentItem.id
            val labels = copyItems.keys.toTypedArray()
            val values = copyItems.values.toTypedArray()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.connections_copy)
                .setItems(labels) { _, which ->
                    if (SagerNet.trySetPrimaryClip(values[which])) {
                        Toast.makeText(ctx, R.string.copy_success, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
        btnCreateRule.setOnClickListener {
            val ruleChoices = ArrayList<Pair<String, Intent>>()
            if (currentItem.rawHost.isNotBlank() && currentItem.rawHost != "未知目标" && !currentItem.rawHost.isIpAddress()) {
                ruleChoices.add(
                    "按域名创建：${currentItem.rawHost}" to Intent(ctx, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_DOMAIN, currentItem.rawHost)
                    }
                )
            }
            if (currentItem.destinationIp.isNotBlank() && currentItem.destinationIp.isIpAddress()) {
                ruleChoices.add(
                    "按目标 IP 创建：${currentItem.destinationIp}" to Intent(ctx, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_IP, currentItem.destinationIp)
                    }
                )
            }
            if (currentItem.packageName.isNotBlank()) {
                ruleChoices.add(
                    "按应用创建：${currentItem.appName}" to Intent(ctx, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_PACKAGE_NAME, currentItem.packageName)
                    }
                )
            }
            if (ruleChoices.isEmpty()) {
                Toast.makeText(ctx, "该连接无可预填的规则条件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = ruleChoices.map { it.first }.toTypedArray()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.connections_create_rule)
                .setItems(labels) { _, which ->
                    dialog.dismiss()
                    startActivity(ruleChoices[which].second)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        dialog.show()
    }

    private inner class ConnectionsAdapter :
        ListAdapter<ConnectionItem, ConnectionViewHolder>(DIFF_CALLBACK) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_connection, parent, false)
            return ConnectionViewHolder(v)
        }

        override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private inner class ConnectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.conn_app_icon)
        val appName: TextView = itemView.findViewById(R.id.conn_app_name)
        val network: TextView = itemView.findViewById(R.id.conn_network)
        val time: TextView = itemView.findViewById(R.id.conn_time)
        val host: TextView = itemView.findViewById(R.id.conn_host)
        val rule: TextView = itemView.findViewById(R.id.conn_rule)
        val download: TextView = itemView.findViewById(R.id.conn_download)
        val upload: TextView = itemView.findViewById(R.id.conn_upload)
        val chains: TextView = itemView.findViewById(R.id.conn_chains)
        val speed: TextView = itemView.findViewById(R.id.conn_speed)
        val closeBtn: ImageView = itemView.findViewById(R.id.conn_close_btn)

        fun bind(item: ConnectionItem) {
            appName.text = item.appName
            network.text = "${item.inboundType} | ${item.network}"
            time.text = formatRelativeTime(item)
            host.text = item.host
            rule.text = item.rule
            chains.text = item.chainsDisplay

            val ctx = itemView.context
            download.text = "▼ ${Formatter.formatFileSize(ctx, item.download)}"
            upload.text = "▲ ${Formatter.formatFileSize(ctx, item.upload)}"

            if (item.active && (item.downloadSpeed > 0L || item.uploadSpeed > 0L)) {
                val mainSpd = if (item.downloadSpeed >= item.uploadSpeed) {
                    "▼ ${Formatter.formatFileSize(ctx, item.downloadSpeed)}/s"
                } else {
                    "▲ ${Formatter.formatFileSize(ctx, item.uploadSpeed)}/s"
                }
                speed.text = mainSpd
                speed.setTextColor(if (item.downloadSpeed >= item.uploadSpeed) COLOR_GREEN else COLOR_BLUE)
                speed.isVisible = true
            } else {
                speed.isVisible = false
            }

            if (item.icon != null) {
                appIcon.setImageDrawable(item.icon)
            } else {
                appIcon.setImageResource(R.drawable.ic_navigation_apps)
            }

            closeBtn.isVisible = item.active
            closeBtn.setOnClickListener {
                closeSingleConnection(item.id)
            }

            itemView.setOnClickListener {
                showConnectionDetailDialog(item)
            }
        }
    }
}
