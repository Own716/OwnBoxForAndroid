package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.ActivityMediaUnlockBinding
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.util.regex.Pattern

class MediaUnlockActivity : ThemedActivity() {

    private lateinit var binding: ActivityMediaUnlockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.media_unlock_title)
        }

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

    private fun startAllTests() {
        val currentProfile = ProfileManager.getProfile(DataStore.selectedProxy)
        binding.tvCurrentNode.text = currentProfile?.displayName() ?: getString(R.string.not_connected)

        val cachedIp = LandingIpManager.getCachedInfo()
        binding.tvCurrentIp.text = if (cachedIp != null) "出口 IP: ${cachedIp.briefText}" else "出口 IP: 查询中..."

        if (!DataStore.serviceState.connected) {
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            showAllNotConnected()
            return
        }

        resetProgress()

        lifecycleScope.launch {
            // Update IP if not present
            if (cachedIp == null) {
                val ipRes = LandingIpManager.queryLandingIp(DataStore.selectedProxy)
                ipRes.onSuccess {
                    binding.tvCurrentIp.text = "出口 IP: ${it.briefText}"
                }
            }

            val netflixDeferred = async { testNetflix() }
            val disneyDeferred = async { testDisney() }
            val chatgptDeferred = async { testChatGpt() }
            val youtubeDeferred = async { testYouTube() }

            val netflixRes = netflixDeferred.await()
            renderNetflix(netflixRes)

            val disneyRes = disneyDeferred.await()
            renderDisney(disneyRes)

            val chatgptRes = chatgptDeferred.await()
            renderChatGpt(chatgptRes)

            val youtubeRes = youtubeDeferred.await()
            renderYouTube(youtubeRes)
        }
    }

    private fun resetProgress() {
        binding.progressNetflix.visibility = View.VISIBLE
        binding.tvNetflixStatus.visibility = View.GONE
        binding.tvNetflixDesc.text = "正在探测 Netflix 版权库与区域授权..."

        binding.progressDisney.visibility = View.VISIBLE
        binding.tvDisneyStatus.visibility = View.GONE
        binding.tvDisneyDesc.text = "正在检测 Disney+ 访问连通性..."

        binding.progressChatgpt.visibility = View.VISIBLE
        binding.tvChatgptStatus.visibility = View.GONE
        binding.tvChatgptDesc.text = "正在检测 OpenAI 接入与 Cloudflare 风控..."

        binding.progressYoutube.visibility = View.VISIBLE
        binding.tvYoutubeStatus.visibility = View.GONE
        binding.tvYoutubeDesc.text = "正在检测 YouTube Premium 地区开放状态..."
    }

    private fun showAllNotConnected() {
        binding.progressNetflix.visibility = View.GONE
        binding.tvNetflixStatus.visibility = View.VISIBLE
        binding.tvNetflixStatus.text = "未连接"
        binding.tvNetflixStatus.setTextColor(Color.parseColor("#94A3B8"))

        binding.progressDisney.visibility = View.GONE
        binding.tvDisneyStatus.visibility = View.VISIBLE
        binding.tvDisneyStatus.text = "未连接"
        binding.tvDisneyStatus.setTextColor(Color.parseColor("#94A3B8"))

        binding.progressChatgpt.visibility = View.GONE
        binding.tvChatgptStatus.visibility = View.VISIBLE
        binding.tvChatgptStatus.text = "未连接"
        binding.tvChatgptStatus.setTextColor(Color.parseColor("#94A3B8"))

        binding.progressYoutube.visibility = View.GONE
        binding.tvYoutubeStatus.visibility = View.VISIBLE
        binding.tvYoutubeStatus.text = "未连接"
        binding.tvYoutubeStatus.setTextColor(Color.parseColor("#94A3B8"))
    }

    // --- Netflix ---
    private data class TestResult(val status: Int, val tag: String, val message: String)

    private suspend fun testNetflix(): TestResult = withContext(Dispatchers.IO) {
        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            // 81280792 = Breaking Bad (licensed title)
            val req1 = client.newRequest().apply {
                setURL("https://www.netflix.com/title/81280792")
                setUserAgent(USER_AGENT)
            }
            val body1 = try {
                val resp1 = req1.execute()
                Util.getStringBox(resp1.contentString)
            } catch (e: Throwable) {
                ""
            }

            if (body1.isNotBlank() && !body1.contains("page-404") && (body1.contains("title/81280792") || body1.contains("watch") || body1.contains("Breaking Bad"))) {
                return@withContext TestResult(1, "已原生解锁", "完整支持全部非自制原生版权剧集与自制剧")
            }

            // Fallback: 80018499 = House of Cards (Netflix original)
            val req2 = client.newRequest().apply {
                setURL("https://www.netflix.com/title/80018499")
                setUserAgent(USER_AGENT)
            }
            val body2 = try {
                val resp2 = req2.execute()
                Util.getStringBox(resp2.contentString)
            } catch (e: Throwable) {
                ""
            }

            if (body2.isNotBlank() && (body2.contains("title/80018499") || body2.contains("watch"))) {
                TestResult(2, "仅自制剧", "仅支持播放 Netflix 自制剧集，非自制版权剧受限")
            } else {
                TestResult(0, "未解锁", "当前节点 IP 无法正常访问 Netflix 或受区域限制")
            }
        } catch (e: Throwable) {
            TestResult(-1, "检测超时", "连接超时或网络异常: ${e.message}")
        }
    }

    private fun renderNetflix(res: TestResult) {
        binding.progressNetflix.visibility = View.GONE
        binding.tvNetflixStatus.visibility = View.VISIBLE
        binding.tvNetflixStatus.text = res.tag
        binding.tvNetflixDesc.text = res.message
        binding.tvNetflixStatus.setTextColor(statusColor(res.status))
    }

    // --- Disney+ ---
    private suspend fun testDisney(): TestResult = withContext(Dispatchers.IO) {
        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            val req = client.newRequest().apply {
                setURL("https://www.disneyplus.com/")
                setUserAgent(USER_AGENT)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (!body.contains("not available in your region") && !body.contains("restricted")) {
                TestResult(1, "已解锁", "支持正常访问与播放 Disney+ 流媒体内容")
            } else {
                TestResult(0, "未解锁", "地区不支持或服务受限")
            }
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            if (msg.contains("403") || msg.contains("restricted") || msg.contains("not available")) {
                TestResult(0, "未解锁", "地区不支持或服务受限")
            } else {
                TestResult(-1, "检测超时", "连接超时或节点网络异常: $msg")
            }
        }
    }

    private fun renderDisney(res: TestResult) {
        binding.progressDisney.visibility = View.GONE
        binding.tvDisneyStatus.visibility = View.VISIBLE
        binding.tvDisneyStatus.text = res.tag
        binding.tvDisneyDesc.text = res.message
        binding.tvDisneyStatus.setTextColor(statusColor(res.status))
    }

    // --- ChatGPT ---
    private suspend fun testChatGpt(): TestResult = withContext(Dispatchers.IO) {
        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            val req = client.newRequest().apply {
                setURL("https://chatgpt.com/")
                setUserAgent(USER_AGENT)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (!body.contains("cf-mitigated") && !body.contains("Attention Required")) {
                TestResult(1, "已解锁", "支持网页端与 API 正常登录对话，无 Cloudflare 拦截")
            } else {
                TestResult(0, "未解锁 (CF 拦截)", "触发 Cloudflare 人机验证或 IP 限制")
            }
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            if (msg.contains("403") || msg.contains("cf-mitigated") || msg.contains("Attention Required")) {
                TestResult(0, "未解锁 (CF 拦截)", "触发 Cloudflare 人机验证或 IP 限制")
            } else {
                TestResult(-1, "检测超时", "连接超时或网络异常: $msg")
            }
        }
    }

    private fun renderChatGpt(res: TestResult) {
        binding.progressChatgpt.visibility = View.GONE
        binding.tvChatgptStatus.visibility = View.VISIBLE
        binding.tvChatgptStatus.text = res.tag
        binding.tvChatgptDesc.text = res.message
        binding.tvChatgptStatus.setTextColor(statusColor(res.status))
    }

    // --- YouTube Premium ---
    private suspend fun testYouTube(): TestResult = withContext(Dispatchers.IO) {
        try {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                tryProxyOutbound()
            }
            val req = client.newRequest().apply {
                setURL("https://www.youtube.com/premium")
                setUserAgent(USER_AGENT)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)

            if (body.contains("Premium is not available in your country")) {
                TestResult(0, "未解锁", "YouTube Premium 在当前节点所在地区暂未开放")
            } else {
                val matcher = Pattern.compile("\"countryCode\":\"([A-Z]{2})\"").matcher(body)
                val country = if (matcher.find()) matcher.group(1) else ""
                val extra = if (country.isNotBlank()) " ($country 地区)" else ""
                TestResult(1, "已解锁$extra", "支持 YouTube Premium 订阅购买与后台画中画播放")
            }
        } catch (e: Throwable) {
            TestResult(-1, "检测超时", "连接超时或网络异常: ${e.message}")
        }
    }

    private fun renderYouTube(res: TestResult) {
        binding.progressYoutube.visibility = View.GONE
        binding.tvYoutubeStatus.visibility = View.VISIBLE
        binding.tvYoutubeStatus.text = res.tag
        binding.tvYoutubeDesc.text = res.message
        binding.tvYoutubeStatus.setTextColor(statusColor(res.status))
    }

    private fun statusColor(status: Int): Int {
        return when (status) {
            1 -> Color.parseColor("#10B981") // Emerald Green (Unlocked)
            2 -> Color.parseColor("#F59E0B") // Amber (Originals only)
            0 -> Color.parseColor("#EF4444") // Rose Red (Locked)
            else -> Color.parseColor("#94A3B8") // Gray (Timeout / error)
        }
    }
}
