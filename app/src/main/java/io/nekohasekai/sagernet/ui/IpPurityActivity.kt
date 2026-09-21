package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityIpPurityBinding
import io.nekohasekai.sagernet.tools.ip.IpReputationManager
import io.nekohasekai.sagernet.tools.ip.IpReputationReport
import io.nekohasekai.sagernet.tools.ip.ScenarioStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class IpPurityActivity : ThemedActivity() {

    private lateinit var binding: ActivityIpPurityBinding
    private var currentIpAddress: String = ""
    private var checkJob: Job? = null
    private var sessionNodeId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIpPurityBinding.inflate(layoutInflater)
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
            setTitle(R.string.ip_purity_title)
        }

        binding.refreshLayout.setOnRefreshListener {
            startCheck()
        }

        binding.btnRetest.setOnClickListener {
            startCheck()
        }

        binding.btnScamalytics.setOnClickListener {
            openExternalReport("https://scamalytics.com/ip/$currentIpAddress")
        }

        binding.btnIpinfo.setOnClickListener {
            openExternalReport("https://ipinfo.io/$currentIpAddress")
        }

        binding.btnAbuseipdb.setOnClickListener {
            openExternalReport("https://www.abuseipdb.com/check/$currentIpAddress")
        }

        startCheck()
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
        checkJob?.cancel()
    }

    private fun openExternalReport(url: String) {
        if (currentIpAddress.isBlank()) {
            Toast.makeText(this, "尚未获取到有效出口 IP 地址", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开系统浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCheck() {
        checkJob?.cancel()

        if (!DataStore.serviceState.connected) {
            binding.refreshLayout.isRefreshing = false
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            showNotConnectedState()
            return
        }

        sessionNodeId = DataStore.selectedProxy

        binding.refreshLayout.isRefreshing = true
        binding.cardStatus.setCardBackgroundColor(Color.parseColor("#475569"))
        binding.tvScoreNumber.text = "--"
        binding.tvScoreBadge.text = "正在全面探测..."
        binding.tvStatusTitle.text = "双源情报正在同步中"
        binding.tvStatusDesc.text = "正在请求 ip-api 与 ipapi.is 风控数据库，比对 ASN 归属与托管机房特征..."
        binding.cardConflict.visibility = View.GONE

        checkJob = lifecycleScope.launch {
            val result = IpReputationManager.queryIpReputation()

            binding.refreshLayout.isRefreshing = false

            // Node context guard: discard stale result if node changed during detection
            if (DataStore.selectedProxy != sessionNodeId) {
                Toast.makeText(this@IpPurityActivity, "检测过程中代理节点已切换，已取消旧结果", Toast.LENGTH_SHORT).show()
                return@launch
            }

            result.onSuccess { report ->
                renderReport(report)
            }.onFailure { e ->
                renderError(e.message ?: "检测失败")
            }
        }
    }

    private fun showNotConnectedState() {
        binding.cardStatus.setCardBackgroundColor(Color.parseColor("#64748B"))
        binding.tvScoreNumber.text = "0"
        binding.tvScoreBadge.text = "未连接 VPN"
        binding.tvStatusTitle.text = "VPN 未连接"
        binding.tvStatusDesc.text = getString(R.string.vpn_not_connected_warning)
        binding.cardConflict.visibility = View.GONE
    }

    private fun renderReport(report: IpReputationReport) {
        currentIpAddress = report.ip

        // 1. Primary Card
        binding.tvScoreNumber.text = report.score.toString()
        binding.tvScoreBadge.text = report.level.title
        binding.tvStatusTitle.text = when {
            report.isHosting.isTrue -> "机房托管 / 数据中心 IP"
            report.isMobile.isTrue -> "移动蜂窝运营商基站 IP"
            report.isHosting.isFalse && !report.hasConflict -> "原生住宅宽带 IP"
            report.hasConflict -> "多源特征存在分歧 IP"
            else -> "混合网络属性 IP"
        }
        binding.tvStatusDesc.text = "出口: ${report.flag} ${report.country} · ${report.city} (${report.countryCode}) | ISP: ${report.isp}"
        binding.cardStatus.setCardBackgroundColor(Color.parseColor(report.level.colorHex))

        // 2. Conflict Banner
        if (report.hasConflict && !report.conflictDescription.isNullOrBlank()) {
            binding.cardConflict.visibility = View.VISIBLE
            binding.tvConflictText.text = report.conflictDescription
        } else {
            binding.cardConflict.visibility = View.GONE
        }

        // 3. Human vs Machine Ratio
        binding.progressHumanRatio.progress = report.humanTrafficRatio
        binding.tvHumanRatio.text = "真实人流: ${report.humanTrafficRatio}%"
        binding.tvMachineRatio.text = "机器/爬虫: ${report.machineTrafficRatio}%"

        // 4. 6-Dimensional Evidence Progress Bars
        binding.progressResidential.progress = report.residentialScore
        binding.tvResidentialVal.text = "${report.residentialScore}%"

        binding.progressDatacenter.progress = report.datacenterProbability
        binding.tvDatacenterVal.text = "${report.datacenterProbability}%"

        binding.progressProxy.progress = report.proxyVpnLikelihood
        binding.tvProxyVal.text = "${report.proxyVpnLikelihood}%"

        binding.progressAbuse.progress = report.abuseRecord
        binding.tvAbuseVal.text = "${report.abuseRecord}%"

        binding.progressAutomation.progress = report.automationTendency
        binding.tvAutomationVal.text = "${report.automationTendency}%"

        binding.progressConfidence.progress = report.confidenceScore
        binding.tvConfidenceVal.text = "${report.confidenceScore}%"

        // 5. Scenarios
        report.scenarios.forEach { scenario ->
            val color = Color.parseColor(scenario.status.colorHex)
            when (scenario.id) {
                "web" -> {
                    binding.tvScenarioWebBadge.text = scenario.status.title
                    binding.tvScenarioWebBadge.setTextColor(color)
                    binding.tvScenarioWebDesc.text = scenario.reason
                }
                "streaming" -> {
                    binding.tvScenarioStreamBadge.text = scenario.status.title
                    binding.tvScenarioStreamBadge.setTextColor(color)
                    binding.tvScenarioStreamDesc.text = scenario.reason
                }
                "ai" -> {
                    binding.tvScenarioAiBadge.text = scenario.status.title
                    binding.tvScenarioAiBadge.setTextColor(color)
                    binding.tvScenarioAiDesc.text = scenario.reason
                }
                "finance" -> {
                    binding.tvScenarioFinanceBadge.text = scenario.status.title
                    binding.tvScenarioFinanceBadge.setTextColor(color)
                    binding.tvScenarioFinanceDesc.text = scenario.reason
                }
            }
        }

        // 6. Data Source Transparency
        if (report.sourceStatuses.size >= 2) {
            val s1 = report.sourceStatuses[0]
            binding.tvSource1Name.text = "1. ${s1.sourceName}"
            binding.tvSource1Status.text = if (s1.isSuccess) "响应正常 · ${s1.latencyMs}ms" else "请求失败/超时"
            binding.tvSource1Status.setTextColor(if (s1.isSuccess) Color.parseColor("#059669") else Color.parseColor("#EF4444"))
            binding.tvSource1Summary.text = s1.summary

            val s2 = report.sourceStatuses[1]
            binding.tvSource2Name.text = "2. ${s2.sourceName}"
            binding.tvSource2Status.text = if (s2.isSuccess) "响应正常 · ${s2.latencyMs}ms" else "请求失败/超时"
            binding.tvSource2Status.setTextColor(if (s2.isSuccess) Color.parseColor("#059669") else Color.parseColor("#EF4444"))
            binding.tvSource2Summary.text = s2.summary
        }

        // 7. Network Detail
        binding.tvDeviceNetwork.text = getLocalNetworkDescription()
        binding.tvDetailIp.text = report.ip
        binding.tvDetailLocation.text = "${report.flag} ${report.country} · ${report.city} (${report.countryCode})"
        binding.tvDetailFraud.text = "${report.level.title} (得分: ${report.score})"
        binding.tvDetailFraud.setTextColor(Color.parseColor(report.level.colorHex))

        binding.tvDetailHosting.text = when {
            report.isHosting.isTrue -> "是 (Hosting / 机房数据中心)"
            report.isHosting.isFalse -> "否 (Residential / 原生家宽)"
            else -> "未知 (数据不足)"
        }

        binding.tvDetailProxy.text = when {
            report.isProxy.isTrue -> "是 (Proxy / VPN 出口标记)"
            report.isProxy.isFalse -> "否 (未标记为公共 VPN)"
            else -> "未知 (未标记)"
        }

        binding.tvDetailMobile.text = when {
            report.isMobile.isTrue -> "是 (移动运营商基站出口)"
            report.isMobile.isFalse -> "否 (固网出口)"
            else -> "未知"
        }

        binding.tvDetailIsp.text = report.isp
        binding.tvDetailAsn.text = report.asn
        binding.tvDetailReverse.text = report.reverseDns
    }

    private fun renderError(error: String) {
        binding.cardStatus.setCardBackgroundColor(Color.parseColor("#DC2626"))
        binding.tvScoreNumber.text = "0"
        binding.tvScoreBadge.text = "检测失败"
        binding.tvStatusTitle.text = "未能获取完整 IP 情报"
        binding.tvStatusDesc.text = error
    }

    private fun getLocalNetworkDescription(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return getString(R.string.unknown)
            val network = cm.activeNetwork ?: return "未联网"
            val caps = cm.getNetworkCapabilities(network) ?: return getString(R.string.unknown)
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.ip_purity_device_cellular) + " (手机当前物理连接)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.ip_purity_device_wifi) + " (手机当前物理连接)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> getString(R.string.ip_purity_device_other) + " (以太网)"
                else -> getString(R.string.ip_purity_device_other)
            }
        } catch (_: Exception) {
            getString(R.string.unknown)
        }
    }
}
