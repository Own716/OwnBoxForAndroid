package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityTrafficChartBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TrafficChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrafficChartBinding
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var trafficWebSocket: WebSocket? = null
    private var isForeground = false
    private var pollingJob: Job? = null

    private lateinit var connectionAdapter: ConnectionAdapter

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
            binding.chartStatusHint.visibility = View.VISIBLE
            binding.chartStatusHint.text = getString(R.string.traffic_waiting_clash)
            return
        }

        binding.chartStatusHint.visibility = View.GONE

        // 1. Connect WebSocket to /traffic
        connectTrafficWebSocket()

        // 2. Poll /connections periodically
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isForeground) {
                fetchConnections()
                delay(2000)
            }
        }
    }

    private fun stopMonitoring() {
        // Prevent battery drain when in background: actively close WebSocket and cancel polling
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
                runOnUiThread {
                    binding.chartStatusHint.visibility = View.GONE
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
                    runOnUiThread {
                        binding.chartStatusHint.visibility = View.VISIBLE
                        binding.chartStatusHint.text = getString(R.string.traffic_waiting_clash)
                    }
                    // Reconnect attempt after 3s
                    lifecycleScope.launch {
                        delay(3000)
                        if (isForeground && trafficWebSocket == null) {
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
                if (!resp.isSuccessful) return
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
                        val port = meta?.optString("destinationPort") ?: ""
                        val fullDest = if (port.isNotEmpty()) "$host:$port" else host

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

                        items.add(
                            ConnectionModel(
                                id = id,
                                network = network,
                                destination = fullDest,
                                rule = "Rule: $rule",
                                chains = if (chainsList.isNotEmpty()) "Chains: " + chainsList.joinToString(" » ") else "",
                                upload = upload,
                                download = download
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
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
        }
    }

    private fun closeConnection(id: String) {
        runOnDefaultDispatcher {
            try {
                val req = Request.Builder()
                    .url("http://127.0.0.1:9090/connections/$id")
                    .delete()
                    .build()
                client.newCall(req).execute().close()
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
        val download: Long
    )

    class ConnectionAdapter(
        private val onClose: (String) -> Unit
    ) : ListAdapter<ConnectionModel, ConnectionAdapter.VH>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<ConnectionModel>() {
            override fun areItemsTheSame(oldItem: ConnectionModel, newItem: ConnectionModel) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ConnectionModel, newItem: ConnectionModel) = oldItem == newItem
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
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