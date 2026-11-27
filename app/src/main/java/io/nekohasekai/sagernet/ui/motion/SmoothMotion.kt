package io.nekohasekai.sagernet.ui.motion

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EdgeEffect
import androidx.appcompat.app.AlertDialog
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * 6-level (0 = Off, 1..5 stepped intensity) spring physics & origin motion engine.
 */
object SmoothMotion {

    data class MotionParams(
        val pressCardScaleX: Float,
        val pressCardScaleY: Float,
        val pressBtnScale: Float,
        val releaseDampingX: Float,
        val releaseStiffnessX: Float,
        val releaseDampingY: Float,
        val releaseStiffnessY: Float,
        val releaseVelocity: Float,
        val popupStartScaleX: Float,
        val popupStartScaleY: Float,
        val popupDamping: Float,
        val popupStiffness: Float,
        val popupCollapseScale: Float,
        val popupCollapseDurationMs: Long,
        val overscrollMaxDp: Float,
        val overscrollDamping: Float,
    )

    val smoothOutInterpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
    val smoothInInterpolator = PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f)

    fun level(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            return 0
        }
        return runCatching { DataStore.motionEffectLevel }.getOrDefault(3).coerceIn(0, 5)
    }

    fun isEnabled(): Boolean = level() > 0

    fun params(lvl: Int = level()): MotionParams = when (lvl.coerceIn(1, 5)) {
        1 -> MotionParams(
            pressCardScaleX = 0.976f,
            pressCardScaleY = 0.976f,
            pressBtnScale = 0.93f,
            releaseDampingX = 1.0f,
            releaseStiffnessX = 560f,
            releaseDampingY = 1.0f,
            releaseStiffnessY = 560f,
            releaseVelocity = 0f,
            popupStartScaleX = 0.62f,
            popupStartScaleY = 0.58f,
            popupDamping = 1.0f,
            popupStiffness = 520f,
            popupCollapseScale = 0.65f,
            popupCollapseDurationMs = 125L,
            overscrollMaxDp = 24f,
            overscrollDamping = 1.0f,
        )
        2 -> MotionParams(
            pressCardScaleX = 0.960f,
            pressCardScaleY = 0.964f,
            pressBtnScale = 0.90f,
            releaseDampingX = 0.82f,
            releaseStiffnessX = 500f,
            releaseDampingY = 0.84f,
            releaseStiffnessY = 520f,
            releaseVelocity = 0f,
            popupStartScaleX = 0.48f,
            popupStartScaleY = 0.44f,
            popupDamping = 0.82f,
            popupStiffness = 470f,
            popupCollapseScale = 0.50f,
            popupCollapseDurationMs = 140L,
            overscrollMaxDp = 38f,
            overscrollDamping = 0.90f,
        )
        3 -> MotionParams(
            pressCardScaleX = 0.942f,
            pressCardScaleY = 0.950f,
            pressBtnScale = 0.86f,
            releaseDampingX = 0.66f,
            releaseStiffnessX = 430f,
            releaseDampingY = 0.70f,
            releaseStiffnessY = 450f,
            releaseVelocity = 0.5f,
            popupStartScaleX = 0.38f,
            popupStartScaleY = 0.32f,
            popupDamping = 0.70f,
            popupStiffness = 420f,
            popupCollapseScale = 0.38f,
            popupCollapseDurationMs = 160L,
            overscrollMaxDp = 54f,
            overscrollDamping = 0.80f,
        )
        4 -> MotionParams(
            pressCardScaleX = 0.922f,
            pressCardScaleY = 0.938f,
            pressBtnScale = 0.82f,
            releaseDampingX = 0.54f,
            releaseStiffnessX = 380f,
            releaseDampingY = 0.58f,
            releaseStiffnessY = 350f,
            releaseVelocity = 1.1f,
            popupStartScaleX = 0.30f,
            popupStartScaleY = 0.24f,
            popupDamping = 0.58f,
            popupStiffness = 380f,
            popupCollapseScale = 0.32f,
            popupCollapseDurationMs = 175L,
            overscrollMaxDp = 72f,
            overscrollDamping = 0.70f,
        )
        else -> MotionParams(
            pressCardScaleX = 0.895f,
            pressCardScaleY = 0.920f,
            pressBtnScale = 0.76f,
            releaseDampingX = 0.43f,
            releaseStiffnessX = 330f,
            releaseDampingY = 0.47f,
            releaseStiffnessY = 300f,
            releaseVelocity = 1.8f,
            popupStartScaleX = 0.22f,
            popupStartScaleY = 0.16f,
            popupDamping = 0.48f,
            popupStiffness = 340f,
            popupCollapseScale = 0.25f,
            popupCollapseDurationMs = 185L,
            overscrollMaxDp = 92f,
            overscrollDamping = 0.60f,
        )
    }

    fun View.performSubtleHaptic() {
        if (!isEnabled() || !DataStore.hapticFeedback) return
        runCatching {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun View.springFor(
        tagId: Int,
        property: DynamicAnimation.ViewProperty,
        finalPos: Float,
        dampingRatio: Float,
        stiffness: Float,
    ): SpringAnimation {
        val existing = getTag(tagId) as? SpringAnimation
        if (existing != null) {
            existing.spring.finalPosition = finalPos
            existing.spring.dampingRatio = dampingRatio
            existing.spring.stiffness = stiffness
            return existing
        }
        return SpringAnimation(this, property, finalPos).apply {
            spring = SpringForce(finalPos).apply {
                this.dampingRatio = dampingRatio
                this.stiffness = stiffness
            }
            setTag(tagId, this)
        }
    }

    private fun View.resolveBaseScale(): Pair<Float, Float> {
        val sxAnim = getTag(R.id.motion_tag_spring_x) as? SpringAnimation
        val syAnim = getTag(R.id.motion_tag_spring_y) as? SpringAnimation
        val storedX = getTag(R.id.motion_tag_base_sx) as? Float
        val storedY = getTag(R.id.motion_tag_base_sy) as? Float
        if (storedX == null || sxAnim?.isRunning != true) {
            setTag(R.id.motion_tag_base_sx, scaleX.takeIf { it > 0.2f } ?: 1f)
        }
        if (storedY == null || syAnim?.isRunning != true) {
            setTag(R.id.motion_tag_base_sy, scaleY.takeIf { it > 0.2f } ?: 1f)
        }
        val baseX = (getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
        val baseY = (getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
        return baseX to baseY
    }

    fun View.attachSmoothPress(scaleDown: Float = 0.972f) {
        attachSmoothPress(isCompactButton = scaleDown <= 0.935f)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun View.attachSmoothPress(isCompactButton: Boolean = false) {
        if (getTag(R.id.motion_tag_press_attached) == true) return
        setTag(R.id.motion_tag_press_attached, true)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var isPressedMotion = false
        var downRawX = 0f
        var downRawY = 0f

        fun releaseSpring(baseX: Float, baseY: Float, kickVelocity: Boolean) {
            val p = params()
            springFor(
                R.id.motion_tag_spring_x,
                DynamicAnimation.SCALE_X,
                baseX,
                p.releaseDampingX,
                p.releaseStiffnessX,
            ).apply {
                if (kickVelocity && p.releaseVelocity > 0f) setStartVelocity(p.releaseVelocity)
                start()
            }
            springFor(
                R.id.motion_tag_spring_y,
                DynamicAnimation.SCALE_Y,
                baseY,
                p.releaseDampingY,
                p.releaseStiffnessY,
            ).apply {
                if (kickVelocity && p.releaseVelocity > 0f) setStartVelocity(-p.releaseVelocity * 0.6f)
                start()
            }
        }

        setOnTouchListener { v, event ->
            if (!isEnabled()) {
                if (isPressedMotion) {
                    isPressedMotion = false
                    v.scaleX = (v.getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
                    v.scaleY = (v.getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
                }
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    TouchOriginTracker.recordTouchEvent(event)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val (baseX, baseY) = v.resolveBaseScale()
                    val p = params()
                    val sx = if (isCompactButton) p.pressBtnScale else p.pressCardScaleX
                    val sy = if (isCompactButton) p.pressBtnScale else p.pressCardScaleY
                    isPressedMotion = true
                    v.springFor(
                        R.id.motion_tag_spring_x,
                        DynamicAnimation.SCALE_X,
                        baseX * sx,
                        0.95f,
                        820f,
                    ).start()
                    v.springFor(
                        R.id.motion_tag_spring_y,
                        DynamicAnimation.SCALE_Y,
                        baseY * sy,
                        0.95f,
                        820f,
                    ).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPressedMotion && hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop) {
                        isPressedMotion = false
                        val baseX = (v.getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
                        val baseY = (v.getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
                        releaseSpring(baseX, baseY, kickVelocity = false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressedMotion) {
                        isPressedMotion = false
                        val baseX = (v.getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
                        val baseY = (v.getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
                        releaseSpring(
                            baseX,
                            baseY,
                            kickVelocity = event.actionMasked == MotionEvent.ACTION_UP,
                        )
                    }
                }
            }
            false
        }
    }

    /**
     * Tactile pop pulse used for tabs, toggles, and interactive 5-bar stepper preview.
     */
    fun View.playTapPop(dipFactor: Float = 0.88f, levelOverride: Int? = null) {
        val lvl = levelOverride ?: level()
        if (lvl <= 0) return
        val (baseX, baseY) = resolveBaseScale()
        val p = params(lvl)
        val scaleDip = (dipFactor * (p.pressBtnScale / 0.86f)).coerceIn(0.68f, 0.96f)
        scaleX = baseX * scaleDip
        scaleY = baseY * (scaleDip + (1f - scaleDip) * 0.18f)
        springFor(
            R.id.motion_tag_spring_x,
            DynamicAnimation.SCALE_X,
            baseX,
            p.releaseDampingX,
            p.releaseStiffnessX,
        ).apply {
            if (p.releaseVelocity > 0f) setStartVelocity(p.releaseVelocity * 1.4f)
            start()
        }
        springFor(
            R.id.motion_tag_spring_y,
            DynamicAnimation.SCALE_Y,
            baseY,
            p.releaseDampingY,
            p.releaseStiffnessY,
        ).apply {
            if (p.releaseVelocity > 0f) setStartVelocity(p.releaseVelocity * 0.9f)
            start()
        }
    }

    fun View.animatePopupBloomIn(pivotXRatio: Float, pivotYRatio: Float) {
        if (!isEnabled()) {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            return
        }
        val p = params()
        pivotX = width * pivotXRatio
        pivotY = height * pivotYRatio
        scaleX = p.popupStartScaleX
        scaleY = p.popupStartScaleY
        alpha = 0f

        animate().cancel()
        animate()
            .alpha(1f)
            .setDuration(140L)
            .setInterpolator(smoothOutInterpolator)
            .start()

        springFor(
            R.id.motion_tag_spring_x,
            DynamicAnimation.SCALE_X,
            1f,
            p.popupDamping,
            p.popupStiffness,
        ).apply {
            cancel()
            start()
        }
        springFor(
            R.id.motion_tag_spring_y,
            DynamicAnimation.SCALE_Y,
            1f,
            p.popupDamping,
            p.popupStiffness,
        ).apply {
            cancel()
            start()
        }
    }

    fun View.animateBarShow(onFinished: () -> Unit) {
        val p = params()
        pivotX = width * 0.5f
        pivotY = height * 0.9f
        scaleX = (0.86f + (1f - p.popupStartScaleX) * -0.06f).coerceIn(0.78f, 0.95f)
        scaleY = 0.84f
        alpha = 0f

        animate().setListener(null).withEndAction(null).cancel()
        animate()
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(smoothOutInterpolator)
            .start()

        springFor(
            R.id.motion_tag_spring_x,
            DynamicAnimation.SCALE_X,
            1f,
            p.popupDamping,
            p.popupStiffness,
        ).apply {
            cancel()
            start()
        }
        springFor(
            R.id.motion_tag_spring_y,
            DynamicAnimation.SCALE_Y,
            1f,
            p.popupDamping,
            p.popupStiffness,
        ).apply {
            cancel()
            start()
        }
        val tySpring = springFor(
            R.id.motion_tag_spring_ty,
            DynamicAnimation.TRANSLATION_Y,
            0f,
            p.popupDamping.coerceAtLeast(0.60f),
            p.popupStiffness * 0.90f,
        )
        var endCalled = false
        val listener = object : DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(
                animation: DynamicAnimation<*>?,
                canceled: Boolean,
                value: Float,
                velocity: Float,
            ) {
                animation?.removeEndListener(this)
                if (!endCalled) {
                    endCalled = true
                    onFinished()
                }
            }
        }
        tySpring.cancel()
        tySpring.addEndListener(listener)
        tySpring.start()
    }

    fun View.animateFabMoveDelta(dx: Float, dy: Float, targetTransY: Float) {
        if (!isEnabled()) {
            translationX = 0f
            translationY = targetTransY
            return
        }
        val p = params()
        if (abs(dx) > 0.5f) {
            translationX = dx
            springFor(
                R.id.motion_tag_spring_tx,
                DynamicAnimation.TRANSLATION_X,
                0f,
                p.popupDamping.coerceAtLeast(0.62f),
                p.popupStiffness * 0.92f,
            ).apply {
                cancel()
                start()
            }
        }
        if (abs(dy) > 0.5f) {
            translationY = dy
        }
        springFor(
            R.id.motion_tag_spring_ty,
            DynamicAnimation.TRANSLATION_Y,
            targetTransY,
            p.popupDamping.coerceAtLeast(0.62f),
            p.popupStiffness * 0.92f,
        ).apply {
            cancel()
            start()
        }
    }

    fun AlertDialog.showSmooth(anchor: View? = null): AlertDialog {
        if (anchor != null) TouchOriginTracker.capture(anchor)
        if (!isEnabled()) {
            window?.setWindowAnimations(0)
            show()
            return this
        }
        window?.setWindowAnimations(R.style.SmoothDialogExitOnlyAnimation)
        show()
        val decor = window?.decorView ?: return this
        val target = decor.findViewById<View>(androidx.appcompat.R.id.parentPanel)
            ?: decor.findViewById<ViewGroup>(android.R.id.content)?.children?.firstOrNull()
            ?: return this
        val origin = TouchOriginTracker.peekRecentPoint()
        val p = params()
        target.doOnPreDraw { v ->
            val w = v.width.toFloat().coerceAtLeast(1f)
            val h = v.height.toFloat().coerceAtLeast(1f)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val cx = loc[0] + w * 0.5f
            val cy = loc[1] + h * 0.5f
            if (origin != null) {
                v.pivotX = (origin.x - loc[0]).coerceIn(w * 0.1f, w * 0.9f)
                v.pivotY = (origin.y - loc[1]).coerceIn(h * 0.1f, h * 0.9f)
                v.translationX = (origin.x - cx).coerceIn(-w * 0.45f, w * 0.45f) * 0.35f
                v.translationY = (origin.y - cy).coerceIn(-h * 0.45f, h * 0.45f) * 0.35f
            } else {
                v.pivotX = w * 0.5f
                v.pivotY = h * 0.5f
                v.translationX = 0f
                v.translationY = v.resources.displayMetrics.density * 14f
            }
            v.scaleX = p.popupStartScaleX.coerceAtLeast(0.45f)
            v.scaleY = p.popupStartScaleY.coerceAtLeast(0.40f)
            v.alpha = 0f
            v.animate()
                .alpha(1f)
                .setDuration(150L)
                .setInterpolator(smoothOutInterpolator)
                .start()
            v.springFor(R.id.motion_tag_spring_tx, DynamicAnimation.TRANSLATION_X, 0f, p.popupDamping.coerceAtLeast(0.62f), p.popupStiffness * 0.9f).start()
            v.springFor(R.id.motion_tag_spring_ty, DynamicAnimation.TRANSLATION_Y, 0f, p.popupDamping.coerceAtLeast(0.62f), p.popupStiffness * 0.9f).start()
            v.springFor(R.id.motion_tag_spring_x, DynamicAnimation.SCALE_X, 1f, p.popupDamping, p.popupStiffness).start()
            v.springFor(R.id.motion_tag_spring_y, DynamicAnimation.SCALE_Y, 1f, p.popupDamping, p.popupStiffness).start()
        }
        return this
    }

    fun MaterialAlertDialogBuilder.showSmooth(anchor: View? = null): AlertDialog {
        return create().showSmooth(anchor)
    }

    fun BottomSheetDialog.showSmooth(): BottomSheetDialog {
        if (!isEnabled()) {
            window?.setWindowAnimations(0)
            show()
            return this
        }
        show()
        val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return this
        val p = params()
        sheet.doOnPreDraw { v ->
            val h = v.height.toFloat().coerceAtLeast(200f)
            v.pivotX = v.width * 0.5f
            v.pivotY = h
            v.translationY = h * 0.22f
            v.scaleX = 0.95f
            v.scaleY = 0.92f
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(150L).setInterpolator(smoothOutInterpolator).start()
            v.springFor(R.id.motion_tag_spring_ty, DynamicAnimation.TRANSLATION_Y, 0f, p.popupDamping.coerceAtLeast(0.65f), p.popupStiffness * 0.9f).start()
            v.springFor(R.id.motion_tag_spring_x, DynamicAnimation.SCALE_X, 1f, p.popupDamping, p.popupStiffness).start()
            v.springFor(R.id.motion_tag_spring_y, DynamicAnimation.SCALE_Y, 1f, p.popupDamping, p.popupStiffness).start()
        }
        return this
    }

    fun RecyclerView.attachSmoothOverscroll() {
        if (getTag(R.id.motion_tag_overscroll_attached) == true) return
        setTag(R.id.motion_tag_overscroll_attached, true)

        val density = resources.displayMetrics.density
        var rawPullDp = 0f

        fun updateTranslationFromPull() {
            val p = params()
            val maxDp = p.overscrollMaxDp
            val sign = if (rawPullDp >= 0f) 1f else -1f
            val absDp = abs(rawPullDp)
            val dampedDp = maxDp * (1f - 1f / (1f + ln(1f + absDp / 24f) * 1.15f))
            (getTag(R.id.motion_tag_spring_ty) as? SpringAnimation)?.cancel()
            translationY = sign * dampedDp * density
        }

        fun springBack(velocityPxPerSec: Float = 0f) {
            rawPullDp = 0f
            if (abs(translationY) < 0.5f && abs(velocityPxPerSec) < 20f) {
                translationY = 0f
                return
            }
            if (!isEnabled()) {
                (getTag(R.id.motion_tag_spring_ty) as? SpringAnimation)?.cancel()
                translationY = 0f
                return
            }
            val p = params()
            springFor(
                R.id.motion_tag_spring_ty,
                DynamicAnimation.TRANSLATION_Y,
                0f,
                p.overscrollDamping,
                380f,
            ).apply {
                if (velocityPxPerSec != 0f) {
                    setStartVelocity(velocityPxPerSec.coerceIn(-2200f, 2200f))
                }
                start()
            }
        }

        edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
                val isVertical = direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM
                if (!isVertical) {
                    return super.createEdgeEffect(view, direction)
                }
                val dirSign = if (direction == DIRECTION_TOP) 1f else -1f
                return object : EdgeEffect(view.context) {
                    private var pulled = false

                    override fun isFinished(): Boolean = !pulled && abs(view.translationY) < 0.5f

                    override fun finish() {
                        pulled = false
                        rawPullDp = 0f
                        if (view.translationY != 0f) springBack(0f)
                    }

                    override fun onPull(deltaDistance: Float) {
                        onPull(deltaDistance, 0.5f)
                    }

                    override fun onPull(deltaDistance: Float, displacement: Float) {
                        if (!isEnabled()) return
                        pulled = true
                        val heightPx = view.height.coerceAtLeast(300)
                        rawPullDp += dirSign * (deltaDistance * heightPx / density) * 0.62f
                        rawPullDp = rawPullDp.coerceIn(-260f, 260f)
                        updateTranslationFromPull()
                    }

                    override fun onRelease() {
                        if (!pulled && abs(view.translationY) < 0.5f) return
                        pulled = false
                        springBack(0f)
                    }

                    override fun onAbsorb(velocity: Int) {
                        if (!isEnabled()) return
                        pulled = false
                        val flingKick = dirSign * (velocity.coerceIn(0, 6000) * 0.16f)
                        springBack(flingKick)
                    }

                    override fun draw(canvas: Canvas): Boolean = false
                }
            }
        }
    }

    fun ViewPager2.attachSmoothPageTransformer() {
        setPageTransformer { page, position ->
            if (!isEnabled()) {
                page.scaleX = 1f
                page.scaleY = 1f
                page.alpha = 1f
                return@setPageTransformer
            }
            val lvl = level()
            val absPos = abs(position).coerceIn(0f, 1f)
            val scaleDrop = 0.015f + lvl * 0.006f
            val scale = 1f - absPos * scaleDrop
            page.scaleX = scale
            page.scaleY = scale
            page.alpha = 1f - absPos * 0.16f
        }
    }

    fun TabLayout.attachSmoothTabPop() {
        addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.view?.playTapPop(0.88f)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

            override fun onTabReselected(tab: TabLayout.Tab?) {
                tab?.view?.playTapPop(0.88f)
            }
        })
    }
}
