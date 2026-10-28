package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.ActiveOutboundTracker
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAutoSelectorMemberBinding
import io.nekohasekai.sagernet.databinding.LayoutAutoSelectorStatusBinding
import io.nekohasekai.sagernet.ui.test.TestFormat
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutoSelectorStatusActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SELECTOR_ID = "selector_id"

        fun intent(context: Context, selectorId: Long): Intent =
            Intent(context, AutoSelectorStatusActivity::class.java).putExtra(EXTRA_SELECTOR_ID, selectorId)
    }

    private lateinit var binding: LayoutAutoSelectorStatusBinding
    private val adapter = MemberAdapter()
    private var selectorId: Long = -1L
    private var onlyProblems = false
    private var profiles: List<ProxyEntity> = emptyList()
    private var activeLeafDisplayName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutAutoSelectorStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.autosel_stats_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        selectorId = intent.getLongExtra(EXTRA_SELECTOR_ID, -1L)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.recheck.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                if (selectorId > 0) {
                    val p = ProfileManager.getProfile(selectorId)
                    if (p != null && p.type == ProxyEntity.TYPE_BALANCER) {
                        val members = ProfileManager.resolveBalancerMembers(p)
                        if (members.isNotEmpty()) {
                            TestSessionClient.startProfiles(TestSpec.KIND_URL, members.map { it.id }, p.groupId)
                        } else {
                            TestSessionClient.startGroup(TestSpec.KIND_URL, p.groupId)
                        }
                    } else {
                        val gid = p?.groupId ?: selectorId
                        TestSessionClient.startGroup(TestSpec.KIND_URL, gid)
                    }
                } else {
                    TestSessionClient.startGroup(TestSpec.KIND_URL, DataStore.selectedGroup)
                }
                withContext(Dispatchers.Main) {
                    reload()
                }
            }
        }

        binding.automatic.setOnClickListener {
            reload()
        }

        lifecycleScope.launch {
            TestSessionClient.state.collect { state ->
                binding.progress.isVisible = state.running
                if (!state.running) {
                    reload()
                }
            }
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_selector_status_menu, menu)
        menu.findItem(R.id.action_only_problems)?.isChecked = onlyProblems
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_only_problems -> {
                onlyProblems = !onlyProblems
                item.isChecked = onlyProblems
                render()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun reload() {
        lifecycleScope.launch(Dispatchers.IO) {
            var leafName: String? = null
            val list = if (selectorId > 0) {
                val p = ProfileManager.getProfile(selectorId)
                if (p != null) {
                    if (p.type == ProxyEntity.TYPE_BALANCER) {
                        leafName = ActiveOutboundTracker.getActiveLeafNodeDisplay(p)
                        val members = ProfileManager.resolveBalancerMembers(p)
                        if (members.isNotEmpty()) members else SagerDatabase.proxyDao.getByGroup(p.groupId).filter { it.type != ProxyEntity.TYPE_BALANCER }
                    } else {
                        SagerDatabase.proxyDao.getByGroup(p.groupId)
                    }
                } else {
                    SagerDatabase.proxyDao.getByGroup(selectorId)
                }
            } else {
                SagerDatabase.proxyDao.getByGroup(DataStore.selectedGroup)
            }
            profiles = list
            activeLeafDisplayName = leafName
            withContext(Dispatchers.Main) {
                render()
            }
        }
    }

    private fun render() {
        val activeMember = if (!activeLeafDisplayName.isNullOrBlank()) {
            profiles.firstOrNull { it.displayName() == activeLeafDisplayName }
        } else {
            profiles.firstOrNull { it.id == DataStore.selectedProxy }
                ?: profiles.filter { it.status > 0 }.minByOrNull { it.status }
        }
        val targetProfile = if (selectorId > 0) ProfileManager.getProfile(selectorId) else null
        val headlineName = if (targetProfile != null && targetProfile.type == ProxyEntity.TYPE_BALANCER) {
            val leaf = activeMember?.displayName()
            if (!leaf.isNullOrBlank()) {
                "${targetProfile.displayName()} · $leaf"
            } else {
                targetProfile.displayName()
            }
        } else {
            activeMember?.displayName()
                ?: targetProfile?.displayName()
                ?: ""
        }

        val workingCount = profiles.count { it.status > 0 }
        val totalCount = profiles.size

        if (headlineName.isNotBlank()) {
            binding.headline.text = getString(
                R.string.autosel_status_on_working,
                headlineName,
                workingCount,
                totalCount
            )
        } else {
            binding.headline.text = getString(R.string.autosel_title)
        }

        binding.detail.text = getString(
            R.string.autosel_plan_counts,
            workingCount,
            totalCount,
            workingCount
        )
        binding.detail.isVisible = true

        val displayList = (if (onlyProblems) {
            profiles.filter { it.status < 0 }
        } else {
            profiles
        }).sortedWith(compareBy(
            { it.status <= 0 },
            { if (it.status > 0) it.status else Int.MAX_VALUE }
        ))

        binding.empty.isVisible = displayList.isEmpty()
        adapter.submitList(displayList, activeMember?.id ?: DataStore.selectedProxy)
    }

    private inner class MemberAdapter : RecyclerView.Adapter<MemberViewHolder>() {
        private var items = listOf<ProxyEntity>()
        private var activeId: Long = -1L

        fun submitList(list: List<ProxyEntity>, activeProfileId: Long = -1L) {
            items = list
            activeId = activeProfileId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
            val itemBinding = LayoutAutoSelectorMemberBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return MemberViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
            holder.bind(items[position], position + 1, activeId)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class MemberViewHolder(
        val itemBinding: LayoutAutoSelectorMemberBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(profile: ProxyEntity, rankIndex: Int, activeId: Long) {
            itemBinding.rank.text = rankIndex.toString()
            itemBinding.name.text = profile.displayName()

            val isActive = profile.id == activeId || profile.id == DataStore.selectedProxy
            itemBinding.note.isVisible = isActive
            if (isActive) {
                itemBinding.note.text = getString(R.string.autosel_note_selected)
            }

            val latency = profile.status
            if (latency > 0) {
                itemBinding.latency.text = TestFormat.latency(this@AutoSelectorStatusActivity, latency)
                itemBinding.latency.setTextColor(ProxyEntity.latencyColor(latency))
            } else if (latency < 0) {
                itemBinding.latency.text = getString(R.string.unavailable)
                itemBinding.latency.setTextColor(ProxyEntity.latencyColor(-1))
            } else {
                itemBinding.latency.text = "—"
                itemBinding.latency.setTextColor(ProxyEntity.latencyColor(0))
            }

            itemBinding.root.setOnClickListener {
                DataStore.selectedProxy = profile.id
                DataStore.currentProfile = profile.id
                render()
            }
        }
    }
}
