package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

enum class ServiceCategory(val label: String) {
    ALL("全部"),
    AI("AI"),
    STREAMING("流媒体"),
    SOCIAL("社区平台"),
    GAMING_DEV("游戏与开发"),
    CRYPTO("金融加密")
}

enum class ServiceTestState {
    IDLE,
    TESTING,
    AVAILABLE,
    RESTRICTED,
    UNAVAILABLE
}

data class ServiceStatusItem(
    val id: String,
    val name: String,
    val category: ServiceCategory,
    val iconRes: Int,
    val testUrl: String,
    var state: ServiceTestState = ServiceTestState.IDLE,
    var latencyMs: Long = 0L,
    var outboundIp: String = "",
    var regionCode: String = "",
    var countryFlag: String = "",
    var subtitle: String = "-",
    var testTimestamp: String = "-",
    var extraDetails: String = "",
)

object ServiceDiagnosticsManager {

    private val concurrencySemaphore = Semaphore(8) // 最大并发数 8
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val openaiBlockedRegions = setOf("CN", "HK", "MO", "RU", "IR", "KP", "SY", "CU", "VE", "BY", "AF", "SS", "YE", "ZW", "MM", "SD", "SO", "CF")
    private val claudeBlockedRegions = setOf("CN", "HK", "MO", "RU", "IR", "KP", "SY", "CU", "BY", "VN", "TR", "AF", "SS", "YE", "ZW", "MM", "SD", "SO", "CF", "VE")
    private val geminiBlockedRegions = setOf("CHN", "CN", "RUS", "RU", "BLR", "BY", "CUB", "CU", "IRN", "IR", "PRK", "KP", "SYR", "SY", "HKG", "HK", "MAC", "MO", "AFG", "AF")

    private val youtubeRegionPattern = Pattern.compile("(?i)(?:\"countryCode\"\\s*:\\s*\"|\"GL\"\\s*:\\s*\")([A-Za-z]{2})\"")
    private val tikTokRegionPattern = Pattern.compile("\"webapp\\.app-context\"\\s*:\\s*\\{[^{}]*?\"region\"\\s*:\\s*\"([^\"]*)\"")
    private val spotifyPathPattern = Pattern.compile("spotify\\.com/([a-z]{2})(?:-[a-z]{2})?/")
    private val netflixPathPattern = Pattern.compile("netflix\\.com/([a-z]{2})(?:-[a-z]{2})?/title/")
    private val steamCountryPattern = Pattern.compile("(?:COUNTRY|country_code|countrycode)(?:&quot;|\"):\\s*(?:&quot;|\")([A-Z]{2})")
    private val redditPopPattern = Pattern.compile("p=([A-Z]{3})")
    private val wikiGeoPattern = Pattern.compile("GeoIP=([A-Z]{2}):")

