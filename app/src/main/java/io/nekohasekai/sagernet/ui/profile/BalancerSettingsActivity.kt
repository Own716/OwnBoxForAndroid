package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_balancer_settings) {

    override fun createEntity(): BalancerBean {
        val count = runCatching { SagerDatabase.proxyDao.getByType(ProxyEntity.TYPE_BALANCER).size }.getOrDefault(0) + 1
        return BalancerBean().apply {
            name = String.format("Balancer %02d", count)
        }
    }

    val proxyList = ArrayList<ProxyEntity>()

    override fun BalancerBean.init() {
        DataStore.profileName = name
        DataStore.balancerType = balancerType
        DataStore.balancerTargetGroup = targetGroupId
        DataStore.balancerStrategy = strategy
        DataStore.balancerTestUrl = testUrl
        DataStore.balancerInterval = interval
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        balancerType = DataStore.balancerType
        targetGroupId = DataStore.balancerTargetGroup
        strategy = DataStore.balancerStrategy
        testUrl = DataStore.balancerTestUrl
        interval = DataStore.balancerInterval
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.balancer_preferences)

        val groupPref = findPreference<SimpleMenuPreference>("balancerTargetGroup")
        val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())
        if (allGroups.isNotEmpty()) {
            groupPref?.entries = allGroups.map { it.displayName() }.toTypedArray()
            groupPref?.entryValues = allGroups.map { it.id.toString() }.toTypedArray()
            if (DataStore.balancerTargetGroup == 0L || allGroups.none { it.id == DataStore.balancerTargetGroup }) {
                DataStore.balancerTargetGroup = allGroups.first().id
            }
            groupPref?.value = DataStore.balancerTargetGroup.toString()
        }

        fun updateTypeVisibility(type: Int) {
            val isGroup = type == BalancerBean.TYPE_GROUP
            groupPref?.isVisible = isGroup
            configurationList.isVisible = !isGroup
            listCell.isVisible = !isGroup
        }

        val typePref = findPreference<SimpleMenuPreference>("balancerType")
        typePref?.setOnPreferenceChangeListener { _, newValue ->
            val type = (newValue as? String)?.toIntOrNull() ?: 0
            DataStore.balancerType = type
            updateTypeVisibility(type)
            true
        }

        val urlPref = findPreference<EditTextPreference>("balancerTestUrl")
        urlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) {
                getString(R.string.balancer_custom_url_sum)
            } else {
                pref.text
            }
        }

        val intervalPref = findPreference<EditTextPreference>("balancerInterval")
        intervalPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val text = pref.text?.trim()
            val value = text?.toIntOrNull()
            if (value != null && value > 0) {
                "${value}s"
            } else {
                "300s"
            }
        }

        updateTypeVisibility(DataStore.balancerType)
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager
    lateinit var listCell: View

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.balancer_settings)
        configurationList = findViewById(R.id.configuration_list)
        listCell = findViewById(R.id.list_cell)
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter
        configurationList.isNestedScrollingEnabled = false

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view)?.apply {
            isNestedScrollingEnabled = false
            (layoutParams ?: ViewGroup.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = DataStore.serverProtocol.split(",")
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLong() }
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    proxyList.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                notifyDataSetChanged()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.balancerBean ?: return true
            if (bean.proxies.contains(DataStore.editingId)) return false
        }
        return true
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true

                val profile = ProfileManager.getProfile(
                    result.data!!.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                    )
                )!!

                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@BalancerSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.circular_reference_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    configurationList.post {
                        if (replacing != 0) {
                            proxyList[replacing - 1] = profile
                            configurationAdapter.notifyItemChanged(replacing)
                        } else {
                            proxyList.add(profile)
                            configurationAdapter.notifyItemInserted(proxyList.size)
                        }
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
        }
    }

    inner class ProfileHolder(val binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(proxyEntity: ProxyEntity) {
            val profile = proxyEntity.requireBean()

            binding.profileName.text = profile.displayName()
            binding.profileType.text = proxyEntity.displayType()
            binding.profileType.setTextColor(getProtocolColor(proxyEntity.type))
            binding.profileAddress.text = profile.displayAddress()

            binding.edit.setImageResource(R.drawable.ic_image_edit)
            binding.edit.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
            binding.remove.isVisible = true
            binding.remove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos > 0 && pos <= proxyList.size) {
                    proxyList.removeAt(pos - 1)
                    configurationAdapter.notifyItemRemoved(pos)
                }
            }
            binding.share.isVisible = false
        }
    }
}
