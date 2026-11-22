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
import io.nekohasekai.sagernet.utils.DnsLeakTester
import moe.matsuri.nb4a.utils.SendLog

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)
        val openLanSharing = View.OnClickListener {
            startActivity(Intent(requireContext(), LanSharingActivity::class.java))
        }
        binding.lanSharingCard.setOnClickListener(openLanSharing)
        binding.lanSharingBtn.setOnClickListener(openLanSharing)

        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }

        binding.dnsLeakTest.setOnClickListener {
            binding.dnsLeakResult.visibility = View.VISIBLE
            binding.dnsLeakResult.text = getString(R.string.testing_dns)
            binding.dnsLeakTest.isEnabled = false

            runOnDefaultDispatcher {
                val reportText = try {
                    DnsLeakTester.formatReport(DnsLeakTester.runFullCheck())
                } catch (e: Exception) {
                    "检测失败: ${e.message ?: e.javaClass.simpleName}"
                }

                onMainDispatcher {
                    if (!isAdded || getView() == null) return@onMainDispatcher
                    binding.dnsLeakResult.text = reportText
                    binding.dnsLeakTest.isEnabled = true
                }
            }
        }

        binding.exportCrashLog.setOnClickListener {
            SendLog.sendLog(requireContext(), "OWN_Diagnostics")
        }

        binding.localBackupNow.setOnClickListener {
            runOnDefaultDispatcher {
                val success = BackupHelper.autoBackupLocal()
                onMainDispatcher {
                    if (!isAdded || getView() == null) return@onMainDispatcher
                    Snackbar.make(binding.root, if (success) "本地备份成功 (已保存至应用备份目录)" else "备份失败", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }
}