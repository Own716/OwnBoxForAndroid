package io.nekohasekai.sagernet.tools.media

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.util.regex.Pattern

object MediaUnlockManager {

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    // Limit concurrency to 3 simultaneous tests to prevent triggering 429 rate limits or Cloudflare challenges
    val testSemaphore = Semaphore(3)

    fun createInitialItems(notConnected: Boolean = false): MutableList<MediaUnlockItem> {
        val initialStatus = if (notConnected) UnlockStatus.NOT_CONNECTED else UnlockStatus.TESTING
        fun defDesc(name: String) = if (notConnected) "VPN 未连接，请先连接代理节点" else "正在探测 $name 区域授权与访问限制..."
        fun defStatus() = if (notConnected) "未连接" else "检测中..."

        return mutableListOf(
            MediaUnlockItem("netflix", "Netflix", "流媒体服务", R.drawable.ic_platform_netflix, initialStatus, defStatus(), defDesc("Netflix")),
            MediaUnlockItem("disney", "Disney+", "流媒体服务", R.drawable.ic_platform_disney, initialStatus, defStatus(), defDesc("Disney+")),
            MediaUnlockItem("max", "Max (HBO)", "流媒体服务", R.drawable.ic_platform_max, initialStatus, defStatus(), defDesc("Max")),
            MediaUnlockItem("prime", "Prime Video", "流媒体服务", R.drawable.ic_platform_prime, initialStatus, defStatus(), defDesc("Amazon Prime Video")),
            MediaUnlockItem("youtube", "YouTube Premium", "流媒体服务", R.drawable.ic_platform_youtube, initialStatus, defStatus(), defDesc("YouTube Premium")),
            MediaUnlockItem("tiktok", "TikTok", "流媒体服务", R.drawable.ic_platform_tiktok, initialStatus, defStatus(), defDesc("TikTok")),
            MediaUnlockItem("spotify", "Spotify", "音乐音频服务", R.drawable.ic_platform_spotify, initialStatus, defStatus(), defDesc("Spotify")),
            MediaUnlockItem("wikipedia", "Wikipedia", "网络百科服务", R.drawable.ic_platform_wikipedia, initialStatus, defStatus(), defDesc("Wikipedia")),
            MediaUnlockItem("chatgpt", "ChatGPT (OpenAI)", "AI 智能服务", R.drawable.ic_platform_chatgpt, initialStatus, defStatus(), defDesc("ChatGPT")),
            MediaUnlockItem("claude", "Claude (Anthropic)", "AI 智能服务", R.drawable.ic_platform_claude, initialStatus, defStatus(), defDesc("Claude")),
            MediaUnlockItem("gemini", "Google Gemini", "AI 智能服务", R.drawable.ic_platform_gemini, initialStatus, defStatus(), defDesc("Gemini")),
        )
    }

    private fun createHttpClient(): libcore.HTTPClient {
        return Libcore.newHttpClient().apply {
            modernTLS()
            val mixedPort = DataStore.mixedPort
            if (mixedPort > 0) {
                trySocks5(mixedPort.toInt(), "", "")
            } else {
                tryProxyOutbound()
            }
        }
    }

