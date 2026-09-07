package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityIpPurityBinding
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

class IpPurityActivity : ThemedActivity() {

    private lateinit var binding: ActivityIpPurityBinding
    private var currentIpAddress: String = ""

    private val cloudKeywords = listOf(
        "Cloudflare" to "Cloudflare",
        "Amazon" to "AWS (Amazon Web Services)",
        "AWS" to "AWS (Amazon Web Services)",
        "DigitalOcean" to "DigitalOcean",
        "Google" to "Google Cloud Platform",
        "Microsoft" to "Microsoft Azure",
        "Azure" to "Microsoft Azure",
        "Alibaba" to "阿里云 (Alibaba Cloud)",
        "Aliyun" to "阿里云 (Alibaba Cloud)",
        "Tencent" to "腾讯云 (Tencent Cloud)",
        "Hetzner" to "Hetzner Online",
        "OVH" to "OVHcloud",
        "Linode" to "Linode / Akamai",
        "Akamai" to "Akamai",
        "Vultr" to "Vultr / Choopa",
        "Choopa" to "Vultr / Choopa",
        "Oracle" to "Oracle Cloud",
        "Hostinger" to "Hostinger",
        "Contabo" to "Contabo",
        "Datacamp" to "DataCamp / CDN77",
        "Leaseweb" to "Leaseweb",
        "Fastly" to "Fastly",
        "M247" to "M247 Ltd",
        "Zenlayer" to "Zenlayer"
    )

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

    private fun openExternalReport(url: String) {
        if (currentIpAddress.isBlank()) {
            Toast.makeText(this, "尚未获取到有效 IP 地址", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCheck() {
        if (!DataStore.serviceState.connected) {
            binding.refreshLayout.isRefreshing = false
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            showNotConnectedState()
            return
        }

        binding.refreshLayout.isRefreshing = true
        binding.tvStatusTitle.text = "正在检测 IP 纯净度..."
        binding.tvStatusDesc.text = "通过当前代理节点出口探测托管属性与 ASN 归属..."
        binding.cardStatus.setCardBackgroundColor(Color.parseColor("#475569"))

        lifecycleScope.launch {
            val result = queryPurityData()
            binding.refreshLayout.isRefreshing = false

            result.onSuccess { data ->
                renderResult(data)
            }.onFailure { e ->
                renderError(e.message ?: "检测失败")
            }
        }
    }

    private fun showNotConnectedState() {
        binding.cardStatus.setCardBackgroundColor(Color.parseColor("#64748B"))
        binding.tvStatusTitle.text = "VPN 未连接"
        binding.tvStatusDesc.text = getString(R.string.vpn_not_connected_warning)
    }

    private suspend fun queryPurityData(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            val req = client.newRequest().apply {
                setURL("http://ip-api.com/json/?fields=status,message,country,countryCode,regionName,city,isp,org,as,asname,reverse,mobile,proxy,hosting,query")
                setUserAgent(USER_AGENT)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val json = JSONObject(body)
            if (json.optString("status") == "success") {
                Result.success(json)
            } else {
                Result.failure(Exception(json.optString("message", "IP 接口返回错误")))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun renderResult(json: JSONObject) {
        val ip = json.optString("query")
        currentIpAddress = ip
        val country = json.optString("country")
        val countryCode = json.optString("countryCode")
        val city = json.optString("city")
        val flag = LandingIpManager.countryCodeToFlagEmoji(countryCode)
        val isp = json.optString("isp")
        val org = json.optString("org")
        val asn = json.optString("as")
        val reverse = json.optString("reverse")
        val isHosting = json.optBoolean("hosting", false)
        val isProxy = json.optBoolean("proxy", false)
        val isMobile = json.optBoolean("mobile", false)

        binding.tvDeviceNetwork.text = getLocalNetworkDescription()
        binding.tvDetailIp.text = ip
        binding.tvDetailLocation.text = "$flag $country · $city ($countryCode)"
        binding.tvDetailHosting.text = if (isHosting) "是 (Hosting / 机房数据中心)" else "否 (Residential / 原生家宽)"
        binding.tvDetailProxy.text = if (isProxy) "是 (Proxy / VPN 出口标记)" else "否 (未标记为公共 VPN)"
        binding.tvDetailMobile.text = if (isMobile) "是 (移动运营商基站出口)" else "否 (机房固网 / 常规固网宽带出口)"
        binding.tvDetailIsp.text = isp.ifBlank { "未知" }
        binding.tvDetailAsn.text = asn.ifBlank { "未知" }
        binding.tvDetailReverse.text = reverse.ifBlank { "无反向解析记录" }

        // Find matched cloud provider
        val combinedText = "$asn $org $isp".uppercase()
        var matchedCloud: String? = null
        for ((keyword, name) in cloudKeywords) {
            if (combinedText.contains(keyword.uppercase())) {
                matchedCloud = name
                break
            }
        }

        when {
            isHosting || isProxy || matchedCloud != null -> {
                // Datacenter / Cloud / Proxy IP
                binding.cardStatus.setCardBackgroundColor(Color.parseColor("#E11D48")) // Rose Red
                binding.tvStatusTitle.text = getString(R.string.ip_purity_status_datacenter)
                val cloudDesc = matchedCloud?.let { "；所属云商：$it" } ?: ""
                binding.tvStatusDesc.text = "已被标记为托管机房、数据中心或公共代理出口$cloudDesc"
                binding.tvDetailFraud.text = "中高风险 (数据中心机房 IP，易触发风控验证)"
                binding.tvDetailFraud.setTextColor(Color.parseColor("#E11D48"))
            }
            json.has("hosting") && !isHosting && !isProxy -> {
                // Pure Residential
                binding.cardStatus.setCardBackgroundColor(Color.parseColor("#059669")) // Emerald Green
                binding.tvStatusTitle.text = getString(R.string.ip_purity_status_pure)
                binding.tvStatusDesc.text = "该 IP 属于原生住宅宽带，纯净度极高，风控风险低"
                binding.tvDetailFraud.text = "极低风险 (原生住宅宽带，风控通过率极高)"
                binding.tvDetailFraud.setTextColor(Color.parseColor("#059669"))
            }
            else -> {
                // Unknown
                binding.cardStatus.setCardBackgroundColor(Color.parseColor("#64748B")) // Slate Gray
                binding.tvStatusTitle.text = getString(R.string.ip_purity_status_unknown)
                binding.tvStatusDesc.text = "接口未返回确切的托管字段，建议结合实际网络使用情况评判"
                binding.tvDetailFraud.text = "中等风险 (未明确标记托管属性)"
                binding.tvDetailFraud.setTextColor(Color.parseColor("#F59E0B"))
            }
        }
    }

    private fun renderError(error: String) {
        binding.cardStatus.setCardBackgroundColor(Color.parseColor("#DC2626"))
        binding.tvStatusTitle.text = "检测失败"
        binding.tvStatusDesc.text = error
    }

    private fun getLocalNetworkDescription(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return getString(R.string.unknown)
            val network = cm.activeNetwork ?: return "未联网"
            val caps = cm.getNetworkCapabilities(network) ?: return getString(R.string.unknown)
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.ip_purity_device_cellular) + " (当前手机物理连接)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.ip_purity_device_wifi) + " (当前手机物理连接)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> getString(R.string.ip_purity_device_other) + " (以太网)"
                else -> getString(R.string.ip_purity_device_other)
            }
        } catch (_: Exception) {
            getString(R.string.unknown)
        }
    }
}
