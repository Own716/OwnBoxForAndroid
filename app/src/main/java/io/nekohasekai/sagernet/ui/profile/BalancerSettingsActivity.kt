package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
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
import io.nekohasekai.sagernet.widget.OutboundPreference
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_balancer_settings) {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.balancerFrontProxy = profile.id
            onMainDispatcher {
                frontProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.balancerLandingProxy = profile.id
            onMainDispatcher {
                landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

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
        val gids = if (targetGroupIds != null && targetGroupIds.isNotEmpty()) {
            targetGroupIds
        } else if (targetGroupId > 0L) {
            listOf(targetGroupId)
        } else emptyList()
        DataStore.balancerTargetGroups = gids.joinToString(",")
        DataStore.balancerTargetGroup = gids.firstOrNull() ?: 0L
        DataStore.balancerStrategy = when (strategy) {
            "round_robin" -> BalancerBean.STRATEGY_ROUND_ROBIN
            "failover", "stable" -> BalancerBean.STRATEGY_LEAST_PING
            "consistent_hash" -> BalancerBean.STRATEGY_ROUND_ROBIN
            null, "" -> BalancerBean.STRATEGY_LEAST_PING
            else -> strategy
        }
        DataStore.balancerTestUrl = testUrl
        DataStore.balancerInterval = interval
        DataStore.balancerTolerance = tolerance
        DataStore.balancerToleranceUnit = if (toleranceUnit.isNullOrBlank()) "ms" else toleranceUnit
        DataStore.balancerUseFrontProxy = useFrontProxy
        DataStore.balancerUseLandingProxy = useLandingProxy
        DataStore.balancerFrontProxy = frontProxy
        DataStore.balancerFrontProxyTmp = if (frontProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerLandingProxy = landingProxy
        DataStore.balancerLandingProxyTmp = if (landingProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerNameExclude = nameExclude ?: ""
        DataStore.balancerNameInclude = nameInclude ?: ""
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        balancerType = DataStore.balancerType
        val gids = DataStore.balancerTargetGroups.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
        targetGroupIds = ArrayList(gids)
        targetGroupId = gids.firstOrNull() ?: 0L
        strategy = DataStore.balancerStrategy
        testUrl = DataStore.balancerTestUrl
        interval = DataStore.balancerInterval
        tolerance = DataStore.balancerTolerance
        toleranceUnit = DataStore.balancerToleranceUnit
        useFrontProxy = DataStore.balancerUseFrontProxy
        useLandingProxy = DataStore.balancerUseLandingProxy
        frontProxy = if (DataStore.balancerFrontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerFrontProxy
        } else {
            -1L
        }
        landingProxy = if (DataStore.balancerLandingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerLandingProxy
        } else {
            -1L
        }
        nameExclude = DataStore.balancerNameExclude
        nameInclude = DataStore.balancerNameInclude
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.balancer_preferences)

        frontProxyPreference = findPreference("balancerFrontProxy")!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerFrontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(
                            this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.balancerFrontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }

        landingProxyPreference = findPreference("balancerLandingProxy")!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerLandingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(
                            this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.balancerLandingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupPref = findPreference<Preference>("balancerTargetGroup")
        val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())

        fun updateGroupSummary() {
            val selectedGids = DataStore.balancerTargetGroups.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > 0L }
                .toSet()
            val matchedGroups = allGroups.filter { selectedGids.contains(it.id) }
            groupPref?.summary = when {
                matchedGroups.isEmpty() -> getString(androidx.preference.R.string.not_set)
                matchedGroups.size <= 2 -> matchedGroups.joinToString(", ") { it.displayName() }
                else -> "${matchedGroups.size} 个分组: " + matchedGroups.take(2).joinToString(", ") { it.displayName() } + "..."
            }
        }
        updateGroupSummary()

        groupPref?.setOnPreferenceClickListener {
            val currentSelected = DataStore.balancerTargetGroups.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > 0L }
                .toMutableSet()
            if (currentSelected.isEmpty() && DataStore.balancerTargetGroup > 0L) {
                currentSelected.add(DataStore.balancerTargetGroup)
            }
            val groupNames = allGroups.map { g ->
                val count = runCatching { SagerDatabase.proxyDao.getByGroup(g.id).size }.getOrDefault(0)
                "${g.displayName()} ($count)"
            }.toTypedArray()
            val checkedStates = allGroups.map { currentSelected.contains(it.id) }.toBooleanArray()

            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                .setTitle(R.string.balancer_select_group)
                .setMultiChoiceItems(groupNames, checkedStates) { _, which, isChecked ->
                    checkedStates[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newGids = allGroups.filterIndexed { index, _ -> checkedStates[index] }.map { it.id }
                    DataStore.balancerTargetGroups = newGids.joinToString(",")
                    DataStore.balancerTargetGroup = newGids.firstOrNull() ?: 0L
                    updateGroupSummary()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        val useFrontProxyPref = findPreference<Preference>("balancerUseFrontProxy")
        val useLandingProxyPref = findPreference<Preference>("balancerUseLandingProxy")
        val nameExcludePref = findPreference<EditTextPreference>("balancerNameExclude")
        val nameIncludePref = findPreference<EditTextPreference>("balancerNameInclude")

        fun updateTypeVisibility(type: Int) {
            val isGroup = type == BalancerBean.TYPE_GROUP
            groupPref?.isVisible = isGroup
            useFrontProxyPref?.isVisible = isGroup
            useLandingProxyPref?.isVisible = isGroup
            nameExcludePref?.isVisible = isGroup
            nameIncludePref?.isVisible = isGroup
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
                getString(androidx.preference.R.string.not_set)
            } else {
                pref.text
            }
        }

        val intervalPref = findPreference<EditTextPreference>("balancerInterval")
        intervalPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val text = pref.text?.trim()
            val value = text?.toIntOrNull()
            if (value != null && value > 0) {
                value.toString()
            } else {
                "300"
            }
        }

        val tolerancePref = findPreference<EditTextPreference>("balancerTolerance")
        val toleranceUnitPref = findPreference<SimpleMenuPreference>("balancerToleranceUnit")

        // Strategy-based visibility: only leastPing uses tolerance / url / interval probe settings
        fun updateStrategyVisibility(strategy: String) {
            val isLeastPing = strategy == BalancerBean.STRATEGY_LEAST_PING
            tolerancePref?.isVisible = isLeastPing
            toleranceUnitPref?.isVisible = isLeastPing
            urlPref?.isVisible = isLeastPing
            intervalPref?.isVisible = isLeastPing
        }

        fun updateToleranceSummary() {
            val text = tolerancePref?.text?.trim()
            val unit = DataStore.balancerToleranceUnit
            val value = text?.toIntOrNull()
            tolerancePref?.summary = when {
                value != null && value >= 0 -> "$value $unit"
                unit == "s" -> "1 s"
                else -> "300 ms"
            }
        }
        updateToleranceSummary()
        updateStrategyVisibility(DataStore.balancerStrategy)

        val strategyPref = findPreference<SimpleMenuPreference>("balancerStrategy")
        strategyPref?.value = DataStore.balancerStrategy
        strategyPref?.setOnPreferenceChangeListener { _, newValue ->
            val strategy = (newValue as? String) ?: BalancerBean.STRATEGY_LEAST_PING
            DataStore.balancerStrategy = strategy
            updateStrategyVisibility(strategy)
            true
        }

        tolerancePref?.setOnPreferenceChangeListener { _, newValue ->
            val str = (newValue as? String)?.trim().orEmpty()
            val v = str.toIntOrNull()
            if (v != null && v >= 0) {
                DataStore.balancerTolerance = v
                tolerancePref.text = v.toString()
                updateToleranceSummary()
            }
            true
        }

        toleranceUnitPref?.setOnPreferenceChangeListener { _, newValue ->
            val unit = (newValue as? String) ?: "ms"
            DataStore.balancerToleranceUnit = unit
            updateToleranceSummary()
            true
        }

        // Name filter summary providers
        nameExcludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(androidx.preference.R.string.not_set) else pref.text
        }
        nameIncludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(androidx.preference.R.string.not_set) else pref.text
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
        if (profile.id == DataStore.balancerFrontProxy || profile.id == DataStore.balancerLandingProxy) return false
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.balancerBean ?: return true
            if (bean.proxies.contains(DataStore.editingId)) return false
            if (bean.frontProxy == DataStore.editingId || bean.landingProxy == DataStore.editingId) return false
        }
        return true
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true
                val data = result.data ?: return@runOnDefaultDispatcher
                val multiIds = data.getLongArrayExtra(ProfileSelectActivity.EXTRA_PROFILE_IDS)
                if (multiIds != null) {
                    val profiles = ProfileManager.getProfiles(multiIds.toList()).associateBy { it.id }
                    var circularFound = false
                    val newProxyList = ArrayList<ProxyEntity>()
                    val seen = HashSet<Long>()
                    for (id in multiIds) {
                        if (seen.add(id)) {
                            val p = profiles[id] ?: continue
                            if (!testProfileAllowed(p)) {
                                circularFound = true
                                continue
                            }
                            newProxyList.add(p)
                        }
                    }
                    onMainDispatcher {
                        if (circularFound) {
                            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                .setTitle(R.string.circular_reference)
                                .setMessage(R.string.circular_reference_sum)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        proxyList.clear()
                        proxyList.addAll(newProxyList)
                        configurationAdapter.notifyDataSetChanged()
                    }
                } else {
                    val singleId = data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
                    if (singleId > 0L) {
                        val profile = ProfileManager.getProfile(singleId)
                        if (profile != null) {
                            if (!testProfileAllowed(profile)) {
                                onMainDispatcher {
                                    MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                        .setTitle(R.string.circular_reference)
                                        .setMessage(R.string.circular_reference_sum)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            } else {
                                onMainDispatcher {
                                    if (replacing != 0 && replacing <= proxyList.size) {
                                        proxyList[replacing - 1] = profile
                                        configurationAdapter.notifyItemChanged(replacing)
                                    } else {
                                        if (proxyList.none { it.id == profile.id }) {
                                            proxyList.add(profile)
                                            configurationAdapter.notifyItemInserted(proxyList.size)
                                        }
                                    }
                                }
                            }
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
                val excludeList = mutableListOf<Long>()
                if (DataStore.editingId > 0L) excludeList.add(DataStore.editingId)
                if (DataStore.balancerFrontProxy > 0L) excludeList.add(DataStore.balancerFrontProxy)
                if (DataStore.balancerLandingProxy > 0L) excludeList.add(DataStore.balancerLandingProxy)

                selectProfileForAdd.launch(
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    ).apply {
                        putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                        putExtra(
                            ProfileSelectActivity.EXTRA_SELECTED_IDS,
                            proxyList.map { it.id }.toLongArray()
                        )
                        if (excludeList.isNotEmpty()) {
                            putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeList.toLongArray())
                        }
                    }
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

            val serverAddress = profile.displayAddress().trim()
            val group = if (proxyEntity.groupId > 0L) {
                runCatching { SagerDatabase.groupDao.getById(proxyEntity.groupId) }.getOrNull()
            } else null
            val groupName = group?.takeIf { !it.ungrouped }?.name?.trim()?.takeIf {
                it.isNotBlank() && it != "null" && it != "[]"
            }

            val detailText = if (groupName != null) {
                if (serverAddress.isNotBlank()) "$serverAddress      [$groupName]" else "[$groupName]"
            } else {
                serverAddress
            }

            binding.profileAddress.text = detailText
            binding.profileAddress.isSelected = true
            (binding.profileAddress.parent as View).isVisible = detailText.isNotBlank()
            binding.trafficText.text = ""
            binding.trafficText.isGone = true

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
