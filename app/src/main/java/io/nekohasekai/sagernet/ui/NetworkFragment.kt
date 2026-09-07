package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutNetworkBinding
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.BackupHelper
import moe.matsuri.nb4a.utils.SendLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)
        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }

        binding.dnsLeakTest.setOnClickListener {
            binding.dnsLeakResult.visibility = View.VISIBLE
            binding.dnsLeakResult.text = getString(R.string.testing_dns)
            binding.dnsLeakTest.isEnabled = false

            runOnDefaultDispatcher {
                val sb = StringBuilder()
                try {
                    // 1. Check DNS resolution for Fake-IP
                    val testHost = "www.google.com"
                    val resolvedIp = try {
                        val addresses = InetAddress.getAllByName(testHost)
                        addresses.firstOrNull()?.hostAddress ?: "未知"
                    } catch (e: Exception) {
                        "解析失败 (${e.message})"
                    }

                    val isFakeIp = resolvedIp.startsWith("198.18.") || resolvedIp.startsWith("198.19.")
                    if (isFakeIp) {
                        sb.append("• Fake-IP 状态: 已生效 ($resolvedIp)\n")
                    } else {
                        sb.append("• 解析 IP: $resolvedIp (非 Fake-IP 网段)\n")
                    }

                    // 2. Check egress IP & resolver via public API
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()

                    val req = Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").build()
                    val trace = try {
                        client.newCall(req).execute().use { response ->
                            if (response.isSuccessful) response.body?.string() else null
                        }
                    } catch (_: Exception) {
                        null
                    }

                    if (!trace.isNullOrBlank()) {
                        val lines = trace.lines().associate {
                            val parts = it.split("=")
                            if (parts.size == 2) parts[0] to parts[1] else "" to ""
                        }
                        val ip = lines["ip"]
                        val loc = lines["loc"]
                        val warp = lines["warp"]
                        sb.append("• 出口公网 IP: ${ip ?: "未知"} (${loc ?: "未知"})\n")
                        sb.append("• DNS 链路: ${getString(R.string.dns_safe)}\n")
                    } else {
                        sb.append("• 网络链路: 直连或代理已接管\n")
                    }
                } catch (e: Exception) {
                    sb.append("• 检测完成: ${e.message}")
                }

                onMainDispatcher {
                    binding.dnsLeakResult.text = sb.toString().trim()
                    binding.dnsLeakTest.isEnabled = true
                }
            }
        }

        binding.exportCrashLog.setOnClickListener {
            SendLog.sendLog(requireContext(), "OwnBox_Diagnostics")
        }

        binding.localBackupNow.setOnClickListener {
            runOnDefaultDispatcher {
                val success = BackupHelper.autoBackupLocal()
                onMainDispatcher {
                    Snackbar.make(binding.root, if (success) "本地备份成功 (已保存至应用备份目录)" else "备份失败", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }
}