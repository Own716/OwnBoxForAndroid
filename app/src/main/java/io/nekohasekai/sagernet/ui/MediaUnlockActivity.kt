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
import io.nekohasekai.sagernet.tools.media.MediaUnlockItem
import io.nekohasekai.sagernet.tools.media.MediaUnlockManager
import io.nekohasekai.sagernet.tools.media.UnlockStatus
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaUnlockActivity : ThemedActivity() {

    private lateinit var binding: ActivityMediaUnlockBinding
    private lateinit var adapter: MediaUnlockAdapter
    private var testJob: Job? = null
    private var sessionNodeId: Long = -1L

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

        sessionNodeId = DataStore.selectedProxy
        val currentProfile = ProfileManager.getProfile(sessionNodeId)
        binding.tvCurrentNode.text = currentProfile?.displayName() ?: getString(R.string.not_connected)

        val cachedIp = LandingIpManager.getCachedInfo()
        binding.tvCurrentIp.text = if (cachedIp != null) "出口 IP: ${cachedIp.briefText}" else "出口 IP: 查询中..."

        if (!DataStore.serviceState.connected) {
            Toast.makeText(this, getString(R.string.vpn_not_connected_warning), Toast.LENGTH_LONG).show()
            val initialList = MediaUnlockManager.createInitialItems(notConnected = true)
            adapter.setItems(initialList)
            binding.tvSummaryStats.text = "代理服务未连接"
            binding.tvSummaryRate.text = "未开始"
            binding.progressUnlockRatio.progress = 0
            return
        }

        val items = MediaUnlockManager.createInitialItems(notConnected = false)
        adapter.setItems(items)

        val total = items.size
        binding.progressUnlockRatio.max = total
        binding.progressUnlockRatio.progress = 0
        binding.tvSummaryStats.text = "检测进度: 0 / $total 已就绪"
        binding.tvSummaryRate.text = "探测中..."

        testJob = lifecycleScope.launch {
            if (cachedIp == null) {
                val ipRes = LandingIpManager.queryLandingIp(sessionNodeId)
                ipRes.onSuccess {
                    binding.tvCurrentIp.text = "出口 IP: ${it.briefText}"
                }
            }

            var completedCount = 0
            var unlockedCount = 0

            items.forEachIndexed { index, item ->
                launch {
                    val tested = MediaUnlockManager.executePlatformTest(item)

                    withContext(Dispatchers.Main) {
                        // Node context guard: if user switched proxy node while tests were running, cancel and discard
                        if (DataStore.selectedProxy != sessionNodeId) {
                            testJob?.cancel()
                            Toast.makeText(this@MediaUnlockActivity, "检测过程中代理节点已切换，已取消旧结果", Toast.LENGTH_SHORT).show()
                            return@withContext
                        }

                        items[index] = tested
                        adapter.updateItem(index, tested)

                        completedCount++
                        if (tested.status == UnlockStatus.UNLOCKED || tested.status == UnlockStatus.PARTIAL) {
                            unlockedCount++
                        }

                        binding.progressUnlockRatio.progress = completedCount
                        binding.tvSummaryStats.text = "检测进度: $completedCount / $total 已完成"
                        binding.tvSummaryRate.text = "可用平台: $unlockedCount / $total"
                    }
                }
            }
        }
    }

    class MediaUnlockAdapter : RecyclerView.Adapter<MediaUnlockAdapter.VH>() {

        private val items = ArrayList<MediaUnlockItem>()

        fun setItems(newItems: List<MediaUnlockItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun updateItem(index: Int, item: MediaUnlockItem) {
            if (index in items.indices) {
                items[index] = item
                notifyItemChanged(index)
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.iv_platform_icon)
            val name: TextView = view.findViewById(R.id.tv_platform_name)
            val category: TextView = view.findViewById(R.id.tv_platform_category)
            val progress: CircularProgressIndicator = view.findViewById(R.id.progress_indicator)
            val badge: TextView = view.findViewById(R.id.tv_status_badge)
            val latency: TextView = view.findViewById(R.id.tv_latency)
            val desc: TextView = view.findViewById(R.id.tv_description)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_unlock, parent, false)
            return VH(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.iconRes)
            holder.name.text = item.name
            holder.category.text = item.category
            holder.desc.text = item.description

            if (item.status == UnlockStatus.TESTING) {
                holder.progress.visibility = View.VISIBLE
                holder.badge.visibility = View.GONE
                holder.latency.visibility = View.GONE
            } else {
                holder.progress.visibility = View.GONE
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = item.badgeText
                holder.badge.setTextColor(Color.parseColor(item.status.colorHex))

                if (item.latencyMs > 0) {
                    holder.latency.visibility = View.VISIBLE
                    holder.latency.text = "${item.latencyMs} ms"
                } else {
                    holder.latency.visibility = View.GONE
                }
            }
        }
    }
}
