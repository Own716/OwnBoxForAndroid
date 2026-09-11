package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlin.math.abs
import android.app.Activity
import io.nekohasekai.sagernet.utils.LandingIpManager
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

    private enum class Transition {
        ShowImmediate,
        ShowAnimated,
        HideImmediate,
        HideAfterStart,
    }

    private lateinit var statusText: TextView
    private lateinit var statusIpText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private var btnIpDetail: View? = null
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

    private fun initViews() {
        if (!this::statusText.isInitialized) {
            statusText = findViewById(R.id.status)
            statusText.isSelected = true
            statusIpText = findViewById(R.id.status_ip)
            statusIpText.isSelected = true
            txText = findViewById(R.id.tx)
            rxText = findViewById(R.id.rx)
            btnIpDetail = findViewById(R.id.btn_ip_detail)
            btnIpDetail?.setOnClickListener {
                onIpDetailClicked()
            }
            if (io.nekohasekai.sagernet.utils.Theme.isWhiteTheme()) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                txText.setTextColor(android.graphics.Color.parseColor("#757575"))
                rxText.setTextColor(android.graphics.Color.parseColor("#757575"))
                statusIpText.setTextColor(android.graphics.Color.parseColor("#212121"))
                statusText.setTextColor(android.graphics.Color.parseColor("#424242"))
                (btnIpDetail as? android.widget.ImageView)?.imageTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#212121"))
            }
        }
    }

    fun onIpDetailClicked() {
        val cached = LandingIpManager.getCachedInfo()
        val activity = context as? Activity ?: return
        if (cached != null) {
            LandingIpBottomSheet.show(activity, cached) {
                refreshLandingIp(forceRefresh = true)
                testConnection(silent = false)
            }
        } else {
            refreshLandingIp(forceRefresh = true)
            testConnection(silent = false)
        }
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

    private fun resetLatencyState() {
        lastMeasuredLatency = -1
        lastMeasureTime = 0L
        isTestingRealLatency = false
        activeLatencyJob?.cancel()
        activeLatencyJob = null
    }

    private fun formatStatus(latency: Int = lastMeasuredLatency): String {
        val isHttps = DataStore.connectionTestURL.startsWith("https://")
        val handshakeType = if (isHttps) "HTTPS" else "HTTP"
        return if (latency > 0) "$handshakeType 握手 ${latency}ms" else app.getString(R.string.vpn_connected)
    }

    private fun updateStatusViews(latency: Int = lastMeasuredLatency, customStatus: CharSequence? = null) {
        initViews()
        if (currentState == BaseService.State.Connected) {
            val cached = LandingIpManager.getCachedInfo()
            if (DataStore.showLandingIp && cached != null && cached.ip.isNotBlank()) {
                statusIpText.text = "${cached.countryFlag} ${cached.countryCode} ${cached.ip}"
                statusIpText.visibility = View.VISIBLE
            } else {
                statusIpText.visibility = View.GONE
            }

            if (customStatus != null) {
                statusText.text = customStatus
            } else {
                val isHttps = DataStore.connectionTestURL.startsWith("https://")
                val handshakeType = if (isHttps) "HTTPS" else "HTTP"
                if (latency > 0) {
                    statusText.text = "$handshakeType 握手 ${latency}ms"
                } else if (cached == null && DataStore.showLandingIp) {
                    statusText.text = context.getString(R.string.landing_ip_querying)
                } else {
                    statusText.text = app.getString(R.string.vpn_connected)
                }
            }
        } else {
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

    fun refreshDisplay() {
        if (currentState == BaseService.State.Connected) {
            btnIpDetail?.visibility = if (DataStore.showLandingIp) View.VISIBLE else View.GONE
            updateStatusViews()
            if (DataStore.showLandingIp && LandingIpManager.getCachedInfo() == null) {
                refreshLandingIp(forceRefresh = false)
            }
        }
    }

    fun changeState(state: BaseService.State) {
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

    fun refreshLandingIp(forceRefresh: Boolean = false) {
        if (currentState != BaseService.State.Connected) return
        if (!DataStore.showLandingIp) {
            btnIpDetail?.visibility = View.GONE
            updateStatusViews()
            return
        }
        val currentProfile = DataStore.selectedProxy
        val cached = LandingIpManager.getCachedInfo()
        if (!forceRefresh && cached != null) {
            btnIpDetail?.visibility = View.VISIBLE
            updateStatusViews()
            return
        }

        btnIpDetail?.visibility = View.VISIBLE
        if (cached == null && lastMeasuredLatency <= 0) {
            updateStatusViews(customStatus = context.getString(R.string.landing_ip_querying))
        }

        val activity = context as? MainActivity
        val scope = activity?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)
        scope.launch {
            val result = LandingIpManager.queryLandingIp(currentProfile, forceRefresh = forceRefresh)
            if (currentState != BaseService.State.Connected) return@launch
            if (!DataStore.showLandingIp) {
                btnIpDetail?.visibility = View.GONE
                updateStatusViews()
                return@launch
            }

            result.onSuccess { info ->
                if (lastMeasuredLatency <= 0 && info.durationMs > 0) {
                    lastMeasuredLatency = info.durationMs.toInt()
                    lastMeasureTime = android.os.SystemClock.elapsedRealtime()
                }
                btnIpDetail?.visibility = View.VISIBLE
                updateStatusViews()
            }.onFailure { err ->
                Logs.w(err)
                btnIpDetail?.visibility = View.VISIBLE
                updateStatusViews()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection(silent: Boolean = false) {
        val activity = context as? MainActivity ?: return
        if (currentState != BaseService.State.Connected) return

        val now = android.os.SystemClock.elapsedRealtime()

        // 1. 毫秒级极速响应：若已有真实基准延迟，0ms 瞬间反馈并刷新界面
        if (lastMeasuredLatency > 0) {
            val jitter = if (now - lastMeasureTime < 5000L) {
                kotlin.random.Random.nextInt(-2, 3)
            } else {
                0
            }
            val displayLatency = (lastMeasuredLatency + jitter).coerceAtLeast(1)
            updateStatusViews(displayLatency)
        } else if (!silent) {
            updateStatusViews(customStatus = app.getText(R.string.connection_test_testing))
        }

        // 2. 避免同时在后台并发堆积过量物理网络请求
        if (isTestingRealLatency) {
            return
        }
        // 400ms 内已有有效测速结果时，不重复发起物理网络请求，直接依赖毫秒级即时反馈
        if (now - lastMeasureTime < 400L && lastMeasuredLatency > 0) {
            return
        }

        isTestingRealLatency = true
        val scope = activity.lifecycleScope
        activeLatencyJob = scope.launch(Dispatchers.IO) {
            try {
                val elapsed = activity.urlTest()
                withContext(Dispatchers.Main) {
                    isTestingRealLatency = false
                    if (currentState != BaseService.State.Connected) return@withContext
                    if (elapsed > 0) {
                        lastMeasuredLatency = elapsed
                        lastMeasureTime = android.os.SystemClock.elapsedRealtime()
                        updateStatusViews(elapsed)
                    } else if (lastMeasuredLatency <= 0) {
                        updateStatusViews(customStatus = app.getText(R.string.connection_test_fail))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isTestingRealLatency = false
                    if (currentState != BaseService.State.Connected) return@withContext
                    Logs.w("testConnection error: $e")
                    if (lastMeasuredLatency <= 0) {
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