    private fun libcore.HTTPRequest.applyBrowserHeaders(host: String? = null) {
        setUserAgent(BROWSER_USER_AGENT)
        setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        setHeader("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7")
        setHeader("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"128\", \"Google Chrome\";v=\"128\"")
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

    private fun isCloudflareChallenge(body: String, errorMsg: String = ""): Boolean {
        val combined = "$body $errorMsg".lowercase()
        return combined.contains("cf-mitigated") ||
            combined.contains("just a moment") ||
            combined.contains("attention required") ||
            combined.contains("1020") ||
            combined.contains("turnstile") ||
            combined.contains("cf_chl") ||
            combined.contains("challenge-platform")
    }

    suspend fun executePlatformTest(item: MediaUnlockItem): MediaUnlockItem = testSemaphore.withPermit {
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val tested = when (item.id) {
                    "netflix" -> testNetflix(item)
                    "disney" -> testDisney(item)
                    "max" -> testMax(item)
                    "prime" -> testPrime(item)
                    "youtube" -> testYouTube(item)
                    "tiktok" -> testTikTok(item)
                    "spotify" -> testSpotify(item)
                    "wikipedia" -> testWikipedia(item)
                    "chatgpt" -> testChatGpt(item)
                    "claude" -> testClaude(item)
                    "gemini" -> testGemini(item)
                    else -> item
                }
                val cost = System.currentTimeMillis() - start
                tested.copy(latencyMs = cost)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val cost = System.currentTimeMillis() - start
                val msg = e.message.orEmpty()
                if (isCloudflareChallenge("", msg)) {
                    item.copy(
                        status = UnlockStatus.CHALLENGE,
                        badgeText = "触发风控验证",
                        description = "HTTP 403 触发 Cloudflare 验证码或反爬安全拦截",
                        latencyMs = cost,
                    )
                } else if (msg.contains("403") || msg.contains("Forbidden", ignoreCase = true)) {
                    item.copy(
                        status = UnlockStatus.BLOCKED,
                        badgeText = "访问受阻 (403)",
                        description = "平台返回 403 封禁或 IP 风控拒绝访问",
                        latencyMs = cost,
                    )
                } else {
                    item.copy(
                        status = UnlockStatus.TIMEOUT,
                        badgeText = "检测超时",
                        description = "连接超时或网络异常: ${msg.ifEmpty { "未能收到有效响应" }}",
                        latencyMs = cost,
                    )
                }
            }
        }
    }

    // --- 1. Netflix ---
    private fun testNetflix(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()

            // 1. Check licensed non-original: Breaking Bad (81280792)
            var body1 = ""
            var region = ""
            try {
                val req1 = client.newRequest().apply {
                    setURL("https://www.netflix.com/title/81280792")
                    applyBrowserHeaders("www.netflix.com")
                }
                val resp1 = req1.execute()
                body1 = Util.getStringBox(resp1.contentString)
                val matcher = Pattern.compile("geolocation_country.*?([A-Za-z]{2})").matcher(body1)
                if (matcher.find()) {
                    region = matcher.group(1)?.uppercase() ?: ""
                }
            } catch (_: Throwable) {}

            if (isCloudflareChallenge(body1)) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控",
                    description = "Netflix 访问触发 Cloudflare / 机器人验证拦截",
                )
            }

            val flag = if (region.isNotBlank()) LandingIpManager.countryCodeToFlagEmoji(region) + " " + region else ""

            if (body1.isNotBlank() && !body1.contains("page-404") &&
                (body1.contains("title/81280792") || body1.contains("watch") || body1.contains("Breaking Bad"))
            ) {
                return item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = if (flag.isNotBlank()) "原生全解锁 $flag" else "原生全解锁",
                    description = "完整支持播放全部非自制版权剧集与 Netflix 原创自制剧",
                    region = region,
                )
            }

            // 2. Check Netflix original: House of Cards (80018499)
            var body2 = ""
            try {
                val req2 = client.newRequest().apply {
                    setURL("https://www.netflix.com/title/80018499")
                    applyBrowserHeaders("www.netflix.com")
                }
                val resp2 = req2.execute()
                body2 = Util.getStringBox(resp2.contentString)
            } catch (_: Throwable) {}

            if (body2.isNotBlank() && (body2.contains("title/80018499") || body2.contains("watch") || body2.contains("House of Cards"))) {
                return item.copy(
                    status = UnlockStatus.PARTIAL,
                    badgeText = "仅自制剧",
                    description = "仅支持播放 Netflix 自制剧集，非自制版权剧集受到地区或机房限制",
                )
            }

            return item.copy(
                status = UnlockStatus.BLOCKED,
                badgeText = "未解锁",
                description = "当前出口 IP 被 Netflix 识别为代理，或无法正常获取播放授权",
            )
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 2. Disney+ ---
    private fun testDisney(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://www.disneyplus.com/")
                applyBrowserHeaders("www.disneyplus.com")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (isCloudflareChallenge(body)) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控",
                    description = "Disney+ 访问触发人机安全验证拦截",
                )
            }

            val isBlocked = body.contains("not available in your region", ignoreCase = true) ||
                body.contains("is not available in your area", ignoreCase = true) ||
                body.contains("disneyplus.com/unavailable", ignoreCase = true) ||
                body.contains("restricted", ignoreCase = true)

            return if (!isBlocked && (body.contains("disney") || body.contains("sign up") || body.contains("log in") || body.contains("disneyplus"))) {
                item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "原生解锁",
                    description = "支持正常浏览与播放 Disney+ 影视内容库",
                )
            } else {
                item.copy(
                    status = UnlockStatus.REGION_UNSUPPORTED,
                    badgeText = "地区受限",
                    description = "当前出口所在地区不在 Disney+ 官方服务范围内或已被限制",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 3. Max (HBO) ---
    private fun testMax(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://auth.max.com/")
                applyBrowserHeaders("auth.max.com")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (isCloudflareChallenge(body)) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控",
                    description = "Max (HBO) 触发安全验证拦截",
                )
            }

            val isBlocked = body.contains("not available in your region", ignoreCase = true) ||
                body.contains("unsupported_location", ignoreCase = true) ||
                body.contains("georestricted", ignoreCase = true)

            return if (!isBlocked && body.isNotBlank()) {
                item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "原生解锁",
                    description = "支持访问 Max (HBO) 流媒体服务与内容版权",
                )
            } else {
                item.copy(
                    status = UnlockStatus.REGION_UNSUPPORTED,
                    badgeText = "地区受限",
                    description = "当前出口 IP 所在地区暂未开放 Max (HBO) 流媒体服务",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 4. Prime Video ---
    private fun testPrime(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://www.primevideo.com/")
                applyBrowserHeaders("www.primevideo.com")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (isCloudflareChallenge(body)) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控",
                    description = "Amazon Prime 访问触发验证码拦截",
                )
            }

            val isBlocked = body.contains("georestricted", ignoreCase = true) ||
                body.contains("not-available-in-your-country", ignoreCase = true) ||
                body.contains("unavailable in your territory", ignoreCase = true)

            return if (!isBlocked && (body.contains("primevideo") || body.contains("Amazon") || body.contains("Watch now"))) {
                item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "原生解锁",
                    description = "支持畅享 Amazon Prime Video 流媒体版权库",
                )
            } else {
                item.copy(
                    status = UnlockStatus.BLOCKED,
                    badgeText = "未解锁",
                    description = "出口 IP 无法正常访问 Prime Video 或受地域限制",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 5. YouTube Premium ---
    private fun testYouTube(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
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

            return if (countryCode.isNotBlank() && !isNotAvailable) {
                val flag = LandingIpManager.countryCodeToFlagEmoji(countryCode)
                item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "支持 $flag $countryCode",
                    description = "支持开通与畅享 YouTube Premium 会员无广告播放",
                    region = countryCode,
                )
            } else if (body.contains("Premium") && !isNotAvailable) {
                item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "支持",
                    description = "支持开通与畅享 YouTube Premium 会员服务",
                )
            } else {
                item.copy(
                    status = UnlockStatus.BLOCKED,
                    badgeText = "未解锁",
                    description = "该地区或出口 IP 不支持 YouTube Premium 会员服务",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 6. TikTok ---
    private fun testTikTok(item: MediaUnlockItem): MediaUnlockItem {
        val nodeCountry = LandingIpManager.getCachedInfo()?.countryCode?.uppercase().orEmpty()
        if (nodeCountry == "HK") {
            return item.copy(
                status = UnlockStatus.REGION_UNSUPPORTED,
                badgeText = "不支持 🇭🇰 HK",
                description = "TikTok 官方已全面停止在中国香港地区提供服务",
                region = "HK",
            )
        }

        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
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

            if (region == "HK") {
                return item.copy(
                    status = UnlockStatus.REGION_UNSUPPORTED,
                    badgeText = "不支持 🇭🇰 HK",
                    description = "TikTok 官方已停止在中国香港提供服务",
                    region = "HK",
                )
            }

            if (isCloudflareChallenge(body) || body.contains("tiktok-verify-page") || body.contains("verify-center")) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控",
                    description = "TikTok 触发人机验证码拦截",
                )
            }

            val flag = if (region.isNotBlank()) LandingIpManager.countryCodeToFlagEmoji(region) + " " + region else ""
            return item.copy(
                status = UnlockStatus.UNLOCKED,
                badgeText = if (flag.isNotBlank()) "原生支持 $flag" else "原生支持",
                description = "支持正常浏览 TikTok 国际版短视频与直播内容",
                region = region,
            )
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 7. Spotify ---
    private fun testSpotify(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://www.spotify.com/")
                applyBrowserHeaders("www.spotify.com")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (isCloudflareChallenge(body)) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控",
                    description = "Spotify 访问触发安全验证拦截",
                )
            }

            val isBlocked = body.contains("not available in your country", ignoreCase = true) ||
                body.contains("Spotify is not available", ignoreCase = true)

            return if (!isBlocked && (body.contains("Spotify") || body.contains("music") || body.contains("Listen"))) {
                item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "原生解锁",
                    description = "支持 Spotify 歌曲播放与客户端正常登录认证",
                )
            } else {
                item.copy(
                    status = UnlockStatus.REGION_UNSUPPORTED,
                    badgeText = "未开放",
                    description = "当前出口 IP 所在地区暂未开放 Spotify 官方音乐服务",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 8. Wikipedia ---
    private fun testWikipedia(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://en.wikipedia.org/w/api.php?action=query&meta=userinfo&uiprop=blockinfo&format=json")
                applyBrowserHeaders("en.wikipedia.org")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            return when {
                body.contains("\"blockid\"") || body.contains("\"blockedby\"") -> {
                    item.copy(
                        status = UnlockStatus.PARTIAL,
                        badgeText = "仅只读 (编辑封禁)",
                        description = "当前出口 IP 被维基百科列入机房封禁列表，不可匿名编辑词条",
                    )
                }
                body.contains("\"userinfo\"") || body.contains("\"id\":0") || body.contains("\"anon\"") -> {
                    item.copy(
                        status = UnlockStatus.UNLOCKED,
                        badgeText = "完整支持",
                        description = "出口 IP 未被封禁，支持维基百科词条浏览与匿名编辑",
                    )
                }
                else -> {
                    item.copy(
                        status = UnlockStatus.UNLOCKED,
                        badgeText = "正常浏览",
                        description = "维基百科访问顺畅",
                    )
                }
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 9. ChatGPT (OpenAI) ---
    private fun testChatGpt(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()

            // 1. Web client check
            var webSuccess = false
            var cfChallenge = false
            try {
                val req = client.newRequest().apply {
                    setURL("https://chatgpt.com/")
                    applyBrowserHeaders("chatgpt.com")
                }
                val resp = req.execute()
                val body = Util.getStringBox(resp.contentString)
                if (isCloudflareChallenge(body)) {
                    cfChallenge = true
                } else if (body.contains("ChatGPT") || body.contains("Log in") || body.contains("Sign up") || body.contains("OAI")) {
                    webSuccess = true
                }
            } catch (e: Throwable) {
                if (isCloudflareChallenge("", e.message.orEmpty())) {
                    cfChallenge = true
                }
            }

            if (webSuccess) {
                return item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "原生解锁",
                    description = "无 Cloudflare 拦截，网页端与 API 对话通道畅通",
                )
            }

            // 2. Mobile official endpoint dual-check
            try {
                val mobileReq = client.newRequest().apply {
                    setURL("https://ios.chat.openai.com/public-api/mobile/server_status/v1")
                    setUserAgent("ChatGPT/1.2024.135 (iOS 17.5.1; iPhone15,2)")
                    setHeader("Accept", "application/json")
                }
                val mobileResp = mobileReq.execute()
                val mobileBody = Util.getStringBox(mobileResp.contentString)
                if (mobileBody.contains("status") || mobileBody.contains("ok") || mobileBody.contains("normal")) {
                    return item.copy(
                        status = UnlockStatus.PARTIAL,
                        badgeText = "支持 (App/API)",
                        description = "网页端触发 Cloudflare 验证码，但官方 App / API 链路通畅",
                    )
                }
            } catch (_: Throwable) {}

            return if (cfChallenge) {
                item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控验证",
                    description = "触发 Cloudflare / OpenAI 平台安全风控验证拦截",
                )
            } else {
                item.copy(
                    status = UnlockStatus.BLOCKED,
                    badgeText = "未解锁",
                    description = "出口 IP 无法正常连接 ChatGPT 官方对话服务",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 10. Claude (Anthropic) ---
    // Strictly enforces official ClaudeRegionRegistry to avoid false positives on HK, CN, RU, etc.
    private fun testClaude(item: MediaUnlockItem): MediaUnlockItem {
        val currentCountry = LandingIpManager.getCachedInfo()?.countryCode?.uppercase().orEmpty()

        // 1. Strict whitelist / blacklist check
        if (ClaudeRegionRegistry.isExplicitlyUnsupported(currentCountry)) {
            val flag = LandingIpManager.countryCodeToFlagEmoji(currentCountry)
            return item.copy(
                status = UnlockStatus.REGION_UNSUPPORTED,
                badgeText = "地区不支持 $flag $currentCountry",
                description = ClaudeRegionRegistry.getRegionExplanation(currentCountry),
                region = currentCountry,
            )
        }

        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://claude.ai/login")
                applyBrowserHeaders("claude.ai")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (isCloudflareChallenge(body)) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控验证",
                    description = "HTTP 403 触发 Cloudflare / Claude 平台风控拦截",
                )
            }

            val isBlocked = body.contains("App unavailable in your region", ignoreCase = true) ||
                body.contains("not available in your country", ignoreCase = true) ||
                body.contains("claude.ai/unavailable", ignoreCase = true) ||
                body.contains("unsupported_location", ignoreCase = true) ||
                body.contains("403 Forbidden", ignoreCase = true)

            val flag = if (currentCountry.isNotBlank()) " " + LandingIpManager.countryCodeToFlagEmoji(currentCountry) + " " + currentCountry else ""

            return if (!isBlocked && (body.contains("Continue with Google") || body.contains("email") || body.contains("Claude") || body.contains("login"))) {
                if (ClaudeRegionRegistry.isRegionSupported(currentCountry)) {
                    item.copy(
                        status = UnlockStatus.UNLOCKED,
                        badgeText = "原生支持$flag",
                        description = "Anthropic 官方支持服务区，区域授权正常开放",
                        region = currentCountry,
                    )
                } else {
                    item.copy(
                        status = UnlockStatus.PARTIAL,
                        badgeText = "疑似支持$flag",
                        description = "登录页面可载入，但出口地区未在官方白名单中，存在风控风险",
                        region = currentCountry,
                    )
                }
            } else {
                item.copy(
                    status = UnlockStatus.REGION_UNSUPPORTED,
                    badgeText = "地区受限$flag",
                    description = ClaudeRegionRegistry.getRegionExplanation(currentCountry),
                    region = currentCountry,
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    // --- 11. Google Gemini ---
    private fun testGemini(item: MediaUnlockItem): MediaUnlockItem {
        var client: libcore.HTTPClient? = null
        try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://gemini.google.com/")
                applyBrowserHeaders("gemini.google.com")
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (isCloudflareChallenge(body)) {
                return item.copy(
                    status = UnlockStatus.CHALLENGE,
                    badgeText = "触发风控",
                    description = "访问触发安全防御拦截",
                )
            }

            val isBlocked = body.contains("not supported in your country", ignoreCase = true) ||
                body.contains("unavailable in your territory", ignoreCase = true) ||
                body.contains("not available in your region", ignoreCase = true)

            return if (!isBlocked && (body.contains("Gemini") || body.contains("Google AI") || body.contains("Sign in"))) {
                item.copy(
                    status = UnlockStatus.UNLOCKED,
                    badgeText = "原生支持",
                    description = "支持全功能正常使用 Google Gemini AI 模型与对话",
                )
            } else {
                item.copy(
                    status = UnlockStatus.REGION_UNSUPPORTED,
                    badgeText = "未开放",
                    description = "Google Gemini 暂未对该地区或机房 IP 开放访问服务",
                )
            }
        } finally {
            runCatching { client?.close() }
        }
    }
}
