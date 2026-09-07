package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.ActivityPortTestBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class PortTestActivity : ThemedActivity() {

    private lateinit var binding: ActivityPortTestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPortTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.port_test_title)
        }

        setupChips()

        binding.btnStartTest.setOnClickListener {
            runPortTest()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupChips() {
        binding.chip443.setOnClickListener { binding.edtPort.setText("443") }
        binding.chip80.setOnClickListener { binding.edtPort.setText("80") }
        binding.chip853.setOnClickListener { binding.edtPort.setText("853") }
        binding.chip53.setOnClickListener { binding.edtPort.setText("53") }
        binding.chip22.setOnClickListener { binding.edtPort.setText("22") }
        binding.chip8443.setOnClickListener { binding.edtPort.setText("8443") }
    }

    private fun runPortTest() {
        var host = binding.edtHost.text?.toString()?.trim().orEmpty()
        val portStr = binding.edtPort.text?.toString()?.trim().orEmpty()

        if (host.isBlank()) {
            binding.layoutHost.error = "请输入目标主机或 IP"
            return
        }
        binding.layoutHost.error = null

        // Clean protocol prefix if user pasted a URL like https://example.com/
        host = host.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.layoutPort.error = "请输入有效端口 (1-65535)"
            return
        }
        binding.layoutPort.error = null

        if (!DataStore.serviceState.connected) {
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            showNotConnected(host, port)
            return
        }

        binding.btnStartTest.isEnabled = false
        binding.btnStartTest.text = "正在通过代理出口连接中..."
        binding.cardResult.visibility = View.GONE

        lifecycleScope.launch {
            val result = testSocketViaProxy(host, port)
            binding.btnStartTest.isEnabled = true
            binding.btnStartTest.text = getString(R.string.port_test_btn)

            result.fold(
                onSuccess = { cost ->
                    showSuccess(host, port, cost)
                },
                onFailure = { err ->
                    showFailure(host, port, err)
                }
            )
        }
    }

    private fun showNotConnected(host: String, port: Int) {
        binding.cardResult.visibility = View.VISIBLE
        binding.cardResult.setCardBackgroundColor(Color.parseColor("#64748B"))
        binding.ivResultIcon.setImageResource(R.drawable.ic_baseline_cancel_24)
        binding.tvResultTitle.text = "VPN 未连接"
        binding.tvResultLatency.text = "无法通过节点代理出口测试"
        binding.tvResultDetails.text = getString(R.string.vpn_not_connected_warning)
    }

    private fun showSuccess(host: String, port: Int, cost: Long) {
        binding.cardResult.visibility = View.VISIBLE
        binding.cardResult.setCardBackgroundColor(Color.parseColor("#059669")) // Emerald Green
        binding.ivResultIcon.setImageResource(R.drawable.ic_baseline_check_circle_24)
        binding.tvResultTitle.text = getString(R.string.port_test_success)
        binding.tvResultLatency.text = "TCP 握手耗时: $cost ms"
        binding.tvResultDetails.text = "通过当前节点代理出口已成功与 $host:$port 建立 TCP 双向连接会话"
    }

    private fun showFailure(host: String, port: Int, error: Throwable) {
        binding.cardResult.visibility = View.VISIBLE
        binding.cardResult.setCardBackgroundColor(Color.parseColor("#DC2626")) // Red
        binding.ivResultIcon.setImageResource(R.drawable.ic_baseline_cancel_24)
        binding.tvResultTitle.text = getString(R.string.port_test_failed)
        binding.tvResultLatency.text = "目标端口未响应"
        val reason = error.message ?: error.javaClass.simpleName
        binding.tvResultDetails.text = "无法连通 $host:$port，错误原因: $reason"
    }

    private suspend fun testSocketViaProxy(host: String, port: Int): Result<Long> = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        val startTime = System.currentTimeMillis()
        try {
            val mixedPort = DataStore.mixedPort
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", mixedPort))
            socket = Socket(proxy)
            socket.soTimeout = 6000
            val target = InetSocketAddress.createUnresolved(host, port)
            socket.connect(target, 6000)
            val cost = System.currentTimeMillis() - startTime
            Result.success(cost)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {}
        }
    }
}
