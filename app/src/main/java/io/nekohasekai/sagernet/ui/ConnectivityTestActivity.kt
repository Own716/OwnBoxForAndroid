package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.ActivityConnectivityTestBinding
import io.nekohasekai.sagernet.tools.connectivity.ConnectivityDiagnosticsManager
import io.nekohasekai.sagernet.tools.connectivity.EntryProbeResult
import io.nekohasekai.sagernet.tools.connectivity.NodeUsability
import io.nekohasekai.sagernet.tools.connectivity.StageState
import io.nekohasekai.sagernet.tools.connectivity.TargetProbeResult
import io.nekohasekai.sagernet.tools.connectivity.TunnelProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectivityTestActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: ActivityConnectivityTestBinding
    private lateinit var adapter: TargetProbesAdapter
    private var testJob: Job? = null
    private var sessionNodeId: Long = -1L

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_CONNECTIVITY_TEST)

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
    override fun onServiceConnected(service: ISagerNetService) {}
    override fun onServiceDisconnected() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectivityTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.appbar.updatePadding(top = statusBars.top)
            binding.root.updatePadding(bottom = navBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.connectivity_test_title)
        }

        adapter = TargetProbesAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnRetest.setOnClickListener {
            startAllTests()
        }

        connection.connect(this, this)
        startAllTests()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
        connection.disconnect(this)
    }

    private fun getCurrentTargetProxy(): ProxyEntity? {
        val selectedId = DataStore.selectedProxy
        var profile = ProfileManager.getProfile(selectedId) ?: SagerDatabase.proxyDao.getById(selectedId)
        if (profile == null) {
            val all = SagerDatabase.proxyDao.getAll()
            profile = all.firstOrNull()
        }
        return profile
    }

    private fun startAllTests() {
        testJob?.cancel()

        sessionNodeId = DataStore.selectedProxy
        val proxy = getCurrentTargetProxy()
        val nodeName = proxy?.displayName() ?: getString(R.string.unknown)
        val bean = runCatching { proxy?.requireBean() }.getOrNull()
        val host = bean?.serverAddress.orEmpty()
        val port = bean?.serverPort ?: 0

        binding.tvCurrentNode.text = nodeName
        binding.tvCurrentTarget.text = if (host.isNotBlank() && port > 0) {
            "目标入口: $host:$port"
        } else {
            "目标入口: 未配置"
        }

        // Initialize Stage 1 UI
        binding.progressEntry.visibility = View.VISIBLE
        binding.badgeEntry.visibility = View.GONE
        binding.tvEntryStatus.text = "正在探测入口物理握手..."
        binding.tvEntryDetail.text = "向目标节点服务器发起 DNS 解析与原生直连 TCP 握手 RTT 探测"

        // Initialize Stage 2 UI
        binding.progressTunnel.visibility = View.VISIBLE
        binding.badgeTunnel.visibility = View.GONE
        binding.tvTunnelStatus.text = "等待前置握手完成..."
        binding.tvTunnelDetail.text = "验证本地混合入站代理连接及核心出站隧道链路建立"

        // Initialize Usability Banner
        binding.tvUsabilityBadge.text = "诊断中..."
        binding.tvUsabilityBadge.setTextColor(Color.parseColor("#3B82F6"))
        binding.tvUsabilitySummary.text = "正在执行三阶段全面连通性探测..."

        val targets = ConnectivityDiagnosticsManager.createInitialTargets()
        adapter.submitList(targets.map { it.copy() })

        testJob = lifecycleScope.launch {
            // --- Stage 1: Inbound Entry Probe ---
            val entryResult = ConnectivityDiagnosticsManager.probeEntry(host, port)
            if (isNodeChanged()) return@launch

            withContext(Dispatchers.Main) {
                renderStage1(entryResult)
            }

            // --- Stage 2: Core Outbound Tunnel Probe ---
            val tunnelResult = ConnectivityDiagnosticsManager.probeTunnel()
            if (isNodeChanged()) return@launch

            withContext(Dispatchers.Main) {
                renderStage2(tunnelResult)
            }

            // --- Stage 3: Multi-Target Real Probes ---
            val updatedTargets = targets.map { it.copy() }.toMutableList()
            for (i in updatedTargets.indices) {
                if (isNodeChanged()) return@launch

                updatedTargets[i] = updatedTargets[i].copy(
                    state = StageState.RUNNING,
                    summary = "探测中...",
                )
                withContext(Dispatchers.Main) {
                    adapter.submitList(updatedTargets.toList())
                }

                val probed = ConnectivityDiagnosticsManager.probeTargetWithRetry(updatedTargets[i])
                if (isNodeChanged()) return@launch

                updatedTargets[i] = probed
                withContext(Dispatchers.Main) {
                    adapter.submitList(updatedTargets.toList())
                    // Dynamically update overall usability
                    val (verdict, explanation) = ConnectivityDiagnosticsManager.synthesizeUsability(entryResult, tunnelResult, updatedTargets)
                    renderUsability(verdict, explanation)
                }
            }
        }
    }

    private fun isNodeChanged(): Boolean {
        if (DataStore.selectedProxy != sessionNodeId) {
            testJob?.cancel()
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(this@ConnectivityTestActivity, "检测过程中代理节点已切换，已取消旧结果", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return false
    }

    private fun renderStage1(entry: EntryProbeResult) {
        binding.progressEntry.visibility = View.GONE
        binding.badgeEntry.visibility = View.VISIBLE
        binding.tvEntryStatus.text = entry.summary
        binding.tvEntryDetail.text = entry.errorDetail ?: "DNS 解析成功 (${entry.dnsResolvedIp ?: entry.host})，物理握手延迟 ${entry.rttMs} ms"

        if (entry.state == StageState.SUCCESS) {
            binding.badgeEntry.text = "${entry.rttMs} ms"
            binding.badgeEntry.setTextColor(Color.parseColor("#059669"))
        } else {
            binding.badgeEntry.text = "握手受阻"
            binding.badgeEntry.setTextColor(Color.parseColor("#DC2626"))
        }
    }

    private fun renderStage2(tunnel: TunnelProbeResult) {
        binding.progressTunnel.visibility = View.GONE
        binding.badgeTunnel.visibility = View.VISIBLE
        binding.tvTunnelStatus.text = tunnel.summary
        binding.tvTunnelDetail.text = tunnel.errorDetail ?: "经本地端口 (${tunnel.mixedPort}) 建立代理出站完成，建立时延 ${tunnel.rttMs} ms"

        if (tunnel.state == StageState.SUCCESS) {
            binding.badgeTunnel.text = "${tunnel.rttMs} ms"
            binding.badgeTunnel.setTextColor(Color.parseColor("#059669"))
        } else {
            binding.badgeTunnel.text = "出站失败"
            binding.badgeTunnel.setTextColor(Color.parseColor("#DC2626"))
        }
    }

    private fun renderUsability(usability: NodeUsability, explanation: String) {
        binding.tvUsabilityBadge.text = usability.title
        binding.tvUsabilityBadge.setTextColor(Color.parseColor(usability.colorHex))
        binding.tvUsabilitySummary.text = explanation
    }

    // --- RecyclerView Adapter ---

    class TargetProbesAdapter : ListAdapter<TargetProbeResult, TargetProbesAdapter.VH>(DiffCallback) {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_dimension_icon)
            val name: TextView = view.findViewById(R.id.tv_dimension_name)
            val category: TextView = view.findViewById(R.id.tv_dimension_category)
            val progress: CircularProgressIndicator = view.findViewById(R.id.progress_indicator)
            val statusBadge: TextView = view.findViewById(R.id.tv_status_badge)
            val jitterTag: TextView = view.findViewById(R.id.tv_jitter_tag)
            val description: TextView = view.findViewById(R.id.tv_description)
        }

        object DiffCallback : DiffUtil.ItemCallback<TargetProbeResult>() {
            override fun areItemsTheSame(oldItem: TargetProbeResult, newItem: TargetProbeResult) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TargetProbeResult, newItem: TargetProbeResult) =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_connectivity_test, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.icon.setImageResource(item.iconRes)
            holder.name.text = item.name
            holder.category.text = item.targetUrl
            holder.description.text = item.detail

            if (item.isJitterRecovered) {
                holder.jitterTag.visibility = View.VISIBLE
            } else {
                holder.jitterTag.visibility = View.GONE
            }

            when (item.state) {
                StageState.IDLE -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = "等待中"
                    holder.statusBadge.setTextColor(Color.parseColor("#94A3B8"))
                }
                StageState.RUNNING -> {
                    holder.progress.visibility = View.VISIBLE
                    holder.statusBadge.visibility = View.GONE
                }
                StageState.SUCCESS -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = item.summary
                    holder.statusBadge.setTextColor(Color.parseColor("#059669"))
                }
                StageState.WARNING -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = item.summary
                    holder.statusBadge.setTextColor(Color.parseColor("#F59E0B"))
                }
                StageState.FAILED -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = item.summary
                    holder.statusBadge.setTextColor(Color.parseColor("#EF4444"))
                }
                StageState.SKIPPED -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = "已跳过"
                    holder.statusBadge.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }
    }
}