    fun createInitialList(): List<ServiceStatusItem> {
        return listOf(
            // --- AI (9 项) ---
            ServiceStatusItem(
                id = "openai",
                name = "OpenAI",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_chatgpt,
                testUrl = "https://chatgpt.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "claude",
                name = "Claude",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_claude,
                testUrl = "https://api.anthropic.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "gemini",
                name = "Gemini",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_gemini,
                testUrl = "https://gemini.google.com/"
            ),
            ServiceStatusItem(
                id = "grok",
                name = "Grok",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_grok,
                testUrl = "https://api.x.ai/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "openrouter",
                name = "OpenRouter",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_openrouter,
                testUrl = "https://openrouter.ai/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "suno",
                name = "Suno",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_suno,
                testUrl = "https://suno.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "cloudflare",
                name = "Cloudflare",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_cloudflare,
                testUrl = "https://api.cloudflare.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "perplexity",
                name = "Perplexity",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_perplexity,
                testUrl = "https://www.perplexity.ai/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "poe",
                name = "Poe",
                category = ServiceCategory.AI,
                iconRes = R.drawable.ic_platform_poe,
                testUrl = "https://poe.com/cdn-cgi/trace"
            ),

            // --- 流媒体 (8 项) ---
            ServiceStatusItem(
                id = "youtube",
                name = "YouTube",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_youtube,
                testUrl = "https://www.youtube.com/premium?hl=en"
            ),
            ServiceStatusItem(
                id = "ytmusic",
                name = "YouTube Music",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_ytmusic,
                testUrl = "https://music.youtube.com/"
            ),
            ServiceStatusItem(
                id = "netflix",
                name = "Netflix",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_netflix,
                testUrl = "https://www.netflix.com/title/81280792"
            ),
            ServiceStatusItem(
                id = "disney",
                name = "Disney+",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_disney,
                testUrl = "https://www.disneyplus.com/"
            ),
            ServiceStatusItem(
                id = "prime",
                name = "Prime Video",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_prime,
                testUrl = "https://www.primevideo.com"
            ),
            ServiceStatusItem(
                id = "spotify",
                name = "Spotify",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_spotify,
                testUrl = "https://www.spotify.com/signup"
            ),
            ServiceStatusItem(
                id = "tiktok",
                name = "TikTok",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_tiktok,
                testUrl = "https://www.tiktok.com/"
            ),
            ServiceStatusItem(
                id = "bilibili",
                name = "哔哩哔哩(海外)",
                category = ServiceCategory.STREAMING,
                iconRes = R.drawable.ic_platform_bilibili,
                testUrl = "https://api.bilibili.com/pgc/player/web/playurl?avid=18281381&cid=29892777&qn=0&type=&otype=json&ep_id=183799&fourk=1&fnver=0&fnval=16&module=bangumi"
            ),

            // --- 社区平台 (6 项) ---
            ServiceStatusItem(
                id = "reddit",
                name = "Reddit",
                category = ServiceCategory.SOCIAL,
                iconRes = R.drawable.ic_platform_reddit,
                testUrl = "https://www.reddit.com/"
            ),
            ServiceStatusItem(
                id = "x",
                name = "X (Twitter)",
                category = ServiceCategory.SOCIAL,
                iconRes = R.drawable.ic_platform_x,
                testUrl = "https://x.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "discord",
                name = "Discord",
                category = ServiceCategory.SOCIAL,
                iconRes = R.drawable.ic_platform_discord,
                testUrl = "https://gateway.discord.gg/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "telegram",
                name = "Telegram",
                category = ServiceCategory.SOCIAL,
                iconRes = R.drawable.ic_platform_telegram,
                testUrl = "https://api.telegram.org/"
            ),
            ServiceStatusItem(
                id = "medium",
                name = "Medium",
                category = ServiceCategory.SOCIAL,
                iconRes = R.drawable.ic_platform_medium,
                testUrl = "https://medium.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "wikipedia",
                name = "Wikipedia",
                category = ServiceCategory.SOCIAL,
                iconRes = R.drawable.ic_platform_wikipedia,
                testUrl = "https://www.wikipedia.org/"
            ),

            // --- 游戏与开发 (7 项) ---
            ServiceStatusItem(
                id = "google",
                name = "Google",
                category = ServiceCategory.GAMING_DEV,
                iconRes = R.drawable.ic_platform_google,
                testUrl = "https://www.google.com/generate_204"
            ),
            ServiceStatusItem(
                id = "github",
                name = "GitHub",
                category = ServiceCategory.GAMING_DEV,
                iconRes = R.drawable.ic_platform_github,
                testUrl = "https://github.com/"
            ),
            ServiceStatusItem(
                id = "gitlab",
                name = "GitLab",
                category = ServiceCategory.GAMING_DEV,
                iconRes = R.drawable.ic_platform_gitlab,
                testUrl = "https://gitlab.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "steam",
                name = "Steam",
                category = ServiceCategory.GAMING_DEV,
                iconRes = R.drawable.ic_platform_steam,
                testUrl = "https://store.steampowered.com/"
            ),
            ServiceStatusItem(
                id = "epic",
                name = "Epic Games",
                category = ServiceCategory.GAMING_DEV,
                iconRes = R.drawable.ic_platform_epic,
                testUrl = "https://store.epicgames.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "ubisoft",
                name = "Ubisoft",
                category = ServiceCategory.GAMING_DEV,
                iconRes = R.drawable.ic_platform_ubisoft,
                testUrl = "https://store.ubisoft.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "humblebundle",
                name = "Humble Bundle",
                category = ServiceCategory.GAMING_DEV,
                iconRes = R.drawable.ic_platform_humblebundle,
                testUrl = "https://humblebundle.com/cdn-cgi/trace"
            ),

            // --- 金融加密 (6 项) ---
            ServiceStatusItem(
                id = "coinbase",
                name = "Coinbase",
                category = ServiceCategory.CRYPTO,
                iconRes = R.drawable.ic_platform_coinbase,
                testUrl = "https://www.coinbase.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "okx",
                name = "OKX",
                category = ServiceCategory.CRYPTO,
                iconRes = R.drawable.ic_platform_okx,
                testUrl = "https://www.okx.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "kraken",
                name = "Kraken",
                category = ServiceCategory.CRYPTO,
                iconRes = R.drawable.ic_platform_kraken,
                testUrl = "https://www.kraken.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "crypto",
                name = "Crypto.com",
                category = ServiceCategory.CRYPTO,
                iconRes = R.drawable.ic_platform_crypto,
                testUrl = "https://crypto.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "phantom",
                name = "Phantom",
                category = ServiceCategory.CRYPTO,
                iconRes = R.drawable.ic_platform_phantom,
                testUrl = "https://phantom.com/cdn-cgi/trace"
            ),
            ServiceStatusItem(
                id = "paypal",
                name = "PayPal",
                category = ServiceCategory.CRYPTO,
                iconRes = R.drawable.ic_platform_paypal,
                testUrl = "https://www.paypal.com/"
            )
        )
    }

