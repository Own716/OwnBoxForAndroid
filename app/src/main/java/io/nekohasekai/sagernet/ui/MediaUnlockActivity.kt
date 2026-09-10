package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
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
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.ActivityMediaUnlockBinding
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.*
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.util.regex.Pattern

class MediaUnlockActivity : ThemedActivity() {

    private lateinit var binding: ActivityMediaUnlockBinding
    private lateinit var adapter: MediaUnlockAdapter
    private var testJob: Job? = null

    enum class TestState {
        TESTING,
        UNLOCKED,
        PARTIAL,
        BLOCKED,
        TIMEOUT,
        NOT_CONNECTED
    }

    data class MediaItem(
        val id: String,
        val name: String,
        val category: String,
        val iconRes: Int,
        var state: TestState = TestState.TESTING,
        var statusText: String = "检测中...",
        var description: String = "正在探测服务可用性...",
        var region: String? = null,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaUnlockBinding.inflate(layoutInflater)
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
            setTitle(R.string.media_unlock_title)
        }

        adapter = MediaUnlockAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnRetest.setOnClickListener {
            startAllTests()
        }

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
    }

    private fun startAllTests() {
        testJob?.cancel()

        val currentProfile = ProfileManager.getProfile(DataStore.selectedProxy)
        binding.tvCurrentNode.text = currentProfile?.displayName() ?: getString(R.string.not_connected)

        val cachedIp = LandingIpManager.getCachedInfo()
        binding.tvCurrentIp.text = if (cachedIp != null) "出口 IP: ${cachedIp.briefText}" else "出口 IP: 查询中..."

        if (!DataStore.serviceState.connected) {
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            val initialList = createDefaultItems(TestState.NOT_CONNECTED)
            adapter.submitList(initialList)
            return
        }

        val items = createDefaultItems(TestState.TESTING)
        adapter.submitList(items)

        testJob = lifecycleScope.launch {
            if (cachedIp == null) {
                val ipRes = LandingIpManager.queryLandingIp(DataStore.selectedProxy)
                ipRes.onSuccess {
                    binding.tvCurrentIp.text = "出口 IP: ${it.briefText}"
                }
            }

            // Launch detection concurrently
            items.forEachIndexed { index, item ->
                launch {
                    val tested = runTestForItem(item)
                    withContext(Dispatchers.Main) {
                        items[index] = tested
                        adapter.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    private fun createDefaultItems(initialState: TestState): MutableList<MediaItem> {
        val notConnected = initialState == TestState.NOT_CONNECTED
        fun defDesc(name: String) = if (notConnected) "VPN 未连接，请先连接代理节点" else "正在探测 $name 区域授权与访问限制..."
        fun defStatus() = if (notConnected) "未连接" else "检测中..."

        return mutableListOf(
            MediaItem("netflix", "Netflix", "流媒体服务", R.drawable.ic_platform_netflix, initialState, defStatus(), defDesc("Netflix")),
            MediaItem("disney", "Disney+", "流媒体服务", R.drawable.ic_platform_disney, initialState, defStatus(), defDesc("Disney+")),
            MediaItem("max", "Max (HBO)", "流媒体服务", R.drawable.ic_platform_max, initialState, defStatus(), defDesc("Max")),
            MediaItem("prime", "Prime Video", "流媒体服务", R.drawable.ic_platform_prime, initialState, defStatus(), defDesc("Amazon Prime Video")),
            MediaItem("youtube", "YouTube Premium", "流媒体服务", R.drawable.ic_platform_youtube, initialState, defStatus(), defDesc("YouTube Premium")),
            MediaItem("tiktok", "TikTok", "流媒体服务", R.drawable.ic_platform_tiktok, initialState, defStatus(), defDesc("TikTok")),
            MediaItem("spotify", "Spotify", "音乐音频服务", R.drawable.ic_platform_spotify, initialState, defStatus(), defDesc("Spotify")),
            MediaItem("chatgpt", "ChatGPT (OpenAI)", "AI 智能服务", R.drawable.ic_platform_chatgpt, initialState, defStatus(), defDesc("ChatGPT")),
            MediaItem("claude", "Claude (Anthropic)", "AI 智能服务", R.drawable.ic_platform_claude, initialState, defStatus(), defDesc("Claude")),
            MediaItem("gemini", "Google Gemini", "AI 智能服务", R.drawable.ic_platform_gemini, initialState, defStatus(), defDesc("Gemini"))
        )
    }

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val CLAUDE_UNSUPPORTED_REGIONS = setOf(
            "HK", "MO", "CN", "RU", "BY", "IR", "KP", "SY", "CU"
        )

        private fun libcore.HTTPRequest.applyBrowserHeaders(host: String? = null) {
            setUserAgent(BROWSER_USER_AGENT)
            setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            setHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
            setHeader("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
            setHeader("sec-ch-ua-mobile", "?0")
            setHeader("sec-ch-ua-platform", "\"Windows\"")
            setHeader("sec-fetch-dest", "document")
            setHeader("sec-fetch-mode", "navigate")
            setHeader("sec-fetch-site", "none")
            setHeader("sec-fetch-user", "?1")
            setHeader("upgrade-insecure-requests", "1")
            if (host != null) {
                setHeader("Host", host)
            }
        }
    }

    private suspend fun runTestForItem(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        try {
            when (item.id) {
                "netflix" -> testNetflix(item)
                "disney" -> testDisney(item)
                "max" -> testMax(item)
                "prime" -> testPrime(item)
                "youtube" -> testYouTube(item)
                "tiktok" -> testTikTok(item)
                "spotify" -> testSpotify(item)
                "chatgpt" -> testChatGpt(item)
                "claude" -> testClaude(item)
                "gemini" -> testGemini(item)
                else -> item
            }
        } catch (e: CancellationException) {
            item
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("403") || msg.contains("Forbidden", ignoreCase = true) ||
                msg.contains("cf-mitigated", ignoreCase = true) || msg.contains("Just a moment", ignoreCase = true) ||
                msg.contains("Attention Required", ignoreCase = true) || msg.contains("Cloudflare", ignoreCase = true)
            ) {
                item.copy(
                    state = TestState.BLOCKED,
                    statusText = "受限/触发风控",
                    description = "HTTP 403 触发平台或 Cloudflare 安全风控拦截"
                )
            } else {
                item.copy(
                    state = TestState.TIMEOUT,
                    statusText = "检测超时",
                    description = "连接超时或网络异常: ${msg.ifEmpty { "未知错误" }}"
                )
            }
        }
    }

    // --- Specific Tests ---

    private suspend fun testNetflix(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }

        // Test licensed non-original: 81280792 (Breaking Bad)
        var body1 = ""
        var region = ""
        try {
            val req1 = client.newRequest().apply {
                setURL("https://www.netflix.com/title/81280792")
                applyBrowserHeaders("www.netflix.com")
            }
            val resp1 = req1.execute()
            body1 = Util.getStringBox(resp1.contentString)

            // Extract region from body or url
            val matcher = Pattern.compile("geolocation_country.*?([A-Za-z]{2})").matcher(body1)
            if (matcher.find()) {
                region = matcher.group(1)?.uppercase() ?: ""
            }
        } catch (_: Throwable) {
        }

        val flag = if (region.isNotBlank()) LandingIpManager.countryCodeToFlagEmoji(region) + " " + region else ""

        if (body1.isNotBlank() && !body1.contains("page-404") && (body1.contains("title/81280792") || body1.contains("watch") || body1.contains("Breaking Bad"))) {
            return@withContext item.copy(
                state = TestState.UNLOCKED,
                statusText = if (flag.isNotBlank()) "完整解锁 $flag" else "完整原生解锁",
                description = "支持播放全部非自制版权剧集与 Netflix 原创自制剧",
                region = region
            )
        }

        // Test Netflix original: 80018499 (House of Cards)
        var body2 = ""
        try {
            val req2 = client.newRequest().apply {
                setURL("https://www.netflix.com/title/80018499")
                applyBrowserHeaders("www.netflix.com")
            }
            val resp2 = req2.execute()
            body2 = Util.getStringBox(resp2.contentString)
        } catch (_: Throwable) {
        }

        if (body2.isNotBlank() && (body2.contains("title/80018499") || body2.contains("watch") || body2.contains("House of Cards"))) {
            item.copy(
                state = TestState.PARTIAL,
                statusText = "仅自制剧",
                description = "仅支持播放 Netflix 自制内容，非自制版权剧集受限"
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "未解锁",
                description = "当前节点 IP 无法正常播放 Netflix 内容或被识别为代理"
            )
        }
    }

    private suspend fun testDisney(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.disneyplus.com/")
            applyBrowserHeaders("www.disneyplus.com")
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        val isBlocked = body.contains("not available in your region", ignoreCase = true) ||
                body.contains("is not available in your area", ignoreCase = true) ||
                body.contains("disneyplus.com/unavailable", ignoreCase = true) ||
                body.contains("restricted", ignoreCase = true)

        if (!isBlocked) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = "支持",
                description = "支持正常访问与播放 Disney+ 影视内容"
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "地区限制",
                description = "当前地区不在 Disney+ 官方服务范围内或已被限制"
            )
        }
    }

    private suspend fun testMax(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://auth.max.com/")
            applyBrowserHeaders("auth.max.com")
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("not available in your region", ignoreCase = true) &&
            !body.contains("unsupported_location", ignoreCase = true)
        ) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = "支持",
                description = "支持访问 Max (HBO) 流媒体服务与内容授权"
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "未解锁",
                description = "当前节点不支持 Max (HBO) 服务地区"
            )
        }
    }

    private suspend fun testPrime(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.primevideo.com/")
            applyBrowserHeaders("www.primevideo.com")
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("georestricted", ignoreCase = true) &&
            !body.contains("not-available-in-your-country", ignoreCase = true)
        ) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = "支持",
                description = "支持访问 Amazon Prime Video 流媒体版权库"
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "未解锁",
                description = "节点 IP 无法正常访问 Prime Video 或受地域限制"
            )
        }
    }

    private suspend fun testYouTube(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.youtube.com/premium")
            applyBrowserHeaders("www.youtube.com")
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        var countryCode = ""
        val matcher1 = Pattern.compile("\"countryCode\"\\s*:\\s*\"([A-Za-z]{2})\"").matcher(body)
        if (matcher1.find()) {
            countryCode = matcher1.group(1)?.uppercase() ?: ""
        }
        if (countryCode.isBlank()) {
            val matcher2 = Pattern.compile("\"INNERTUBE_CONTEXT_GL\"\\s*:\\s*\"([A-Za-z]{2})\"").matcher(body)
            if (matcher2.find()) {
                countryCode = matcher2.group(1)?.uppercase() ?: ""
            }
        }

        val isNotAvailable = body.contains("not available in your country", ignoreCase = true) ||
                body.contains("Premium is not available", ignoreCase = true)

        if (countryCode.isNotBlank() && !isNotAvailable) {
            val flag = LandingIpManager.countryCodeToFlagEmoji(countryCode)
            item.copy(
                state = TestState.UNLOCKED,
                statusText = "支持 $flag $countryCode",
                description = "支持开通与畅享 YouTube Premium 会员无广告服务",
                region = countryCode
            )
        } else if (body.contains("Premium") && !isNotAvailable) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = "支持",
                description = "支持开通与畅享 YouTube Premium 会员服务"
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "未解锁",
                description = "该地区或出口 IP 不支持 YouTube Premium"
            )
        }
    }

    private suspend fun testTikTok(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.tiktok.com/")
            applyBrowserHeaders("www.tiktok.com")
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        val matcher = Pattern.compile("\"region\"\\s*:\\s*\"([A-Za-z]{2})\"").matcher(body)
        var region = ""
        if (matcher.find()) {
            region = matcher.group(1)?.uppercase() ?: ""
        }

        if (!body.contains("tiktok-verify-page", ignoreCase = true)) {
            val flag = if (region.isNotBlank()) LandingIpManager.countryCodeToFlagEmoji(region) + " " + region else ""
            item.copy(
                state = TestState.UNLOCKED,
                statusText = if (flag.isNotBlank()) "支持 $flag" else "支持",
                description = "支持正常浏览 TikTok 国际版短视频与直播内容",
                region = region
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "未解锁",
                description = "无法正常访问 TikTok 或触发人机风控拦截"
            )
        }
    }

    private suspend fun testSpotify(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        val req = client.newRequest().apply {
            setURL("https://www.spotify.com/")
            applyBrowserHeaders("www.spotify.com")
        }
        val resp = req.execute()
        val body = Util.getStringBox(resp.contentString)

        if (!body.contains("not available in your country", ignoreCase = true)) {
            item.copy(
                state = TestState.UNLOCKED,
                statusText = "支持",
                description = "支持 Spotify 歌曲播放与客户端正常登录"
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "未解锁",
                description = "当前出口 IP 所在地区暂未开放 Spotify 服务"
            )
        }
    }

    private suspend fun testChatGpt(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }

        // 1. 优先尝试 Web 网页端
        var webSuccess = false
        var cfChallenge = false
        try {
            val req = client.newRequest().apply {
                setURL("https://chatgpt.com/")
                applyBrowserHeaders("chatgpt.com")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (!body.contains("cf-mitigated") &&
                !body.contains("Attention Required") &&
                !body.contains("1020") &&
                !body.contains("Just a moment")
            ) {
                webSuccess = true
            } else {
                cfChallenge = true
            }
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("403") || msg.contains("Forbidden", ignoreCase = true) ||
                msg.contains("cf-mitigated", ignoreCase = true) || msg.contains("Just a moment", ignoreCase = true) ||
                msg.contains("1020")
            ) {
                cfChallenge = true
            }
        }

        if (webSuccess) {
            return@withContext item.copy(
                state = TestState.UNLOCKED,
                statusText = "支持",
                description = "无 Cloudflare 拦截，网页端与 API 可正常对话"
            )
        }

        // 2. 若 Web 端遇到 Cloudflare 拦截/403，探测移动端端点双链路兜底
        try {
            val mobileReq = client.newRequest().apply {
                setURL("https://ios.chat.openai.com/public-api/mobile/server_status/v1")
                setUserAgent("ChatGPT/1.2024.135 (iOS 17.5.1; iPhone15,2)")
                setHeader("Accept", "application/json")
            }
            val mobileResp = mobileReq.execute()
            val mobileBody = Util.getStringBox(mobileResp.contentString)
            if (mobileBody.contains("status") || mobileBody.contains("ok") || mobileBody.contains("normal") || mobileBody.contains("maintenance")) {
                return@withContext item.copy(
                    state = TestState.UNLOCKED,
                    statusText = "支持 (App/API)",
                    description = "网页端触发 Cloudflare 验证，但官方 App / API 链路可用"
                )
            }
        } catch (_: Throwable) {
        }

        if (cfChallenge) {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "受限/触发风控",
                description = "HTTP 403 触发 Cloudflare/OpenAI 平台风控拦截"
            )
        } else {
            item.copy(
                state = TestState.BLOCKED,
                statusText = "未解锁",
                description = "节点 IP 无法正常连接 ChatGPT 官方服务"
            )
        }
    }

    private suspend fun testClaude(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        // 1. 先行对 Anthropic 明确不支持的地区（如香港 HK、中国大陆 CN、澳门 MO、俄罗斯 RU 等）进行过滤，杜绝虚假解锁
        val currentCountry = LandingIpManager.getCachedInfo()?.countryCode?.uppercase()
        if (currentCountry != null && currentCountry in CLAUDE_UNSUPPORTED_REGIONS) {
            val flag = LandingIpManager.countryCodeToFlagEmoji(currentCountry)
            return@withContext item.copy(
                state = TestState.BLOCKED,
                statusText = "地区受限 $flag $currentCountry",
                description = "Anthropic 官方未在该地区开放 Claude 访问服务"
            )
        }

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        try {
            val req = client.newRequest().apply {
                setURL("https://claude.ai/login")
                applyBrowserHeaders("claude.ai")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            val isBlocked = body.contains("App unavailable in your region", ignoreCase = true) ||
                    body.contains("not available in your country", ignoreCase = true) ||
                    body.contains("not available in your region", ignoreCase = true) ||
                    body.contains("claude.ai/unavailable", ignoreCase = true) ||
                    body.contains("unsupported_location", ignoreCase = true) ||
                    body.contains("403 Forbidden", ignoreCase = true)

            if (!isBlocked && (body.contains("Continue with Google") || body.contains("email") || body.contains("Claude") || body.contains("login"))) {
                val flag = if (!currentCountry.isNullOrBlank()) " " + LandingIpManager.countryCodeToFlagEmoji(currentCountry) + " " + currentCountry else ""
                item.copy(
                    state = TestState.UNLOCKED,
                    statusText = "支持$flag",
                    description = "支持访问 Anthropic Claude，区域授权正常开放"
                )
            } else {
                val flag = if (!currentCountry.isNullOrBlank()) " " + LandingIpManager.countryCodeToFlagEmoji(currentCountry) + " " + currentCountry else ""
                item.copy(
                    state = TestState.BLOCKED,
                    statusText = "地区受限$flag",
                    description = "当前节点所在地区尚未开放 Claude 访问服务"
                )
            }
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("403") || msg.contains("Forbidden", ignoreCase = true) ||
                msg.contains("cf-mitigated", ignoreCase = true) || msg.contains("Just a moment", ignoreCase = true)
            ) {
                item.copy(
                    state = TestState.BLOCKED,
                    statusText = "受限/触发风控",
                    description = "HTTP 403 触发 Cloudflare/Claude 平台风控拦截"
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun testGemini(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val client = Libcore.newHttpClient().apply {
            modernTLS()
            tryProxyOutbound()
        }
        try {
            val req = client.newRequest().apply {
                setURL("https://gemini.google.com/")
                applyBrowserHeaders("gemini.google.com")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (!body.contains("not supported in your country", ignoreCase = true) &&
                !body.contains("unavailable in your territory", ignoreCase = true)
            ) {
                item.copy(
                    state = TestState.UNLOCKED,
                    statusText = "支持",
                    description = "支持全功能正常使用 Google Gemini AI 模型与对话"
                )
            } else {
                item.copy(
                    state = TestState.BLOCKED,
                    statusText = "未开放",
                    description = "Google Gemini 暂未对该地区或机房 IP 开放服务"
                )
            }
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if (msg.contains("403") || msg.contains("Forbidden", ignoreCase = true)) {
                item.copy(
                    state = TestState.BLOCKED,
                    statusText = "受限/触发风控",
                    description = "HTTP 403 触发 Google 平台风控拦截"
                )
            } else {
                throw e
            }
        }
    }

    // --- Adapter ---

    class MediaUnlockAdapter : ListAdapter<MediaItem, MediaUnlockAdapter.VH>(DiffCallback) {

        object DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_platform_icon)
            val name: TextView = view.findViewById(R.id.tv_platform_name)
            val category: TextView = view.findViewById(R.id.tv_platform_category)
            val progress: CircularProgressIndicator = view.findViewById(R.id.progress_indicator)
            val badge: TextView = view.findViewById(R.id.tv_status_badge)
            val desc: TextView = view.findViewById(R.id.tv_description)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_unlock, parent, false)
            return VH(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.icon.setImageResource(item.iconRes)
            holder.name.text = item.name
            holder.category.text = item.category
            holder.desc.text = item.description

            when (item.state) {
                TestState.TESTING -> {
                    holder.progress.visibility = View.VISIBLE
                    holder.badge.visibility = View.GONE
                }
                TestState.UNLOCKED -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor(Color.parseColor("#10B981")) // Green
                }
                TestState.PARTIAL -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor(Color.parseColor("#F59E0B")) // Amber
                }
                TestState.BLOCKED -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor(Color.parseColor("#EF4444")) // Red
                }
                TestState.TIMEOUT -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor(Color.parseColor("#F97316")) // Orange
                }
                TestState.NOT_CONNECTED -> {
                    holder.progress.visibility = View.GONE
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = item.statusText
                    holder.badge.setTextColor(Color.parseColor("#94A3B8")) // Slate Gray
                }
            }
        }
    }
}
