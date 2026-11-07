package io.nekohasekai.sagernet.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders 360-degree soft Gaussian floating shadows + crisp hairline border + capsule surface
 * matching the exact SDF profile of the OwnBox 3D floating capsule design.
 */
object FloatingCapsuleRenderer {

    private data class ShadowStep(
        val expandDp: Float,
        val dySpotFactor: Float,
        val spotAlphaLight: Int,
        val ambAlphaLight: Int,
        val spotAlphaDark: Int,
        val ambAlphaDark: Int,
    )

    // Precomputed 16-step Gaussian SDF iso-contour steps from outer (4.8dp) to inner (0.22dp)
    private val SHADOW_STEPS: Array<ShadowStep> by lazy {
        val count = 16
        val maxDp = 4.8f
        val minDp = 0.22f
        val radii = FloatArray(count + 1) { i ->
            maxDp - (maxDp - minDp) * (i.toFloat() / count.toFloat())
        }
        fun spotG(d: Float, peak: Float): Int {
            val v = peak * exp(-(d * d) / (2f * 2.05f * 2.05f))
            return v.roundToInt()
        }
        fun ambG(d: Float, peak: Float): Int {
            val v = peak * exp(-(d * d) / (2f * 1.40f * 1.40f))
            return v.roundToInt()
        }

        var prevSpotL = 0
        var prevAmbL = 0
        var prevSpotD = 0
        var prevAmbD = 0

        Array(count) { i ->
            val r = radii[i + 1]
            val curSpotL = spotG(r, 19.5f)
            val curAmbL = ambG(r, 9.0f)
            val curSpotD = spotG(r, 34.0f)
            val curAmbD = ambG(r, 16.0f)

            val dSpotL = max(0, curSpotL - prevSpotL)
            val dAmbL = max(0, curAmbL - prevAmbL)
            val dSpotD = max(0, curSpotD - prevSpotD)
            val dAmbD = max(0, curAmbD - prevAmbD)

            prevSpotL = curSpotL
            prevAmbL = curAmbL
            prevSpotD = curSpotD
            prevAmbD = curAmbD

            ShadowStep(
                expandDp = r,
                dySpotFactor = 1.05f,
                spotAlphaLight = dSpotL,
                ambAlphaLight = dAmbL,
                spotAlphaDark = dSpotD,
                ambAlphaDark = dAmbD,
            )
        }
    }

    fun drawFloatingCapsule(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float,
        shadowBaseColor: Int,
        isDark: Boolean,
        density: Float,
        shadowPaint: Paint,
        fillPaint: Paint,
        strokePaint: Paint,
        scratchRect: RectF,
    ) {
        if (right <= left || bottom <= top) return

        val srColor = Color.red(shadowBaseColor)
        val sgColor = Color.green(shadowBaseColor)
        val sbColor = Color.blue(shadowBaseColor)

        // 1. Draw 360-degree soft Gaussian SDF shadow layers from outside in
        val dyAmbPx = 0.25f * density
        for (step in SHADOW_STEPS) {
            val expandPx = step.expandDp * density
            val r = cornerRadius + expandPx

            val spotA = if (isDark) step.spotAlphaDark else step.spotAlphaLight
            if (spotA > 0) {
                val dySpotPx = step.dySpotFactor * density
                shadowPaint.color = Color.argb(spotA, srColor, sgColor, sbColor)
                scratchRect.set(
                    left - expandPx,
                    top - expandPx + dySpotPx,
                    right + expandPx,
                    bottom + expandPx + dySpotPx
                )
                canvas.drawRoundRect(scratchRect, r, r, shadowPaint)
            }

            val ambA = if (isDark) step.ambAlphaDark else step.ambAlphaLight
            if (ambA > 0) {
                shadowPaint.color = Color.argb(ambA, srColor, sgColor, sbColor)
                scratchRect.set(
                    left - expandPx,
                    top - expandPx + dyAmbPx,
                    right + expandPx,
                    bottom + expandPx + dyAmbPx
                )
                canvas.drawRoundRect(scratchRect, r, r, shadowPaint)
            }
        }

        // 2. Draw solid capsule surface fill (masks interior of shadow layers)
        fillPaint.color = fillColor
        scratchRect.set(left, top, right, bottom)
        canvas.drawRoundRect(scratchRect, cornerRadius, cornerRadius, fillPaint)

        // 3. Draw crisp anti-aliased hairline border
        if (strokeWidthPx > 0f && Color.alpha(strokeColor) > 0) {
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidthPx
            val half = strokeWidthPx * 0.5f
            scratchRect.set(left + half, top + half, right - half, bottom - half)
            val sr = max(0f, cornerRadius - half)
            canvas.drawRoundRect(scratchRect, sr, sr, strokePaint)
        }
    }
}