    private val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4L, TimeUnit.SECONDS)
            .readTimeout(4L, TimeUnit.SECONDS)
            .writeTimeout(4L, TimeUnit.SECONDS)
            .build()
    }

    private fun buildOkHttpClient(followRedirects: Boolean = true, timeoutSec: Long = 4L): OkHttpClient {
        val builder = baseHttpClient.newBuilder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)

        if (timeoutSec != 4L) {
            builder.connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        }

        val mixedPort = DataStore.mixedPort
        if (mixedPort > 0) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", mixedPort.toInt())))
        }
        return builder.build()
    }

    private fun checkCloudflareTrace(
        traceUrl: String,
        ua: String,
        unsupportedRegions: Set<String>? = null,
        fallbackUrl: String? = null
    ): Triple<ServiceTestState, String, String> {
        val client = buildOkHttpClient(followRedirects = true)
        val request = Request.Builder()
            .url(traceUrl)
            .header("User-Agent", ua)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        var loc = ""
        var ip = ""
        var isChallenge = false
        var hasResponse = false

        try {
            client.newCall(request).execute().use { response ->
                hasResponse = true
                val body = response.body?.string().orEmpty()
                if (body.contains("challenges.cloudflare.com") || body.contains("cf-mitigated") || body.contains("cf-turnstile")) {
                    isChallenge = true
                }
                for (line in body.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("loc=")) {
                        loc = trimmed.substring(4).trim().uppercase()
                    } else if (trimmed.startsWith("ip=")) {
                        ip = trimmed.substring(3).trim()
                    }
                }
            }
        } catch (_: Throwable) {
        }

        if (isChallenge) {
            return Triple(ServiceTestState.RESTRICTED, loc, ip)
        }

        if (loc.isNotBlank() && ip.isNotBlank()) {
            val isBlocked = unsupportedRegions != null && unsupportedRegions.contains(loc)
            val state = if (isBlocked) ServiceTestState.RESTRICTED else ServiceTestState.AVAILABLE
            return Triple(state, loc, ip)
        }

        if (fallbackUrl != null) {
            try {
                val fbReq = Request.Builder()
                    .url(fallbackUrl)
                    .header("User-Agent", ua)
                    .build()
                client.newCall(fbReq).execute().use { fbRes ->
                    if (fbRes.isSuccessful) {
                        return Triple(ServiceTestState.AVAILABLE, loc, ip)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return Triple(if (hasResponse) ServiceTestState.RESTRICTED else ServiceTestState.UNAVAILABLE, loc, ip)
    }

    suspend fun testService(
        item: ServiceStatusItem,
        onUpdate: ((ServiceStatusItem) -> Unit)? = null,
    ): ServiceStatusItem = withContext(Dispatchers.IO) {
        if (!DataStore.serviceState.connected) {
            item.state = ServiceTestState.UNAVAILABLE
            item.subtitle = "-"
            item.latencyMs = 0L
            item.testTimestamp = timeFormat.format(Date())
            onUpdate?.invoke(item)
            return@withContext item
        }

        item.state = ServiceTestState.TESTING
        item.subtitle = "检测中..."
        onUpdate?.invoke(item)

        concurrencySemaphore.withPermit {
            val startTime = System.currentTimeMillis()
            val ua = USER_AGENT.takeIf { it.isNotBlank() }
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

            val success = withTimeoutOrNull(5000L) {
                var detectedRegion = ""
                var detectedIp = ""
                var detectedState = ServiceTestState.AVAILABLE

                when (item.id) {
                    "openai" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://chatgpt.com/cdn-cgi/trace", ua, openaiBlockedRegions)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "claude" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://api.anthropic.com/cdn-cgi/trace", ua, claudeBlockedRegions)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "gemini" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url("https://gemini.google.com/")
                            .header("User-Agent", ua)
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val realUrl = resp.request.url.toString()
                                val body = resp.body?.string().orEmpty()
                                if (realUrl.contains("sorry.google.com")) {
                                    detectedState = ServiceTestState.RESTRICTED
                                } else {
                                    val marker = ",2,1,200,\""
                                    val idx = body.indexOf(marker)
                                    if (idx >= 0 && idx + marker.length + 3 <= body.length) {
                                        val code = body.substring(idx + marker.length, idx + marker.length + 3).uppercase()
                                        detectedRegion = code
                                        if (geminiBlockedRegions.contains(code)) {
                                            detectedState = ServiceTestState.RESTRICTED
                                        } else {
                                            detectedState = ServiceTestState.AVAILABLE
                                        }
                                    } else if (body.contains("not available in your country") || body.contains("unavailable in your country")) {
                                        detectedState = ServiceTestState.RESTRICTED
                                    } else if (resp.isSuccessful) {
                                        detectedState = ServiceTestState.AVAILABLE
                                    } else {
                                        detectedState = ServiceTestState.RESTRICTED
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "grok" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://api.x.ai/cdn-cgi/trace", ua)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "openrouter" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://openrouter.ai/cdn-cgi/trace", ua)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "suno" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://suno.com/cdn-cgi/trace", ua)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "cloudflare" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://api.cloudflare.com/cdn-cgi/trace", ua)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "perplexity" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://www.perplexity.ai/cdn-cgi/trace", ua)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "poe" -> {
                        val (state, loc, ip) = checkCloudflareTrace("https://poe.com/cdn-cgi/trace", ua)
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }

                    "youtube", "ytmusic" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url(item.testUrl)
                            .header("User-Agent", ua)
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val realUrl = resp.request.url.toString()
                                if (realUrl.contains("sorry.google.com")) {
                                    detectedState = ServiceTestState.RESTRICTED
                                } else {
                                    val body = resp.body?.string().orEmpty()
                                    val matcher = youtubeRegionPattern.matcher(body)
                                    if (matcher.find()) {
                                        detectedRegion = matcher.group(1)?.uppercase().orEmpty()
                                    }
                                    val lower = body.lowercase()
                                    if (lower.contains("premium is not available in your country") ||
                                        lower.contains("premium is not available in your region")
                                    ) {
                                        detectedState = ServiceTestState.RESTRICTED
                                    } else {
                                        detectedState = ServiceTestState.AVAILABLE
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "netflix" -> {
                        val client = buildOkHttpClient(followRedirects = false)
                        var firstPass = false
                        try {
                            val req1 = Request.Builder()
                                .url("https://www.netflix.com/title/70143836")
                                .header("User-Agent", ua)
                                .build()
                            client.newCall(req1).execute().use { res1 ->
                                val loc = res1.header("Location").orEmpty()
                                val matcher = netflixPathPattern.matcher(loc)
                                if (matcher.find()) {
                                    detectedRegion = matcher.group(1)?.uppercase().orEmpty()
                                }
                                if ((res1.code == 301 || res1.code == 302 || res1.code == 200) && loc.contains("/title/70143836")) {
                                    firstPass = true
                                    detectedState = ServiceTestState.AVAILABLE
                                }
                            }
                        } catch (_: Throwable) {}

                        if (!firstPass) {
                            try {
                                val req2 = Request.Builder()
                                    .url("https://www.netflix.com/title/81280792")
                                    .header("User-Agent", ua)
                                    .build()
                                client.newCall(req2).execute().use { res2 ->
                                    val loc = res2.header("Location").orEmpty()
                                    val matcher = netflixPathPattern.matcher(loc)
                                    if (matcher.find()) {
                                        detectedRegion = matcher.group(1)?.uppercase().orEmpty()
                                    }
                                    if (res2.code in 200..302 && loc.contains("/title/81280792")) {
                                        detectedState = ServiceTestState.RESTRICTED // 仅自制剧解锁
                                    } else {
                                        detectedState = ServiceTestState.UNAVAILABLE
                                    }
                                }
                            } catch (_: Throwable) {
                                detectedState = ServiceTestState.UNAVAILABLE
                            }
                        }
                    }

                    "disney" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url("https://www.disneyplus.com/")
                            .head()
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val loc = resp.header("physical-location") ?: resp.header("region")
                                if (!loc.isNullOrBlank()) {
                                    detectedRegion = loc.uppercase()
                                }
                                val realUrl = resp.request.url.toString()
                                detectedState = if (resp.code == 200 && !realUrl.contains("unavailable")) {
                                    ServiceTestState.AVAILABLE
                                } else if (resp.code in 200..399) {
                                    ServiceTestState.AVAILABLE
                                } else {
                                    ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "prime" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url(item.testUrl)
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val body = resp.body?.string().orEmpty()
                                if (body.contains("isServiceRestricted")) {
                                    detectedState = ServiceTestState.RESTRICTED
                                } else if (resp.isSuccessful || resp.code in 200..399) {
                                    detectedState = ServiceTestState.AVAILABLE
                                } else {
                                    detectedState = ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "spotify" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url("https://www.spotify.com/signup")
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val realUrl = resp.request.url.toString()
                                val matcher = spotifyPathPattern.matcher(realUrl)
                                if (matcher.find()) {
                                    detectedRegion = matcher.group(1)?.uppercase().orEmpty()
                                }
                                detectedState = if (realUrl.contains("why-not-available")) {
                                    ServiceTestState.RESTRICTED
                                } else if (resp.isSuccessful || resp.code in 200..399) {
                                    ServiceTestState.AVAILABLE
                                } else {
                                    ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "tiktok" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url(item.testUrl)
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val body = resp.body?.string().orEmpty()
                                val lower = body.lowercase()
                                if (lower.contains("access denied") || lower.contains("not available in your region")) {
                                    detectedState = ServiceTestState.RESTRICTED
                                } else if (resp.isSuccessful || resp.code in 200..399) {
                                    detectedState = ServiceTestState.AVAILABLE
                                    val matcher = tikTokRegionPattern.matcher(body)
                                    if (matcher.find()) {
                                        detectedRegion = matcher.group(1)?.uppercase().orEmpty()
                                    }
                                } else {
                                    detectedState = ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "bilibili" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url(item.testUrl)
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val body = resp.body?.string().orEmpty()
                                if (body.contains("\"code\":0")) {
                                    detectedState = ServiceTestState.AVAILABLE
                                    detectedRegion = "HK"
                                } else {
                                    detectedState = ServiceTestState.RESTRICTED
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "reddit" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url("https://www.reddit.com/")
                            .head()
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val timing = resp.header("server-timing").orEmpty()
                                val popMatch = redditPopPattern.matcher(timing)
                                if (popMatch.find()) {
                                    detectedRegion = popMatch.group(1)?.uppercase().orEmpty()
                                }
                                detectedState = if (resp.code in 200..399) {
                                    ServiceTestState.AVAILABLE
                                } else if (resp.code == 403) {
                                    ServiceTestState.RESTRICTED
                                } else {
                                    ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "github" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url("https://github.com/")
                            .head()
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val edge = resp.header("x-github-edge-region").orEmpty()
                                if (edge.isNotBlank()) {
                                    detectedRegion = edge.take(2).uppercase()
                                }
                                detectedState = if (resp.isSuccessful || resp.code in 200..399) {
                                    ServiceTestState.AVAILABLE
                                } else {
                                    ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "wikipedia" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url("https://www.wikipedia.org/")
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val cookies = resp.header("set-cookie").orEmpty()
                                val matcher = wikiGeoPattern.matcher(cookies)
                                if (matcher.find()) {
                                    detectedRegion = matcher.group(1)?.uppercase().orEmpty()
                                }
                                detectedState = if (resp.isSuccessful || resp.code in 200..399) {
                                    ServiceTestState.AVAILABLE
                                } else {
                                    ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "steam" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url("https://store.steampowered.com/")
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                val body = resp.body?.string().orEmpty()
                                val matcher = steamCountryPattern.matcher(body)
                                if (matcher.find()) {
                                    detectedRegion = matcher.group(1)?.uppercase().orEmpty()
                                }
                                detectedState = if (resp.isSuccessful || resp.code in 200..399) {
                                    ServiceTestState.AVAILABLE
                                } else {
                                    ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    "telegram", "paypal", "google" -> {
                        val client = buildOkHttpClient(followRedirects = true)
                        val req = Request.Builder()
                            .url(item.testUrl)
                            .header("User-Agent", ua)
                            .build()
                        try {
                            client.newCall(req).execute().use { resp ->
                                detectedState = if (resp.code in 200..499) {
                                    ServiceTestState.AVAILABLE
                                } else {
                                    ServiceTestState.UNAVAILABLE
                                }
                            }
                        } catch (_: Throwable) {
                            detectedState = ServiceTestState.UNAVAILABLE
                        }
                    }

                    // Cloudflare trace platforms: epic, ubisoft, humblebundle, coinbase, okx, kraken, crypto, phantom, discord, x, gitlab, medium
                    else -> {
                        val (state, loc, ip) = checkCloudflareTrace(item.testUrl, ua, fallbackUrl = item.testUrl.replace("/cdn-cgi/trace", ""))
                        detectedState = state
                        detectedRegion = loc
                        detectedIp = ip
                    }
                }

                val cost = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                val cachedInfo = LandingIpManager.getCachedInfo()
                val ip = if (detectedIp.isNotBlank()) detectedIp else cachedInfo?.ip.orEmpty()
                val code = if (detectedRegion.isNotBlank()) {
                    detectedRegion.take(2).uppercase()
                } else {
                    cachedInfo?.countryCode?.take(2)?.uppercase().orEmpty()
                }
                val flag = if (code.isNotBlank()) {
                    LandingIpManager.countryCodeToFlagEmoji(code)
                } else {
                    cachedInfo?.countryFlag ?: "🌐"
                }

                item.latencyMs = cost
                item.outboundIp = ip
                item.regionCode = code
                item.countryFlag = flag
                item.state = detectedState

                item.subtitle = when (detectedState) {
                    ServiceTestState.AVAILABLE, ServiceTestState.RESTRICTED -> {
                        if (code.isNotBlank()) "$flag $code ${cost}ms" else "${cost}ms"
                    }
                    ServiceTestState.UNAVAILABLE -> "-"
                    ServiceTestState.TESTING -> "检测中..."
                    ServiceTestState.IDLE -> "-"
                }
                true
            } ?: false

            if (!success) {
                item.state = ServiceTestState.UNAVAILABLE
                item.subtitle = "-"
                item.latencyMs = 0L
            }
            item.testTimestamp = timeFormat.format(Date())
            onUpdate?.invoke(item)
            item
        }
    }
}
