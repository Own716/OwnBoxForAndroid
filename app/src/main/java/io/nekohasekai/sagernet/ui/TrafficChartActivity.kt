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

class TrafficChartActivity : ThemedActivity() {

    private lateinit var binding: ActivityTrafficChartBinding
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var trafficWebSocket: WebSocket? = null
    private var isForeground = false
    private var wsRetryCount = 0
    private var failedPollCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrafficChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

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

        // Connect WebSocket to /traffic
        connectTrafficWebSocket()
    }

    private fun stopMonitoring() {
        trafficWebSocket?.cancel()
        trafficWebSocket = null
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
}