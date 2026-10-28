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

class AutoSelectorStatusActivity : AppCompatActivity() {

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
            val gid = if (selectorId > 0) {
                ProfileManager.getProfile(selectorId)?.groupId ?: selectorId
            } else {
                DataStore.selectedGroup
            }
            TestSessionClient.startGroup(TestSpec.KIND_URL, gid)
            reload()
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
            val list = if (selectorId > 0) {
                val p = ProfileManager.getProfile(selectorId)
                if (p != null) SagerDatabase.proxyDao.getByGroup(p.groupId)
                else SagerDatabase.proxyDao.getByGroup(selectorId)
            } else {
                SagerDatabase.proxyDao.getByGroup(DataStore.selectedGroup)
            }
            profiles = list
            withContext(Dispatchers.Main) {
                render()
            }
        }
    }

    private fun render() {
        val selectedId = DataStore.selectedProxy
        val selectedProfile = profiles.firstOrNull { it.id == selectedId }
        val workingCount = profiles.count { it.status > 0 }
        val totalCount = profiles.size

        if (selectedProfile != null) {
            binding.headline.text = getString(
                R.string.autosel_status_on_working,
                selectedProfile.displayName(),
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

        val displayList = if (onlyProblems) {
            profiles.filter { it.status < 0 }
        } else {
            profiles
        }

        binding.empty.isVisible = displayList.isEmpty()
        adapter.submitList(displayList)
    }

    private inner class MemberAdapter : RecyclerView.Adapter<MemberViewHolder>() {
        private var items = listOf<ProxyEntity>()

        fun submitList(list: List<ProxyEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
            val itemBinding = LayoutAutoSelectorMemberBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return MemberViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
            holder.bind(items[position], position + 1)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class MemberViewHolder(
        val itemBinding: LayoutAutoSelectorMemberBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(profile: ProxyEntity, rankIndex: Int) {
            itemBinding.rank.text = rankIndex.toString()
            itemBinding.name.text = profile.displayName()

            val isSelected = profile.id == DataStore.selectedProxy
            itemBinding.note.isVisible = isSelected
            if (isSelected) {
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
