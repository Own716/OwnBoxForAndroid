package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Typeface
import android.util.TypedValue
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.GravityCompat
import android.view.Gravity
import androidx.core.widget.addTextChangedListener
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.CountryFlagUtils
import kotlinx.coroutines.delay
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SpeedTestDirection
import io.nekohasekai.sagernet.SpeedTestOutcome
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.ActiveOutboundTracker
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.AndroidSpeedTestSession
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.bg.proto.completedSpeedTestCount
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.FixedGridLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.safeSnackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.BalancerSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksRSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SnellSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import okhttp3.internal.closeQuietly
import moe.matsuri.nb4a.utils.toBytesString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.collections.set
import androidx.appcompat.app.AlertDialog
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.utils.UiScaleManager
import kotlin.math.abs

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false,
    val selectedItem: ProxyEntity? = null,
    val titleRes: Int = 0,
    val multiSelect: Boolean = false,
    val initialSelectedIds: LongArray? = null,
    val excludedIds: LongArray? = null
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
        fun onProfileToggled(profileId: Long, isSelected: Boolean, totalSelected: Int) {}
    }

    companion object {
        /** Synthetic ProxyGroup ID used for the "All Groups" tab. Must be negative to avoid DB collision. */
        const val ALL_GROUPS_SENTINEL_ID = -1L
    }

    lateinit var adapter: GroupPagerAdapter
    var groupTabContainer: View? = null
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    val multiSelectedIds = java.util.Collections.synchronizedSet(LinkedHashSet<Long>()).apply {
        if (initialSelectedIds != null) {
            addAll(initialSelectedIds.toList())
        }
    }

    fun toggleMultiSelected(profileId: Long) {
        val isNowSelected: Boolean
        val count: Int
        synchronized(multiSelectedIds) {
            if (multiSelectedIds.contains(profileId)) {
                multiSelectedIds.remove(profileId)
                isNowSelected = false
            } else {
                multiSelectedIds.add(profileId)
                isNowSelected = true
            }
            count = multiSelectedIds.size
        }
        if (::adapter.isInitialized) {
            adapter.groupFragments.values.forEach { fragment ->
                fragment.adapter?.refreshProfileState(setOf(profileId))
            }
        }
        (activity as? SelectCallback)?.onProfileToggled(profileId, isNowSelected, count)
    }

    @Volatile
    private var selectedProxySnapshot = selectedItem?.id ?: 0L

    var currentSearchQuery: String = ""

    @Volatile
    private var currentProfileSnapshot = 0L

    @Volatile
    private var serviceStartedSnapshot = DataStore.serviceState.started

    private data class ProfileStateSnapshot(
        val selectedProxy: Long,
        val currentProfile: Long,
        val serviceStarted: Boolean,
    )

    private val profileStateRequests = Channel<Long>(Channel.CONFLATED)
    private val profileStateGeneration = AtomicLong()
    private val profileStateInitialized = CompletableDeferred<Unit>()
    private var speedTestJob: Job? = null
    private var speedTestRunner: SpeedTestQueueRunner<ProxyEntity>? = null
    private var speedTestDialog: AlertDialog? = null
    private var speedTestNotification: ConnectionTestNotification? = null
    private var speedTestHidden = false

    fun refreshProfileState() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val generation = profileStateGeneration.incrementAndGet()
            profileStateRequests.trySend(generation)
        }
    }

    private fun updateSelectedProxySnapshot(profileId: Long) {
        val generation = profileStateGeneration.incrementAndGet()
        updateProfileStateSnapshots(
            profileId,
            currentProfileSnapshot,
            DataStore.serviceState.started,
        )
        profileStateRequests.trySend(generation)
    }

    private fun startProfileStateActor() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            for (generation in profileStateRequests) {
                val snapshot = try {
                    withContext(Dispatchers.IO) {
                        ProfileStateSnapshot(
                            selectedProxy = selectedItem?.id ?: DataStore.selectedProxy,
                            currentProfile = DataStore.currentProfile,
                            serviceStarted = DataStore.serviceState.started,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    if (generation == profileStateGeneration.get()) {
                        profileStateInitialized.complete(Unit)
                    }
                    continue
                }
                if (generation != profileStateGeneration.get()) continue
                updateProfileStateSnapshots(
                    snapshot.selectedProxy,
                    snapshot.currentProfile,
                    snapshot.serviceStarted,
                )
                profileStateInitialized.complete(Unit)
            }
        }
    }

    private fun updateProfileStateSnapshots(
        selectedProxy: Long,
        currentProfile: Long,
        serviceStarted: Boolean,
    ) {
        val changedIds = mutableSetOf<Long>()
        if (selectedProxySnapshot != selectedProxy) {
            changedIds.add(selectedProxySnapshot)
            changedIds.add(selectedProxy)
        }
        if (currentProfileSnapshot != currentProfile) {
            changedIds.add(currentProfileSnapshot)
            changedIds.add(currentProfile)
        }
        if (serviceStartedSnapshot != serviceStarted) {
            changedIds.add(selectedProxySnapshot)
            changedIds.add(currentProfileSnapshot)
            changedIds.add(selectedProxy)
            changedIds.add(currentProfile)
        }
        changedIds.removeAll { it <= 0L }

        selectedProxySnapshot = selectedProxy
        currentProfileSnapshot = currentProfile
        serviceStartedSnapshot = serviceStarted

        if (changedIds.isEmpty() || !::adapter.isInitialized) return
        adapter.groupFragments.values.forEach { fragment ->
            fragment.adapter?.refreshProfileState(changedIds)
        }
    }

    fun isSelectedProfile(profileId: Long) = if (multiSelect) multiSelectedIds.contains(profileId) else selectedProxySnapshot == profileId

    private fun isCurrentProfile(profileId: Long) = currentProfileSnapshot == profileId

    private fun isCurrentGroupPagerAdapter(candidate: GroupPagerAdapter): Boolean {
        return ::adapter.isInitialized && adapter === candidate
    }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            if (!::adapter.isInitialized) return null
            val pos = if (::groupPager.isInitialized) groupPager.currentItem else adapter.selectedGroupIndex
            val group = adapter.groupList.getOrNull(pos) ?: return null
            adapter.groupFragments[group.id]
                ?: (childFragmentManager.findFragmentByTag("f" + group.id) as? GroupFragment)
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    fun switchAllGroupFragmentsLayout() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.switchLayoutMode()
            }
        }
    }

    fun refreshAllGroupFragmentsCardStyle() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.adapter?.notifyDataSetChanged()
            }
        }
    }

    private var isUserInteractingWithPager = false

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            isUserInteractingWithPager = (state == ViewPager2.SCROLL_STATE_DRAGGING || state == ViewPager2.SCROLL_STATE_SETTLING)
        }

        override fun onPageSelected(position: Int) {
            if (adapter.groupList.size > position) {
                val group = adapter.groupList[position]
                adapter.selectedGroupIndex = position
                updateToolbarMenuTitles()
                if (currentSearchQuery.isNotEmpty()) {
                    getCurrentGroupFragment()?.adapter?.filter(currentSearchQuery)
                }
                // Skip updating DataStore.selectedGroup for the synthetic "All" tab
                if (group.id == ALL_GROUPS_SENTINEL_ID) return
                if (DataStore.selectedGroup != group.id) {
                    DataStore.selectedGroup = group.id
                }
            }
        }
    }

    val isSearchActive: Boolean
        get() {
            val searchItem = if (isToolbarInitialized) toolbar.menu.findItem(R.id.action_search) else null
            val searchView = (searchItem?.actionView as? SearchView) ?: (if (isToolbarInitialized) toolbar.findViewById<SearchView>(R.id.action_search) else null)
            return searchView != null && !searchView.isIconified
        }

    private var searchJob: Job? = null

    override fun onQueryTextChange(query: String): Boolean {
        currentSearchQuery = query
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (query.isNotBlank()) {
                delay(180)
            }
            val groupFragment = getCurrentGroupFragment() ?: return@launch
            val adapter = groupFragment.adapter ?: return@launch
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                adapter.filter("")
            } else {
                val lower = trimmed.lowercase()
                val matched = withContext(Dispatchers.Default) {
                    val all = adapter.allConfigurationIdList
                    val map = adapter.configurationList
                    all.filter { id ->
                        val entity = map[id] ?: return@filter false
                        (entity.displayName()?.lowercase()?.contains(lower) == true) ||
                                (entity.displayType()?.lowercase()?.contains(lower) == true) ||
                                (entity.displayAddress()?.lowercase()?.contains(lower) == true)
                    }
                }
                adapter.applyFilterResult(matched)
            }
            updateToolbarMenuTitles()
        }
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        currentSearchQuery = query
        searchJob?.cancel()
        val groupFragment = getCurrentGroupFragment()
        val adapter = groupFragment?.adapter
        if (adapter != null) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                adapter.filter("")
            } else {
                val lower = trimmed.lowercase()
                lifecycleScope.launch {
                    val matched = withContext(Dispatchers.Default) {
                        val all = adapter.allConfigurationIdList
                        val map = adapter.configurationList
                        all.filter { id ->
                            val entity = map[id] ?: return@filter false
                            (entity.displayName()?.lowercase()?.contains(lower) == true) ||
                                    (entity.displayType()?.lowercase()?.contains(lower) == true) ||
                                    (entity.displayAddress()?.lowercase()?.contains(lower) == true)
                        }
                    }
                    adapter.applyFilterResult(matched)
                    updateToolbarMenuTitles()
                }
            }
        }
        return true
    }

    fun styleSortAndLayoutHeaders(menu: Menu) {
        val ctx = context ?: return
        val primaryColor = ctx.getColorAttr(R.attr.colorPrimary)
        val headerIds = intArrayOf(
            R.id.action_header_order,
            R.id.action_header_layout,
            R.id.action_header_card_style,
            R.id.action_header_card_view_mode,
        )
        for (id in headerIds) {
            val item = menu.findItem(id) ?: continue
            item.isEnabled = true
            item.setOnMenuItemClickListener { true }
            val rawTitle = item.title?.toString() ?: continue
            val spannable = SpannableString(rawTitle).apply {
                setSpan(ForegroundColorSpan(primaryColor), 0, length, SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.88f), 0, length, SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            item.title = spannable
        }
    }

    private fun tintMenuIcons(menu: Menu, color: Int) {
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            item.icon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, color)
                item.icon = tinted
            }
        }
    }

    private var isAdjustingMenuSpacing = false

    private val actionMenuLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (v is ActionMenuView) {
            applyActionMenuSpacing(v)
            (activity as? MainActivity)?.let { main ->
                moe.matsuri.nb4a.ui.MenuScrimHelper.hookActionMenuView(toolbar, v, main)
            }
        }
    }

    private fun getToolbarTitleText(): String {
        return when {
            titleRes != 0 -> getString(titleRes)
            select || activity is ProfileSelectActivity -> {
                val isMulti = multiSelect ||
                    activity?.intent?.getBooleanExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, false) == true
                getString(if (isMulti) R.string.select_multi_profile else R.string.select_profile)
            }
            else -> getString(R.string.app_name)
        }
    }

    private fun adjustToolbarActionButtons() {
        if (!isToolbarInitialized) return
        toolbar.contentInsetStartWithNavigation = 0
        toolbar.setContentInsetsRelative(0, 0)
        toolbar.title = null
        toolbar.setBackgroundColor(Color.TRANSPARENT)
        toolbar.clipChildren = false
        toolbar.clipToPadding = false

        val titleFactor = UiScaleManager.toolbarTitleFactor()
        val iconFactor = UiScaleManager.toolbarIconFactor()
        val navScale = 0.9f * iconFactor
        val navMinW = (dp2px(40) * iconFactor).toInt().coerceAtLeast(dp2px(28))

        val titleView = view?.findViewById<TextView>(R.id.toolbar_title_view)
        titleView?.let { tv ->
            tv.text = getToolbarTitleText()
            tv.visibility = View.VISIBLE
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f * titleFactor)
            tv.letterSpacing = -0.01f
            tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            tv.includeFontPadding = false
            tv.setOnClickListener {
                toolbar.performClick()
            }
        }

        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is android.widget.ImageButton) {
                child.minimumWidth = navMinW
                child.setPadding(dp2px(8), 0, dp2px(8), 0)
                child.scaleX = navScale
                child.scaleY = navScale
            } else if (child is ActionMenuView) {
                child.clipChildren = false
                child.clipToPadding = false
                child.setPadding(0, 0, 0, 0)
                child.removeOnLayoutChangeListener(actionMenuLayoutListener)
                child.addOnLayoutChangeListener(actionMenuLayoutListener)
                applyActionMenuSpacing(child)
                (activity as? MainActivity)?.let { main ->
                    moe.matsuri.nb4a.ui.MenuScrimHelper.hookActionMenuView(toolbar, child, main)
                }
            }
        }
        toolbar.post {
            if (!isAdded || isDetached || !isToolbarInitialized) return@post
            toolbar.title = null
            toolbar.setBackgroundColor(Color.TRANSPARENT)
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is android.widget.ImageButton) {
                    child.minimumWidth = navMinW
                    child.setPadding(dp2px(8), 0, dp2px(8), 0)
                    child.scaleX = navScale
                    child.scaleY = navScale
                } else if (child is ActionMenuView) {
                    child.clipChildren = false
                    child.clipToPadding = false
                    child.setPadding(0, 0, 0, 0)
                    child.removeOnLayoutChangeListener(actionMenuLayoutListener)
                    child.addOnLayoutChangeListener(actionMenuLayoutListener)
                    applyActionMenuSpacing(child)
                    (activity as? MainActivity)?.let { main ->
                        moe.matsuri.nb4a.ui.MenuScrimHelper.hookActionMenuView(toolbar, child, main)
                    }
                }
            }
        }
    }

    private fun applyActionMenuSpacing(menuView: ActionMenuView) {
        if (isAdjustingMenuSpacing) return
        val iconFactor = UiScaleManager.toolbarIconFactor()
        val minW = (dp2px(33) * iconFactor).toInt().coerceIn(dp2px(22), dp2px(46))
        val gap = dp2px(2)
        val endMargin = dp2px(6)
        val iconScale = 0.88f * iconFactor

        menuView.clipChildren = false
        menuView.clipToPadding = false
        menuView.minimumWidth = minW * 5 + gap * 4 + endMargin

        try {
            val cellField = ActionMenuView::class.java.getDeclaredField("mMinCellSize")
            cellField.isAccessible = true
            cellField.setInt(menuView, minW)
        } catch (_: Throwable) {}
        try {
            val padField = ActionMenuView::class.java.getDeclaredField("mGeneratedItemPadding")
            padField.isAccessible = true
            padField.setInt(menuView, 0)
        } catch (_: Throwable) {}

        val searchItem = if (isToolbarInitialized) toolbar.menu.findItem(R.id.action_search) else null
        val sv = searchItem?.actionView as? SearchView
        val isSearching = sv != null && !sv.isIconified

        for (j in 0 until menuView.childCount) {
            val item = menuView.getChildAt(j)
            if (item is ViewGroup) {
                item.clipChildren = false
                item.clipToPadding = false
            }
            if (item is SearchView) {
                item.setPadding(0, 0, 0, 0)
                val sBar = item.findViewById<View>(androidx.appcompat.R.id.search_bar)
                (sBar as? ViewGroup)?.apply {
                    clipChildren = false
                    clipToPadding = false
                }
                sBar?.setPadding(0, 0, 0, 0)
                (sBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    if (lp.leftMargin != 0 || lp.rightMargin != 0) {
                        lp.leftMargin = 0
                        lp.rightMargin = 0
                        sBar.layoutParams = lp
                    }
                }
                val sBtn = item.findViewById<ImageView>(androidx.appcompat.R.id.search_button)
                sBtn?.apply {
                    minimumWidth = minW
                    maxWidth = minW
                    setPadding(0, 0, 0, 0)
                    scaleType = ImageView.ScaleType.CENTER
                    scaleX = iconScale
                    scaleY = iconScale
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                        if (lp.width != minW || lp.leftMargin != 0 || lp.rightMargin != 0) {
                            lp.width = minW
                            lp.leftMargin = 0
                            lp.rightMargin = 0
                            layoutParams = lp
                        }
                    }
                }
                if (!isSearching) {
                    item.minimumWidth = minW
                    item.maxWidth = minW
                }
            } else {
                try {
                    val field = item.javaClass.getDeclaredField("mMinWidth")
                    field.isAccessible = true
                    field.setInt(item, minW)
                } catch (_: Throwable) {}
                item.minimumWidth = minW
                item.setPadding(0, 0, 0, 0)
                item.scaleX = iconScale
                item.scaleY = iconScale
            }
            (item.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != 0 || lp.rightMargin != 0) {
                    lp.leftMargin = 0
                    lp.rightMargin = 0
                    item.layoutParams = lp
                }
            }
        }

        if (!isSearching && menuView.width > 0 && menuView.height > 0) {
            isAdjustingMenuSpacing = true
            try {
                val visibleChildren = mutableListOf<View>()
                for (i in 0 until menuView.childCount) {
                    val child = menuView.getChildAt(i)
                    if (child.visibility != View.GONE) {
                        visibleChildren.add(child)
                    }
                }
                if (visibleChildren.isNotEmpty()) {
                    val wSpec = View.MeasureSpec.makeMeasureSpec(minW, View.MeasureSpec.EXACTLY)
                    val hSpec = View.MeasureSpec.makeMeasureSpec(menuView.height, View.MeasureSpec.EXACTLY)
                    var currentRight = menuView.width - endMargin
                    for (i in visibleChildren.size - 1 downTo 0) {
                        val child = visibleChildren[i]
                        val left = currentRight - minW
                        val top = 0
                        val bottom = menuView.height
                        child.measure(wSpec, hSpec)
                        child.layout(left, top, currentRight, bottom)
                        if (child is SearchView) {
                            val sBar = child.findViewById<View>(androidx.appcompat.R.id.search_bar)
                            val sBtn = child.findViewById<ImageView>(androidx.appcompat.R.id.search_button)
                            sBar?.layout(0, 0, minW, bottom)
                            sBtn?.layout(0, 0, minW, bottom)
                        }
                        currentRight = left - gap
                    }
                }
            } finally {
                isAdjustingMenuSpacing = false
            }
        }
    }

    private fun updateToolbarMenuTitles() {
        if (!isToolbarInitialized) return
        val isAll = isCurrentAllGroups()
        val isSearching = currentSearchQuery.isNotBlank()
        val exportItem = toolbar.menu.findItem(R.id.action_export)
        if (isSearching) {
            exportItem?.title = "导出搜索结果"
        } else if (isAll) {
            exportItem?.title = "导出配置（全部）"
        } else {
            exportItem?.title = "导出配置（本组）"
        }
        if (isAll) {
            toolbar.menu.findItem(R.id.action_update_subscription)?.setTitle("更新全部订阅")
            toolbar.menu.findItem(R.id.action_connection_url_test)?.setTitle("URL 测试（全部）")
            toolbar.menu.findItem(R.id.action_connection_tcp_ping)?.setTitle("TCP Ping 测试（全部）")
            toolbar.menu.findItem(R.id.action_speed_test_group)?.setTitle("速度测试（全部）")
            toolbar.menu.findItem(R.id.action_clear_traffic_statistics)?.setTitle("清空全部流量统计数据")
            toolbar.menu.findItem(R.id.action_remove_duplicate)?.setTitle("删除全部重复的服务器")
            toolbar.menu.findItem(R.id.action_connection_test_clear_results)?.setTitle("清理全部测试结果")
            toolbar.menu.findItem(R.id.action_connection_test_delete_unavailable)?.setTitle("清理全部不可用配置")
        } else {
            toolbar.menu.findItem(R.id.action_update_subscription)?.setTitle(R.string.update_current_subscription)
            toolbar.menu.findItem(R.id.action_connection_url_test)?.setTitle(R.string.connection_test_url_test)
            toolbar.menu.findItem(R.id.action_connection_tcp_ping)?.setTitle(R.string.connection_test_tcp_ping)
            toolbar.menu.findItem(R.id.action_speed_test_group)?.setTitle(R.string.speed_test_group)
            toolbar.menu.findItem(R.id.action_clear_traffic_statistics)?.setTitle(R.string.clear_traffic_statistics)
            toolbar.menu.findItem(R.id.action_remove_duplicate)?.setTitle(R.string.remove_duplicate)
            toolbar.menu.findItem(R.id.action_connection_test_clear_results)?.setTitle(R.string.connection_test_clear_results)
            toolbar.menu.findItem(R.id.action_connection_test_delete_unavailable)?.setTitle(R.string.connection_test_delete_unavailable)
        }
    }

    private fun showSoftKeyboard(view: View?) {
        if (view == null) return
        view.post {
            view.requestFocus()
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            activity?.window?.let { win ->
                WindowCompat.getInsetsController(win, view).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun updateSearchMaxWidth(searchView: SearchView) {
        if (!isToolbarInitialized) return
        if (searchView.isIconified) {
            searchView.maxWidth = dp2px(32)
            return
        }
        val tbWidth = toolbar.width
        if (tbWidth > 0) {
            val maxW = (tbWidth - dp2px(112)).coerceAtLeast(dp2px(160))
            if (searchView.maxWidth != maxW) {
                searchView.maxWidth = maxW
            }
        } else {
            toolbar.post {
                if (isAdded && !isDetached && isToolbarInitialized) {
                    if (searchView.isIconified) {
                        searchView.maxWidth = dp2px(32)
                        return@post
                    }
                    val postW = toolbar.width
                    if (postW > 0) {
                        val maxW = (postW - dp2px(112)).coerceAtLeast(dp2px(160))
                        if (searchView.maxWidth != maxW) {
                            searchView.maxWidth = maxW
                        }
                    }
                }
            }
        }
    }

    private fun applyTabMargins() {
        if (!::tabLayout.isInitialized) return
        val slidingTabIndicator = tabLayout.getChildAt(0) as? ViewGroup ?: return
        val count = slidingTabIndicator.childCount
        if (count == 0) return
        val innerMargin = dp2px(2)
        val edgeMargin = dp2px(4)
        for (i in 0 until count) {
            val tabView = slidingTabIndicator.getChildAt(i)
            val lp = tabView.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                val left = if (i == 0) edgeMargin else innerMargin
                val right = if (i == count - 1) edgeMargin else innerMargin
                if (lp.leftMargin != left || lp.rightMargin != right) {
                    lp.leftMargin = left
                    lp.rightMargin = right
                    tabView.requestLayout()
                }
            }
            if (tabView is ViewGroup) {
                val isSelected = tabLayout.selectedTabPosition == i
                for (j in 0 until tabView.childCount) {
                    val child = tabView.getChildAt(j)
                    if (child is TextView) {
                        child.typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    }
                }
            }
        }
    }

    private fun updateTabTypeface(tab: TabLayout.Tab?, isSelected: Boolean) {
        val tabView = tab?.view ?: return
        for (j in 0 until tabView.childCount) {
            val child = tabView.getChildAt(j)
            if (child is TextView) {
                child.typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    private var topBarCapsulesDrawable: DualFloatingCapsulesDrawable? = null
    private var topBarPreDrawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var topBarPreDrawTarget: View? = null

    private fun setupTopBarFloatingCapsules(
        fillColor: Int,
        strokeColor: Int,
        shadowBaseColor: Int,
        isDark: Boolean,
    ) {
        val rootView = view ?: return
        val toolbarContainer = rootView.findViewById<View>(R.id.toolbar_container) ?: return
        val capsulesBgView = rootView.findViewById<View>(R.id.toolbar_capsules_bg) ?: return
        val density = resources.displayMetrics.density

        val drawable = topBarCapsulesDrawable?.apply {
            this.fillColor = fillColor
            this.strokeColor = strokeColor
            this.strokeWidthPx = 0.9f * density
            this.shadowBaseColor = shadowBaseColor
            this.isDark = isDark
            invalidateSelf()
        } ?: DualFloatingCapsulesDrawable(
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidthPx = 0.9f * density,
            isDark = isDark,
            density = density,
            shadowBaseColor = shadowBaseColor,
        ).also { topBarCapsulesDrawable = it }

        if (capsulesBgView.background !== drawable) {
            capsulesBgView.background = drawable
        }

        if (topBarPreDrawTarget !== toolbarContainer || topBarPreDrawListener == null) {
            topBarPreDrawTarget?.viewTreeObserver?.let { vto ->
                if (vto.isAlive && topBarPreDrawListener != null) {
                    vto.removeOnPreDrawListener(topBarPreDrawListener)
                }
            }
            val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                updateTopBarCapsulesBounds()
                true
            }
            topBarPreDrawListener = listener
            topBarPreDrawTarget = toolbarContainer
            toolbarContainer.viewTreeObserver.addOnPreDrawListener(listener)
        }
        updateTopBarCapsulesBounds()
    }

    private fun updateTopBarCapsulesBounds() {
        val rootView = view ?: return
        val toolbarContainer = rootView.findViewById<View>(R.id.toolbar_container) ?: return
        val drawable = topBarCapsulesDrawable ?: return
        if (!isToolbarInitialized) return

        val cw = toolbarContainer.width
        val ch = toolbarContainer.height
        if (cw <= 0 || ch <= 0) return

        val density = resources.displayMetrics.density
        val iconFactor = UiScaleManager.toolbarIconFactor()
        val capsuleH = (29f * density * iconFactor).coerceIn(
            24f * density,
            (ch - 10f * density).coerceAtLeast(24f * density)
        )
        val cy = ch * 0.5f
        val capTop = cy - capsuleH * 0.5f
        val capBottom = cy + capsuleH * 0.5f
        val capRadius = capsuleH * 0.5f

        var rightL = 0f
        var rightR = 0f
        var menuView: ActionMenuView? = null
        var navBtn: View? = null
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView && child.visibility == View.VISIBLE) {
                menuView = child
            } else if (child is android.widget.ImageButton && child.visibility == View.VISIBLE) {
                navBtn = child
            }
        }

        if (menuView != null && menuView.width > 0) {
            var minChildL = Int.MAX_VALUE
            var maxChildR = Int.MIN_VALUE
            for (j in 0 until menuView.childCount) {
                val item = menuView.getChildAt(j)
                if (item.visibility == View.VISIBLE && item.width > 0) {
                    if (item.left < minChildL) minChildL = item.left
                    if (item.right > maxChildR) maxChildR = item.right
                }
            }
            if (minChildL < maxChildR) {
                val baseOffset = (toolbar.left + menuView.left).toFloat()
                val rawL = baseOffset + minChildL
                val rawR = baseOffset + maxChildR
                rightL = (rawL - 2.5f * density).coerceAtLeast(5.5f * density)
                rightR = (rawR - 1.5f * density).coerceAtMost(cw - 5.5f * density)
                if (rightR - rightL < capsuleH) {
                    val mid = (rightL + rightR) * 0.5f
                    rightL = mid - capsuleH * 0.5f
                    rightR = mid + capsuleH * 0.5f
                }
            }
        }

        // Scheme 1: Left independent circular floating button around navigation icon [|],
        // leaving the "OwnBox" title cleanly outside the capsule.
        val leftL = 5.5f * density
        var leftR = if (navBtn != null && navBtn.width > 0) {
            leftL + capsuleH
        } else {
            0f
        }

        if (leftR > leftL && rightR > rightL) {
            leftR = leftR.coerceAtMost(rightL - 6f * density)
        }

        drawable.updateCapsules(
            leftL = if (leftR > leftL) leftL else 0f,
            leftT = if (leftR > leftL) capTop else 0f,
            leftR = if (leftR > leftL) leftR else 0f,
            leftB = if (leftR > leftL) capBottom else 0f,
            rightL = if (rightR > rightL) rightL else 0f,
            rightT = if (rightR > rightL) capTop else 0f,
            rightR = if (rightR > rightL) rightR else 0f,
            rightB = if (rightR > rightL) capBottom else 0f,
            radius = capRadius,
        )
    }

    private fun resolveTopBarIconTint(ctx: Context): Int {
        val isBlack = Theme.isBlackTheme(ctx)
        val isNight = Theme.usingNightMode(ctx)
        return when {
            isBlack -> Color.WHITE
            isNight -> Color.parseColor("#94A3B8")
            else -> Color.parseColor("#475569")
        }
    }

    private fun applyTopBarIconTint(iconColor: Int) {
        if (!isToolbarInitialized) return
        (toolbar as? com.google.android.material.appbar.MaterialToolbar)?.setNavigationIconTint(iconColor)
        toolbar.navigationIcon?.let {
            val tinted = it.mutate()
            DrawableCompat.setTint(tinted, iconColor)
            toolbar.navigationIcon = tinted
        }
        toolbar.overflowIcon?.let {
            val tinted = it.mutate()
            DrawableCompat.setTint(tinted, iconColor)
            toolbar.overflowIcon = tinted
        }
        tintMenuIcons(toolbar.menu, iconColor)
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val sv = (searchItem?.actionView as? SearchView) ?: toolbar.findViewById<SearchView>(R.id.action_search)
        sv?.findViewById<ImageView>(androidx.appcompat.R.id.search_button)?.setColorFilter(iconColor)
        sv?.findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)?.setColorFilter(iconColor)
        sv?.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)?.setColorFilter(iconColor)
    }

    private fun styleCapsuleTabLayout() {
        if (!::tabLayout.isInitialized) return
        val ctx = context ?: return
        val isBlack = Theme.isBlackTheme(ctx)
        val isNight = Theme.usingNightMode(ctx)
        val isWhite = Theme.isWhiteTheme(ctx)
        val isLightGray = Theme.isLightGrayTheme(ctx)
        val themeColor = Theme.getPrimaryColor(ctx)

        // 1. Style Outer Big Capsule Track (groupTabContainer) & Top Bar Floating Capsules
        val containerRadius = dp2px(19).toFloat()
        val containerBgColor: Int
        val containerStrokeColor: Int
        val shadowBaseColor: Int
        when {
            isBlack -> {
                containerBgColor = Color.parseColor("#141417")
                containerStrokeColor = Color.parseColor("#27272A")
                shadowBaseColor = Color.BLACK
            }
            isWhite -> {
                containerBgColor = Color.WHITE
                containerStrokeColor = Color.parseColor("#E4E8F0")
                shadowBaseColor = Color.BLACK
            }
            isLightGray -> {
                containerBgColor = Color.WHITE
                containerStrokeColor = Color.parseColor("#E2E8F0")
                shadowBaseColor = Color.BLACK
            }
            isNight -> {
                containerBgColor = ColorUtils.blendARGB(Color.parseColor("#18181B"), themeColor, 0.16f)
                containerStrokeColor = ColorUtils.blendARGB(Color.parseColor("#27272A"), themeColor, 0.38f)
                shadowBaseColor = Color.BLACK
            }
            else -> {
                containerBgColor = ColorUtils.blendARGB(Color.WHITE, themeColor, 0.09f)
                containerStrokeColor = ColorUtils.blendARGB(Color.WHITE, themeColor, 0.28f)
                shadowBaseColor = ColorUtils.blendARGB(Color.BLACK, themeColor, 0.35f)
            }
        }
        val density = resources.displayMetrics.density
        val padH = dp2px(8)
        val padV = dp2px(5)
        groupTabContainer?.setPadding(padH, padV, padH, padV)
        groupTabContainer?.clipToOutline = false
        (groupTabContainer as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        groupTabContainer?.background = SingleFloatingCapsuleDrawable(
            fillColor = containerBgColor,
            strokeColor = containerStrokeColor,
            strokeWidthPx = 0.9f * density,
            cornerRadiusPx = containerRadius,
            insetLeft = padH,
            insetTop = padV,
            insetRight = padH,
            insetBottom = padV,
            isDark = isBlack || isNight,
            density = density,
            shadowBaseColor = shadowBaseColor,
        )
        tabLayout.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = (view.height * 0.5f).coerceAtLeast(containerRadius)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        tabLayout.clipToOutline = true
        setupTopBarFloatingCapsules(
            fillColor = containerBgColor,
            strokeColor = containerStrokeColor,
            shadowBaseColor = shadowBaseColor,
            isDark = isBlack || isNight,
        )
        applyTopBarIconTint(resolveTopBarIconTint(ctx))

        // 2. Style Inner Active Sliding Pill & Text colors
        val capsuleBg: Int
        val selectedText: Int
        val unselectedText: Int

        when {
            isBlack -> {
                capsuleBg = Color.parseColor("#2E2E35")
                selectedText = Color.WHITE
                unselectedText = Color.parseColor("#A1A1AA")
            }
            isWhite -> {
                capsuleBg = Color.parseColor("#E5E7EB")
                selectedText = Color.parseColor("#0F172A")
                unselectedText = Color.parseColor("#475569")
            }
            isLightGray -> {
                capsuleBg = Color.parseColor("#E2E8F0")
                selectedText = Color.parseColor("#0F172A")
                unselectedText = Color.parseColor("#475569")
            }
            isNight -> {
                capsuleBg = ColorUtils.setAlphaComponent(themeColor, 60)
                selectedText = ColorUtils.blendARGB(themeColor, Color.WHITE, 0.40f)
                unselectedText = Color.parseColor("#94A3B8")
            }
            else -> {
                capsuleBg = ColorUtils.setAlphaComponent(themeColor, 36)
                val lum = ColorUtils.calculateLuminance(themeColor)
                selectedText = if (lum > 0.45) {
                    ColorUtils.blendARGB(themeColor, Color.BLACK, 0.45f)
                } else {
                    themeColor
                }
                unselectedText = Color.parseColor("#475569")
            }
        }

        tabLayout.setBackgroundColor(Color.TRANSPARENT)
        tabLayout.setSelectedTabIndicatorColor(capsuleBg)
        tabLayout.setTabTextColors(unselectedText, selectedText)
        tabLayout.tabRippleColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
    }

    private fun styleSearchView(searchView: SearchView) {
        val ctx = context ?: return
        val textColor: Int
        val hintColor: Int
        when {
            Theme.isBlackTheme(ctx) -> {
                textColor = Color.WHITE
                hintColor = Color.parseColor("#CAC4D0")
            }
            Theme.isWhiteTheme(ctx) -> {
                textColor = Color.parseColor("#212121")
                hintColor = Color.parseColor("#8A000000")
            }
            Theme.isLightGrayTheme(ctx) -> {
                textColor = Color.parseColor("#1F2937")
                hintColor = Color.parseColor("#6B7280")
            }
            else -> {
                textColor = ctx.getColorAttr(android.R.attr.textColorPrimary)
                hintColor = ctx.getColorAttr(android.R.attr.textColorSecondary)
            }
        }
        val editText = searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
        editText?.setTextColor(textColor)
        editText?.setHintTextColor(hintColor)
        editText?.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN

        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showSoftKeyboard(editText)
            }
        }
        editText?.setOnClickListener {
            showSoftKeyboard(editText)
        }

        val iconColor = resolveTopBarIconTint(ctx)
        val closeBtn = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
        closeBtn?.setColorFilter(iconColor)

        // Ensure close button stays visible while search is active
        editText?.addTextChangedListener {
            if (!searchView.isIconified) {
                closeBtn?.visibility = View.VISIBLE
            }
        }

        closeBtn?.setOnClickListener {
            val text = editText?.text?.toString().orEmpty()
            if (text.isNotEmpty()) {
                editText?.setText("")
                currentSearchQuery = ""
                searchJob?.cancel()
                getCurrentGroupFragment()?.adapter?.filter("")
                updateToolbarMenuTitles()
                closeBtn.post {
                    if (!searchView.isIconified) {
                        closeBtn.visibility = View.VISIBLE
                    }
                }
            } else {
                cancelSearch(searchView)
            }
        }

        val iconFactor = UiScaleManager.toolbarIconFactor()
        val btnW = (dp2px(33) * iconFactor).toInt().coerceIn(dp2px(22), dp2px(46))
        val iconScale = 0.88f * iconFactor
        searchView.clipChildren = false
        searchView.clipToPadding = false
        searchView.setPadding(0, 0, 0, 0)
        val searchBar = searchView.findViewById<View>(androidx.appcompat.R.id.search_bar)
        (searchBar as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
        searchBar?.setPadding(0, 0, 0, 0)
        (searchBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.leftMargin = 0
            lp.rightMargin = 0
            searchBar.layoutParams = lp
        }
        val searchBtn = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_button)
        searchBtn?.setImageResource(R.drawable.ic_baseline_search_24)
        searchBtn?.setColorFilter(iconColor)
        searchBtn?.minimumWidth = btnW
        searchBtn?.maxWidth = btnW
        searchBtn?.setPadding(0, 0, 0, 0)
        searchBtn?.scaleType = ImageView.ScaleType.CENTER
        searchBtn?.scaleX = iconScale
        searchBtn?.scaleY = iconScale
        (searchBtn?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.width = btnW
            lp.leftMargin = 0
            lp.rightMargin = 0
            searchBtn.layoutParams = lp
        }
        val magBtn = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)
        magBtn?.setImageResource(R.drawable.ic_baseline_search_24)
        magBtn?.setColorFilter(iconColor)
    }

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        startProfileStateActor()
        refreshProfileState()

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupPager = view.findViewById(R.id.group_pager)
        groupTabContainer = view.findViewById(R.id.group_tab_container)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        val primaryTextColor = requireContext().getColorAttr(android.R.attr.textColorPrimary)
        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            styleSortAndLayoutHeaders(toolbar.menu)
            toolbar.setOnMenuItemClickListener(this)
            tintMenuIcons(toolbar.menu, primaryTextColor)
            adjustToolbarActionButtons()
        } else {
            toolbar.title = null
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.navigationIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, primaryTextColor)
                toolbar.navigationIcon = tinted
            }
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
            adjustToolbarActionButtons()
        }

        val ctx = requireContext()
        val resolvedTitle = getToolbarTitleText()
        when {
            Theme.isBlackTheme(ctx) -> {
                val bg = Color.BLACK
                view.findViewById<View>(R.id.appbar)?.setBackgroundColor(bg)
                view.findViewById<View>(R.id.toolbar_container)?.setBackgroundColor(bg)
                toolbar.setBackgroundColor(Color.TRANSPARENT)
                toolbar.setTitleTextColor(Color.WHITE)
                val tv = view.findViewById<TextView>(R.id.toolbar_title_view)
                tv?.text = resolvedTitle
                tv?.visibility = View.VISIBLE
                tv?.setTextColor(Color.WHITE)
                toolbar.navigationIcon?.let {
                    val tinted = it.mutate()
                    DrawableCompat.setTint(tinted, Color.WHITE)
                    toolbar.navigationIcon = tinted
                }
                toolbar.overflowIcon?.let {
                    val tinted = it.mutate()
                    DrawableCompat.setTint(tinted, Color.WHITE)
                    toolbar.overflowIcon = tinted
                }
                tintMenuIcons(toolbar.menu, Color.WHITE)
            }
            Theme.isWhiteTheme(ctx) -> {
                view.findViewById<View>(R.id.appbar)?.setBackgroundColor(Color.WHITE)
                view.findViewById<View>(R.id.toolbar_container)?.setBackgroundColor(Color.WHITE)
                toolbar.setBackgroundColor(Color.TRANSPARENT)
                toolbar.setTitleTextColor(Color.parseColor("#212121"))
                val tv = view.findViewById<TextView>(R.id.toolbar_title_view)
                tv?.text = resolvedTitle
                tv?.visibility = View.VISIBLE
                tv?.setTextColor(Color.parseColor("#212121"))
                toolbar.navigationIcon?.let {
                    val tinted = it.mutate()
                    DrawableCompat.setTint(tinted, Color.parseColor("#212121"))
                    toolbar.navigationIcon = tinted
                }
                toolbar.overflowIcon?.let {
                    val tinted = it.mutate()
                    DrawableCompat.setTint(tinted, Color.parseColor("#212121"))
                    toolbar.overflowIcon = tinted
                }
                tintMenuIcons(toolbar.menu, Color.parseColor("#212121"))
            }
            Theme.isLightGrayTheme(ctx) -> {
                val bg = Color.parseColor("#F5F5F7")
                val textPrimary = Color.parseColor("#1F2937")
                val textSecondary = Color.parseColor("#6B7280")
                view.findViewById<View>(R.id.appbar)?.setBackgroundColor(bg)
                view.findViewById<View>(R.id.toolbar_container)?.setBackgroundColor(bg)
                toolbar.setBackgroundColor(Color.TRANSPARENT)
                toolbar.setTitleTextColor(textPrimary)
                val tv = view.findViewById<TextView>(R.id.toolbar_title_view)
                tv?.text = resolvedTitle
                tv?.visibility = View.VISIBLE
                tv?.setTextColor(textPrimary)
                toolbar.navigationIcon?.let {
                    val tinted = it.mutate()
                    DrawableCompat.setTint(tinted, textPrimary)
                    toolbar.navigationIcon = tinted
                }
                toolbar.overflowIcon?.let {
                    val tinted = it.mutate()
                    DrawableCompat.setTint(tinted, textPrimary)
                    toolbar.overflowIcon = tinted
                }
                tintMenuIcons(toolbar.menu, textPrimary)
            }
            else -> {
                val textColorPrimary = requireContext().getColorAttr(android.R.attr.textColorPrimary)
                toolbar.setBackgroundColor(Color.TRANSPARENT)
                val tv = view.findViewById<TextView>(R.id.toolbar_title_view)
                tv?.text = resolvedTitle
                tv?.visibility = View.VISIBLE
                tv?.setTextColor(textColorPrimary)
            }
        }
        styleCapsuleTabLayout()

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = (searchItem?.actionView as? SearchView) ?: toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null) {
            styleSearchView(searchView)
            updateSearchMaxWidth(searchView)
            searchView.setOnQueryTextListener(this)

            searchView.setOnSearchClickListener {
                view.findViewById<View>(R.id.toolbar_title_view)?.visibility = View.GONE
                toolbar.menu.findItem(R.id.action_add)?.isVisible = false
                toolbar.menu.findItem(R.id.action_connection_url_test)?.isVisible = false
                toolbar.menu.findItem(R.id.action_locate_active_profile)?.isVisible = false
                toolbar.title = null
                updateSearchMaxWidth(searchView)
                updateToolbarMenuTitles()

                // Show back navigation arrow on the toolbar for clear exit path
                if (!select) {
                    val searchContext = requireContext()
                    val iconTint = resolveTopBarIconTint(searchContext)
                    (toolbar as? com.google.android.material.appbar.MaterialToolbar)?.setNavigationIconTint(iconTint)
                    toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
                    toolbar.navigationIcon?.let {
                        val tinted = it.mutate()
                        DrawableCompat.setTint(tinted, iconTint)
                        toolbar.navigationIcon = tinted
                    }
                    toolbar.setNavigationOnClickListener {
                        cancelSearch(searchView)
                    }
                }

                val editText = searchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text)
                val closeBtn = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
                closeBtn?.visibility = View.VISIBLE
                showSoftKeyboard(editText)
            }

            searchView.setOnCloseListener {
                if (!isCancelingSearch) {
                    cancelSearch(searchView)
                }
                false
            }
        }

        toolbar.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val newWidth = right - left
            val oldWidth = oldRight - oldLeft
            if (newWidth != oldWidth && newWidth > 0) {
                val sItem = toolbar.menu.findItem(R.id.action_search)
                val sv = (sItem?.actionView as? SearchView) ?: toolbar.findViewById<SearchView>(R.id.action_search)
                if (sv != null) {
                    if (!sv.isIconified) {
                        val targetW = (newWidth - dp2px(112)).coerceAtLeast(dp2px(160))
                        if (sv.maxWidth != targetW) {
                            sv.maxWidth = targetW
                        }
                    } else {
                        sv.maxWidth = dp2px(32)
                    }
                }
            }
        }

        val tabFactor = UiScaleManager.tabBarFactor()
        if (tabFactor != 1.0f) {
            val scaledTabHeight = (dp2px(38) * tabFactor).toInt().coerceIn(dp2px(26), dp2px(54)) + dp2px(10)
            groupTabContainer?.layoutParams = groupTabContainer?.layoutParams?.apply {
                height = scaledTabHeight
            }
        }

        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.post {
                applyTabMargins()
            }
            if (tabFactor != 1.0f) {
                tab.view.post {
                    for (i in 0 until tab.view.childCount) {
                        val c = tab.view.getChildAt(i)
                        if (c is TextView) {
                            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * tabFactor)
                        }
                    }
                }
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }

            var downX = 0f
            var downY = 0f
            var longPressRunnable: Runnable? = null

            tab.view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        longPressRunnable?.let { v.removeCallbacks(it) }

                        val runnable = Runnable {
                            if (!isAdded || isDetached) return@Runnable
                            val pos = tab.position
                            if (pos in 0 until adapter.groupList.size) {
                                val group = adapter.groupList[pos]
                                if (group.id > 0L && group.id != ALL_GROUPS_SENTINEL_ID) {
                                    if (DataStore.hapticFeedback) {
                                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    }
                                    val ctx = context ?: return@Runnable
                                    startActivity(Intent(ctx, GroupSettingsActivity::class.java).apply {
                                        putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                                    })
                                }
                            }
                        }
                        longPressRunnable = runnable
                        v.postDelayed(runnable, 900L) // 800ms ~ 1000ms 黄金时阈
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.rawX - downX)
                        val dy = Math.abs(event.rawY - downY)
                        if (dx > touchSlop || dy > touchSlop) {
                            longPressRunnable?.let { v.removeCallbacks(it) }
                            longPressRunnable = null
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { v.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                }
                false
            }
        }.attach()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                updateTabTypeface(tab, true)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {
                updateTabTypeface(tab, false)
            }
            override fun onTabReselected(tab: TabLayout.Tab?) {
                updateTabTypeface(tab, true)
            }
        })
        tabLayout.post {
            applyTabMargins()
            styleCapsuleTabLayout()
        }

        toolbar.setOnClickListener {
            locateActiveProfile()
        }

        DataStore.profileCacheStore.registerChangeListener(this)
        DataStore.configurationStore.registerChangeListener(this)
    }

    fun locateActiveProfile() {
        runOnDefaultDispatcher {
            val targetId = when {
                select && selectedItem != null -> selectedItem.id
                DataStore.serviceState.started && DataStore.currentProfile > 0L -> DataStore.currentProfile
                DataStore.selectedProxy > 0L -> DataStore.selectedProxy
                DataStore.currentProfile > 0L -> DataStore.currentProfile
                else -> 0L
            }

            if (targetId <= 0L) {
                onMainDispatcher {
                    safeSnackbar(R.string.no_active_profile_found)
                }
                return@runOnDefaultDispatcher
            }

            val targetEntity = SagerDatabase.proxyDao.getById(targetId)
            if (targetEntity == null) {
                onMainDispatcher {
                    safeSnackbar(R.string.no_active_profile_found)
                }
                return@runOnDefaultDispatcher
            }

            if (DataStore.isGroupDisabled(targetEntity.groupId)) {
                onMainDispatcher {
                    safeSnackbar(R.string.active_profile_group_disabled)
                }
                return@runOnDefaultDispatcher
            }

            val targetGroupId = targetEntity.groupId
            val displayName = targetEntity.displayName()

            onMainDispatcher {
                if (!isAdded || view == null || !::adapter.isInitialized || !::groupPager.isInitialized) return@onMainDispatcher

                var targetPageIndex = adapter.groupList.indexOfFirst { it.id == targetGroupId }
                if (targetPageIndex < 0) {
                    targetPageIndex = adapter.groupList.indexOfFirst { it.id == ALL_GROUPS_SENTINEL_ID }
                }

                if (targetPageIndex >= 0 && groupPager.currentItem != targetPageIndex) {
                    groupPager.setCurrentItem(targetPageIndex, true)
                    groupPager.postDelayed({
                        val targetFrag = adapter.groupFragments[targetGroupId]
                            ?: adapter.groupFragments[ALL_GROUPS_SENTINEL_ID]
                            ?: getCurrentGroupFragment()
                        targetFrag?.locateProfile(targetId, true)
                    }, 280L)
                } else {
                    val currentFrag = getCurrentGroupFragment()
                        ?: adapter.groupFragments[targetGroupId]
                    currentFrag?.locateProfile(targetId, true)
                }

                safeSnackbar(getString(R.string.located_active_profile, displayName))
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_hide_unavailable)?.isChecked = DataStore.hideUnavailableProfiles
        updateToolbarMenuTitles()
        super.onPrepareOptionsMenu(menu)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // 只响应外部设置显式更改 editingGroup 的事件，阻断 configurationStore 的自触发，且用户正在拖拽时不打断
            if (store === DataStore.profileCacheStore && key == Key.PROFILE_GROUP) {
                if (isUserInteractingWithPager) return@runOnMainDispatcher
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        adapter.selectedGroupIndex = targetIndex
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            } else if (key == Key.HIDE_UNAVAILABLE_PROFILES) {
                adapter.groupFragments.values.forEach { it.adapter?.reloadProfiles() }
            } else if (key == "activeLeafProfileName" || key == "activeBalancerId") {
                refreshProfileState()
            }
        }
    }

    override fun onDestroyView() {
        topBarPreDrawTarget?.viewTreeObserver?.let { vto ->
            if (vto.isAlive && topBarPreDrawListener != null) {
                vto.removeOnPreDrawListener(topBarPreDrawListener)
            }
        }
        topBarPreDrawListener = null
        topBarPreDrawTarget = null
        topBarCapsulesDrawable = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (speedTestJob != null) {
            speedTestRunner?.cancel()
            speedTestJob?.cancel()
            speedTestNotification?.updateNotification(0, 0, true)
            speedTestNotification = null
            speedTestDialog?.dismiss()
            speedTestDialog = null
            speedTestHidden = false
            speedTestRunner = null
            speedTestJob = null
            DataStore.runningTest = false
        }
        DataStore.profileCacheStore.unregisterChangeListener(this)
        DataStore.configurationStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (speedTestHidden && speedTestJob != null) {
            speedTestHidden = false
            speedTestNotification?.updateNotification(0, 0, true)
            speedTestNotification = null
            speedTestDialog?.show()
        }
    }

    override fun onBackPressed(): Boolean {
        if (isReorderMode) {
            cancelReorder()
            return true
        }
        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = (searchItem?.actionView as? SearchView) ?: toolbar.findViewById<SearchView>(R.id.action_search)
        if (searchView != null && !searchView.isIconified) {
            cancelSearch(searchView)
            return true
        }
        return super.onBackPressed()
    }

    var isReorderMode: Boolean = false
        private set

    fun enterReorderMode() {
        if (isSearchActive || currentSearchQuery.isNotBlank()) {
            safeSnackbar("搜索状态下不支持调整排序")
            return
        }
        val currentFrag = getCurrentGroupFragment() ?: return
        if (currentFrag.isAllGroupsTab) {
            safeSnackbar(R.string.reorder_all_groups_not_supported)
            return
        }
        val ad = currentFrag.adapter ?: return
        if (ad.configurationIdList.size <= 1) {
            return
        }

        isReorderMode = true
        if (::groupPager.isInitialized) {
            groupPager.isUserInputEnabled = false
        }

        if (currentFrag.proxyGroup.order != GroupOrder.ORIGIN) {
            currentFrag.proxyGroup.order = GroupOrder.ORIGIN
            runOnDefaultDispatcher {
                GroupManager.updateGroup(currentFrag.proxyGroup)
            }
        }

        ad.snapshotIds = ad.configurationIdList.toList()
        ad.snapshotOrders = ad.configurationIdList.mapNotNull { id ->
            ad.configurationList[id]?.let { id to it.userOrder }
        }.toMap()
        ad.isReorderMode = true

        currentFrag.view?.findViewById<View>(R.id.btn_save_reorder)?.isVisible = true
        (activity as? MainActivity)?.binding?.fab?.hide()
        (activity as? MainActivity)?.binding?.fabProgress?.visibility = View.INVISIBLE
        ad.notifyDataSetChanged()
        updateToolbarForReorderMode()
    }

    fun saveReorder() {
        if (!isReorderMode) return
        val currentFrag = getCurrentGroupFragment()
        val ad = currentFrag?.adapter
        val snapshotOrders = ad?.snapshotOrders ?: emptyMap()

        ad?.saveMove()
        exitReorderMode(save = true)

        if (snapshotOrders.isNotEmpty() && currentFrag != null) {
            val mainAct = (activity as? MainActivity) ?: (MessageStore.getCurrentActivity() as? MainActivity)
            mainAct?.let { act ->
                try {
                    act.snackbar(getString(R.string.reorder_saved_toast))
                        .setAction(R.string.undo) {
                            runOnDefaultDispatcher {
                                for ((id, order) in snapshotOrders) {
                                    val p = SagerDatabase.proxyDao.getById(id) ?: continue
                                    p.userOrder = order
                                    SagerDatabase.proxyDao.updateProxy(p)
                                }
                                onMainDispatcher {
                                    currentFrag.adapter?.reloadProfiles()
                                }
                            }
                        }
                        .show()
                } catch (e: Exception) {
                    safeSnackbar(R.string.reorder_saved_toast)
                }
            } ?: safeSnackbar(R.string.reorder_saved_toast)
        } else {
            safeSnackbar(R.string.reorder_saved_toast)
        }
    }

    fun cancelReorder() {
        if (!isReorderMode) return
        val currentFrag = getCurrentGroupFragment()
        currentFrag?.adapter?.cancelMove()
        exitReorderMode(save = false)
        safeSnackbar(R.string.reorder_cancelled_toast)
    }

    fun exitReorderMode(save: Boolean) {
        isReorderMode = false
        if (::groupPager.isInitialized) {
            groupPager.isUserInputEnabled = true
        }

        val currentFrag = getCurrentGroupFragment()
        currentFrag?.view?.findViewById<View>(R.id.btn_save_reorder)?.isGone = true
        (activity as? MainActivity)?.binding?.fab?.show()

        currentFrag?.adapter?.let { ad ->
            ad.isReorderMode = false
            ad.snapshotIds = emptyList()
            ad.snapshotOrders = emptyMap()
            ad.notifyDataSetChanged()
        }

        restoreToolbarFromReorderMode()
    }

    fun resetToDefaultOrder() {
        val currentFrag = getCurrentGroupFragment() ?: return
        if (currentFrag.isAllGroupsTab) {
            safeSnackbar(R.string.reorder_all_groups_not_supported)
            return
        }
        val ad = currentFrag.adapter ?: return
        val ctx = context ?: return

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.reset_order_confirm_title)
            .setMessage(R.string.reset_order_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(currentFrag.proxyGroup.id).sortedBy { it.id }
                    profiles.forEachIndexed { index, proxyEntity ->
                        proxyEntity.userOrder = (index + 1).toLong()
                        SagerDatabase.proxyDao.updateProxy(proxyEntity)
                    }
                    currentFrag.proxyGroup.order = GroupOrder.ORIGIN
                    GroupManager.updateGroup(currentFrag.proxyGroup)
                    onMainDispatcher {
                        ad.reloadProfiles()
                        if (isReorderMode) {
                            exitReorderMode(save = true)
                        }
                        safeSnackbar(R.string.reset_order_done_toast)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateToolbarForReorderMode() {
        if (!isToolbarInitialized) return
        val customTitle = view?.findViewById<TextView>(R.id.toolbar_title_view)
        customTitle?.text = getString(R.string.reorder_mode_title)

        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        val iconColor = resolveTopBarIconTint(requireContext())
        toolbar.navigationIcon?.let {
            val tinted = it.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(tinted, iconColor)
            toolbar.navigationIcon = tinted
        }
        toolbar.setNavigationOnClickListener {
            cancelReorder()
        }

        toolbar.menu.findItem(R.id.action_search)?.isVisible = false
        toolbar.menu.findItem(R.id.action_add)?.isVisible = false
        toolbar.menu.findItem(R.id.action_connection_url_test)?.isVisible = false
        toolbar.menu.findItem(R.id.action_locate_active_profile)?.isVisible = false
        toolbar.menu.findItem(R.id.action_misc)?.isVisible = false

        toolbar.menu.findItem(R.id.action_reorder_save)?.let {
            it.isVisible = true
            it.icon?.let { icon ->
                val tinted = icon.mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(tinted, iconColor)
                it.icon = tinted
            }
        }
    }

    private fun restoreToolbarFromReorderMode() {
        if (!isToolbarInitialized) return
        val customTitle = view?.findViewById<TextView>(R.id.toolbar_title_view)
        customTitle?.text = getString(R.string.app_name)

        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        val iconColor = resolveTopBarIconTint(requireContext())
        toolbar.navigationIcon?.let {
            val tinted = it.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(tinted, iconColor)
            toolbar.navigationIcon = tinted
        }
        toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
        }

        toolbar.menu.findItem(R.id.action_search)?.isVisible = true
        toolbar.menu.findItem(R.id.action_add)?.isVisible = true
        toolbar.menu.findItem(R.id.action_connection_url_test)?.isVisible = true
        toolbar.menu.findItem(R.id.action_locate_active_profile)?.isVisible = true
        toolbar.menu.findItem(R.id.action_misc)?.isVisible = true

        toolbar.menu.findItem(R.id.action_reorder_save)?.isVisible = false
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val searchItem = if (isToolbarInitialized) toolbar.menu.findItem(R.id.action_search) else null
        val searchView = (searchItem?.actionView as? SearchView) ?: (if (isToolbarInitialized) toolbar.findViewById<SearchView>(R.id.action_search) else null)
        if (searchView != null && (!searchView.isIconified || searchView.hasFocus())) {
            return false
        }
        if (ketCode == KeyEvent.KEYCODE_DPAD_UP || ketCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val fragment = getCurrentGroupFragment()
            fragment?.configurationListView?.apply {
                if (!hasFocus()) requestFocus()
            }
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        val zip =
                            ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)
                                ?.let { pl -> proxies.addAll(pl) }
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        safeSnackbar(R.string.no_proxies_found_in_file)
                    } else import(proxies)
                } catch (e: SubscriptionFoundException) {
                    ((activity as? MainActivity) ?: (MessageStore.getCurrentActivity() as? MainActivity))?.importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        safeSnackbar(e.readableMessage)
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>) {
        val currentGroup = DataStore.currentGroup()
        val targetId = DataStore.selectedGroupForImport()
        val targetGroup = SagerDatabase.groupDao.getById(targetId)
        val shouldDeduplicate = (targetGroup?.subscription?.deduplication == true) || (currentGroup.subscription?.deduplication == true)
        val finalProxies = if (shouldDeduplicate) proxies.deduplicateProxies() else proxies
        for (proxy in finalProxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            DataStore.editingGroup = targetId
            val res = (context ?: SagerNet.application).resources
            val msg = res.getQuantityString(R.plurals.added, finalProxies.size, finalProxies.size)
            safeSnackbar(msg)
        }

    }

    fun isCurrentAllGroups(): Boolean {
        if (!::adapter.isInitialized) return false
        val pos = if (::groupPager.isInitialized) groupPager.currentItem else adapter.selectedGroupIndex
        val group = adapter.groupList.getOrNull(pos) ?: return false
        return group.id == ALL_GROUPS_SENTINEL_ID
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val rawProxies = RawUpdater.parseRaw(text)
                        val currentGroup = DataStore.currentGroup()
                        val targetId = DataStore.selectedGroupForImport()
                        val targetGroup = SagerDatabase.groupDao.getById(targetId)
                        val shouldDeduplicate = (targetGroup?.subscription?.deduplication == true) || (currentGroup.subscription?.deduplication == true)
                        val proxies = if (shouldDeduplicate) rawProxies?.deduplicateProxies() else rawProxies
                        if (proxies.isNullOrEmpty()) {
                            onMainDispatcher {
                                safeSnackbar(R.string.no_proxies_found_in_clipboard)
                            }
                        } else {
                            onMainDispatcher {
                                val previewList = proxies.take(8).joinToString("\n") {
                                    val proto = it.javaClass.simpleName.removeSuffix("Bean")
                                    "• [$proto] ${it.displayName()}"
                                } + if (proxies.size > 8) "\n... (+${proxies.size - 8})" else ""

                                val ctx = context ?: MessageStore.getCurrentActivity()
                                if (ctx != null) {
                                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                                        .setTitle(ctx.getString(R.string.import_preview_title, proxies.size))
                                        .setMessage(previewList)
                                        .setPositiveButton(R.string.import_preview_confirm) { _, _ ->
                                            runOnDefaultDispatcher {
                                                import(proxies)
                                            }
                                        }
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show()
                                }
                            }
                        }
                    } catch (e: SubscriptionFoundException) {
                        onMainDispatcher {
                            if (e.link.startsWith("sn://")) {
                                ((activity as? MainActivity) ?: (MessageStore.getCurrentActivity() as? MainActivity))?.importSubscription(e.link.toUri())
                            } else {
                                val subscriptionLink = Uri.parse(e.link).getQueryParameter("url") ?: e.link

                                val group = ProxyGroup(type = GroupType.SUBSCRIPTION)
                                val subscription = SubscriptionBean()
                                group.subscription = subscription
                                subscription.link = subscriptionLink
                                subscription.autoUpdate = false
                                group.name = ""
                                val ctx = context ?: MessageStore.getCurrentActivity() ?: SagerNet.application
                                ctx.startActivity(Intent(ctx, GroupSettingsActivity::class.java).apply {
                                    putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                    putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                    if (ctx == SagerNet.application) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            safeSnackbar(e.readableMessage)
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), SnellSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_new_balancer -> {
                startActivity(Intent(requireActivity(), BalancerSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val isAll = isCurrentAllGroups()
                if (isAll) {
                    runOnLifecycleDispatcher {
                        val subGroups = SagerDatabase.groupDao.allGroups().filter { it.type == GroupType.SUBSCRIPTION }
                        if (subGroups.isEmpty()) {
                            safeSnackbar("没有可更新的订阅分组")
                        } else {
                            subGroups.forEach { GroupUpdater.startUpdate(it, true) }
                            safeSnackbar("正在更新全部 ${subGroups.size} 个订阅组...")
                        }
                    }
                } else {
                    val group = DataStore.currentGroup()
                    if (group.type != GroupType.SUBSCRIPTION) {
                        snackbar(R.string.group_not_subscription).show()
                        Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                    } else {
                        runOnLifecycleDispatcher {
                            GroupUpdater.startUpdate(group, true)
                        }
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                val trafficService = (activity as? MainActivity)?.connection?.service
                val isAll = isCurrentAllGroups()
                runOnDefaultDispatcher {
                    val profiles = if (isAll) {
                        SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
                    } else {
                        SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    }
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                    try {
                        trafficService?.resetTraffic(profiles.map { it.id }.toLongArray())
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                    onMainDispatcher {
                        adapter.groupFragments.values.forEach { it.adapter?.clearTrafficStatistics() }
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                val isAll = isCurrentAllGroups()
                runOnDefaultDispatcher {
                    if (isAll) {
                        SagerDatabase.proxyDao.clearAllTestResults()
                    } else {
                        SagerDatabase.proxyDao.clearTestResults(DataStore.currentGroupId())
                    }
                    onMainDispatcher {
                        adapter.groupFragments.values.forEach { it.adapter?.clearTestResults() }
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                val isAll = isCurrentAllGroups()
                runOnDefaultDispatcher {
                    val profiles = if (isAll) {
                        SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
                    } else {
                        SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    }
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(R.string.delete_confirm_prompt)
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments.values.forEach { groupFragment ->
                                            groupFragment.adapter?.apply {
                                                val index = configurationIdList.indexOf(profile.id)
                                                if (index >= 0) {
                                                    configurationIdList.removeAt(index)
                                                    configurationList.remove(profile.id)
                                                    notifyItemRemoved(index)
                                                }
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                val isAll = isCurrentAllGroups()
                runOnDefaultDispatcher {
                    val targetGroupId = if (isAll) ALL_GROUPS_SENTINEL_ID else (DataStore.selectedGroup.takeIf { it > 0 } ?: DataStore.currentGroupId())
                    val profiles = if (isAll) {
                        SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
                    } else {
                        SagerDatabase.proxyDao.getByGroup(targetGroupId)
                    }
                    val grouped = profiles.groupBy { pf ->
                        runCatching {
                            Protocols.Deduplication(pf.requireBean(), pf.displayType()).hash()
                        }.getOrElse { "id_${pf.id}" }
                    }
                    val toClear = mutableListOf<ProxyEntity>()
                    val keptMapping = mutableMapOf<Long, Long>()

                    for ((_, group) in grouped) {
                        if (group.size > 1) {
                            // 保留首个节点；若当前选中的节点也在该重复组内，则优先保留当前选中节点
                            val kept = group.find { it.id == DataStore.selectedProxy } ?: group.first()
                            for (pf in group) {
                                if (pf.id != kept.id) {
                                    toClear.add(pf)
                                    keptMapping[pf.id] = kept.id
                                }
                            }
                        }
                    }

                    onMainDispatcher {
                        if (toClear.isEmpty()) {
                            safeSnackbar(R.string.no_duplicate_profiles)
                        } else {
                            val ctx = context ?: MessageStore.getCurrentActivity()
                            if (ctx != null) {
                                val promptHeader = "已找到 ${toClear.size} 个冗余的重复节点（将保留对应首个节点，仅移除重复项），是否确认删除？\n"
                                MaterialAlertDialogBuilder(ctx).setTitle(R.string.confirm)
                                    .setMessage(
                                        promptHeader +
                                                toClear.mapIndexedNotNull { index, proxyEntity ->
                                                    if (index < 20) {
                                                        proxyEntity.displayName()
                                                    } else if (index == 20) {
                                                        "......"
                                                    } else {
                                                        null
                                                    }
                                                }.joinToString("\n")
                                    )
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        val count = toClear.size
                                        runOnDefaultDispatcher {
                                            // 平滑迁移选中项：若当前选中的节点在删除列表中，无缝迁移至保留节点
                                            val currentSelected = DataStore.selectedProxy
                                            if (keptMapping.containsKey(currentSelected)) {
                                                DataStore.selectedProxy = keptMapping[currentSelected] ?: 0L
                                            }

                                            SagerDatabase.proxyDao.deleteProxy(toClear)
                                            val affectedGroupIds = toClear.map { it.groupId }.distinct().filter { it > 0L }
                                            if (isAll) {
                                                affectedGroupIds.forEach { gid ->
                                                    GroupManager.rearrange(gid)
                                                    GroupManager.postReload(gid)
                                                }
                                            } else {
                                                GroupManager.rearrange(targetGroupId)
                                                GroupManager.postReload(targetGroupId)
                                            }

                                            onMainDispatcher {
                                                adapter.groupFragments.values.forEach { it.adapter?.reloadProfiles() }
                                                val res = (context ?: SagerNet.application).resources
                                                safeSnackbar(res.getString(R.string.duplicate_profiles_removed, count))
                                            }
                                        }
                                    }
                                    .setNegativeButton(R.string.no, null)
                                    .show()
                            }
                        }
                    }
                }
                return true
            }

            R.id.action_export_clipboard -> {
                val isSearching = currentSearchQuery.isNotBlank()
                val isAll = isCurrentAllGroups()
                runOnDefaultDispatcher {
                    val (profiles, _) = getProfilesForExport()
                    if (profiles.isEmpty()) {
                        onMainDispatcher {
                            if (isSearching) safeSnackbar("未找到匹配的搜索结果") else safeSnackbar(R.string.no_proxies_found_in_subscription)
                        }
                        return@runOnDefaultDispatcher
                    }
                    val links = profiles.mapNotNull { p ->
                        runCatching { p.toStdLink(compact = true) }.getOrNull()?.takeIf { it.isNotBlank() }
                    }.joinToString("\n")

                    if (links.isBlank()) {
                        onMainDispatcher {
                            safeSnackbar("当前没有支持标准链接导出的节点")
                        }
                        return@runOnDefaultDispatcher
                    }

                    onMainDispatcher {
                        SagerNet.trySetPrimaryClip(links)
                        val count = profiles.size
                        val msg = if (isSearching) {
                            "已将 $count 个搜索结果导出到剪贴板"
                        } else if (isAll) {
                            "已将全部 $count 个配置导出到剪贴板"
                        } else {
                            "已将本组 $count 个配置导出到剪贴板"
                        }
                        safeSnackbar(msg)
                    }
                }
                return true
            }

            R.id.action_export_file -> {
                val isSearching = currentSearchQuery.isNotBlank()
                val isAll = isCurrentAllGroups()
                runOnDefaultDispatcher {
                    val (profiles, name) = getProfilesForExport()
                    if (profiles.isEmpty()) {
                        onMainDispatcher {
                            if (isSearching) safeSnackbar("未找到匹配的搜索结果") else safeSnackbar(R.string.no_proxies_found_in_subscription)
                        }
                        return@runOnDefaultDispatcher
                    }
                    val links = profiles.mapNotNull { p ->
                        runCatching { p.toStdLink(compact = true) }.getOrNull()?.takeIf { it.isNotBlank() }
                    }.joinToString("\n")

                    if (links.isBlank()) {
                        onMainDispatcher {
                            safeSnackbar("当前没有支持标准链接导出的节点")
                        }
                        return@runOnDefaultDispatcher
                    }

                    pendingExportProfilesText = links
                    onMainDispatcher {
                        startFilesForResult(exportProfilesToFile, "profiles_${name}.txt")
                    }
                }
                return true
            }

            R.id.action_export_qr -> {
                val isSearching = currentSearchQuery.isNotBlank()
                runOnDefaultDispatcher {
                    val (profiles, _) = getProfilesForExport()
                    if (profiles.isEmpty()) {
                        onMainDispatcher {
                            if (isSearching) safeSnackbar("未找到匹配的搜索结果") else safeSnackbar(R.string.no_proxies_found_in_subscription)
                        }
                        return@runOnDefaultDispatcher
                    }
                    val validProfiles = profiles.filter { it.haveStandardLink() || it.haveLink() }
                    if (validProfiles.isEmpty()) {
                        onMainDispatcher {
                            safeSnackbar("当前没有支持二维码导出的节点")
                        }
                        return@runOnDefaultDispatcher
                    }
                    val first = validProfiles.first()
                    val hasStd = first.haveStandardLink()
                    val stdLink = if (hasStd) runCatching { first.toStdLink() }.getOrNull() else null
                    val universalLink = if (first.haveLink()) runCatching { first.requireBean().toUniversalLink() }.getOrNull() else null
                    val title = first.displayName() ?: ""
                    onMainDispatcher {
                        if (validProfiles.size > 1) {
                            safeSnackbar("共找到 ${validProfiles.size} 个节点，已展示第 1 个节点的二维码")
                        }
                        QRCodeDialog(
                            stdLink = stdLink,
                            universalLink = universalLink,
                            displayName = title,
                            displayType = first.displayType(),
                            typeInt = first.type,
                            initialIsSn = false
                        ).showAllowingStateLoss(parentFragmentManager)
                    }
                }
                return true
            }

            R.id.action_locate_active_profile -> {
                locateActiveProfile()
                return true
            }

            R.id.action_speed_test_group -> {
                confirmSpeedTest()
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }

            R.id.action_connection_tcp_ping -> {
                tcpPingTest()
            }

            R.id.action_hide_unavailable -> {
                item.isChecked = !item.isChecked
                DataStore.hideUnavailableProfiles = item.isChecked
                adapter.groupFragments.values.forEach { it.adapter?.reloadProfiles() }
                return true
            }

            R.id.action_reorder_profiles -> {
                enterReorderMode()
                return true
            }

            R.id.action_reset_profiles_order -> {
                resetToDefaultOrder()
                return true
            }

            R.id.action_reorder_save -> {
                saveReorder()
                return true
            }
        }
        return false
    }

    private fun confirmSpeedTest() {
        if (DataStore.runningTest) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.speed_test_confirm_title)
            .setMessage(R.string.speed_test_confirm_message)
            .setPositiveButton(R.string.speed_test_group) { _, _ -> speedTest() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun speedTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val isAll = isCurrentAllGroups()
        val group = DataStore.currentGroup()
        val displayName = if (isAll) getString(R.string.group_tab_all) else group.displayName()
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        binding.progressCircular.isGone = true
        binding.progressLinear.isVisible = true
        binding.progressLinear.max = 1
        binding.progressLinear.setProgressCompat(0, false)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.speed_test_group)
            .setView(binding.root)
            .setPositiveButton(R.string.minimize, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
        val dialog = builder.show()
        speedTestDialog = dialog

        val runner = SpeedTestQueueRunner(
            sessionFactory = ::AndroidSpeedTestSession,
            failureSnapshot = { profile, error ->
                SpeedTestSnapshot(
                    profileId = profile.id,
                    profileName = profile.displayName(),
                    mode = DataStore.speedTestMode,
                    stage = SpeedTestQueueRunner.STAGE_ERROR,
                    error = error.readableMessage,
                    done = true,
                )
            },
        )
        speedTestRunner = runner

        fun stop() {
            runner.cancel()
            speedTestJob?.cancel()
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            speedTestHidden = true
            speedTestNotification = ConnectionTestNotification(
                dialog.context,
                "[$displayName] ${getString(R.string.speed_test_group)}",
            )
            dialog.hide()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            stop()
            dialog.dismiss()
        }

        speedTestJob = runOnDefaultDispatcher {
            try {
                val profiles = if (isAll) {
                    SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
                } else {
                    if (DataStore.isGroupDisabled(group.id)) emptyList() else SagerDatabase.proxyDao.getByGroup(group.id)
                }
                if (profiles.isEmpty()) {
                    onMainDispatcher {
                        dialog.dismiss()
                    }
                    return@runOnDefaultDispatcher
                }
                onMainDispatcher {
                    binding.progressLinear.max = profiles.size
                    binding.progressLinear.setProgressCompat(0, false)
                    binding.progress.text = "0 / ${profiles.size}"
                }
                runner.run(profiles) { index, total, sample ->
                    val rxBytes = sample.downloadBitsPerSecond / 8
                    val txBytes = sample.uploadBitsPerSecond / 8
                    runOnMainDispatcher {
                        (activity as? MainActivity)?.binding?.stats?.updateSpeed(txBytes, rxBytes)
                        try {
                            (activity as? MainActivity)?.connection?.service?.postNotificationSpeed(
                                io.nekohasekai.sagernet.aidl.SpeedDisplayData(
                                    txRateProxy = txBytes,
                                    rxRateProxy = rxBytes,
                                    txTotal = sample.uploadBytes,
                                    rxTotal = sample.downloadBytes,
                                )
                            )
                        } catch (_: Exception) {}
                        adapter.groupFragments.values.forEach { fragment ->
                            fragment.adapter?.updateSpeedTestLive(sample.profileId, sample)
                        }
                    }
                    val outcome = SpeedTestOutcome.completedOrNull(
                        mode = sample.mode,
                        stage = sample.stage,
                        done = sample.done,
                        cancelled = sample.cancelled,
                        error = sample.error,
                        downloadBitsPerSecond = sample.downloadBitsPerSecond,
                        uploadBitsPerSecond = sample.uploadBitsPerSecond,
                    )
                    if (outcome != null) {
                        if (SagerDatabase.proxyDao.updateSpeedTestResult(
                                proxyId = sample.profileId,
                                mode = outcome.mode,
                                downloadBitsPerSecond = outcome.downloadBitsPerSecond,
                                uploadBitsPerSecond = outcome.uploadBitsPerSecond,
                            ) > 0
                        ) {
                            runOnMainDispatcher {
                                adapter.groupFragments.values.forEach { fragment ->
                                    fragment.adapter?.updateSpeedTestResult(sample.profileId, outcome)
                                }
                            }
                        }
                    } else if (sample.done && sample.error.isNotBlank()) {
                        runOnDefaultDispatcher {
                            val p = SagerDatabase.proxyDao.getById(sample.profileId)
                            if (p != null) {
                                p.status = 3
                                p.error = sample.error
                                SagerDatabase.proxyDao.updateProxy(p)
                                ProfileManager.postUpdate(sample.profileId)
                            }
                        }
                    }
                    runOnMainDispatcher {
                        val detail = formatSpeedTestSnapshot(sample)
                        speedTestNotification?.updateNotification(index + 1, total, false, detail)
                        if (!speedTestHidden && isAdded) {
                            val completed = completedSpeedTestCount(index, total, sample.done)
                            binding.nowTesting.text = detail
                            binding.progress.text = "$completed / $total"
                            binding.progressLinear.setProgressCompat(completed, true)
                        }
                    }
                }
                onMainDispatcher {
                    dialog.dismiss()
                }
            } catch (_: CancellationException) {
                runOnMainDispatcher {
                    if (!speedTestHidden && isAdded) {
                        binding.nowTesting.text = getString(R.string.speed_test_stage_cancelled)
                    }
                }
            } finally {
                runOnMainDispatcher {
                    (activity as? MainActivity)?.binding?.stats?.updateSpeed(0, 0)
                    try {
                        (activity as? MainActivity)?.connection?.service?.postNotificationSpeed(
                            io.nekohasekai.sagernet.aidl.SpeedDisplayData(0, 0, 0, 0, 0, 0)
                        )
                    } catch (_: Exception) {}
                    adapter.groupFragments.values.forEach { fragment ->
                        fragment.adapter?.clearSpeedTestLive()
                    }
                }
                speedTestNotification?.updateNotification(0, 0, true)
                speedTestNotification = null
                speedTestDialog = null
                speedTestHidden = false
                speedTestRunner = null
                speedTestJob = null
                DataStore.runningTest = false
            }
        }
    }

    fun speedTestSingle(profile: ProxyEntity) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        binding.progressCircular.isGone = true
        binding.progressLinear.isVisible = true
        binding.progressLinear.max = 1
        binding.progressLinear.setProgressCompat(0, false)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_test_profile_speed)
            .setView(binding.root)
            .setPositiveButton(R.string.minimize, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
        val dialog = builder.show()
        speedTestDialog = dialog

        val runner = SpeedTestQueueRunner(
            sessionFactory = ::AndroidSpeedTestSession,
            failureSnapshot = { p, error ->
                SpeedTestSnapshot(
                    profileId = p.id,
                    profileName = p.displayName(),
                    mode = DataStore.speedTestMode,
                    stage = SpeedTestQueueRunner.STAGE_ERROR,
                    error = error.readableMessage,
                    done = true,
                )
            },
        )
        speedTestRunner = runner

        fun stop() {
            runner.cancel()
            speedTestJob?.cancel()
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            speedTestHidden = true
            speedTestNotification = ConnectionTestNotification(
                dialog.context,
                "[${profile.displayName()}] ${getString(R.string.action_test_profile_speed)}",
            )
            dialog.hide()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            stop()
            dialog.dismiss()
        }

        speedTestJob = runOnDefaultDispatcher {
            try {
                val profiles = listOf(profile)
                onMainDispatcher {
                    binding.progressLinear.max = 1
                    binding.progressLinear.setProgressCompat(0, false)
                    binding.progress.text = "0 / 1"
                }
                runner.run(profiles) { index, total, sample ->
                    val rxBytes = sample.downloadBitsPerSecond / 8
                    val txBytes = sample.uploadBitsPerSecond / 8
                    runOnMainDispatcher {
                        (activity as? MainActivity)?.binding?.stats?.updateSpeed(txBytes, rxBytes)
                        try {
                            (activity as? MainActivity)?.connection?.service?.postNotificationSpeed(
                                io.nekohasekai.sagernet.aidl.SpeedDisplayData(
                                    txRateProxy = txBytes,
                                    rxRateProxy = rxBytes,
                                    txTotal = sample.uploadBytes,
                                    rxTotal = sample.downloadBytes,
                                )
                            )
                        } catch (_: Exception) {}
                        adapter.groupFragments.values.forEach { fragment ->
                            fragment.adapter?.updateSpeedTestLive(sample.profileId, sample)
                        }
                    }
                    val outcome = SpeedTestOutcome.completedOrNull(
                        mode = sample.mode,
                        stage = sample.stage,
                        done = sample.done,
                        cancelled = sample.cancelled,
                        error = sample.error,
                        downloadBitsPerSecond = sample.downloadBitsPerSecond,
                        uploadBitsPerSecond = sample.uploadBitsPerSecond,
                    )
                    if (outcome != null) {
                        if (SagerDatabase.proxyDao.updateSpeedTestResult(
                                proxyId = sample.profileId,
                                mode = outcome.mode,
                                downloadBitsPerSecond = outcome.downloadBitsPerSecond,
                                uploadBitsPerSecond = outcome.uploadBitsPerSecond,
                            ) > 0
                        ) {
                            runOnMainDispatcher {
                                adapter.groupFragments.values.forEach { fragment ->
                                    fragment.adapter?.updateSpeedTestResult(sample.profileId, outcome)
                                }
                            }
                        }
                    } else if (sample.done && sample.error.isNotBlank()) {
                        runOnDefaultDispatcher {
                            val p = SagerDatabase.proxyDao.getById(sample.profileId)
                            if (p != null) {
                                p.status = 3
                                p.error = sample.error
                                SagerDatabase.proxyDao.updateProxy(p)
                                ProfileManager.postUpdate(sample.profileId)
                            }
                        }
                    }
                    runOnMainDispatcher {
                        val detail = formatSpeedTestSnapshot(sample)
                        speedTestNotification?.updateNotification(index + 1, total, false, detail)
                        if (!speedTestHidden && isAdded) {
                            val completed = completedSpeedTestCount(index, total, sample.done)
                            binding.nowTesting.text = detail
                            binding.progress.text = "$completed / $total"
                            binding.progressLinear.setProgressCompat(completed, true)
                        }
                    }
                }
                onMainDispatcher {
                    dialog.dismiss()
                }
            } catch (_: CancellationException) {
                runOnMainDispatcher {
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                Logs.w(e)
                runOnMainDispatcher {
                    snackbar(e.readableMessage).show()
                    dialog.dismiss()
                }
            } finally {
                runOnMainDispatcher {
                    (activity as? MainActivity)?.binding?.stats?.updateSpeed(0, 0)
                    try {
                        (activity as? MainActivity)?.connection?.service?.postNotificationSpeed(
                            io.nekohasekai.sagernet.aidl.SpeedDisplayData(0, 0, 0, 0, 0, 0)
                        )
                    } catch (_: Exception) {}
                    adapter.groupFragments.values.forEach { fragment ->
                        fragment.adapter?.clearSpeedTestLive()
                    }
                    if (speedTestDialog === dialog) {
                        speedTestDialog = null
                    }
                    speedTestNotification?.updateNotification(0, 0, true)
                    speedTestNotification?.cancel()
                    speedTestNotification = null
                    speedTestHidden = false
                }
                speedTestRunner = null
                speedTestJob = null
                DataStore.runningTest = false
            }
        }
    }

    private fun formatSpeedTestSnapshot(snapshot: SpeedTestSnapshot): String {
        val stage = when (snapshot.stage) {
            SpeedTestQueueRunner.STAGE_DISCOVERY -> getString(R.string.speed_test_stage_discovery)
            SpeedTestQueueRunner.STAGE_LATENCY -> getString(R.string.speed_test_stage_latency)
            SpeedTestQueueRunner.STAGE_DOWNLOAD -> getString(R.string.speed_test_stage_download)
            SpeedTestQueueRunner.STAGE_UPLOAD -> getString(R.string.speed_test_stage_upload)
            SpeedTestQueueRunner.STAGE_COMPLETE -> getString(R.string.speed_test_stage_complete)
            SpeedTestQueueRunner.STAGE_CANCELLED -> getString(R.string.speed_test_stage_cancelled)
            SpeedTestQueueRunner.STAGE_ERROR -> getString(R.string.speed_test_stage_error)
            else -> getString(R.string.speed_test_stage_pending)
        }
        return buildString {
            append(snapshot.profileName).append(" — ").append(stage)
            if (snapshot.downloadBitsPerSecond > 0) append('\n').append(
                getString(
                    R.string.speed_test_download_format,
                    getString(R.string.speed_test_rate_mbps, snapshot.downloadBitsPerSecond / 1_000_000.0),
                    Formatter.formatFileSize(requireContext(), snapshot.downloadBytes),
                )
            )
            if (snapshot.uploadBitsPerSecond > 0) append('\n').append(
                getString(
                    R.string.speed_test_upload_format,
                    getString(R.string.speed_test_rate_mbps, snapshot.uploadBitsPerSecond / 1_000_000.0),
                    Formatter.formatFileSize(requireContext(), snapshot.uploadBytes),
                )
            )
            if (snapshot.latencyMs > 0) append('\n').append(
                getString(R.string.speed_test_latency_format, snapshot.latencyMs)
            )
            val server = listOf(snapshot.serverName, snapshot.serverCountry)
                .filter { it.isNotBlank() }
                .joinToString(", ")
            if (server.isNotBlank()) append('\n').append(getString(R.string.speed_test_server_format, server))
            if (snapshot.error.isNotBlank()) append('\n').append(snapshot.error)
        }
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }

    }

    fun urlTestSingle(profile: ProxyEntity) {
        val targetUrl = DataStore.groupUrlTestUrl(profile.groupId).takeIf { it.isNotBlank() } ?: DataStore.connectionTestURL
        runOnDefaultDispatcher {
            profile.status = 0
            ProfileManager.postUpdate(profile, false)
            try {
                val urlTest = UrlTest(targetUrl)
                val result = kotlinx.coroutines.withTimeoutOrNull(DataStore.connectionTestTimeout * 2 + 2500L) {
                    urlTest.doTest(profile)
                } ?: throw java.util.concurrent.TimeoutException("URL test timeout")
                profile.status = 1
                profile.ping = result
                profile.error = null
            } catch (e: PluginManager.PluginNotFoundException) {
                profile.status = 2
                profile.error = e.readableMessage
            } catch (e: Exception) {
                profile.status = 3
                profile.error = e.readableMessage
            }
            try {
                SagerDatabase.proxyDao.updatePingResult(profile.id, profile.status, profile.ping, profile.error)
                ProfileManager.postUpdate(profile, false)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val isAll = isCurrentAllGroups()
        val group = DataStore.currentGroup()
        val displayName = if (isAll) getString(R.string.group_tab_all) else group.displayName()
        val targetUrl = if (isAll) DataStore.connectionTestURL else DataStore.groupUrlTestUrl(group.id)
        Logs.d(
            "URLTestTrace batch=start isAll=$isAll groupId=${group.id} group=${group.name} " +
                    "concurrent=${DataStore.connectionTestConcurrent} timeout=${DataStore.connectionTestTimeout}ms " +
                    "link=$targetUrl serviceState=${DataStore.serviceState} " +
                    "currentProfile=${DataStore.currentProfile} network=${SagerNet.underlyingNetwork}"
        )

        val mainJob = runOnDefaultDispatcher {
            val profilesList = if (isAll) {
                SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
            } else {
                if (DataStore.isGroupDisabled(group.id)) emptyList() else SagerDatabase.proxyDao.getByGroup(group.id)
            }
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            Logs.d("URLTestTrace batch=loaded profiles=${profilesList.size}")
            repeat(DataStore.connectionTestConcurrent) { workerId ->
                testJobs.add(launch(Dispatchers.IO) {
                    val urlTest = UrlTest(targetUrl) // note: this is NOT in bg process
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0
                        Logs.d(
                            "URLTestTrace batch=dispatch worker=$workerId profileId=${profile.id} " +
                                    "profile=${profile.displayName()} isCurrent=${profile.id == DataStore.currentProfile}"
                        )

                        try {
                            val result = kotlinx.coroutines.withTimeoutOrNull(DataStore.connectionTestTimeout * 2 + 2500L) {
                                urlTest.doTest(profile)
                            } ?: throw java.util.concurrent.TimeoutException("URL test timeout")
                            profile.status = 1
                            profile.ping = result
                            profile.error = null
                            Logs.d("URLTest ${profile.displayName()}: done, ping=${result}ms")
                        } catch (e: PluginManager.PluginNotFoundException) {
                            profile.status = 2
                            profile.error = e.readableMessage
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                            Logs.w(
                                "URLTestTrace batch=result worker=$workerId profileId=${profile.id} " +
                                        "profile=${profile.displayName()} failed error=${e.readableMessage}"
                            )
                        }

                        try {
                            SagerDatabase.proxyDao.updatePingResult(profile.id, profile.status, profile.ping, profile.error)
                            ProfileManager.postUpdate(profile, false)
                        } catch (e: Exception) {
                            Logs.w(e)
                        }

                        test.update(profile)
                    }
                })
            }

            testJobs.joinAll()
            Logs.d("URLTestTrace batch=finished profiles=${profilesList.size}")

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                if (isAll) {
                    onMainDispatcher {
                        adapter.groupFragments.values.forEach { it.adapter?.reloadProfiles() }
                    }
                } else {
                    GroupManager.postReload(DataStore.currentGroupId())
                }
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[$displayName] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun tcpPingTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val isAll = isCurrentAllGroups()
        val group = DataStore.currentGroup()
        val displayName = if (isAll) getString(R.string.group_tab_all) else group.displayName()
        Logs.d("TcpPingTrace batch=start isAll=$isAll groupId=${group.id} group=${group.name}")

        val mainJob = runOnDefaultDispatcher {
            val profilesList = if (isAll) {
                SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
            } else {
                if (DataStore.isGroupDisabled(group.id)) emptyList() else SagerDatabase.proxyDao.getByGroup(group.id)
            }
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            val tcpPing = io.nekohasekai.sagernet.bg.proto.TcpPing()
            repeat(DataStore.connectionTestConcurrent) { workerId ->
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0
                        try {
                            val result = kotlinx.coroutines.withTimeoutOrNull(DataStore.connectionTestTimeout + 1500L) {
                                tcpPing.doTest(profile)
                            } ?: throw java.util.concurrent.TimeoutException("TCP ping timeout")
                            profile.status = 1
                            profile.ping = result
                            profile.error = null
                            Logs.d("TcpPing ${profile.displayName()}: done, ping=${result}ms")
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                            Logs.w("TcpPing ${profile.displayName()} failed error=${e.readableMessage}")
                        }

                        try {
                            SagerDatabase.proxyDao.updatePingResult(profile.id, profile.status, profile.ping, profile.error)
                            ProfileManager.postUpdate(profile, false)
                        } catch (e: Exception) {
                            Logs.w(e)
                        }

                        test.update(profile)
                    }
                })
            }

            testJobs.joinAll()
            Logs.d("TcpPingTrace batch=finished profiles=${profilesList.size}")

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                if (isAll) {
                    onMainDispatcher {
                        adapter.groupFragments.values.forEach { it.adapter?.reloadProfiles() }
                    }
                } else {
                    GroupManager.postReload(DataStore.currentGroupId())
                }
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[$displayName] TCP Ping"
            )
            dialog.hide()
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()
        private val reloadGeneration = AtomicLong()

        fun reload(now: Boolean = false) {
            val generation = reloadGeneration.incrementAndGet()

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                // 彻底过滤已禁用的分组，绝不在主界面顶部 Tab 栏与轮播列表中展示
                newGroupList.removeAll { !it.ungrouped && DataStore.isGroupDisabled(it.id) }
                // 若存在其他有效分组且未分组中没有配置，不在主界面顶部 Tab 中冗余展示空未分组
                if (newGroupList.size > 1) {
                    newGroupList.removeAll { it.ungrouped && SagerDatabase.proxyDao.countByGroup(it.id) == 0L }
                }

                if (generation != reloadGeneration.get()) return@runOnDefaultDispatcher

                val browsingGroupId = if (selectedGroupIndex in 0 until groupList.size) {
                    groupList[selectedGroupIndex].id
                } else {
                    DataStore.selectedGroup
                }
                var selectedGroup = if (browsingGroupId > 0L && newGroupList.any { it.id == browsingGroupId }) {
                    browsingGroupId
                } else {
                    selectedItem?.groupId?.takeIf { gid -> newGroupList.any { it.id == gid } }
                        ?: newGroupList.firstOrNull { it.id > 0L }?.id
                        ?: 0L
                }
                if (newGroupList.none { it.id == selectedGroup }) {
                    selectedGroup = newGroupList.firstOrNull()?.id ?: 0L
                }
                if (DataStore.selectedGroup != selectedGroup) {
                    DataStore.selectedGroup = selectedGroup
                }
                var newSelectedGroupIndex: Int? = null
                if (selectedGroup > 0L) {
                    newSelectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }.takeIf { it >= 0 }
                } else if (newGroupList.isNotEmpty()) {
                    selectedGroup = newGroupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                    newSelectedGroupIndex = 0
                }

                // 校验当前选中节点是否属于已禁用的分组；若属于，则平滑降级切换至首个未禁用的可用节点
                if (DataStore.selectedProxy > 0L) {
                    val currentProxy = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                    if (currentProxy != null && DataStore.isGroupDisabled(currentProxy.groupId)) {
                        val fallback = SagerDatabase.proxyDao.getAll().firstOrNull { !DataStore.isGroupDisabled(it.groupId) }
                        DataStore.selectedProxy = fallback?.id ?: 0L
                    }
                }

                // Inject "All" tab sentinel at position 0 when the setting is enabled
                if (DataStore.showAllGroupsTab && newGroupList.isNotEmpty()) {
                    val sentinelGroup = ProxyGroup().apply {
                        id = ALL_GROUPS_SENTINEL_ID
                        name = app.getString(R.string.group_tab_all)
                    }
                    newGroupList.add(0, sentinelGroup)
                    // Shift the real-group index by 1 to account for sentinel at 0
                    if (newSelectedGroupIndex != null && newSelectedGroupIndex!! >= 0) {
                        newSelectedGroupIndex = newSelectedGroupIndex!! + 1
                    }
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    val reloadAdapter = this@GroupPagerAdapter
                    runFunc {
                        val viewOwner = viewLifecycleOwnerLiveData.value
                        if (generation == reloadGeneration.get() && viewOwner != null &&
                            isCurrentGroupPagerAdapter(reloadAdapter)
                        ) {
                            viewOwner.lifecycleScope.launch(Dispatchers.Main.immediate) {
                                profileStateInitialized.await()
                                if (generation != reloadGeneration.get() ||
                                    viewLifecycleOwnerLiveData.value !== viewOwner ||
                                    !isCurrentGroupPagerAdapter(reloadAdapter)
                                ) {
                                    return@launch
                                }
                                refreshProfileState()
                                newSelectedGroupIndex?.let { selectedGroupIndex = it }
                                groupList = newGroupList
                                notifyDataSetChanged()
                                if (!isUserInteractingWithPager && newSelectedGroupIndex != null && groupPager.currentItem != selectedGroupIndex) {
                                    groupPager.setCurrentItem(selectedGroupIndex, false)
                                }
                                // Show tab if there are ≥2 real groups (or ≥3 items including sentinel)
                                val realGroupCount = groupList.count { it.id != ALL_GROUPS_SENTINEL_ID }
                                val hideTab = realGroupCount < 2
                                groupTabContainer?.isGone = hideTab
                                tabLayout.isGone = hideTab
                                toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                                if (!hideTab) {
                                    tabLayout.post {
                                        applyTabMargins()
                                        styleCapsuleTabLayout()
                                    }
                                }
                                if (!select) {
                                    groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                                }
                            }
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                isAllGroupsTab = (proxyGroup.id == ALL_GROUPS_SENTINEL_ID)
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    groupTabContainer?.visibility = View.VISIBLE
                    tabLayout.visibility = View.VISIBLE
                    applyTabMargins()
                    styleCapsuleTabLayout()
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            reload()
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            reload()
        }

        override suspend fun groupUpdated(groupId: Long) {
            reload()
        }

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: List<TrafficData>) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false
        /** True when this fragment represents the synthetic "All Groups" tab. */
        var isAllGroupsTab = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null
        var pendingLocateProfileId: Long? = null

        fun locateProfile(profileId: Long, highlight: Boolean = true) {
            val ad = adapter
            if (ad == null || !::configurationListView.isInitialized) {
                pendingLocateProfileId = profileId
                return
            }
            val index = ad.configurationIdList.indexOf(profileId)
            if (index == -1) {
                pendingLocateProfileId = profileId
                return
            }
            pendingLocateProfileId = null
            scrollToAndHighlight(index, highlight)
        }

        fun scrollToAndHighlight(index: Int, highlight: Boolean = true) {
            if (!::configurationListView.isInitialized) return
            val lm = layoutManager
            var alreadyVisible = false
            if (lm is LinearLayoutManager) {
                val first = lm.findFirstCompletelyVisibleItemPosition()
                val last = lm.findLastCompletelyVisibleItemPosition()
                if (index in first..last) {
                    alreadyVisible = true
                }
            }

            if (!alreadyVisible) {
                val ctx = context
                if (ctx != null) {
                    try {
                        val scroller = object : LinearSmoothScroller(ctx) {
                            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
                            override fun calculateDtToFit(
                                viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int
                            ): Int {
                                val topOffset = dp2px(48)
                                return (boxStart + topOffset) - viewStart
                            }
                        }
                        scroller.targetPosition = index
                        lm.startSmoothScroll(scroller)
                    } catch (e: Exception) {
                        configurationListView.scrollToPosition(index)
                    }
                } else {
                    configurationListView.scrollToPosition(index)
                }
            }

            if (highlight) {
                val delay = if (alreadyVisible) 60L else 320L
                configurationListView.postDelayed({
                    highlightCard(index)
                }, delay)
            }
        }

        private fun highlightCard(index: Int) {
            if (!isAdded || view == null) return
            val holder = configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder
            val cardView = holder?.itemView ?: return

            if (DataStore.hapticFeedback) {
                cardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }

            cardView.animate()
                .scaleX(1.025f)
                .scaleY(1.025f)
                .setDuration(160)
                .withEndAction {
                    cardView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(200)
                        .start()
                }
                .start()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                isAllGroupsTab = (it.id == ALL_GROUPS_SENTINEL_ID)
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: RecyclerView.LayoutManager
        lateinit var itemTouchHelper: ItemTouchHelper
        private val alwaysShowAddress: Boolean
            get() = (parentFragment as? ConfigurationFragment)?.alwaysShowAddress == true

        private fun setupItemTouchHelper() {
            if (select) return
            
            if (::itemTouchHelper.isInitialized) {
                itemTouchHelper.attachToRecyclerView(null)
            }
            
            itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, 0) {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    val dragFlags = if (DataStore.groupLayoutMode == 1) {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    } else {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN
                    }
                    return makeMovementFlags(dragFlags, 0) // No swipe flags
                }

                override fun isLongPressDragEnabled(): Boolean = adapter?.isReorderMode == true

                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return 0
                }

                override fun getDragDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return if (isEnabled) {
                        if (DataStore.groupLayoutMode == 1) {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        } else {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN
                        }
                    } else 0
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                }

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        viewHolder.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start()
                    }
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                ): Boolean {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition
                    
                    if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                        return false
                    }
                    
                    adapter?.move(fromPosition, toPosition)
                    return true
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    if (adapter?.isReorderMode != true) {
                        adapter?.commitMove()
                    }
                }

            })
            itemTouchHelper.attachToRecyclerView(configurationListView)
        }
        lateinit var configurationListView: RecyclerView

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        private var lastCompactProfileRows: Boolean? = null

        override fun onResume() {
            super.onResume()

            if (::configurationListView.isInitialized) {
                if (lastCompactProfileRows != null && lastCompactProfileRows != DataStore.compactProfileRows) {
                    configurationListView.adapter = adapter
                }
                lastCompactProfileRows = DataStore.compactProfileRows
            }

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
            } else if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()
            updateSubscriptionInfoCard()
            val pf = parentFragment as? ConfigurationFragment
            if (pf == null || (!pf.isSearchActive && pf.currentSearchQuery.isBlank())) {
                configurationListView.requestFocus()
            }
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            (pf as? ConfigurationFragment)?.styleSortAndLayoutHeaders(menu)
            menu.findItem(R.id.action_hide_unavailable)?.isChecked = DataStore.hideUnavailableProfiles
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            val currentOrder = if (isAllGroupsTab) DataStore.allGroupsOrder else proxyGroup.order
            when (currentOrder) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (isAllGroupsTab) {
                    DataStore.allGroupsOrder = order
                    runOnDefaultDispatcher {
                        onMainDispatcher {
                            adapter?.reloadProfiles()
                        }
                    }
                } else {
                    val needUpdateDb = proxyGroup.order != order
                    proxyGroup.order = order
                    runOnDefaultDispatcher {
                        if (needUpdateDb) {
                            GroupManager.updateGroup(proxyGroup)
                        }
                        onMainDispatcher {
                            adapter?.reloadProfiles()
                        }
                    }
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }

            val reorderItem = menu.findItem(R.id.action_reorder_profiles)
            val resetOrderItem = menu.findItem(R.id.action_reset_profiles_order)
            reorderItem?.isVisible = !isAllGroupsTab && !select
            resetOrderItem?.isVisible = !isAllGroupsTab && !select
            reorderItem?.setOnMenuItemClickListener {
                (requireParentFragment() as? ConfigurationFragment)?.enterReorderMode()
                true
            }
            resetOrderItem?.setOnMenuItemClickListener {
                (requireParentFragment() as? ConfigurationFragment)?.resetToDefaultOrder()
                true
            }
            
            val layoutSingle = menu.findItem(R.id.action_layout_single)
            val layoutDouble = menu.findItem(R.id.action_layout_double)
            when (DataStore.groupLayoutMode) {
                0 -> layoutSingle.isChecked = true
                1 -> layoutDouble.isChecked = true
            }
            layoutSingle.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != 0) {
                    DataStore.groupLayoutMode = 0
                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                }
                true
            }
            layoutDouble.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != 1) {
                    DataStore.groupLayoutMode = 1
                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                }
                true
            }

            val cardClassic = menu.findItem(R.id.action_card_style_classic)
            val cardStroke = menu.findItem(R.id.action_card_style_stroke)
            when (DataStore.profileCardStyle) {
                1 -> cardStroke.isChecked = true
                else -> cardClassic.isChecked = true
            }
            cardClassic.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.profileCardStyle != 0) {
                    DataStore.profileCardStyle = 0
                    (parentFragment as? ConfigurationFragment)?.refreshAllGroupFragmentsCardStyle()
                }
                true
            }
            cardStroke.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.profileCardStyle != 1) {
                    DataStore.profileCardStyle = 1
                    (parentFragment as? ConfigurationFragment)?.refreshAllGroupFragmentsCardStyle()
                }
                true
            }

            val viewDetailed = menu.findItem(R.id.action_view_mode_detailed)
            val viewCompact = menu.findItem(R.id.action_view_mode_compact)
            when (DataStore.profileViewMode) {
                1 -> viewCompact?.isChecked = true
                else -> viewDetailed?.isChecked = true
            }
            viewDetailed?.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.profileViewMode != 0) {
                    DataStore.profileViewMode = 0
                    (parentFragment as? ConfigurationFragment)?.refreshAllGroupFragmentsCardStyle()
                }
                true
            }
            viewCompact?.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.profileViewMode != 1) {
                    DataStore.profileViewMode = 1
                    (parentFragment as? ConfigurationFragment)?.refreshAllGroupFragmentsCardStyle()
                }
                true
            }
        }

        private fun setupLayoutManager() {
            layoutManager = if (DataStore.groupLayoutMode == 1) {
                FixedGridLayoutManager(configurationListView, 2)
            } else {
                FixedLinearLayoutManager(configurationListView)
            }
        }
        
        fun switchLayoutMode() {
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            
            setupItemTouchHelper()
            
            adapter?.notifyDataSetChanged()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            configurationListView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            (configurationListView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            configurationListView.setItemViewCacheSize(20)
            configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        adapter?.flushPendingTrafficUpdates()
                    }
                }
            })

            if (!select) {
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)
                setupItemTouchHelper()
                setupBottomBarScrollDriver()
            }
            view.findViewById<View>(R.id.btn_save_reorder)?.setOnClickListener {
                (parentFragment as? ConfigurationFragment)?.saveReorder()
            }
            updateSubscriptionInfoCard(view)
        }

        fun updateSubscriptionInfoCard(targetView: View? = this.view) {
            val root = targetView ?: return
            val card = root.findViewById<MaterialCardView>(R.id.card_subscription_info) ?: return

            if (select || !::proxyGroup.isInitialized || !DataStore.showSubscriptionInfoCard) {
                card.isGone = true
                return
            }

            val currentGroup = SagerDatabase.groupDao.getById(proxyGroup.id) ?: proxyGroup
            proxyGroup = currentGroup

            val tvTitle = root.findViewById<TextView>(R.id.tv_subscription_title)
            val tvExpire = root.findViewById<TextView>(R.id.tv_expire_date)
            val layoutTraffic = root.findViewById<View>(R.id.layout_traffic_details)
            val tvTrafficStatLabel = root.findViewById<TextView>(R.id.tv_traffic_stat_label)
            val tvTrafficStat = root.findViewById<TextView>(R.id.tv_traffic_stat)
            val tvTrafficRemaining = root.findViewById<TextView>(R.id.tv_traffic_remaining)
            val progressTraffic = root.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_traffic)
            val tvNodeCount = root.findViewById<TextView>(R.id.tv_node_count)
            val tvLastUpdated = root.findViewById<TextView>(R.id.tv_last_updated)
            val btnUpdate = root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_card_update)

            if (isAllGroupsTab || currentGroup.type != GroupType.SUBSCRIPTION || currentGroup.subscription == null) {
                val groupName = if (isAllGroupsTab) {
                    getString(R.string.group_tab_all)
                } else {
                    currentGroup.displayName()
                }
                tvTitle?.text = groupName
                tvExpire?.isGone = true
                layoutTraffic?.isGone = true
                tvLastUpdated?.isGone = true
                btnUpdate?.isGone = true
                val count = adapter?.configurationIdList?.size ?: 0
                tvNodeCount?.text = "$count 个节点"
                card.isVisible = true
                return
            }

            layoutTraffic?.isVisible = true
            val sub = currentGroup.subscription!!
            tvTitle?.text = currentGroup.displayName()

            btnUpdate?.isVisible = true
            btnUpdate?.setOnClickListener {
                GroupUpdater.startUpdate(currentGroup, true)
            }

            var usedBytes = 0L
            var totalBytes = 0L
            var expireMillis = 0L

            if (!sub.subscriptionUserinfo.isNullOrBlank()) {
                val info = sub.subscriptionUserinfo
                fun extract(pattern: String): Long {
                    return Regex(pattern, RegexOption.IGNORE_CASE).find(info)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                }
                val up = extract("upload=([0-9]+)")
                val down = extract("download=([0-9]+)")
                usedBytes = up + down
                totalBytes = extract("total=([0-9]+)")
                val exp = extract("expire=([0-9]+)")
                if (exp > 0L) {
                    expireMillis = if (exp > 100_000_000_000L) exp else exp * 1000L
                }
            } else if (sub.bytesUsed != null || sub.bytesRemaining != null) {
                usedBytes = sub.bytesUsed ?: 0L
                totalBytes = (sub.bytesUsed ?: 0L) + (sub.bytesRemaining ?: 0L)
                if (sub.expiryDate != null && sub.expiryDate > 0) {
                    val exp = sub.expiryDate.toLong()
                    expireMillis = if (exp > 100_000_000_000L) exp else exp * 1000L
                }
            }

            // 仅在完全没有提取到任何流量信息时，尝试从节点名称回退
            var fallbackExpireStr: String? = null
            var fallbackTrafficStr: String? = null
            var fallbackUsedStr: String? = null
            var isUnlimited = false
            if (usedBytes == 0L && totalBytes == 0L && expireMillis <= 0L) {
                val expireRegex = Regex(".*(?:套餐到期|到期时间|过期时间|到期)[：:]\\s*([0-9]{4}[-/][0-9]{2}[-/][0-9]{2})", RegexOption.IGNORE_CASE)
                val trafficRegex = Regex(".*(?:剩余流量|可用流量|剩余)[：:]\\s*([0-9.]+\\s*[KMGT]?B|无限|不限|不限量)", RegexOption.IGNORE_CASE)
                val usedRegex = Regex(".*(?:已用流量|已用|已使用)[：:]\\s*([0-9.]+\\s*[KMGT]?B)", RegexOption.IGNORE_CASE)
                val allGroupProfiles = SagerDatabase.proxyDao.getByGroup(currentGroup.id)
                for (p in allGroupProfiles) {
                    val name = p.displayName()
                    if (expireMillis <= 0L && fallbackExpireStr == null) {
                        val m = expireRegex.find(name)
                        if (m != null) {
                            fallbackExpireStr = m.groupValues[1]
                            try {
                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                expireMillis = sdf.parse(fallbackExpireStr.replace('/', '-'))?.time ?: 0L
                            } catch (_: Throwable) {}
                        }
                    }
                    if (fallbackTrafficStr == null) {
                        val m = trafficRegex.find(name)
                        if (m != null) {
                            val candidate = m.groupValues[1].trim()
                            if (candidate.contains("无限") || candidate.contains("不限")) {
                                isUnlimited = true
                            } else {
                                fallbackTrafficStr = candidate
                            }
                        }
                    }
                    if (fallbackUsedStr == null) {
                        val m = usedRegex.find(name)
                        if (m != null) {
                            fallbackUsedStr = m.groupValues[1].trim()
                        }
                    }
                }
            }

            if (totalBytes > 0L) {
                val remainBytes = (totalBytes - usedBytes).coerceAtLeast(0L)
                tvTrafficStatLabel?.text = "剩余可用"
                tvTrafficStatLabel?.isVisible = true
                tvTrafficStat?.text = remainBytes.toBytesString()
                tvTrafficRemaining?.text = "已用 ${usedBytes.toBytesString()} / 共 ${totalBytes.toBytesString()}"
                tvTrafficRemaining?.isVisible = true
                progressTraffic?.isVisible = true
                val percent = ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                progressTraffic?.progress = percent
                val isExpired = expireMillis > 0L && System.currentTimeMillis() > expireMillis
                if (isExpired || percent >= 90) {
                    progressTraffic?.setIndicatorColor(Color.parseColor("#E53935"))
                } else {
                    progressTraffic?.setIndicatorColor(root.context.getColorAttr(R.attr.colorPrimary))
                }
            } else if (usedBytes > 0L) {
                tvTrafficStatLabel?.text = "已用流量"
                tvTrafficStatLabel?.isVisible = true
                tvTrafficStat?.text = usedBytes.toBytesString()
                tvTrafficRemaining?.text = "不限总量"
                tvTrafficRemaining?.isVisible = true
                progressTraffic?.isGone = true
            } else if (fallbackTrafficStr != null) {
                tvTrafficStatLabel?.text = "可用流量"
                tvTrafficStatLabel?.isVisible = true
                tvTrafficStat?.text = fallbackTrafficStr
                if (fallbackUsedStr != null) {
                    tvTrafficRemaining?.text = "已用 $fallbackUsedStr / 不限总量"
                    tvTrafficRemaining?.isVisible = true
                } else {
                    tvTrafficRemaining?.text = "不限总量"
                    tvTrafficRemaining?.isVisible = true
                }
                progressTraffic?.isGone = true
            } else {
                tvTrafficStatLabel?.text = "套餐流量"
                tvTrafficStatLabel?.isVisible = true
                tvTrafficStat?.text = "不限量"
                tvTrafficRemaining?.isGone = true
                progressTraffic?.isGone = true
            }

            if (expireMillis > 0L) {
                val isExpired = System.currentTimeMillis() > expireMillis
                val dateStr = SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(expireMillis))
                if (isExpired) {
                    tvExpire?.text = "已过期（$dateStr）"
                    tvExpire?.setTextColor(Color.parseColor("#E53935"))
                } else {
                    tvExpire?.text = "到期时间：$dateStr"
                    tvExpire?.setTextColor(root.context.getColorAttr(android.R.attr.textColorSecondary))
                }
                tvExpire?.isVisible = true
            } else if (fallbackExpireStr != null) {
                tvExpire?.text = "到期时间：$fallbackExpireStr"
                tvExpire?.setTextColor(root.context.getColorAttr(android.R.attr.textColorSecondary))
                tvExpire?.isVisible = true
            } else {
                tvExpire?.text = "到期时间：长期有效"
                tvExpire?.setTextColor(root.context.getColorAttr(android.R.attr.textColorSecondary))
                tvExpire?.isVisible = true
            }

            val count = adapter?.configurationIdList?.size ?: 0
            tvNodeCount?.text = "$count 个节点"

            if (sub.lastUpdated != null && sub.lastUpdated > 0) {
                val seconds = sub.lastUpdated.toLong()
                val diffSec = (System.currentTimeMillis() / 1000L) - seconds
                val timeStr = when {
                    diffSec < 60L -> "刚刚更新"
                    diffSec < 3600L -> "${diffSec / 60}分钟前更新"
                    diffSec < 86400L -> "${diffSec / 3600}小时前更新"
                    diffSec < 30L * 86400L -> "${diffSec / 86400}天前更新"
                    else -> "更新于 " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(seconds * 1000L))
                }
                tvLastUpdated?.text = " · $timeStr"
                tvLastUpdated?.isVisible = true
            } else {
                tvLastUpdated?.isGone = true
            }

            card.isVisible = true
        }

        private fun setupBottomBarScrollDriver() {
            val mainActivity = activity as? MainActivity ?: return
            configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) mainActivity.driveBottomBar(dy)
                }
            })

            val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
            var lastRawY = 0f
            configurationListView.setOnTouchListener { recyclerView, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lastRawY = event.rawY
                    MotionEvent.ACTION_MOVE -> {
                        val cannotScroll = !recyclerView.canScrollVertically(-1) &&
                                !recyclerView.canScrollVertically(1)
                        if (cannotScroll) {
                            val fingerDy = event.rawY - lastRawY
                            if (abs(fingerDy) >= touchSlop) {
                                mainActivity.driveBottomBar(-fingerDy.toInt())
                                lastRawY = event.rawY
                            }
                        }
                    }
                }
                false
            }
        }

        private var activeNodePopupMenu: PopupMenu? = null

        override fun onDestroyView() {
            activeNodePopupMenu?.dismiss()
            activeNodePopupMenu = null
            super.onDestroyView()
        }

        override fun onDestroy() {
            activeNodePopupMenu?.dismiss()
            activeNodePopupMenu = null
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }


        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val allConfigurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()
            private val pendingTrafficUpdates = HashSet<Long>()
            private val profileStatePayload = Any()

            var isReorderMode: Boolean = false
            var snapshotIds: List<Long> = emptyList()
            var snapshotOrders: Map<Long, Long> = emptyMap()

            fun cancelMove() {
                if (snapshotIds.isNotEmpty()) {
                    for ((id, order) in snapshotOrders) {
                        configurationList[id]?.userOrder = order
                    }
                    configurationIdList.clear()
                    configurationIdList.addAll(snapshotIds)
                    allConfigurationIdList.clear()
                    allConfigurationIdList.addAll(snapshotIds)
                    updated.clear()
                    notifyDataSetChanged()
                }
            }

            fun saveMove() {
                for (i in configurationIdList.indices) {
                    val item = getItem(configurationIdList[i])
                    val newOrder = (i + 1).toLong()
                    if (item.userOrder != newOrder) {
                        item.userOrder = newOrder
                        updated.add(item)
                    }
                }
                commitMove()
            }

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            fun resolveBalancerDisplay(p: ProxyEntity, bean: BalancerBean?): String {
                if (!DataStore.showBalancerSummary) return ""
                val b = bean ?: (p.requireBean() as? BalancerBean) ?: return ""

                val pf = parentFragment as? ConfigurationFragment
                val isSelected = pf?.isSelectedProfile(p.id) == true
                val isConnected = DataStore.serviceState.started && (
                    p.id == DataStore.activeBalancerId ||
                    p.id == DataStore.currentProfile ||
                    p.id == DataStore.selectedProxy ||
                    (isSelected && pf?.isCurrentProfile(p.id) == true)
                )

                if (isConnected) {
                    val activeLeaf = DataStore.activeLeafProfileName.takeIf { it.isNotBlank() }
                        ?: ActiveOutboundTracker.getActiveLeafNodeDisplay(p)
                    if (!activeLeaf.isNullOrBlank()) {
                        return b.displayWithNodeName(activeLeaf)
                    }
                }

                return b.displayNodeOnly()
            }

            private fun hasMiddleRow(p: ProxyEntity): Boolean {
                val showTraffic = (DataStore.profileViewMode == 0) && (p.rx + p.tx != 0L)
                val bean = p.requireBean()
                val address = if (p.type == ProxyEntity.TYPE_BALANCER) {
                    resolveBalancerDisplay(p, bean as? BalancerBean)
                } else if (alwaysShowAddress && bean.name.isNotBlank()) {
                    bean.displayAddress()
                } else ""
                return !((!showTraffic || p.status <= 0) && address.isBlank())
            }

            fun neighbourHasMiddleRow(position: Int): Boolean {
                if (position == RecyclerView.NO_POSITION) return false
                val lm = (layoutManager as? FixedGridLayoutManager) ?: return false
                val spanCount = lm.spanCount
                val rowCount = lm.rowIndexOf(position)
                var rowMax = (rowCount + 1) * spanCount - 1
                if (rowMax >= itemCount) rowMax = itemCount - 1
                var rowStart = rowCount * spanCount
                if (rowStart < 0) rowStart = 0
                for (i in rowStart..rowMax) {
                    if (i == position) continue
                    if (try {
                            hasMiddleRow(getItemAt(i))
                        } catch (e: Exception) {
                            false
                        }
                    ) return true
                }
                return false
            }

            override fun getItemViewType(position: Int): Int {
                return if (DataStore.compactProfileRows) 1 else 0
            }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                val layoutRes = if (viewType == 1) R.layout.layout_profile_compact else R.layout.layout_profile
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(layoutRes, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun onBindViewHolder(
                holder: ConfigurationHolder,
                position: Int,
                payloads: List<Any>,
            ) {
                if (payloads.isNotEmpty() && payloads.all { it === profileStatePayload }) {
                    try {
                        holder.bindProfileState(getItemAt(position))
                    } catch (ignored: NullPointerException) { // when group deleted
                    }
                } else {
                    onBindViewHolder(holder, position)
                }
            }

            override fun onViewRecycled(holder: ConfigurationHolder) {
                holder.lastSelfHasMiddleRow = null
                holder.lastBoundTx = Long.MIN_VALUE
                holder.lastBoundRx = Long.MIN_VALUE
            }

            override fun onViewAttachedToWindow(holder: ConfigurationHolder) {
                super.onViewAttachedToWindow(holder)
                val profileId = holder.itemId
                val cached = configurationList[profileId] ?: return
                if (holder.lastBoundTx == cached.tx && holder.lastBoundRx == cached.rx) return
                if (configurationListView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    pendingTrafficUpdates.add(profileId)
                    return
                }
                updateVisibleTraffic(profileId, holder)
            }

            private fun updateVisibleTraffic(
                profileId: Long,
                visibleHolder: ConfigurationHolder? = null,
            ) {
                val cached = configurationList[profileId] ?: return
                val holder = if (visibleHolder != null) {
                    visibleHolder
                } else {
                    configurationListView.findViewHolderForItemId(profileId)
                        as? ConfigurationHolder ?: return
                }
                if (holder.lastBoundTx == cached.tx && holder.lastBoundRx == cached.rx) return

                val index = holder.bindingAdapterPosition
                val previousHasMiddleRow = holder.lastSelfHasMiddleRow
                val previouslyShowedTraffic = holder.lastBoundTx != Long.MIN_VALUE &&
                        holder.lastBoundRx != Long.MIN_VALUE &&
                        holder.lastBoundTx + holder.lastBoundRx != 0L
                val showTraffic = cached.tx + cached.rx != 0L

                if (previousHasMiddleRow == null || previouslyShowedTraffic != showTraffic) {
                    holder.bind(cached)
                } else if (showTraffic) {
                    holder.bindTraffic(cached)
                }

                if (index != RecyclerView.NO_POSITION && previousHasMiddleRow != null &&
                    previousHasMiddleRow != holder.lastSelfHasMiddleRow
                ) {
                    refreshSameRowNeighbours(index)
                }
            }

            fun flushPendingTrafficUpdates() {
                if (pendingTrafficUpdates.isEmpty()) return
                for (index in 0 until configurationListView.childCount) {
                    val holder = configurationListView.getChildViewHolder(
                        configurationListView.getChildAt(index)
                    ) as? ConfigurationHolder ?: continue
                    val profileId = holder.itemId
                    if (profileId in pendingTrafficUpdates) {
                        updateVisibleTraffic(profileId, holder)
                    }
                }
                pendingTrafficUpdates.clear()
            }

            fun refreshSameRowNeighbours(position: Int) {
                if (position == RecyclerView.NO_POSITION) return
                val lm = (layoutManager as? FixedGridLayoutManager) ?: return
                val spanCount = lm.spanCount
                val rowCount = lm.rowIndexOf(position)
                var rowMax = (rowCount + 1) * spanCount - 1
                if (rowMax >= itemCount) rowMax = itemCount - 1
                var rowStart = rowCount * spanCount
                if (rowStart < 0) rowStart = 0
                configurationListView.post {
                    for (i in rowStart..rowMax) {
                        if (i == position) continue
                        val holder = layoutManager.findViewByPosition(i)
                            ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                        if (holder != null) {
                            val ent = try { getItemAt(i) } catch (_: Exception) { null }
                            if (ent != null) {
                                val middle = holder.itemView.findViewById<LinearLayout>(R.id.profile_middle_row)
                                val hasSelf = hasMiddleRow(ent)
                                middle?.visibility = when {
                                    hasSelf -> View.VISIBLE
                                    neighbourHasMiddleRow(i) -> View.INVISIBLE
                                    else -> View.GONE
                                }
                            }
                        } else {
                            notifyItemChanged(i)
                        }
                    }
                }
            }

            fun refreshFromPosition(startPosition: Int) {
                if (layoutManager !is FixedGridLayoutManager) return
                val start = startPosition.coerceAtLeast(0)
                if (start >= itemCount) return
                val count = itemCount - start
                configurationListView.post {
                    notifyItemRangeChanged(start, count)
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            fun refreshProfileState(profileIds: Set<Long>) {
                profileIds.forEach { profileId ->
                    val index = configurationIdList.indexOf(profileId)
                    if (index >= 0) notifyItemChanged(index, profileStatePayload)
                }
            }

            private val updated = HashSet<ProxyEntity>()

            fun applyFilterResult(matched: List<Long>) {
                configurationIdList.clear()
                configurationIdList.addAll(matched)
                notifyDataSetChanged()
            }

            fun filter(name: String) {
                val query = name.trim()
                if (query.isEmpty()) {
                    configurationIdList.clear()
                    configurationIdList.addAll(allConfigurationIdList)
                    notifyDataSetChanged()
                    return
                }
                configurationIdList.clear()
                val lower = query.lowercase()
                val matched = allConfigurationIdList.filter { id ->
                    val entity = configurationList[id] ?: return@filter false
                    (entity.displayName()?.lowercase()?.contains(lower) == true) ||
                            (entity.displayType()?.lowercase()?.contains(lower) == true) ||
                            (entity.displayAddress()?.lowercase()?.contains(lower) == true)
                }
                configurationIdList.addAll(matched)
                notifyDataSetChanged()
            }

            fun move(from: Int, to: Int) {
                if (from == to) return

                if (layoutManager is FixedGridLayoutManager) {
                    moveDualColumn(from, to)
                } else {
                    moveLinear(from, to)
                }
            }
            
            private fun moveLinear(from: Int, to: Int) {
                val draggedItemId = configurationIdList[from]

                configurationIdList.removeAt(from)
                configurationIdList.add(to, draggedItemId)
                
                for (i in configurationIdList.indices) {
                    val item = getItem(configurationIdList[i])
                    val newOrder = (i + 1).toLong()
                    if (item.userOrder != newOrder) {
                        item.userOrder = newOrder
                        updated.add(item)
                    }
                }
                
                notifyItemMoved(from, to)
            }
            
            private fun moveDualColumn(from: Int, to: Int) {
                val draggedItemId = configurationIdList[from]

                configurationIdList.removeAt(from)
                configurationIdList.add(to, draggedItemId)
                
                for (i in configurationIdList.indices) {
                    val item = getItem(configurationIdList[i])
                    val newOrder = (i + 1).toLong()
                    if (item.userOrder != newOrder) {
                        item.userOrder = newOrder
                        updated.add(item)
                    }
                }
                
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
                onMainDispatcher {
                    if (layoutManager is FixedGridLayoutManager) {
                        notifyDataSetChanged()
                    }
                }
            }

            fun clearTrafficStatistics() {
                for (profile in configurationList.values) {
                    if (profile.tx != 0L || profile.rx != 0L) {
                        profile.tx = 0
                        profile.rx = 0
                    }
                }
                notifyDataSetChanged()
            }

            fun clearTestResults() {
                reloadProfiles()
            }

            val liveSpeedTests = HashMap<Long, SpeedTestSnapshot>()

            fun updateSpeedTestLive(profileId: Long, sample: SpeedTestSnapshot) {
                liveSpeedTests[profileId] = sample
                val index = configurationIdList.indexOf(profileId)
                if (index >= 0) notifyItemChanged(index)
            }

            fun clearSpeedTestLive() {
                liveSpeedTests.clear()
                notifyDataSetChanged()
            }

            fun updateSpeedTestResult(profileId: Long, outcome: SpeedTestOutcome) {
                liveSpeedTests.remove(profileId)
                val profile = configurationList[profileId] ?: ProfileManager.getProfile(profileId) ?: return
                configurationList[profileId] = profile
                profile.speedTestMode = outcome.mode
                profile.speedTestDownloadBitsPerSecond = outcome.downloadBitsPerSecond
                profile.speedTestUploadBitsPerSecond = outcome.uploadBitsPerSecond
                val index = configurationIdList.indexOf(profileId)
                if (index >= 0) notifyItemChanged(index)
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
                refreshFromPosition(pos - 1)
                updateSubscriptionInfoCard()
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    configurationListView.post {
                        configurationList[item.id] = item
                        configurationIdList.add(index, item.id)
                        notifyItemInserted(index)
                        refreshFromPosition(index - 1)
                        updateSubscriptionInfoCard()
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id && !configurationIdList.contains(profile.id)) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    if (configurationIdList.contains(profile.id)) {
                        configurationList[profile.id] = profile
                        val index = configurationIdList.indexOf(profile.id)
                        notifyItemChanged(index)
                        return@post
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                    refreshFromPosition(pos - 1)
                    updateSubscriptionInfoCard()
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id && !configurationIdList.contains(profile.id)) return
                if (noTraffic) {
                    (parentFragment as? ConfigurationFragment)?.refreshProfileState()
                }
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val cachedProfile = configurationList[profile.id]
                    val updatedProfile = if (noTraffic && cachedProfile != null) {
                        profile.copy(
                            tx = cachedProfile.tx,
                            rx = cachedProfile.rx,
                        ).also { it.dirty = profile.dirty }
                    } else {
                        profile
                    }
                    val contentChanged = !noTraffic ||
                            cachedProfile == null ||
                            cachedProfile != updatedProfile ||
                            cachedProfile.dirty != updatedProfile.dirty ||
                            cachedProfile.displayName() != updatedProfile.displayName()
                    configurationList[profile.id] = updatedProfile
                    if (noTraffic && !contentChanged) return@post

                    val newHasMiddleRow = hasMiddleRow(updatedProfile)
                    val holder = layoutManager.findViewByPosition(index)
                        ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                    val previous = holder?.lastSelfHasMiddleRow
                    if (noTraffic) {
                        notifyItemChanged(index, profileStatePayload)
                    } else {
                        notifyItemChanged(index)
                        if (previous != null && previous != newHasMiddleRow) {
                            refreshSameRowNeighbours(index)
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: List<TrafficData>) {
                try {
                    onMainDispatcher {
                        for (update in data) {
                            val cached = configurationList[update.id] ?: continue
                            if (cached.tx == update.tx && cached.rx == update.rx) continue

                            cached.tx = update.tx
                            cached.rx = update.rx
                            if (configurationListView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                                pendingTrafficUpdates.add(update.id)
                            } else {
                                updateVisibleTraffic(update.id)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id && !isAllGroupsTab) return
                val index = configurationIdList.indexOf(profileId)
                if (index < 0) return

                configurationListView.post {
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                    refreshFromPosition(index - 1)
                    updateSubscriptionInfoCard()
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()
            }

            fun reloadProfiles() {
                var newProfiles = if (isAllGroupsTab) {
                    // All-groups tab: fetch every profile from non-disabled groups
                    SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
                } else {
                    if (!proxyGroup.ungrouped && DataStore.isGroupDisabled(proxyGroup.id)) {
                        emptyList()
                    } else {
                        SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                    }
                }
                val pf = parentFragment as? ConfigurationFragment
                if (pf?.excludedIds != null && pf.excludedIds.isNotEmpty()) {
                    val excludeSet = pf.excludedIds.toSet()
                    newProfiles = newProfiles.filter { it.id !in excludeSet }
                }
                val currentOrder = if (isAllGroupsTab) DataStore.allGroupsOrder else proxyGroup.order
                when (currentOrder) {
                    GroupOrder.ORIGIN -> {
                        if (isAllGroupsTab) {
                            newProfiles = newProfiles.sortedBy { it.id }
                        } else {
                            newProfiles = newProfiles.sortedBy { it.userOrder }
                        }
                    }

                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }
                    }

                    GroupOrder.BY_DELAY -> {
                        newProfiles = newProfiles.sortedWith(
                            compareBy(
                                { if (it.status == 1 && it.ping > 0) 0 else 1 },
                                { if (it.status == 1 && it.ping > 0) it.ping else Int.MAX_VALUE },
                                { it.displayName() }
                            )
                        )
                    }
                }

                if (DataStore.hideUnavailableProfiles) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    val available = newProfiles.filter { it.status == 0 || it.status == 1 || it.id == selectedProxy }
                    // 兜底策略：若某个分组内所有节点均测试不通，需有合理的空状态或全部展示兜底逻辑，避免列表直接变成空白界面。
                    if (available.isNotEmpty()) {
                        newProfiles = available
                    }
                }


                val newProfileMap = newProfiles.associateBy { it.id }
                val newProfileIds = newProfiles.map { it.id }.distinct()

                var selectedProfileIndex = -1

                if (selected) {
                    if (pf?.multiSelect == true) {
                        val firstSelected = pf.multiSelectedIds.firstOrNull()
                        if (firstSelected != null) {
                            selectedProfileIndex = newProfileIds.indexOf(firstSelected)
                        }
                    } else {
                        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                        selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                    }
                }

                configurationListView.post {
                    configurationList.clear()
                    configurationList.putAll(newProfileMap)
                    allConfigurationIdList.clear()
                    allConfigurationIdList.addAll(newProfileIds)
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    notifyDataSetChanged()

                    val pendingId = pendingLocateProfileId
                    if (pendingId != null && pendingId > 0L) {
                        val pendingIndex = configurationIdList.indexOf(pendingId)
                        if (pendingIndex != -1) {
                            pendingLocateProfileId = null
                            scrollToAndHighlight(pendingIndex, true)
                        } else if (selectedProfileIndex != -1) {
                            configurationListView.scrollTo(selectedProfileIndex, true)
                        } else if (newProfiles.isNotEmpty()) {
                            configurationListView.scrollTo(0, true)
                        }
                    } else if (selectedProfileIndex != -1) {
                        configurationListView.scrollTo(selectedProfileIndex, true)
                    } else if (newProfiles.isNotEmpty()) {
                        configurationListView.scrollTo(0, true)
                    }
                    updateSubscriptionInfoCard()

                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            var lastSelfHasMiddleRow: Boolean? = null
            var lastBoundTx = Long.MIN_VALUE
            var lastBoundRx = Long.MIN_VALUE
            private fun showNodeCascadingMenu(anchor: View, proxyEntity: ProxyEntity) {
                if (select) return
                activeNodePopupMenu?.dismiss()
                val popup = PopupMenu(requireContext(), anchor, Gravity.END)
                activeNodePopupMenu = popup
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                val hasStd = proxyEntity.haveStandardLink()
                val hasLink = proxyEntity.haveLink()

                if (!hasStd) {
                    popup.menu.findItem(R.id.action_group_qr)?.subMenu?.removeItem(R.id.action_standard_qr)
                    popup.menu.findItem(R.id.action_group_clipboard)?.subMenu?.removeItem(
                        R.id.action_standard_clipboard
                    )
                }

                if (!hasLink) {
                    popup.menu.removeItem(R.id.action_group_qr)
                    popup.menu.removeItem(R.id.action_group_clipboard)
                }

                if (select) {
                    popup.menu.removeItem(R.id.action_delete)
                }

                popup.setOnMenuItemClickListener(this)
                moe.matsuri.nb4a.ui.MenuScrimHelper.showPopupMenuWithScrim(popup, activity) {
                    if (activeNodePopupMenu === popup) {
                        activeNodePopupMenu = null
                    }
                }
            }

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            private val card = view as MaterialCardView
            private val selectedIndicator: View = view.findViewById(R.id.selected_indicator)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val doubleColumnMenuButton: ImageView = view.findViewById(R.id.double_column_menu)
            val dragHandle: ImageView = view.findViewById(R.id.drag_handle)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            private val baseNameTextSizePx = profileName.textSize
            private val baseTypeTextSizePx = profileType.textSize
            private val baseAddressTextSizePx = profileAddress.textSize
            private val baseStatusTextSizePx = profileStatus.textSize
            private val baseTrafficTextSizePx = trafficText.textSize

            private fun applyCardScale() {
                val factor = UiScaleManager.profileCardFactor()
                profileName.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseNameTextSizePx * factor)
                profileType.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTypeTextSizePx * factor)
                profileAddress.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseAddressTextSizePx * factor)
                profileStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseStatusTextSizePx * factor)
                trafficText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTrafficTextSizePx * factor)
                doubleColumnMenuButton.scaleX = factor
                doubleColumnMenuButton.scaleY = factor
                dragHandle.scaleX = factor
                dragHandle.scaleY = factor
                editButton.scaleX = factor
                editButton.scaleY = factor
                shareButton.scaleX = factor
                shareButton.scaleY = factor
                removeButton.scaleX = factor
                removeButton.scaleY = factor
                val hPad = (dp2px(8) * factor).toInt().coerceAtLeast(dp2px(4))
                val vPad = (dp2px(2) * factor).toInt().coerceAtLeast(dp2px(1))
                profileType.setPadding(hPad, vPad, hPad, vPad)
            }

            init {
                applyCardScale()
                dragHandle.setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (DataStore.hapticFeedback) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        itemTouchHelper.startDrag(this)
                    }
                    false
                }
                view.setOnClickListener {
                    if (adapter?.isReorderMode == true) return@setOnClickListener
                    if (DataStore.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val proxyEntity = entity
                    if (select) {
                        val pf = parentFragment as? ConfigurationFragment
                        if (pf?.multiSelect == true) {
                            pf.toggleMultiSelected(proxyEntity.id)
                        } else {
                            (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                        }
                    } else {
                        selectProfile(proxyEntity)
                    }
                }
                profileStatus.setOnClickListener {
                    val proxyEntity = entity
                    if (proxyEntity.status == 3) {
                        alert(proxyEntity.error ?: "<?>" ).tryToShow()
                    }
                }
                profileStatus.isFocusable = false
                editButton.setOnClickListener {
                    if (DataStore.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    showNodeCascadingMenu(it, entity)
                }
                removeButton.setOnClickListener {
                    if (DataStore.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    removeProfile(entity)
                }
                doubleColumnMenuButton.setOnClickListener {
                    if (DataStore.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    showNodeCascadingMenu(it, entity)
                }
                shareLayout.setOnClickListener {
                    if (DataStore.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val proxyEntity = entity
                    if (!select && proxyEntity.type != ProxyEntity.TYPE_CHAIN && proxyEntity.type != ProxyEntity.TYPE_BALANCER) {
                        showNodeCascadingMenu(it, proxyEntity)
                    }
                }
                view.isLongClickable = true
                view.setOnLongClickListener {
                    if (adapter?.isReorderMode == true) {
                        if (DataStore.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        itemTouchHelper.startDrag(this)
                        return@setOnLongClickListener true
                    }
                    if (DataStore.hapticFeedback) it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val proxyEntity = entity
                    if (!select) {
                        showNodeCascadingMenu(it, proxyEntity)
                        true
                    } else {
                        false
                    }
                }
            }



            private fun selectProfile(proxyEntity: ProxyEntity) {
                val pf = parentFragment as? ConfigurationFragment ?: return
                runOnDefaultDispatcher {
                    var update: Boolean
                    var lastSelected: Long
                    profileAccess.withLock {
                        update = DataStore.selectedProxy != proxyEntity.id
                        lastSelected = DataStore.selectedProxy
                        DataStore.selectedProxy = proxyEntity.id
                        onMainDispatcher {
                            pf.updateSelectedProxySnapshot(proxyEntity.id)
                        }
                    }

                    if (update) {
                        ProfileManager.postUpdate(lastSelected, noTraffic = true)
                        if (DataStore.serviceState != BaseService.State.Stopped && reloadAccess.tryLock()) {
                            SagerNet.reloadService()
                            reloadAccess.unlock()
                        }
                    } else if (SagerNet.isTv) {
                        if (DataStore.serviceState.started) {
                            SagerNet.stopService()
                        } else {
                            SagerNet.startService()
                        }
                    }
                }
            }

            private fun removeProfile(proxyEntity: ProxyEntity) {
                if (select) return
                val pf = parentFragment as? ConfigurationFragment
                val isSelected = pf?.isSelectedProfile(proxyEntity.id) == true
                val isConnected = DataStore.serviceState.started && (proxyEntity.id == DataStore.currentProfile || (isSelected && pf?.isCurrentProfile(proxyEntity.id) == true))
                if (isConnected) {
                    alert(getString(R.string.cannot_delete_active_profile)).tryToShow()
                    return
                }
                val currentAdapter = adapter ?: return
                val index = currentAdapter.configurationIdList.indexOf(proxyEntity.id)
                if (index < 0) return
                val removeAction = {
                    currentAdapter.remove(index)
                    undoManager.remove(index to proxyEntity)
                }
                if (DataStore.confirmProfileDelete) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.delete)
                        .setMessage(R.string.delete_confirm_prompt)
                        .setPositiveButton(R.string.yes) { _, _ -> removeAction() }
                        .setNegativeButton(R.string.no, null)
                        .show()
                } else {
                    removeAction()
                }
            }

            private fun showDoubleColumnMenu(anchor: View, proxyEntity: ProxyEntity) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.double_column_item_menu, popup.menu)
                if (select) popup.menu.removeItem(R.id.action_delete)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit -> {
                            anchor.context.startActivity(
                                proxyEntity.settingIntent(
                                    anchor.context, proxyGroup.type == GroupType.SUBSCRIPTION
                                )
                            )
                            true
                        }
                        R.id.action_share -> {
                            showNodeCascadingMenu(anchor, proxyEntity)
                            true
                        }
                        R.id.action_delete -> {
                            removeProfile(proxyEntity)
                            true
                        }
                        else -> false
                    }
                }
                moe.matsuri.nb4a.ui.MenuScrimHelper.showPopupMenuWithScrim(popup, activity)
            }

            private fun applySelected(selected: Boolean) {
                val ctx = card.context
                val surface = ctx.getColorAttr(R.attr.colorSurface)
                card.setCardBackgroundColor(surface)
                if (DataStore.profileCardStyle == 1) {
                    val primary = ctx.getColorAttr(R.attr.colorPrimary)
                    selectedIndicator.isVisible = false
                    card.cardElevation = 0f
                    card.strokeWidth = ctx.resources.getDimensionPixelSize(
                        if (selected) R.dimen.card_stroke_width_selected
                        else R.dimen.card_stroke_width
                    )
                    card.strokeColor =
                        if (selected) primary else ctx.getColour(R.color.card_stroke)
                } else {
                    selectedIndicator.isVisible = selected
                    card.strokeWidth = 0
                    card.cardElevation =
                        ctx.resources.getDimension(R.dimen.profile_card_elevation_classic)
                }
            }

            private fun speedTestResultText(proxyEntity: ProxyEntity, isCompactRate: Boolean = false): String? {
                val outcome = SpeedTestOutcome(
                    mode = proxyEntity.speedTestMode,
                    downloadBitsPerSecond = proxyEntity.speedTestDownloadBitsPerSecond,
                    uploadBitsPerSecond = proxyEntity.speedTestUploadBitsPerSecond,
                )
                val rates = outcome.rates()
                if (rates.isEmpty()) return null
                return rates.joinToString("  ") { rate ->
                    val direction = when (rate.direction) {
                        SpeedTestDirection.DOWNLOAD -> "↓"
                        SpeedTestDirection.UPLOAD -> "↑"
                    }
                    val mbps = rate.bitsPerSecond / 1_000_000.0
                    if (isCompactRate) {
                        String.format(Locale.ROOT, "%s%.1fM", direction, mbps)
                    } else {
                        "$direction ${getString(R.string.speed_test_rate_mbps, mbps)}"
                    }
                }
            }

            private fun bindTestResult(
                proxyEntity: ProxyEntity,
                showTraffic: Boolean,
                speedTestText: String?,
            ) {
                if (DataStore.compactProfileRows) {
                    val secondary = requireContext().getColorAttr(android.R.attr.textColorSecondary)
                    val text = SpannableStringBuilder()
                    fun appendPart(value: CharSequence?, color: Int) {
                        if (value.isNullOrEmpty()) return
                        if (text.isNotEmpty()) text.append(' ')
                        val start = text.length
                        text.append(value)
                        text.setSpan(ForegroundColorSpan(color), start, text.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    if (speedTestText != null) {
                        appendPart(speedTestText, secondary)
                    } else {
                        when (proxyEntity.status) {
                            1 -> {
                                val ping = proxyEntity.ping
                                val color = when {
                                    ping <= 0 -> secondary
                                    ping < 300 -> requireContext().getColour(R.color.material_green_500)
                                    ping < 800 -> Color.parseColor("#FFA000")
                                    else -> requireContext().getColour(R.color.material_red_500)
                                }
                                appendPart("$ping ms", color)
                            }
                            2, 3 -> {
                                appendPart(getString(R.string.unavailable), requireContext().getColour(R.color.material_red_500))
                            }
                            else -> {}
                        }
                    }
                    profileStatus.text = text
                    profileStatus.isVisible = text.isNotEmpty()
                    return
                }

                val text = SpannableStringBuilder()
                fun appendPart(value: CharSequence?, color: Int) {
                    if (value.isNullOrEmpty()) return
                    if (text.isNotEmpty()) text.append('\n')
                    val start = text.length
                    text.append(value)
                    text.setSpan(ForegroundColorSpan(color), start, text.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                val secondary = requireContext().getColorAttr(android.R.attr.textColorSecondary)
                when (proxyEntity.status) {
                    1 -> appendPart(
                        getString(R.string.available, proxyEntity.ping),
                        requireContext().getColour(R.color.material_green_500),
                    )

                    2 -> appendPart(
                        proxyEntity.error,
                        requireContext().getColour(R.color.material_red_500),
                    )

                    3 -> {
                        val error = proxyEntity.error ?: "<?>"
                        val friendly = Protocols.genFriendlyMsg(error)
                        appendPart(
                            if (friendly.isNotBlank()) friendly else getString(R.string.unavailable),
                            requireContext().getColour(R.color.material_red_500),
                        )
                    }

                    else -> if (speedTestText == null && showTraffic) {
                        appendPart(trafficText.text, secondary)
                        trafficText.text = ""
                    }
                }
                appendPart(speedTestText, secondary)
                profileStatus.text = text
            }

            fun bind(proxyEntity: ProxyEntity) {
                val pf = parentFragment as? ConfigurationFragment ?: return

                entity = proxyEntity
                val bean = proxyEntity.requireBean()

                profileName.text = bean.displayName()

                val isCompactRow = DataStore.compactProfileRows
                if (isCompactRow) {
                    profileType.isGone = true
                    profileAddress.isGone = true
                    trafficText.isGone = true
                    val middleRow = trafficText.parent as? LinearLayout
                    middleRow?.isGone = true
                    lastSelfHasMiddleRow = false
                    editButton.isGone = true
                    shareLayout.isGone = true
                    removeButton.isGone = true
                    doubleColumnMenuButton.isGone = true
                    dragHandle.isVisible = adapter?.isReorderMode == true

                    val live = adapter?.liveSpeedTests?.get(proxyEntity.id)
                    val liveText = if (live != null && !live.done) {
                        when (live.stage) {
                            SpeedTestQueueRunner.STAGE_DOWNLOAD -> {
                                val mbps = live.downloadBitsPerSecond / 1_000_000.0
                                String.format(Locale.ROOT, "↓%.1fM", mbps)
                            }
                            SpeedTestQueueRunner.STAGE_UPLOAD -> {
                                val mbps = live.uploadBitsPerSecond / 1_000_000.0
                                String.format(Locale.ROOT, "↑%.1fM", mbps)
                            }
                            else -> null
                        }
                    } else null
                    val speedTestText = liveText ?: speedTestResultText(proxyEntity, true)

                    bindTestResult(proxyEntity, false, speedTestText)

                    val selected = pf.isSelectedProfile(proxyEntity.id)
                    val started = selected && DataStore.serviceState.started && pf.isCurrentProfile(proxyEntity.id)
                    editButton.isEnabled = !started
                    removeButton.isEnabled = !started
                    applySelected(selected)

                    lastBoundTx = proxyEntity.tx
                    lastBoundRx = proxyEntity.rx
                    return
                }
                profileType.text = proxyEntity.displayType()
                val protocolColor = requireContext().getProtocolColor(proxyEntity.type)
                val chipBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp2px(6).toFloat()
                    setColor(ColorUtils.setAlphaComponent(protocolColor, (255 * 0.12).toInt()))
                }
                profileType.background = chipBg
                profileType.setTextColor(protocolColor)

                val isCompact = DataStore.profileViewMode == 1
                val isDoubleColumn = DataStore.groupLayoutMode == 1

                val rx = proxyEntity.rx
                val tx = proxyEntity.tx
                val live = adapter?.liveSpeedTests?.get(proxyEntity.id)
                val liveText = if (live != null && !live.done) {
                    when (live.stage) {
                        SpeedTestQueueRunner.STAGE_DOWNLOAD -> {
                            val mbps = live.downloadBitsPerSecond / 1_000_000.0
                            if (isDoubleColumn) String.format(Locale.ROOT, "↓%.1fM", mbps)
                            else "↓ ${getString(R.string.speed_test_rate_mbps, mbps)}"
                        }
                        SpeedTestQueueRunner.STAGE_UPLOAD -> {
                            val mbps = live.uploadBitsPerSecond / 1_000_000.0
                            if (isDoubleColumn) String.format(Locale.ROOT, "↑%.1fM", mbps)
                            else "↑ ${getString(R.string.speed_test_rate_mbps, mbps)}"
                        }
                        else -> null
                    }
                } else null
                val speedTestText = if (isCompact) null else (liveText ?: speedTestResultText(proxyEntity, isDoubleColumn))

                val showTraffic = !isCompact && (rx + tx != 0L)
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                val address = if (proxyEntity.type == ProxyEntity.TYPE_BALANCER) {
                    adapter?.resolveBalancerDisplay(proxyEntity, bean as? BalancerBean) ?: ""
                } else if (pf.alwaysShowAddress && bean.name.isNotBlank()) {
                    bean.displayAddress()
                } else {
                    ""
                }
                profileAddress.text = address
                profileAddress.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                profileAddress.isSelected = true

                val middleRow = trafficText.parent as? LinearLayout
                if (middleRow != null) {
                    if (isDoubleColumn && showTraffic && address.isNotBlank()) {
                        middleRow.orientation = LinearLayout.VERTICAL
                        val addrLp = profileAddress.layoutParams as? LinearLayout.LayoutParams
                        if (addrLp != null) {
                            addrLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                            addrLp.weight = 0f
                            profileAddress.layoutParams = addrLp
                        }
                        val trafLp = trafficText.layoutParams as? LinearLayout.LayoutParams
                        if (trafLp != null) {
                            trafLp.topMargin = dp2px(2)
                            trafLp.gravity = Gravity.END
                            trafficText.layoutParams = trafLp
                        }
                    } else {
                        middleRow.orientation = LinearLayout.HORIZONTAL
                        val addrLp = profileAddress.layoutParams as? LinearLayout.LayoutParams
                        if (addrLp != null) {
                            addrLp.width = 0
                            addrLp.weight = 1f
                            profileAddress.layoutParams = addrLp
                        }
                        val trafLp = trafficText.layoutParams as? LinearLayout.LayoutParams
                        if (trafLp != null) {
                            trafLp.topMargin = 0
                            trafLp.gravity = Gravity.NO_GRAVITY
                            trafficText.layoutParams = trafLp
                        }
                    }
                }

                val trafficRowEmpty =
                    (!showTraffic || proxyEntity.status <= 0) && address.isBlank()
                middleRow?.visibility = when {
                    !trafficRowEmpty -> View.VISIBLE
                    adapter?.neighbourHasMiddleRow(bindingAdapterPosition) == true -> View.INVISIBLE
                    else -> View.GONE
                }
                lastSelfHasMiddleRow = !trafficRowEmpty

                bindTestResult(proxyEntity, showTraffic, speedTestText)

                editButton.isGone = true
                shareLayout.isGone = true
                removeButton.isGone = true
                doubleColumnMenuButton.isGone = true
                dragHandle.isVisible = adapter?.isReorderMode == true

                val selected = pf.isSelectedProfile(proxyEntity.id)
                val started =
                    selected && DataStore.serviceState.started && pf.isCurrentProfile(proxyEntity.id)
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                applySelected(selected)

                lastBoundTx = tx
                lastBoundRx = rx

            }

            fun bindProfileState(proxyEntity: ProxyEntity) {
                if (!::entity.isInitialized || entity.id != proxyEntity.id) {
                    bind(proxyEntity)
                    return
                }
                val pf = parentFragment as? ConfigurationFragment ?: return
                val selected = pf.isSelectedProfile(proxyEntity.id)
                val started = selected && DataStore.serviceState.started &&
                        pf.isCurrentProfile(proxyEntity.id)
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                applySelected(selected)

                if (proxyEntity.type == ProxyEntity.TYPE_BALANCER) {
                    val newAddress = adapter?.resolveBalancerDisplay(proxyEntity, entity.requireBean() as? BalancerBean) ?: ""
                    if (profileAddress.text?.toString() != newAddress) {
                        profileAddress.text = newAddress
                    }
                }
            }

            fun bindTraffic(proxyEntity: ProxyEntity) {
                if (entity.id != proxyEntity.id) {
                    bind(proxyEntity)
                    return
                }

                if (proxyEntity.type == ProxyEntity.TYPE_BALANCER) {
                    val newAddress = adapter?.resolveBalancerDisplay(proxyEntity, entity.requireBean() as? BalancerBean) ?: ""
                    if (profileAddress.text?.toString() != newAddress) {
                        profileAddress.text = newAddress
                    }
                }

                if (DataStore.profileViewMode == 1) {
                    trafficText.isVisible = false
                    lastBoundTx = proxyEntity.tx
                    lastBoundRx = proxyEntity.rx
                    return
                }

                val traffic = view.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(view.context, proxyEntity.tx),
                    Formatter.formatFileSize(view.context, proxyEntity.rx)
                )
                if (proxyEntity.status <= 0 && speedTestResultText(proxyEntity) == null) {
                    if (profileStatus.text?.toString() != traffic) {
                        profileStatus.text = traffic
                        profileStatus.setTextColor(
                            requireContext().getColorAttr(android.R.attr.textColorSecondary)
                        )
                    }
                } else if (trafficText.text?.toString() != traffic) {
                    trafficText.text = traffic
                }
                lastBoundTx = proxyEntity.tx
                lastBoundRx = proxyEntity.rx
            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                safeSnackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName() ?: ""
                    when (item.itemId) {
                        R.id.action_test_profile_speed -> {
                            (parentFragment as? ConfigurationFragment)?.speedTestSingle(entity)
                        }
                        R.id.action_urltest -> {
                            (parentFragment as? ConfigurationFragment)?.urlTestSingle(entity)
                        }
                        R.id.action_edit -> {
                            val pf = parentFragment as? ConfigurationFragment
                            val isSelected = pf?.isSelectedProfile(entity.id) == true
                            val isConnected = DataStore.serviceState.started && (entity.id == DataStore.currentProfile || (isSelected && pf?.isCurrentProfile(entity.id) == true))
                            if (isConnected) {
                                alert(getString(R.string.cannot_edit_active_profile)).tryToShow()
                            } else {
                                view.context.startActivity(
                                    entity.settingIntent(
                                        view.context, proxyGroup.type == GroupType.SUBSCRIPTION
                                    )
                                )
                            }
                        }
                        R.id.action_delete -> {
                            removeProfile(entity)
                        }
                        R.id.action_standard_qr -> {
                            val hasStd = entity.haveStandardLink()
                            val stdLink = if (hasStd) entity.toStdLink() else null
                            val universalLink = if (entity.haveLink()) entity.requireBean().toUniversalLink() else null
                            QRCodeDialog(
                                stdLink = stdLink,
                                universalLink = universalLink,
                                displayName = currentName,
                                displayType = entity.displayType(),
                                typeInt = entity.type,
                                initialIsSn = false
                            ).showAllowingStateLoss(parentFragmentManager)
                        }
                        R.id.action_universal_qr -> {
                            val hasStd = entity.haveStandardLink()
                            val stdLink = if (hasStd) entity.toStdLink() else null
                            val universalLink = if (entity.haveLink()) entity.requireBean().toUniversalLink() else null
                            QRCodeDialog(
                                stdLink = stdLink,
                                universalLink = universalLink,
                                displayName = currentName,
                                displayType = entity.displayType(),
                                typeInt = entity.type,
                                initialIsSn = true
                            ).showAllowingStateLoss(parentFragmentManager)
                        }
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    safeSnackbar(e.readableMessage)
                    return true
                }
                return true
            }
        }

    }

    internal val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        val resolver = (context ?: MessageStore.getCurrentActivity() ?: SagerNet.application).contentResolver
                        resolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            safeSnackbar(R.string.action_export_msg)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            safeSnackbar(e.readableMessage)
                        }
                    }

                }
            }
        }

    private var pendingExportProfilesText: String? = null

    private val exportProfilesToFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
            val text = pendingExportProfilesText
            pendingExportProfilesText = null
            if (uri != null && !text.isNullOrEmpty()) {
                runOnDefaultDispatcher {
                    try {
                        val resolver = (context ?: MessageStore.getCurrentActivity() ?: SagerNet.application).contentResolver
                        resolver.openOutputStream(uri)!!.bufferedWriter().use {
                            it.write(text)
                        }
                        onMainDispatcher {
                            safeSnackbar(R.string.action_export_msg)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            safeSnackbar(e.readableMessage)
                        }
                    }
                }
            }
        }

    private fun getProfilesForExport(): Pair<List<ProxyEntity>, String> {
        val isAll = isCurrentAllGroups()
        val isSearching = currentSearchQuery.isNotBlank()
        val currentFragment = getCurrentGroupFragment()

        if (isSearching && currentFragment?.adapter != null) {
            val ad = currentFragment.adapter!!
            val list = ad.configurationIdList.mapNotNull { id ->
                ad.configurationList[id] ?: ProfileManager.getProfile(id)
            }
            return Pair(list, "search_${currentSearchQuery.trim()}")
        }

        if (isAll) {
            val list = SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
            return Pair(list, "all")
        }

        val groupId = DataStore.selectedGroup.takeIf { it > 0 } ?: DataStore.currentGroupId()
        val group = SagerDatabase.groupDao.getById(groupId)
        val list = if (DataStore.isGroupDisabled(groupId)) emptyList() else SagerDatabase.proxyDao.getByGroup(groupId)
        return Pair(list, group?.displayName() ?: "group_$groupId")
    }

    private var isCancelingSearch = false

    private fun cancelSearch(searchView: SearchView) {
        if (isCancelingSearch) return
        isCancelingSearch = true
        try {
            currentSearchQuery = ""
            searchJob?.cancel()
            getCurrentGroupFragment()?.adapter?.filter("")

            searchView.setQuery("", false)
            searchView.onActionViewCollapsed()
            val searchItem = toolbar.menu.findItem(R.id.action_search)
            searchItem?.collapseActionView()
            searchView.clearFocus()
            searchView.maxWidth = dp2px(32)

            val cancelContext = requireContext()
            val primaryColor = when {
                Theme.isBlackTheme(cancelContext) -> Color.WHITE
                Theme.isWhiteTheme(cancelContext) -> Color.parseColor("#212121")
                Theme.isLightGrayTheme(cancelContext) -> Color.parseColor("#1F2937")
                else -> cancelContext.getColorAttr(android.R.attr.textColorPrimary)
            }

            view?.findViewById<View>(R.id.toolbar_title_view)?.let { tv ->
                tv.visibility = View.VISIBLE
                (tv as? TextView)?.text = getToolbarTitleText()
                (tv as? TextView)?.setTextColor(primaryColor)
            }
            toolbar.menu.findItem(R.id.action_add)?.isVisible = true
            toolbar.menu.findItem(R.id.action_connection_url_test)?.isVisible = true
            toolbar.menu.findItem(R.id.action_locate_active_profile)?.isVisible = true
            toolbar.title = null

            if (!select) {
                toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
                toolbar.setNavigationOnClickListener {
                    (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
                }
            }

            applyTopBarIconTint(resolveTopBarIconTint(cancelContext))
            adjustToolbarActionButtons()

            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchView.windowToken, 0)
            updateToolbarMenuTitles()
            toolbar.post {
                toolbar.requestLayout()
                adjustToolbarActionButtons()
            }
        } finally {
            isCancelingSearch = false
        }
    }

}
