package io.nekohasekai.sagernet.ui.motion

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.EdgeEffect
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * High-damping spring physics engine for OwnBox.
 * Designed for crisp iOS-like tactile response that settles cleanly in one smooth motion
 * without back-and-forth wobble.
 */
object SmoothMotion {

    data class MotionParams(
        val releaseDamping: Float,
        val releaseStiffness: Float,
        val popupDamping: Float,
        val popupStiffness: Float,
        val overscrollDamping: Float,
        val pressScaleMultiplier: Float,
    )

    val smoothOutInterpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
    val smoothInInterpolator = PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f)

    fun level(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            return 0
        }
        return runCatching { DataStore.motionEffectLevel }.getOrDefault(2).coerceIn(0, 3)
    }

    fun isEnabled(): Boolean = level() > 0

    fun params(lvl: Int = level()): MotionParams = when (lvl.coerceIn(0, 3)) {
        1 -> MotionParams(
            releaseDamping = 1.0f,
            releaseStiffness = 640f,
            popupDamping = 1.0f,
            popupStiffness = 580f,
            overscrollDamping = 1.0f,
            pressScaleMultiplier = 0.5f,
        )
        3 -> MotionParams(
            releaseDamping = 0.74f,
            releaseStiffness = 480f,
            popupDamping = 0.76f,
            popupStiffness = 480f,
            overscrollDamping = 0.86f,
            pressScaleMultiplier = 1.35f,
        )
        else -> MotionParams(
            releaseDamping = 0.86f,
            releaseStiffness = 560f,
            popupDamping = 0.86f,
            popupStiffness = 540f,
            overscrollDamping = 0.94f,
            pressScaleMultiplier = 1.0f,
        )
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
        if (storedX == null || (sxAnim?.isRunning != true)) {
            val sx = scaleX.takeIf { it > 0.2f } ?: 1f
            setTag(R.id.motion_tag_base_sx, sx)
        }
        if (storedY == null || (syAnim?.isRunning != true)) {
            val sy = scaleY.takeIf { it > 0.2f } ?: 1f
            setTag(R.id.motion_tag_base_sy, sy)
        }
        val baseX = (getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
        val baseY = (getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
        return baseX to baseY
    }

    /**
     * Attaches subtle, high-damping press feedback alongside existing click/long-click/drag listeners.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun View.attachSmoothPress(scaleDown: Float = 0.972f) {
        if (getTag(R.id.motion_tag_press_attached) == true) return
        setTag(R.id.motion_tag_press_attached, true)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var isPressedMotion = false
        var downRawX = 0f
        var downRawY = 0f

        setOnTouchListener { v, event ->
            if (!isEnabled()) {
                if (isPressedMotion) {
                    isPressedMotion = false
                    val bx = (v.getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
                    val by = (v.getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
                    v.scaleX = bx
                    v.scaleY = by
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
                    val downOffset = (1f - scaleDown) * p.pressScaleMultiplier
                    val factor = (1f - downOffset).coerceIn(0.86f, 0.994f)
                    isPressedMotion = true
                    v.springFor(
                        R.id.motion_tag_spring_x,
                        DynamicAnimation.SCALE_X,
                        baseX * factor,
                        0.98f,
                        900f,
                    ).start()
                    v.springFor(
                        R.id.motion_tag_spring_y,
                        DynamicAnimation.SCALE_Y,
                        baseY * factor,
                        0.98f,
                        900f,
                    ).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPressedMotion && hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop) {
                        isPressedMotion = false
                        val p = params()
                        val baseX = (v.getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
                        val baseY = (v.getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
                        v.springFor(
                            R.id.motion_tag_spring_x,
                            DynamicAnimation.SCALE_X,
                            baseX,
                            p.releaseDamping,
                            p.releaseStiffness,
                        ).start()
                        v.springFor(
                            R.id.motion_tag_spring_y,
                            DynamicAnimation.SCALE_Y,
                            baseY,
                            p.releaseDamping,
                            p.releaseStiffness,
                        ).start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressedMotion) {
                        isPressedMotion = false
                        val p = params()
                        val baseX = (v.getTag(R.id.motion_tag_base_sx) as? Float) ?: 1f
                        val baseY = (v.getTag(R.id.motion_tag_base_sy) as? Float) ?: 1f
                        v.springFor(
                            R.id.motion_tag_spring_x,
                            DynamicAnimation.SCALE_X,
                            baseX,
                            p.releaseDamping,
                            p.releaseStiffness,
                        ).start()
                        v.springFor(
                            R.id.motion_tag_spring_y,
                            DynamicAnimation.SCALE_Y,
                            baseY,
                            p.releaseDamping,
                            p.releaseStiffness,
                        ).start()
                    }
                }
            }
            false
        }
    }

    /**
     * Plays a single crisp tap dip-and-settle pop on programmatic selection (e.g. Tab pills).
     */
    fun View.playTapPop(dipFactor: Float = 0.93f) {
        if (!isEnabled()) return
        val (baseX, baseY) = resolveBaseScale()
        val p = params()
        val downOffset = (1f - dipFactor) * p.pressScaleMultiplier
        val factor = (1f - downOffset).coerceIn(0.88f, 0.99f)
        scaleX = baseX * factor
        scaleY = baseY * factor
        springFor(
            R.id.motion_tag_spring_x,
            DynamicAnimation.SCALE_X,
            baseX,
            p.releaseDamping,
            p.releaseStiffness,
        ).start()
        springFor(
            R.id.motion_tag_spring_y,
            DynamicAnimation.SCALE_Y,
            baseY,
            p.releaseDamping,
            p.releaseStiffness,
        ).start()
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
        scaleX = 0.90f
        scaleY = 0.86f
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

    /**
     * Smooth damped rubber-band overscroll on RecyclerViews.
     */
    fun RecyclerView.attachSmoothOverscroll() {
        if (getTag(R.id.motion_tag_overscroll_attached) == true) return
        setTag(R.id.motion_tag_overscroll_attached, true)

        val density = resources.displayMetrics.density
        var rawPullDp = 0f

        fun updateTranslationFromPull() {
            val p = params()
            val maxDp = 38f * p.pressScaleMultiplier.coerceAtLeast(0.45f)
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
            val p = params()
            springFor(
                R.id.motion_tag_spring_ty,
                DynamicAnimation.TRANSLATION_Y,
                0f,
                p.overscrollDamping,
                380f,
            ).apply {
                if (velocityPxPerSec != 0f) {
                    setStartVelocity(velocityPxPerSec.coerceIn(-1800f, 1800f))
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

                    override fun setSize(width: Int, height: Int) {
                        super.setSize(width, height)
                    }

                    override fun isFinished(): Boolean = !pulled && abs(view.translationY) < 0.5f

                    override fun finish() {
                        pulled = false
                        rawPullDp = 0f
                        if (view.translationY != 0f) {
                            springBack(0f)
                        }
                    }

                    override fun onPull(deltaDistance: Float) {
                        onPull(deltaDistance, 0.5f)
                    }

                    override fun onPull(deltaDistance: Float, displacement: Float) {
                        if (!isEnabled()) return
                        pulled = true
                        val heightPx = view.height.coerceAtLeast(300)
                        rawPullDp += dirSign * (deltaDistance * heightPx / density) * 0.55f
                        rawPullDp = rawPullDp.coerceIn(-220f, 220f)
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
                        val flingKick = dirSign * (velocity.coerceIn(0, 6000) * 0.14f)
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
            val absPos = abs(position).coerceIn(0f, 1f)
            val scale = 1f - absPos * 0.025f
            page.scaleX = scale
            page.scaleY = scale
            page.alpha = 1f - absPos * 0.14f
        }
    }

    fun TabLayout.attachSmoothTabPop() {
        addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.view?.playTapPop(0.93f)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

            override fun onTabReselected(tab: TabLayout.Tab?) {
                tab?.view?.playTapPop(0.93f)
            }
        })
    }
}
