package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import android.app.Activity
import android.util.TypedValue
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.UiScaleManager
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    companion object {
        private const val INITIAL_HIDE_DELAY_MS = 100L
        private const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private enum class Transition {
        ShowImmediate,
        ShowAnimated,
        HideImmediate,
        HideAfterStart,
    }

    private lateinit var statusText: TextView
    private lateinit var statusTitleText: TextView
    private lateinit var statusIpText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private var btnIpDetail: View? = null
    private var baseStatusTextSizePx = 0f
    private var baseStatusTitleTextSizePx = 0f
    private var baseStatusIpTextSizePx = 0f
    private var baseTxTextSizePx = 0f
    private var baseRxTextSizePx = 0f
    @Suppress("unused")
    private lateinit var behavior: YourBehavior
    private var currentState = BaseService.State.Idle
    private var pendingTransition: Transition? = Transition.HideImmediate
    private var transitionJob: Job? = null

    private var scrollHidden = false
    private var scrollDirection = 0 // 1 = hide (dy>0), -1 = show (dy<0), 0 = none
    private var scrollAccumulatedDy = 0
    private val scrollToggleThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    var useExternalScrollDriver = false
        set(value) {
            if (field == value) return
            field = value
            syncScrollHiddenFromView()
            resetScrollDriverState()
            updateHideOnScroll()
        }

    var allowShow = false
        set(value) {
            field = value
            updateHideOnScroll()
        }

    init {
        alpha = 0f
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    inner class YourBehavior : Behavior() {

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTarget: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int,
        ): Boolean {
            if (useExternalScrollDriver) return false
            return super.onStartNestedScroll(
                coordinatorLayout,
                child,
                directTarget,
                target,
                nestedScrollAxes,
                type,
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!allowShow) return
            super.slideUp(child)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val transition = pendingTransition
        if (transition != null) {
            pendingTransition = null
            applyTransition(transition)
        } else if (alpha == 0f) {
            alpha = 1f
        }
    }

    private fun applyBottomBarScale() {
        val factor = UiScaleManager.bottomBarFactor()
        if (baseStatusTextSizePx > 0f) statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseStatusTextSizePx * factor)
        if (baseStatusTitleTextSizePx > 0f) statusTitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseStatusTitleTextSizePx * factor)
        if (baseStatusIpTextSizePx > 0f) statusIpText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseStatusIpTextSizePx * factor)
        if (baseTxTextSizePx > 0f) txText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTxTextSizePx * factor)
        if (baseRxTextSizePx > 0f) rxText.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseRxTextSizePx * factor)
        btnIpDetail?.scaleX = factor
        btnIpDetail?.scaleY = factor
    }

    private fun initViews() {
        if (!this::statusText.isInitialized) {
            statusText = findViewById(R.id.status)
            statusText.isSelected = true
            statusTitleText = findViewById(R.id.status_title)
            statusTitleText.isSelected = true
            statusIpText = findViewById(R.id.status_ip)
            statusIpText.isSelected = true
            txText = findViewById(R.id.tx)
            rxText = findViewById(R.id.rx)
            btnIpDetail = findViewById(R.id.btn_ip_detail)
            baseStatusTextSizePx = statusText.textSize
            baseStatusTitleTextSizePx = statusTitleText.textSize
            baseStatusIpTextSizePx = statusIpText.textSize
            baseTxTextSizePx = txText.textSize
            baseRxTextSizePx = rxText.textSize
            applyBottomBarScale()

            val onCardClick = View.OnClickListener { v ->
                if (DataStore.hapticFeedback) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                if (DataStore.serviceState.connected || currentState == BaseService.State.Connected) {
                    onIpDetailClicked()
                }
            }

            val onCardLongClick = View.OnLongClickListener { v ->
                if (DataStore.hapticFeedback) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                if (!DataStore.showLandingIp) {
                    if (DataStore.serviceState.connected || currentState == BaseService.State.Connected) {
                        retestLatencyInPlace()
                    }
                    return@OnLongClickListener true
                }
                val cached = LandingIpManager.getCachedInfo()
                val activity = context as? Activity
                if (activity != null) {
                    if (cached != null) {
                        LandingIpBottomSheet.show(activity, cached) {
                            if (DataStore.showLandingIp) {
                                refreshLandingIp(forceRefresh = true)
                            }
                            retestLatencyInPlace()
                        }
                    } else {
                        refreshLandingIp(forceRefresh = true)
                        retestLatencyInPlace()
                    }
                }
                true
            }

            super.setOnClickListener(onCardClick)
            super.setOnLongClickListener(onCardLongClick)
            val container = findViewById<View>(R.id.stats_content_container)
            container?.setOnClickListener(onCardClick)
            container?.setOnLongClickListener(onCardLongClick)
            statusIpText.setOnClickListener(onCardClick)
            statusIpText.setOnLongClickListener(onCardLongClick)
            statusText.setOnClickListener(onCardClick)
            statusText.setOnLongClickListener(onCardLongClick)
            statusTitleText.setOnClickListener(onCardClick)
            statusTitleText.setOnLongClickListener(onCardLongClick)
            txText.setOnClickListener(onCardClick)
            txText.setOnLongClickListener(onCardLongClick)
            rxText.setOnClickListener(onCardClick)
            rxText.setOnLongClickListener(onCardLongClick)
            btnIpDetail?.setOnClickListener(onCardClick)
            btnIpDetail?.setOnLongClickListener(onCardLongClick)
            updateThemeColors()
        }
    }

    fun updateThemeColors() {
        if (!this::statusText.isInitialized) return
        val currentContext = context ?: return
        if (Theme.isBlackTheme(currentContext)) {
            backgroundTintList = ColorStateList.valueOf(Color.BLACK)
        } else if (Theme.isWhiteTheme(currentContext)) {
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        } else if (Theme.isLightGrayTheme(currentContext)) {
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F5F5F7"))
        }
        val effectiveBgColor = backgroundTintList?.defaultColor
            ?: currentContext.getColorAttr(R.attr.colorPrimary)

        val isLightBg = ColorUtils.calculateLuminance(effectiveBgColor) > 0.45

        if (isLightBg) {
            val primaryTextColor = Color.parseColor("#1E293B")
            val secondaryTextColor = Color.parseColor("#64748B")
            txText.setTextColor(secondaryTextColor)
            rxText.setTextColor(secondaryTextColor)
            statusIpText.setTextColor(primaryTextColor)
            statusTitleText.setTextColor(secondaryTextColor)
            statusText.setTextColor(primaryTextColor)
            (btnIpDetail as? ImageView)?.imageTintList = ColorStateList.valueOf(primaryTextColor)
        } else {
            val primaryTextColor = Color.WHITE
            val secondaryTextColor = Color.parseColor("#CCFFFFFF")
            txText.setTextColor(secondaryTextColor)
            rxText.setTextColor(secondaryTextColor)
            statusIpText.setTextColor(primaryTextColor)
            statusTitleText.setTextColor(secondaryTextColor)
            statusText.setTextColor(primaryTextColor)
            (btnIpDetail as? ImageView)?.imageTintList = ColorStateList.valueOf(primaryTextColor)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initViews()
        updateThemeColors()
    }

    fun onIpDetailClicked() {
        if (!DataStore.serviceState.connected && currentState != BaseService.State.Connected) return
        if (currentState != BaseService.State.Connected && DataStore.serviceState.connected) {
            currentState = BaseService.State.Connected
        }
        // 就地刷新：开启“显示落地 IP”时同步刷新落地 IP 与握手延迟；关闭时仅重新测试握手延迟
        if (DataStore.showLandingIp) {
            (btnIpDetail as? android.widget.ImageView)?.apply {
                animate().rotationBy(360f).setDuration(400).start()
            }
            statusIpText.text = context.getString(R.string.landing_ip_querying)
            statusIpText.visibility = View.VISIBLE
            refreshLandingIp(forceRefresh = true)
        } else {
            btnIpDetail?.visibility = View.GONE
            statusIpText.visibility = View.GONE
        }
        retestLatencyInPlace()
    }

    fun retestLatencyInPlace() {
        testConnection(silent = false)
    }

    override fun setOnClickListener(l: OnClickListener?) {
        initViews()
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        initViews()
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun updateHideOnScroll() {
        hideOnScroll =
            !useExternalScrollDriver && allowShow && currentState == BaseService.State.Connected
    }

    private fun shouldShow(): Boolean {
        return allowShow && currentState == BaseService.State.Connected
    }

    private fun resetScrollDriverState() {
        scrollDirection = 0
        scrollAccumulatedDy = 0
    }

    private fun syncScrollHiddenFromView() {
        if (!isLaidOut || height <= 0) return
        scrollHidden = translationY >= height / 2f
    }

    fun onListScrolled(dy: Int) {
        if (!useExternalScrollDriver || !shouldShow() || dy == 0) return

        val direction = if (dy > 0) 1 else -1
        if (direction != scrollDirection) {
            scrollDirection = direction
            scrollAccumulatedDy = 0
        }
        scrollAccumulatedDy += dy

        val wantHidden = scrollAccumulatedDy > 0
        if (wantHidden == scrollHidden) {
            if (abs(scrollAccumulatedDy) > scrollToggleThresholdPx) {
                scrollAccumulatedDy = direction * scrollToggleThresholdPx
            }
            return
        }
        if (abs(scrollAccumulatedDy) < scrollToggleThresholdPx) return

        scrollHidden = wantHidden
        scrollAccumulatedDy = 0
        if (wantHidden) performHide() else performShow()
    }

    fun syncMainControls(
        showControls: Boolean,
        state: BaseService.State,
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        runOnUi {
            currentState = state
            allowShow = showControls
            when {
                !showControls || state != BaseService.State.Connected -> {
                    applyTransition(
                        if (animate && showControls) Transition.HideAfterStart else Transition.HideImmediate
                    )
                }

                showWhenConnected -> applyTransition(
                    if (animate) Transition.ShowAnimated else Transition.ShowImmediate
                )
                alpha == 0f && isLaidOut -> alpha = 1f
            }
        }
    }

    private fun applyTransition(transition: Transition) {
        if (transition == Transition.HideImmediate && hasPendingDelayedHide()) return
        cancelPendingTransition()
        if (!isLaidOut || height == 0) {
            pendingTransition = transition
            alpha = if (transition == Transition.HideImmediate && !allowShow) 0f else 1f
            return
        }
        pendingTransition = null
        when (transition) {
            Transition.ShowImmediate -> commitVisible(animated = false)
            Transition.ShowAnimated -> commitVisible(animated = true)
            Transition.HideImmediate -> commitHidden(animated = false)
            Transition.HideAfterStart -> commitHidden(animated = true)
        }
    }

    private fun commitVisible(animated: Boolean) {
        alpha = 1f
        scrollHidden = false
        resetScrollDriverState()
        if (animated) {
            performShow()
        } else {
            getBehavior().slideUp(this)
            animate().cancel()
            translationY = 0f
        }
    }

    private fun commitHidden(animated: Boolean) {
        alpha = 1f
        scrollHidden = true
        resetScrollDriverState()
        if (!animated) {
            syncHiddenPosition()
            return
        }
        val activity = context as? MainActivity
        if (activity == null) {
            post {
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
            return
        }
        transitionJob = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(INITIAL_HIDE_DELAY_MS)
            activity.whenStarted {
                transitionJob = null
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
        }
    }

    private fun syncHiddenPosition() {
        getBehavior().slideDown(this)
        animate().cancel()
        translationY = height.toFloat()
        alpha = 1f
    }

    private fun cancelPendingTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    private fun hasPendingDelayedHide(): Boolean {
        return pendingTransition == Transition.HideAfterStart || transitionJob != null
    }

    private var lastMeasuredLatency: Int = -1
    private var lastMeasureTime: Long = 0L
    private var isTestingRealLatency = false
    private var activeLatencyJob: Job? = null
    private var latencyTestSeq: Int = 0

    private fun resetLatencyState() {
        lastMeasuredLatency = -1
        lastMeasureTime = 0L
        isTestingRealLatency = false
        latencyTestSeq++
        activeLatencyJob?.cancel()
        activeLatencyJob = null
    }

    private fun formatStatus(latency: Int = lastMeasuredLatency): String {
        val isHttps = DataStore.connectionTestURL.startsWith("https://")
        val handshakeType = if (isHttps) "HTTPS" else "HTTP"
        return if (latency > 0) "$handshakeType 握手 ${latency}ms" else app.getString(R.string.vpn_connected)
    }

    private fun updateStatusViews(latency: Int = lastMeasuredLatency, customStatus: CharSequence? = null) {
        runOnUi {
            initViews()
            if (currentState == BaseService.State.Connected || DataStore.serviceState.connected) {
                val cached = LandingIpManager.getCachedInfo()
                if (DataStore.showLandingIp && cached != null && cached.ip.isNotBlank()) {
                    statusIpText.text = "${cached.countryFlag} ${cached.countryCode} ${cached.ip}"
                    statusIpText.visibility = View.VISIBLE
                } else if (DataStore.showLandingIp && LandingIpManager.isCurrentlyQuerying()) {
                    statusIpText.text = context.getString(R.string.landing_ip_querying)
                    statusIpText.visibility = View.VISIBLE
                } else if (DataStore.showLandingIp) {
                    statusIpText.text = LandingIpManager.getProfileFallbackDisplay(DataStore.selectedProxy)
                    statusIpText.visibility = View.VISIBLE
                } else {
                    statusIpText.visibility = View.GONE
                }

                val isHttps = DataStore.connectionTestURL.startsWith("https://", ignoreCase = true)
                val handshakeType = if (isHttps) "HTTPS" else "HTTP"

                if (customStatus != null) {
                    statusTitleText.text = "$handshakeType 握手延迟"
                    statusTitleText.visibility = View.VISIBLE
                    statusText.text = customStatus
                } else if (isTestingRealLatency) {
                    statusTitleText.text = "$handshakeType 握手延迟"
                    statusTitleText.visibility = View.VISIBLE
                    statusText.text = context.getString(R.string.connection_test_testing)
                } else if (latency > 0) {
                    statusTitleText.text = "$handshakeType 握手延迟"
                    statusTitleText.visibility = View.VISIBLE
                    statusText.text = "${latency}ms"
                } else {
                    statusTitleText.visibility = View.GONE
                    statusText.text = app.getString(R.string.vpn_connected)
                }
            } else {
                statusTitleText.visibility = View.GONE
                statusIpText.visibility = View.GONE
                statusText.text = customStatus ?: context.getText(
                    when (currentState) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            }
            TooltipCompat.setTooltipText(this, statusText.text)
        }
    }

    fun refreshDisplay() {
        runOnUi {
            updateThemeColors()
            if (currentState == BaseService.State.Connected || DataStore.serviceState.connected) {
                if (currentState != BaseService.State.Connected) {
                    currentState = BaseService.State.Connected
                }
                btnIpDetail?.visibility = if (DataStore.showLandingIp) View.VISIBLE else View.GONE
                updateStatusViews()
                if (DataStore.showLandingIp && LandingIpManager.getCachedInfo() == null) {
                    refreshLandingIp(forceRefresh = false)
                }
                if (lastMeasuredLatency <= 0) {
                    testConnection(silent = true)
                }
            }
        }
    }

    fun changeState(state: BaseService.State) {
        runOnUi {
            currentState = state
            updateHideOnScroll()
            if (state == BaseService.State.Connected) {
                btnIpDetail?.visibility = if (DataStore.showLandingIp) View.VISIBLE else View.GONE
                updateStatusViews()
                if (DataStore.showLandingIp) {
                    refreshLandingIp(forceRefresh = false)
                }
                testConnection(silent = true)
            } else {
                btnIpDetail?.visibility = View.GONE
                resetLatencyState()
                LandingIpManager.clearCache()
                updateSpeed(0, 0)
                updateStatusViews()
            }
        }
    }

    fun refreshLandingIp(forceRefresh: Boolean = false) {
        runOnUi {
            if (currentState != BaseService.State.Connected && !DataStore.serviceState.connected) return@runOnUi
            if (!DataStore.showLandingIp) {
                btnIpDetail?.visibility = View.GONE
                statusIpText.visibility = View.GONE
                updateStatusViews()
                return@runOnUi
            }
            val currentProfile = DataStore.selectedProxy
            val cached = LandingIpManager.getCachedInfo()
            if (!forceRefresh && cached != null && LandingIpManager.cachedProfileId == currentProfile) {
                btnIpDetail?.visibility = View.VISIBLE
                updateStatusViews()
                return@runOnUi
            }

            btnIpDetail?.visibility = View.VISIBLE
            if (forceRefresh || cached == null) {
                statusIpText.text = context.getString(R.string.landing_ip_querying)
                statusIpText.visibility = View.VISIBLE
            }

            val activity = context as? MainActivity
            val scope = activity?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
            scope.launch {
                val result = LandingIpManager.queryLandingIp(currentProfile, forceRefresh = forceRefresh) { intermediateInfo ->
                    runOnUi {
                        if ((currentState == BaseService.State.Connected || DataStore.serviceState.connected) && DataStore.showLandingIp) {
                            statusIpText.text = "${intermediateInfo.countryFlag} ${intermediateInfo.countryCode} ${intermediateInfo.ip}"
                            statusIpText.visibility = View.VISIBLE
                        }
                    }
                }
                if (currentState != BaseService.State.Connected && !DataStore.serviceState.connected) return@launch
                if (!DataStore.showLandingIp) {
                    btnIpDetail?.visibility = View.GONE
                    statusIpText.visibility = View.GONE
                    updateStatusViews()
                    return@launch
                }

                result.onSuccess {
                    btnIpDetail?.visibility = View.VISIBLE
                    updateStatusViews()
                }.onFailure { err ->
                    Logs.w(err)
                    btnIpDetail?.visibility = View.VISIBLE
                    updateStatusViews()
                }
                if (lastMeasuredLatency <= 0 && !isTestingRealLatency) {
                    testConnection(silent = true)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        runOnUi {
            txText.text = "▲ ${
                context.getString(
                    R.string.speed, Formatter.formatFileSize(context, txRate)
                )
            }"
            rxText.text = "▼ ${
                context.getString(
                    R.string.speed, Formatter.formatFileSize(context, rxRate)
                )
            }"
        }
    }

    fun testConnection(silent: Boolean = false) {
        runOnUi {
            val activity = context as? MainActivity ?: return@runOnUi
            if (currentState != BaseService.State.Connected && !DataStore.serviceState.connected) return@runOnUi
            if (currentState != BaseService.State.Connected && DataStore.serviceState.connected) {
                currentState = BaseService.State.Connected
            }

            val now = android.os.SystemClock.elapsedRealtime()

            // 自动静默检测时避免并发重复请求；用户主动点击（silent = false）时总是立即重测并显示“正在测试…”
            if (silent) {
                if (isTestingRealLatency) {
                    return@runOnUi
                }
                if (now - lastMeasureTime < 400L && lastMeasuredLatency > 0) {
                    return@runOnUi
                }
            } else {
                activeLatencyJob?.cancel()
            }

            val seq = ++latencyTestSeq
            isTestingRealLatency = true
            if (!silent || lastMeasuredLatency <= 0) {
                updateStatusViews(customStatus = app.getText(R.string.connection_test_testing))
            }

            val scope = activity.lifecycleScope
            activeLatencyJob = scope.launch(Dispatchers.IO) {
                try {
                    val elapsed = activity.urlTest()
                    withContext(Dispatchers.Main) {
                        if (seq != latencyTestSeq) return@withContext
                        isTestingRealLatency = false
                        if (currentState != BaseService.State.Connected && !DataStore.serviceState.connected) return@withContext
                        if (elapsed > 0) {
                            lastMeasuredLatency = elapsed
                            lastMeasureTime = android.os.SystemClock.elapsedRealtime()
                            LandingIpManager.updateCachedDuration(elapsed.toLong())
                            updateStatusViews(elapsed)
                        } else if (!silent || lastMeasuredLatency <= 0) {
                            lastMeasuredLatency = -1
                            updateStatusViews(customStatus = app.getText(R.string.connection_test_fail))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (seq != latencyTestSeq) return@withContext
                        isTestingRealLatency = false
                        if (currentState != BaseService.State.Connected && !DataStore.serviceState.connected) return@withContext
                        Logs.w("testConnection error: $e")
                        if (!silent || lastMeasuredLatency <= 0) {
                            lastMeasuredLatency = -1
                            updateStatusViews(customStatus = app.getText(R.string.connection_test_fail))
                            if (!silent) {
                                activity.snackbar(
                                    app.getString(
                                        R.string.connection_test_error, e.readableMessage
                                    )
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

}
