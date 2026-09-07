package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.system.OsConstants
import android.text.format.Formatter
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityTrafficChartBinding
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.math.min

class TrafficChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrafficChartBinding
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var trafficWebSocket: WebSocket? = null
    private var isForeground = false
    private var pollingJob: Job? = null
    private var wsRetryCount = 0
    private var failedPollCount = 0

    private lateinit var connectionAdapter: ConnectionAdapter

    data class AppDisplayInfo(val name: String, val icon: Drawable?)
    private val appInfoCache = LruCache<Int, AppDisplayInfo>(128)
    private val portToUidCache = LruCache<Int, Int>(256)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrafficChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        connectionAdapter = ConnectionAdapter { connId ->
            closeConnection(connId)
        }
        binding.connectionsRecycler.layoutManager = LinearLayoutManager(this)
        binding.connectionsRecycler.adapter = connectionAdapter

        binding.btnCloseAllConnections.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.traffic_close_all)
                .setMessage(R.string.traffic_close_all_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    closeAllConnections()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnRefreshConnections.setOnClickListener {
            fetchConnectionsManual()
        }

        binding.btnGoConnect.setOnClickListener {
            finish()
        }

        binding.btnRetryMonitor.setOnClickListener {
            wsRetryCount = 0
            failedPollCount = 0
            binding.cardConnectionError.visibility = View.GONE
            startMonitoring()
        }
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

    private fun startMonitoring() {
        if (!DataStore.serviceState.connected) {
            binding.cardNotConnected.visibility = View.VISIBLE
            binding.cardConnectionError.visibility = View.GONE
            binding.tvMonitorStatus.text = "● 监控等待中 (未连接 VPN)"
            binding.tvMonitorStatus.setTextColor(Color.parseColor("#E65100"))
            binding.chartStatusHint.visibility = View.VISIBLE
            binding.chartStatusHint.text = getString(R.string.traffic_waiting_clash)
            return
        }

        binding.cardNotConnected.visibility = View.GONE
        binding.cardConnectionError.visibility = View.GONE
        binding.tvMonitorStatus.text = getString(R.string.traffic_chart_monitor_active)
        binding.tvMonitorStatus.setTextColor(Color.parseColor("#059669"))
        binding.chartStatusHint.visibility = View.GONE

        // 1. Connect WebSocket to /traffic
        connectTrafficWebSocket()

        // 2. Poll /connections periodically
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isForeground) {
                fetchConnections()
                delay(1500)
            }
        }
    }

    private fun fetchConnectionsManual() {
        lifecycleScope.launch(Dispatchers.IO) {
            fetchConnections()
        }
    }

    private fun stopMonitoring() {
        trafficWebSocket?.cancel()
        trafficWebSocket = null
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun connectTrafficWebSocket() {
        if (trafficWebSocket != null) return
        val request = Request.Builder()
            .url("ws://127.0.0.1:9090/traffic")
            .build()

        trafficWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsRetryCount = 0
                runOnUiThread {
                    binding.chartStatusHint.visibility = View.GONE
                    binding.cardConnectionError.visibility = View.GONE
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isForeground) return
                try {
                    val obj = JSONObject(text)
                    val up = obj.optLong("up", 0L)
                    val down = obj.optLong("down", 0L)

                    runOnUiThread {
                        binding.trafficChart.addSpeed(up, down)
                        binding.speedUpText.text = Formatter.formatFileSize(this@TrafficChartActivity, up) + "/s"
                        binding.speedDownText.text = Formatter.formatFileSize(this@TrafficChartActivity, down) + "/s"
                        binding.peakUpText.text = "峰值: " + Formatter.formatFileSize(this@TrafficChartActivity, binding.trafficChart.peakUp) + "/s"
                        binding.peakDownText.text = "峰值: " + Formatter.formatFileSize(this@TrafficChartActivity, binding.trafficChart.peakDown) + "/s"
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trafficWebSocket = null
                if (isForeground) {
                    wsRetryCount++
                    val delayMs = min(wsRetryCount * 1000L, 5000L)
                    runOnUiThread {
                        if (wsRetryCount >= 4) {
                            binding.cardConnectionError.visibility = View.VISIBLE
                        } else {
                            binding.chartStatusHint.visibility = View.VISIBLE
                            binding.chartStatusHint.text = "正在连接 Clash 监控服务 (127.0.0.1:9090)..."
                        }
                    }
                    // Reconnect attempt
                    lifecycleScope.launch {
                        delay(delayMs)
                        if (isForeground && trafficWebSocket == null && DataStore.serviceState.connected) {
                            connectTrafficWebSocket()
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trafficWebSocket = null
            }
        })
    }

    @SuppressLint("SetTextI18n")
    private suspend fun fetchConnections() {
        try {
            val req = Request.Builder().url("http://127.0.0.1:9090/connections").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    handlePollFailure()
                    return
                }
                failedPollCount = 0
                val bodyStr = resp.body?.string() ?: return
                val root = JSONObject(bodyStr)
                val upTotal = root.optLong("uploadTotal", 0L)
                val downTotal = root.optLong("downloadTotal", 0L)
                val connArray = root.optJSONArray("connections")

                val items = mutableListOf<ConnectionModel>()
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
                        val sourcePortStr = meta?.optString("sourcePort") ?: ""
                        val sourcePort = sourcePortStr.toIntOrNull() ?: 0
                        val destIP = meta?.optString("destinationIP") ?: ""
                        val processPath = meta?.optString("processPath") ?: ""

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

                        // Resolve App Identity & Icon
                        val uid = resolveUid(network, sourceIP, sourcePort, destIP, destPort.toIntOrNull() ?: 0)
                        val appDisplay = getAppDisplayInfo(uid, processPath, destPort)

                        items.add(
                            ConnectionModel(
                                id = id,
                                network = network,
                                destination = fullDest,
                                rule = "Rule: $rule",
                                chains = if (chainsList.isNotEmpty()) "Chains: " + chainsList.joinToString(" » ") else "",
                                upload = upload,
                                download = download,
                                appName = appDisplay.name,
                                appIcon = appDisplay.icon
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.cardConnectionError.visibility = View.GONE
                    binding.connectionsCountTitle.text = "活跃网络连接 (${items.size})"
                    binding.connectionsTotalStats.text = "总计上传: " + Formatter.formatFileSize(this@TrafficChartActivity, upTotal) +
                            "  |  总计下载: " + Formatter.formatFileSize(this@TrafficChartActivity, downTotal)

                    if (items.isEmpty()) {
                        binding.connectionsEmptyHint.visibility = View.VISIBLE
                        binding.connectionsRecycler.visibility = View.GONE
                    } else {
                        binding.connectionsEmptyHint.visibility = View.GONE
                        binding.connectionsRecycler.visibility = View.VISIBLE
                        connectionAdapter.submitList(items)
                    }
                }
            }
        } catch (_: Exception) {
            handlePollFailure()
        }
    }

    private suspend fun handlePollFailure() {
        failedPollCount++
        if (failedPollCount >= 4 && isForeground && DataStore.serviceState.connected) {
            withContext(Dispatchers.Main) {
                binding.cardConnectionError.visibility = View.VISIBLE
            }
        }
    }

    private fun resolveUid(network: String, sourceIP: String, sourcePort: Int, destIP: String, destPort: Int): Int {
        if (sourcePort <= 0) return -1
        portToUidCache.get(sourcePort)?.let { return it }

        var resolvedUid = -1

        // Method 1: Android 10+ ConnectivityManager.getConnectionOwnerUid
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

        // Method 2: Fallback to /proc/net/tcp & /proc/net/tcp6
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
                        val localAddr = tokens[1] // e.g. 0100007F:1F90
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
        val defaultName = if (destPort == "53") "系统 DNS 解析" else if (uid == 0) "系统核心服务" else "网络服务进程"
        return AppDisplayInfo(defaultName, defaultIcon)
    }

    private fun closeConnection(id: String) {
        runOnDefaultDispatcher {
            try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:9090/connections/$id")
                    .delete()
                    .build()
                client.newCall(req).execute().close()
                delay(200)
                fetchConnections()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeAllConnections() {
        runOnDefaultDispatcher {
            try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:9090/connections")
                    .delete()
                    .build()
                client.newCall(req).execute().close()
                delay(200)
                fetchConnections()
            } catch (_: Exception) {
            }
        }
    }

    data class ConnectionModel(
        val id: String,
        val network: String,
        val destination: String,
        val rule: String,
        val chains: String,
        val upload: Long,
        val download: Long,
        val appName: String,
        val appIcon: Drawable?
    )

    class ConnectionAdapter(
        private val onClose: (String) -> Unit
    ) : ListAdapter<ConnectionModel, ConnectionAdapter.VH>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<ConnectionModel>() {
            override fun areItemsTheSame(oldItem: ConnectionModel, newItem: ConnectionModel) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ConnectionModel, newItem: ConnectionModel) = oldItem == newItem
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val appIcon: ImageView = view.findViewById(R.id.conn_app_icon)
            val appName: TextView = view.findViewById(R.id.conn_app_name)
            val network: TextView = view.findViewById(R.id.conn_network)
            val host: TextView = view.findViewById(R.id.conn_host)
            val rule: TextView = view.findViewById(R.id.conn_rule)
            val chains: TextView = view.findViewById(R.id.conn_chains)
            val traffic: TextView = view.findViewById(R.id.conn_traffic)
            val closeBtn: ImageView = view.findViewById(R.id.conn_close_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_connection, parent, false)
            return VH(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            if (item.appIcon != null) {
                holder.appIcon.setImageDrawable(item.appIcon)
            } else {
                holder.appIcon.setImageResource(R.drawable.ic_navigation_apps)
            }
            holder.appName.text = item.appName
            holder.network.text = item.network
            holder.host.text = item.destination
            holder.rule.text = item.rule
            holder.chains.text = item.chains
            holder.chains.visibility = if (item.chains.isNotEmpty()) View.VISIBLE else View.GONE

            val upStr = Formatter.formatFileSize(holder.itemView.context, item.upload)
            val downStr = Formatter.formatFileSize(holder.itemView.context, item.download)
            holder.traffic.text = "▲ $upStr  ▼ $downStr"

            holder.closeBtn.setOnClickListener {
                onClose(item.id)
            }
        }
    }
}