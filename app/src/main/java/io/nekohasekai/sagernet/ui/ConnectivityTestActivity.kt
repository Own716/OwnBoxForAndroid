package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import kotlinx.coroutines.*
import libcore.Libcore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Random

class ConnectivityTestActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: ActivityConnectivityTestBinding
    private lateinit var adapter: ConnectivityAdapter
    private var testJob: Job? = null

    // AIDL service connection for urlTestFull via running VPN core (bypasses routing rules)
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_CONNECTIVITY_TEST)

    @Volatile
    private var sagerService: ISagerNetService? = null

    enum class TestState {
        TESTING,
        SUCCESS,
        WARNING,
        FAILED,
        NOT_CONNECTED
    }

    data class DimensionItem(
        val id: String,
        val name: String,
        val category: String,
        val iconRes: Int,
        var state: TestState = TestState.TESTING,
        var statusText: String = "检测中...",
        var description: String = "正在执行网络连通性探测...",
    )

    // --- SagerConnection.Callback ---

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onServiceConnected(service: ISagerNetService) {
        sagerService = service
    }

    override fun onServiceDisconnected() {
        sagerService = null
    }

    // --- Lifecycle ---

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

        adapter = ConnectivityAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnRetest.setOnClickListener {
            startAllTests()
        }

        // Bind to running VPN service so we can call urlTestCustomUrl via AIDL
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

    // --- Test Orchestration ---

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

        val items = listOf(
            DimensionItem(
                id = "tcp_direct",
                name = "大陆直连 TCP 握手",
                category = "入口可达性探测",
                iconRes = R.drawable.ic_tool_connectivity,
                state = TestState.TESTING,
                statusText = "握手中...",
                description = "正在向节点服务器发起原生直连 TCP 握手探测..."
            ),
            DimensionItem(
                id = "tcp_rst",
                name = "TCP RST 阻断探测",
                category = "GFW 干扰探测",
                iconRes = R.drawable.ic_baseline_security_24,
                state = TestState.TESTING,
                statusText = "探测中...",
                description = "正在向目标端口发送探测报文检测是否存在 GFW 伪造重置包..."
            ),
            DimensionItem(
                id = "http_outbound",
                name = "经核心出站 HTTP 延迟",
                category = "代理链路全流程",
                iconRes = R.drawable.ic_baseline_http_24,
                state = TestState.TESTING,
                statusText = "测速中...",
                description = "正在通过 sing-box 核心代理链路探测出站 HTTP 延迟..."
            ),
            DimensionItem(
                id = "google_cn_check",
                name = "Google 送中检测",
                category = "出口质量检测",
                iconRes = R.drawable.baseline_public_24,
                state = TestState.TESTING,
                statusText = "检测中...",
                description = "正在检测 Google 流量是否被导向中国大陆服务器（送中）..."
            )
        )

        adapter.submitList(items.toList())

        testJob = lifecycleScope.launch {
            val currentList = items.map { it.copy() }.toMutableList()

            val item1 = testDirectTcp(currentList[0], host, port)
            currentList[0] = item1
            adapter.submitList(currentList.toList())

            val item2 = testTcpRst(currentList[1], host, port, bean as? StandardV2RayBean)
            currentList[1] = item2
            adapter.submitList(currentList.toList())

            val item3 = testHttpOutbound(currentList[2])
            currentList[2] = item3
            adapter.submitList(currentList.toList())

            val item4 = testGoogleCnCheck(currentList[3])
            currentList[3] = item4
            adapter.submitList(currentList.toList())
        }
    }

    // --- Test: Direct TCP ---

    private suspend fun testDirectTcp(
        item: DimensionItem,
        host: String,
        port: Int
    ): DimensionItem = withContext(Dispatchers.IO) {
        if (host.isBlank() || port <= 0) {
            return@withContext item.copy(
                state = TestState.WARNING,
                statusText = "未配置",
                description = "节点未配置有效的服务器地址或端口"
            )
        }

        try {
            val socket = Socket()
            val start = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(host, port), 5000)
            val rtt = SystemClock.elapsedRealtime() - start
            socket.close()

            item.copy(
                state = TestState.SUCCESS,
                statusText = "${rtt} ms",
                description = "直连握手成功，服务器入口节点物理网络畅通"
            )
        } catch (e: SocketTimeoutException) {
            item.copy(
                state = TestState.FAILED,
                statusText = "握手超时",
                description = "直连握手超时 (>5000ms)，服务器入口可能已离线或被阻断"
            )
        } catch (e: UnknownHostException) {
            item.copy(
                state = TestState.FAILED,
                statusText = "域名无法解析",
                description = "本地 DNS 无法解析域名: ${host}"
            )
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("refused", ignoreCase = true) || msg.contains("ECONNREFUSED", ignoreCase = true)) {
                item.copy(
                    state = TestState.FAILED,
                    statusText = "连接被拒绝",
                    description = "目标服务器端口 (${port}) 未处于监听状态或被防火墙拦截"
                )
            } else {
                item.copy(
                    state = TestState.FAILED,
                    statusText = "连接失败",
                    description = "直连 TCP 失败: ${if (msg.isEmpty()) "未知异常" else msg}"
                )
            }
        }
    }

    // --- Test: TCP RST ---

    private suspend fun testTcpRst(
        item: DimensionItem,
        host: String,
        port: Int,
        v2rayBean: StandardV2RayBean?
    ): DimensionItem = withContext(Dispatchers.IO) {
        if (host.isBlank() || port <= 0) {
            return@withContext item.copy(
                state = TestState.WARNING,
                statusText = "未配置",
                description = "节点缺少入口主机或端口信息"
            )
        }

        try {
            val socket = Socket()
            socket.soTimeout = 3000
            socket.connect(InetSocketAddress(host, port), 5000)

            val sni = v2rayBean?.sni?.takeIf { it.isNotBlank() } ?: host
            val probe = buildTlsClientHelloProbe(sni)

            socket.getOutputStream().write(probe)
            socket.getOutputStream().flush()

            val buf = ByteArray(512)
            try {
                socket.getInputStream().read(buf)
            } catch (_: SocketTimeoutException) {
                // Read timeout is normal
            }
            socket.close()

            item.copy(
                state = TestState.SUCCESS,
                statusText = "未受干扰",
                description = "连接探测未触发 GFW TCP RST 伪造重置干扰，链路状态正常"
            )
        } catch (e: SocketException) {
            val msg = e.message.orEmpty()
            if (msg.contains("reset", ignoreCase = true) ||
                msg.contains("ECONNRESET", ignoreCase = true) ||
                msg.contains("Broken pipe", ignoreCase = true)
            ) {
                item.copy(
                    state = TestState.FAILED,
                    statusText = "触发 RST 阻断",
                    description = "检测到 GFW 发送 TCP RST 伪造重置包，连接已被即时阻断"
                )
            } else if (msg.contains("refused", ignoreCase = true)) {
                item.copy(
                    state = TestState.WARNING,
                    statusText = "端口拒绝",
                    description = "服务器端口未开放，未能完成阻断探测"
                )
            } else {
                item.copy(
                    state = TestState.WARNING,
                    statusText = "连接异常",
                    description = "探测过程网络异常: ${msg}"
                )
            }
        } catch (e: Throwable) {
            item.copy(
                state = TestState.WARNING,
                statusText = "探测超时",
                description = "未收到服务器有效响应: ${e.message ?: "网络超时"}"
            )
        }
    }

    // --- Test: HTTP Outbound ---
    // Fix 1: Primary path uses ISagerNetService.urlTestCustomUrl() via AIDL.
    //         Calls Libcore.urlTestFull() which dials directly through default outbound,
    //         bypassing sing-box routing rules. Accepts any HTTP status < 400 (incl. 204).
    // Fix 2: Fallback uses libcore httpClient with 2xx error message detection.

    private suspend fun testHttpOutbound(item: DimensionItem): DimensionItem = withContext(Dispatchers.IO) {
        val testUrl = DataStore.connectionTestURL.takeIf { it.isNotBlank() }
            ?: "https://cp.cloudflare.com/generate_204"

        val service = sagerService

        if (service != null) {
            // Path A: AIDL urlTestCustomUrl -> urlTestFull (bypasses routing, accepts 204)
            try {
                val rtt = service.urlTestCustomUrl(testUrl, 10_000)
                return@withContext item.copy(
                    state = TestState.SUCCESS,
                    statusText = "${rtt} ms",
                    description = "经代理核心 default outbound 出站成功（已绕过路由规则），HTTP 链路通畅"
                )
            } catch (e: Throwable) {
                val msg = e.message.orEmpty()
                if (msg.contains("core not started", ignoreCase = true) ||
                    msg.contains("not started", ignoreCase = true)
                ) {
                    return@withContext item.copy(
                        state = TestState.NOT_CONNECTED,
                        statusText = "核心未启动",
                        description = "VPN 代理服务核心未就绪，请在主界面连接后再测试出站延迟"
                    )
                }
                Logs.w("urlTestCustomUrl via AIDL failed, fallback to libcore httpClient: ${msg}")
            }
        }

        // Path B: libcore httpClient fallback with 2xx compatibility
        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            val req = client.newRequest().apply {
                setURL(testUrl)
                setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            val start = SystemClock.elapsedRealtime()
            try {
                req.execute()
                val rtt = SystemClock.elapsedRealtime() - start
                item.copy(
                    state = TestState.SUCCESS,
                    statusText = "${rtt} ms",
                    description = "经代理核心出站成功，HTTP 链路整体通畅"
                )
            } catch (ex: Throwable) {
                val rtt = SystemClock.elapsedRealtime() - start
                val exMsg = ex.message.orEmpty()
                if (isHttpSuccessErrorMessage(exMsg)) {
                    val status = extractHttpStatus(exMsg)
                    item.copy(
                        state = TestState.SUCCESS,
                        statusText = "${rtt} ms",
                        description = "经代理核心出站成功（HTTP ${status} 响应），链路通畅"
                    )
                } else {
                    throw ex
                }
            }
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("box not running", ignoreCase = true) ||
                msg.contains("fail connect socks5", ignoreCase = true) ||
                msg.contains("no default outbound", ignoreCase = true) ||
                (service == null && msg.contains("not started", ignoreCase = true))
            ) {
                item.copy(
                    state = TestState.NOT_CONNECTED,
                    statusText = "核心未启动",
                    description = "VPN 代理服务未开启，请在主页连接后再测试出站延迟"
                )
            } else {
                item.copy(
                    state = TestState.FAILED,
                    statusText = "出站失败",
                    description = "代理链路出站异常: ${if (msg.isEmpty()) "连接超时" else msg}"
                )
            }
        }
    }

    // libcore Execute() errorString() format: "HTTP <code> <reason>: <body>"
    // Returns true for 2xx/3xx which are network successes.
    private fun isHttpSuccessErrorMessage(msg: String): Boolean {
        val m = Regex("""^HTTP\s+(\d{3})""").find(msg) ?: return false
        val code = m.groupValues[1].toIntOrNull() ?: return false
        return code in 200..399
    }

    private fun extractHttpStatus(msg: String): String {
        return Regex("""^HTTP\s+(\d{3}\s+\S+)""").find(msg)?.groupValues?.get(1)
            ?: Regex("""^HTTP\s+(\d{3})""").find(msg)?.groupValues?.get(1)
            ?: "2xx"
    }

    // --- Test: Google China Routing Detection ---
    // Uses SOCKS5 via local mixed inbound. Redirect-following disabled to catch 301->google.cn.

    private suspend fun testGoogleCnCheck(item: DimensionItem): DimensionItem = withContext(Dispatchers.IO) {
        val testUrl = "https://www.google.com/generate_204"
        val mixedPort = DataStore.mixedPort

        if (!DataStore.serviceState.connected) {
            return@withContext item.copy(
                state = TestState.NOT_CONNECTED,
                statusText = "未连接",
                description = "VPN 代理服务未开启，送中检测需要代理服务运行才能执行"
            )
        }

        if (mixedPort <= 0) {
            return@withContext item.copy(
                state = TestState.WARNING,
                statusText = "混合端口未配置",
                description = "未找到有效的本地混合代理端口，无法通过代理执行送中检测"
            )
        }

        try {
            val socks5Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", mixedPort))
            val url = URL(testUrl)
            val conn = url.openConnection(socks5Proxy) as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36")

            try {
                conn.connect()
                val code = conn.responseCode
                val location = conn.getHeaderField("Location").orEmpty()

                when {
                    code == 204 -> item.copy(
                        state = TestState.SUCCESS,
                        statusText = "未送中 ✓",
                        description = "出口正常，Google 返回 204 No Content，未被导向中国大陆服务器"
                    )
                    (code == 301 || code == 302) && location.contains("google.cn", ignoreCase = true) -> item.copy(
                        state = TestState.FAILED,
                        statusText = "已送中 ✗",
                        description = "警告：出口将 Google 重定向至 ${location.substringBefore("?")}，流量已被送往中国大陆"
                    )
                    (code == 301 || code == 302) -> item.copy(
                        state = TestState.WARNING,
                        statusText = "重定向 ${code}",
                        description = "Google 响应 ${code} 重定向至: ${location.take(80)}（非 google.cn，但需确认）"
                    )
                    code in 200..299 -> item.copy(
                        state = TestState.SUCCESS,
                        statusText = "未送中 ✓",
                        description = "出口正常，Google 返回 ${code}，未被导向中国大陆服务器"
                    )
                    else -> item.copy(
                        state = TestState.WARNING,
                        statusText = "HTTP ${code}",
                        description = "Google 返回异常状态码 ${code}，无法确认送中状态"
                    )
                }
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: SocketTimeoutException) {
            item.copy(
                state = TestState.FAILED,
                statusText = "检测超时",
                description = "通过代理访问 Google 超时（>10s），可能为代理连接问题或 Google 不可达"
            )
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("SOCKS", ignoreCase = true) || msg.contains("127.0.0.1", ignoreCase = true)) {
                item.copy(
                    state = TestState.NOT_CONNECTED,
                    statusText = "代理未就绪",
                    description = "无法连接到本地代理端口 ${mixedPort}，请确认 VPN 服务已正常运行"
                )
            } else {
                item.copy(
                    state = TestState.FAILED,
                    statusText = "检测失败",
                    description = "送中检测异常: ${if (msg.isEmpty()) "未知错误" else msg}"
                )
            }
        }
    }

    // --- Helper: Build TLS ClientHello probe packet ---

    private fun buildTlsClientHelloProbe(serverName: String): ByteArray {
        val sniBytes = serverName.toByteArray(Charsets.UTF_8)
        val sniExtension = ByteArray(sniBytes.size + 9).apply {
            this[0] = 0x00
            this[1] = 0x00
            val extLen = sniBytes.size + 5
            this[2] = (extLen shr 8).toByte()
            this[3] = (extLen and 0xFF).toByte()
            val listLen = sniBytes.size + 3
            this[4] = (listLen shr 8).toByte()
            this[5] = (listLen and 0xFF).toByte()
            this[6] = 0x00
            this[7] = (sniBytes.size shr 8).toByte()
            this[8] = (sniBytes.size and 0xFF).toByte()
            System.arraycopy(sniBytes, 0, this, 9, sniBytes.size)
        }

        val handshake = ByteArrayOutputStream().apply {
            write(0x01)
            val body = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x03, 0x03))
                val random = ByteArray(32)
                Random().nextBytes(random)
                write(random)
                write(0x00)
                write(byteArrayOf(0x00, 0x04, 0x13.toByte(), 0x01, 0xc0.toByte(), 0x2f))
                write(0x01)
                write(0x00)
                val extLen = sniExtension.size
                write(byteArrayOf((extLen shr 8).toByte(), (extLen and 0xFF).toByte()))
                write(sniExtension)
            }.toByteArray()
            val len = body.size
            write(byteArrayOf((len shr 16).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()))
            write(body)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x16)
            write(byteArrayOf(0x03, 0x01))
            val len = handshake.size
            write(byteArrayOf((len shr 8).toByte(), (len and 0xFF).toByte()))
            write(handshake)
        }.toByteArray()
    }

    // --- RecyclerView Adapter ---

    class ConnectivityAdapter : ListAdapter<DimensionItem, ConnectivityAdapter.VH>(DiffCallback) {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_dimension_icon)
            val name: TextView = view.findViewById(R.id.tv_dimension_name)
            val category: TextView = view.findViewById(R.id.tv_dimension_category)
            val progress: CircularProgressIndicator = view.findViewById(R.id.progress_indicator)
            val statusBadge: TextView = view.findViewById(R.id.tv_status_badge)
            val description: TextView = view.findViewById(R.id.tv_description)
        }

        object DiffCallback : DiffUtil.ItemCallback<DimensionItem>() {
            override fun areItemsTheSame(oldItem: DimensionItem, newItem: DimensionItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DimensionItem, newItem: DimensionItem) =
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
            holder.category.text = item.category
            holder.description.text = item.description

            when (item.state) {
                TestState.TESTING -> {
                    holder.progress.visibility = View.VISIBLE
                    holder.statusBadge.visibility = View.GONE
                }
                TestState.SUCCESS -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = item.statusText
                    holder.statusBadge.setTextColor(Color.parseColor("#10B981"))
                }
                TestState.WARNING -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = item.statusText
                    holder.statusBadge.setTextColor(Color.parseColor("#F59E0B"))
                }
                TestState.FAILED -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = item.statusText
                    holder.statusBadge.setTextColor(Color.parseColor("#EF4444"))
                }
                TestState.NOT_CONNECTED -> {
                    holder.progress.visibility = View.GONE
                    holder.statusBadge.visibility = View.VISIBLE
                    holder.statusBadge.text = item.statusText
                    holder.statusBadge.setTextColor(Color.parseColor("#6B7280"))
                }
            }
        }
    }
}