/**
 * Drawable for a single floating capsule (e.g. group_tab_container) where the shadow
 * is rendered inside the view's padding/inset area so it is never clipped by RenderNode.
 */
class SingleFloatingCapsuleDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val strokeWidthPx: Float,
    private val cornerRadiusPx: Float,
    private val insetLeft: Int,
    private val insetTop: Int,
    private val insetRight: Int,
    private val insetBottom: Int,
    private val isDark: Boolean,
    private val density: Float,
    private val shadowBaseColor: Int = Color.BLACK,
) : Drawable() {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scratchRect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val left = (b.left + insetLeft).toFloat()
        val top = (b.top + insetTop).toFloat()
        val right = (b.right - insetRight).toFloat()
        val bottom = (b.bottom - insetBottom).toFloat()
        val r = cornerRadiusPx.coerceAtMost((bottom - top) * 0.5f)

        FloatingCapsuleRenderer.drawFloatingCapsule(
            canvas = canvas,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadius = r,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidthPx = strokeWidthPx,
            shadowBaseColor = shadowBaseColor,
            isDark = isDark,
            density = density,
            shadowPaint = shadowPaint,
            fillPaint = fillPaint,
            strokePaint = strokePaint,
            scratchRect = scratchRect,
        )
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Drawable for the top toolbar floating capsules (Scheme 1: Left independent circular floating
 * button [|] + Right 5-in-1 action buttons floating capsule).
 */
class DualFloatingCapsulesDrawable(
    var fillColor: Int,
    var strokeColor: Int,
    var strokeWidthPx: Float,
    var isDark: Boolean,
    private val density: Float,
    var shadowBaseColor: Int = Color.BLACK,
) : Drawable() {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scratchRect = RectF()

    val leftCapsuleRect = RectF()
    val rightCapsuleRect = RectF()
    var cornerRadiusPx: Float = 18f * density

    fun updateCapsules(
        leftL: Float,
        leftT: Float,
        leftR: Float,
        leftB: Float,
        rightL: Float,
        rightT: Float,
        rightR: Float,
        rightB: Float,
        radius: Float,
    ) {
        var changed = false
        if (kotlin.math.abs(leftCapsuleRect.left - leftL) > 0.4f ||
            kotlin.math.abs(leftCapsuleRect.top - leftT) > 0.4f ||
            kotlin.math.abs(leftCapsuleRect.right - leftR) > 0.4f ||
            kotlin.math.abs(leftCapsuleRect.bottom - leftB) > 0.4f
        ) {
            leftCapsuleRect.set(leftL, leftT, leftR, leftB)
            changed = true
        }
        if (kotlin.math.abs(rightCapsuleRect.left - rightL) > 0.4f ||
            kotlin.math.abs(rightCapsuleRect.top - rightT) > 0.4f ||
            kotlin.math.abs(rightCapsuleRect.right - rightR) > 0.4f ||
            kotlin.math.abs(rightCapsuleRect.bottom - rightB) > 0.4f
        ) {
            rightCapsuleRect.set(rightL, rightT, rightR, rightB)
            changed = true
        }
        if (kotlin.math.abs(cornerRadiusPx - radius) > 0.4f) {
            cornerRadiusPx = radius
            changed = true
        }
        if (changed) {
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        if (!leftCapsuleRect.isEmpty) {
            val r = cornerRadiusPx.coerceAtMost(leftCapsuleRect.height() * 0.5f)
            FloatingCapsuleRenderer.drawFloatingCapsule(
                canvas = canvas,
                left = leftCapsuleRect.left,
                top = leftCapsuleRect.top,
                right = leftCapsuleRect.right,
                bottom = leftCapsuleRect.bottom,
                cornerRadius = r,
                fillColor = fillColor,
                strokeColor = strokeColor,
                strokeWidthPx = strokeWidthPx,
                shadowBaseColor = shadowBaseColor,
                isDark = isDark,
                density = density,
                shadowPaint = shadowPaint,
                fillPaint = fillPaint,
                strokePaint = strokePaint,
                scratchRect = scratchRect,
            )
        }
        if (!rightCapsuleRect.isEmpty) {
            val r = cornerRadiusPx.coerceAtMost(rightCapsuleRect.height() * 0.5f)
            FloatingCapsuleRenderer.drawFloatingCapsule(
                canvas = canvas,
                left = rightCapsuleRect.left,
                top = rightCapsuleRect.top,
                right = rightCapsuleRect.right,
                bottom = rightCapsuleRect.bottom,
                cornerRadius = r,
                fillColor = fillColor,
                strokeColor = strokeColor,
                strokeWidthPx = strokeWidthPx,
                shadowBaseColor = shadowBaseColor,
                isDark = isDark,
                density = density,
                shadowPaint = shadowPaint,
                fillPaint = fillPaint,
                strokePaint = strokePaint,
                scratchRect = scratchRect,
            )
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
