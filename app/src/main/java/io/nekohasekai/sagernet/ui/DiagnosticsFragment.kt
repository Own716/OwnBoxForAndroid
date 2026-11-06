package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.LandingIpInfo
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.ServiceCategory
import io.nekohasekai.sagernet.utils.ServiceDiagnosticsManager
import io.nekohasekai.sagernet.utils.ServiceStatusItem
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.LandingIpBottomSheet
import io.nekohasekai.sagernet.widget.PinnedDiagnosticsBottomSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DiagnosticsFragment : ToolbarFragment(R.layout.layout_diagnostics) {

    private lateinit var cardSummary: MaterialCardView
    private lateinit var tvCountUnlocked: TextView
    private lateinit var tvCountBlocked: TextView
    private lateinit var tvCountOther: TextView

    private lateinit var layoutIpBar: LinearLayout
    private lateinit var tvOverviewFlag: TextView
    private lateinit var tvOverviewSummary: TextView
    private lateinit var btnOverviewDetails: TextView

    private lateinit var chipGroupCategory: ChipGroup
    private lateinit var btnPinnedSettings: FrameLayout
    private lateinit var btnRetestAll: MaterialButton
    private lateinit var recyclerServices: RecyclerView

    private lateinit var adapter: ServiceDiagnosticsAdapter
    private val serviceList = mutableListOf<ServiceStatusItem>()
    private var testJob: Job? = null
    private var ipOverviewJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isToolbarInitialized) {
            toolbar.setTitle(R.string.diagnostics_title)
        }

        cardSummary = view.findViewById(R.id.card_summary)
        tvCountUnlocked = view.findViewById(R.id.tv_count_unlocked)
        tvCountBlocked = view.findViewById(R.id.tv_count_blocked)
        tvCountOther = view.findViewById(R.id.tv_count_other)

        layoutIpBar = view.findViewById(R.id.layout_ip_bar)
        tvOverviewFlag = view.findViewById(R.id.tv_overview_flag)
        tvOverviewSummary = view.findViewById(R.id.tv_overview_summary)
        btnOverviewDetails = view.findViewById(R.id.btn_overview_details)

        chipGroupCategory = view.findViewById(R.id.chip_group_category)
        btnPinnedSettings = view.findViewById(R.id.btn_pinned_settings)
        btnRetestAll = view.findViewById(R.id.btn_retest_all)
        recyclerServices = view.findViewById(R.id.recycler_services)

        // Theme adjustments
        val isNight = Theme.usingNightMode(requireContext())
        val isWhite = Theme.isWhiteTheme(requireContext())
        val cardBg = when {
            isNight -> Color.parseColor("#1B1B1E")
            isWhite -> Color.parseColor("#F7F8FA")
            else -> null
        }
        val strokeColor = when {
            isNight -> Color.parseColor("#2C2C30")
            isWhite -> Color.parseColor("#E8E9EC")
            else -> null
        }
        if (cardBg != null) {
            cardSummary.setCardBackgroundColor(cardBg)
        }
        if (strokeColor != null) {
            cardSummary.strokeColor = strokeColor
        }

        // Initialize Services
        serviceList.clear()
        serviceList.addAll(ServiceDiagnosticsManager.createInitialList())

        adapter = ServiceDiagnosticsAdapter(
            allItems = serviceList,
            onStatsChanged = { unlocked, blocked, other ->
                tvCountUnlocked.text = "$unlocked"
                tvCountBlocked.text = "$blocked"
                tvCountOther.text = "$other"
            },
            onItemClick = { item ->
                testSingleService(item)
            }
        )
        recyclerServices.layoutManager = LinearLayoutManager(requireContext())
        recyclerServices.adapter = adapter

        // Setup Categories
        chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_ai -> adapter.filterByCategory(ServiceCategory.AI)
                R.id.chip_streaming -> adapter.filterByCategory(ServiceCategory.STREAMING)
                R.id.chip_social -> adapter.filterByCategory(ServiceCategory.SOCIAL)
                R.id.chip_gaming_dev -> adapter.filterByCategory(ServiceCategory.GAMING_DEV)
                R.id.chip_crypto -> adapter.filterByCategory(ServiceCategory.CRYPTO)
                else -> adapter.filterByCategory(ServiceCategory.ALL)
            }
        }

        // IP Overview click listeners
        val openDetails = View.OnClickListener { showFullIpDetails() }
        layoutIpBar.setOnClickListener(openDetails)
        btnOverviewDetails.setOnClickListener(openDetails)

        // Pinned settings button
        btnPinnedSettings.setOnClickListener {
            val act = activity ?: return@setOnClickListener
            PinnedDiagnosticsBottomSheet.show(act) { newPinned ->
                startTestPinnedServices(newPinned)
            }
        }

        // Retest All
        btnRetestAll.setOnClickListener {
            startTestAllServices()
        }

        // Initial Load: Only test the 3 pinned services!
        loadIpOverview()
        startTestPinnedServices(DataStore.getPinnedDiagnostics())
    }

    private fun loadIpOverview() {
        val cached = LandingIpManager.getCachedInfo()
        if (cached != null) {
            bindIpInfo(cached)
        } else {
            refreshIpOverview(force = false)
        }
    }

    private fun refreshIpOverview(force: Boolean) {
        if (!DataStore.serviceState.connected) {
            tvOverviewFlag.text = "🌐"
            tvOverviewSummary.text = getString(R.string.diagnostics_not_connected)
            return
        }

        tvOverviewFlag.text = "🌐"
        tvOverviewSummary.text = getString(R.string.landing_ip_querying)

        ipOverviewJob?.cancel()
        ipOverviewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = LandingIpManager.queryLandingIp(DataStore.selectedProxy, forceRefresh = force)
                result.onSuccess { info ->
                    bindIpInfo(info)
                }.onFailure { err ->
                    tvOverviewFlag.text = "⚠️"
                    tvOverviewSummary.text = err.message ?: "无法获取当前出口 IP"
                }
            } catch (_: Throwable) {
                tvOverviewFlag.text = "⚠️"
                tvOverviewSummary.text = "出口 IP 查询失败"
            }
        }
    }

    private fun bindIpInfo(info: LandingIpInfo) {
        tvOverviewFlag.text = info.countryFlag.ifBlank { "🌐" }
        val location = info.locationText.split(",").firstOrNull()?.trim() ?: info.locationText
        val type = if (info.typeLabel.isNotBlank()) info.typeLabel else info.networkType
        tvOverviewSummary.text = listOf(info.ip, location, type).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun showFullIpDetails() {
        val cached = LandingIpManager.getCachedInfo()
        val act = activity ?: return
        if (cached != null) {
            LandingIpBottomSheet.show(act, cached) {
                refreshIpOverview(force = true)
            }
        } else {
            refreshIpOverview(force = true)
            Toast.makeText(requireContext(), getString(R.string.landing_ip_querying), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTestPinnedServices(pinnedIds: List<String>) {
        if (!DataStore.serviceState.connected) {
            return
        }

        val targetItems = serviceList.filter { pinnedIds.contains(it.id) }
        testJob?.cancel()
        testJob = viewLifecycleOwner.lifecycleScope.launch {
            for (item in targetItems) {
                launch {
                    ServiceDiagnosticsManager.testService(item) { updated ->
                        activity?.runOnUiThread {
                            adapter.updateItem(updated)
                        }
                    }
                }
            }
        }
    }

    private fun startTestAllServices() {
        if (!DataStore.serviceState.connected) {
            Toast.makeText(requireContext(), getString(R.string.diagnostics_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        testJob?.cancel()
        testJob = viewLifecycleOwner.lifecycleScope.launch {
            btnRetestAll.isEnabled = false
            btnRetestAll.text = "检测中..."
            try {
                val targets = adapter.getCurrentCategoryItems()
                for (item in targets) {
                    launch {
                        ServiceDiagnosticsManager.testService(item) { updated ->
                            activity?.runOnUiThread {
                                adapter.updateItem(updated)
                            }
                        }
                    }
                }
            } finally {
                btnRetestAll.isEnabled = true
                btnRetestAll.text = getString(R.string.diagnostics_retest_all)
            }
        }
    }

    private fun testSingleService(item: ServiceStatusItem) {
        if (!DataStore.serviceState.connected) {
            Toast.makeText(requireContext(), getString(R.string.diagnostics_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ServiceDiagnosticsManager.testService(item) { updated ->
                activity?.runOnUiThread {
                    adapter.updateItem(updated)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        testJob?.cancel()
        ipOverviewJob?.cancel()
    }
}
