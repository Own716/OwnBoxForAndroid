package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
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

private class CapsuleBitmapCache {
    private var bitmap: Bitmap? = null
    private var dirty: Boolean = true
    val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun invalidate() {
        dirty = true
    }

    fun onBoundsChanged(width: Int, height: Int) {
        val bmp = bitmap
        if (bmp == null || bmp.isRecycled || bmp.width != width || bmp.height != height) {
            dirty = true
        }
    }

    fun draw(canvas: Canvas, bounds: Rect, render: (Canvas) -> Unit) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return

        // Guard against unexpected oversized bounds
        if (w > 4096 || h > 512) {
            render(canvas)
            return
        }

        var bmp = bitmap
        if (dirty || bmp == null || bmp.isRecycled || bmp.width != w || bmp.height != h) {
            // Allocate a fresh immutable snapshot instead of mutating or manually recycling
            // an in-flight bitmap that HardwareRenderer may still reference.
            val newBmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                bitmap = null
                render(canvas)
                return
            }
            val cacheCanvas = Canvas(newBmp)
            cacheCanvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            render(cacheCanvas)
            bitmap = newBmp
            bmp = newBmp
            dirty = false
        }

        canvas.drawBitmap(bmp, bounds.left.toFloat(), bounds.top.toFloat(), paint)
    }
}

/**
 * Drawable for a single floating capsule (e.g. group_tab_container) where the shadow
 * is rendered inside the view's padding/inset area so it is never clipped by RenderNode.
 */
class SingleFloatingCapsuleDrawable(
    private var fillColor: Int,
    private var strokeColor: Int,
    private var strokeWidthPx: Float,
    private var cornerRadiusPx: Float,
    private var insetLeft: Int,
    private var insetTop: Int,
    private var insetRight: Int,
    private var insetBottom: Int,
    private var isDark: Boolean,
    private val density: Float,
    private var shadowBaseColor: Int = Color.BLACK,
) : Drawable() {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scratchRect = RectF()
    private val bitmapCache = CapsuleBitmapCache()

    fun updateStyle(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float,
        cornerRadiusPx: Float,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        isDark: Boolean,
        shadowBaseColor: Int = Color.BLACK,
    ) {
        var changed = false
        if (this.fillColor != fillColor) { this.fillColor = fillColor; changed = true }
        if (this.strokeColor != strokeColor) { this.strokeColor = strokeColor; changed = true }
        if (kotlin.math.abs(this.strokeWidthPx - strokeWidthPx) > 0.05f) { this.strokeWidthPx = strokeWidthPx; changed = true }
        if (kotlin.math.abs(this.cornerRadiusPx - cornerRadiusPx) > 0.4f) { this.cornerRadiusPx = cornerRadiusPx; changed = true }
        if (this.insetLeft != insetLeft) { this.insetLeft = insetLeft; changed = true }
        if (this.insetTop != insetTop) { this.insetTop = insetTop; changed = true }
        if (this.insetRight != insetRight) { this.insetRight = insetRight; changed = true }
        if (this.insetBottom != insetBottom) { this.insetBottom = insetBottom; changed = true }
        if (this.isDark != isDark) { this.isDark = isDark; changed = true }
        if (this.shadowBaseColor != shadowBaseColor) { this.shadowBaseColor = shadowBaseColor; changed = true }
        if (changed) {
            bitmapCache.invalidate()
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        bitmapCache.onBoundsChanged(bounds.width(), bounds.height())
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        bitmapCache.draw(canvas, b) { targetCanvas ->
            val left = (b.left + insetLeft).toFloat()
            val top = (b.top + insetTop).toFloat()
            val right = (b.right - insetRight).toFloat()
            val bottom = (b.bottom - insetBottom).toFloat()
            val r = cornerRadiusPx.coerceAtMost((bottom - top) * 0.5f)

            FloatingCapsuleRenderer.drawFloatingCapsule(
                canvas = targetCanvas,
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
    }

    override fun setAlpha(alpha: Int) {
        if (bitmapCache.paint.alpha != alpha) {
            bitmapCache.paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapCache.paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Drawable for the top toolbar floating capsules (Scheme 1: Left independent circular floating
 * button [|] + Right 5-in-1 action buttons floating capsule).
 */
class DualFloatingCapsulesDrawable(
    fillColor: Int,
    strokeColor: Int,
    strokeWidthPx: Float,
    isDark: Boolean,
    private val density: Float,
    shadowBaseColor: Int = Color.BLACK,
) : Drawable() {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scratchRect = RectF()
    private val bitmapCache = CapsuleBitmapCache()

    var fillColor: Int = fillColor
        set(value) {
            if (field != value) {
                field = value
                bitmapCache.invalidate()
            }
        }
    var strokeColor: Int = strokeColor
        set(value) {
            if (field != value) {
                field = value
                bitmapCache.invalidate()
            }
        }
    var strokeWidthPx: Float = strokeWidthPx
        set(value) {
            if (kotlin.math.abs(field - value) > 0.05f) {
                field = value
                bitmapCache.invalidate()
            }
        }
    var isDark: Boolean = isDark
        set(value) {
            if (field != value) {
                field = value
                bitmapCache.invalidate()
            }
        }
    var shadowBaseColor: Int = shadowBaseColor
        set(value) {
            if (field != value) {
                field = value
                bitmapCache.invalidate()
            }
        }

    val leftCapsuleRect = RectF()
    val rightCapsuleRect = RectF()
    var cornerRadiusPx: Float = 18f * density

    fun updateStyle(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Float,
        shadowBaseColor: Int,
        isDark: Boolean,
    ) {
        var changed = false
        if (this.fillColor != fillColor) { this.fillColor = fillColor; changed = true }
        if (this.strokeColor != strokeColor) { this.strokeColor = strokeColor; changed = true }
        if (kotlin.math.abs(this.strokeWidthPx - strokeWidthPx) > 0.05f) { this.strokeWidthPx = strokeWidthPx; changed = true }
        if (this.shadowBaseColor != shadowBaseColor) { this.shadowBaseColor = shadowBaseColor; changed = true }
        if (this.isDark != isDark) { this.isDark = isDark; changed = true }
        if (changed) {
            bitmapCache.invalidate()
            invalidateSelf()
        }
    }

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
            bitmapCache.invalidate()
            invalidateSelf()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        bitmapCache.onBoundsChanged(bounds.width(), bounds.height())
    }

    override fun draw(canvas: Canvas) {
        if (leftCapsuleRect.isEmpty && rightCapsuleRect.isEmpty) return
        bitmapCache.draw(canvas, bounds) { targetCanvas ->
            if (!leftCapsuleRect.isEmpty) {
                val r = cornerRadiusPx.coerceAtMost(leftCapsuleRect.height() * 0.5f)
                FloatingCapsuleRenderer.drawFloatingCapsule(
                    canvas = targetCanvas,
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
                    canvas = targetCanvas,
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
    }

    override fun setAlpha(alpha: Int) {
        if (bitmapCache.paint.alpha != alpha) {
            bitmapCache.paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapCache.paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Pure-drawn same-color concentric safety ring + dynamic scroll feathering overlay
 * inside [group_tab_container] (under the outer hairline border, above [TabLayout]).
 *
 * Replaces `TabLayout.clipToOutline` (3dp inset RoundRect Outline) and
 * `requiresFadingEdge="horizontal"`, eliminating the GPU anti-aliasing inner arc
 * artifact `(` and `)` while keeping scrolling labels & sliding pill confined within
 * the 3dp concentric inner capsule.
 */
class TabCapsuleEdgeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var fillColor: Int = Color.TRANSPARENT
    private var innerGapPx: Int = 0
    private var strokeInsetPx: Float = 0f
    private var fadingEdgePx: Int = 0
    private var leftFraction: Float = 0f
    private var rightFraction: Float = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outerSafePath = Path()
    private val ringPath = Path().apply { fillType = Path.FillType.EVEN_ODD }

    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var pathsDirty: Boolean = true

    private var leftShader: LinearGradient? = null
    private var rightShader: LinearGradient? = null
    private var leftShaderAlphaKey: Int = -1
    private var rightShaderAlphaKey: Int = -1

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToOutline = false
        elevation = 0f
        setWillNotDraw(false)
    }

    fun updateStyle(
        fillColor: Int,
        innerGapPx: Int,
        strokeInsetPx: Float,
        fadingEdgePx: Int,
    ) {
        var changed = false
        if (this.fillColor != fillColor) {
            this.fillColor = fillColor
            leftShader = null
            rightShader = null
            leftShaderAlphaKey = -1
            rightShaderAlphaKey = -1
            changed = true
        }
        if (this.innerGapPx != innerGapPx) {
            this.innerGapPx = innerGapPx
            pathsDirty = true
            leftShader = null
            rightShader = null
            changed = true
        }
        if (kotlin.math.abs(this.strokeInsetPx - strokeInsetPx) > 0.05f) {
            this.strokeInsetPx = strokeInsetPx
            pathsDirty = true
            changed = true
        }
        if (this.fadingEdgePx != fadingEdgePx) {
            this.fadingEdgePx = fadingEdgePx
            leftShader = null
            rightShader = null
            changed = true
        }
        if (changed) {
            invalidate()
        }
    }

    fun updateScrollState(leftFraction: Float, rightFraction: Float) {
        val clampedL = leftFraction.coerceIn(0f, 1f)
        val clampedR = rightFraction.coerceIn(0f, 1f)
        if (kotlin.math.abs(this.leftFraction - clampedL) > 0.008f ||
            kotlin.math.abs(this.rightFraction - clampedR) > 0.008f
        ) {
            this.leftFraction = clampedL
            this.rightFraction = clampedR
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pathsDirty = true
        leftShader = null
        rightShader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || Color.alpha(fillColor) == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val inset = innerGapPx.toFloat().coerceIn(0f, h * 0.25f)
        val s = strokeInsetPx.coerceIn(0f, inset)
        val outerR = ((h - s * 2f) * 0.5f).coerceAtLeast(0f)
        val innerR = ((h - inset * 2f) * 0.5f).coerceAtLeast(0f)

        if (pathsDirty || cachedWidth != width || cachedHeight != height) {
            outerSafePath.reset()
            outerSafePath.addRoundRect(s, s, w - s, h - s, outerR, outerR, Path.Direction.CW)

            ringPath.reset()
            ringPath.fillType = Path.FillType.EVEN_ODD
            ringPath.addRoundRect(s, s, w - s, h - s, outerR, outerR, Path.Direction.CW)
            ringPath.addRoundRect(
                inset,
                inset,
                w - inset,
                h - inset,
                innerR,
                innerR,
                Path.Direction.CW,
            )

            cachedWidth = width
            cachedHeight = height
            pathsDirty = false
        }

        val fadeLen = fadingEdgePx.toFloat().coerceAtLeast(0f).coerceAtMost((w - inset * 2f) * 0.4f)
        if (fadeLen > 0f && (leftFraction > 0.01f || rightFraction > 0.01f)) {
            val saveCount = canvas.save()
            canvas.clipPath(outerSafePath)

            if (leftFraction > 0.01f) {
                val alphaMax = (255f * leftFraction).roundToInt().coerceIn(0, 255)
                if (leftShader == null || leftShaderAlphaKey != alphaMax) {
                    val alphaMidHigh = (225f * leftFraction).roundToInt().coerceIn(0, 255)
                    val alphaMidLow = (110f * leftFraction).roundToInt().coerceIn(0, 255)
                    leftShader = LinearGradient(
                        inset,
                        0f,
                        inset + fadeLen,
                        0f,
                        intArrayOf(
                            ColorUtils.setAlphaComponent(fillColor, alphaMax),
                            ColorUtils.setAlphaComponent(fillColor, alphaMidHigh),
                            ColorUtils.setAlphaComponent(fillColor, alphaMidLow),
                            ColorUtils.setAlphaComponent(fillColor, 0),
                        ),
                        floatArrayOf(0f, 0.22f, 0.58f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    leftShaderAlphaKey = alphaMax
                }
                fadePaint.shader = leftShader
                canvas.drawRect(s, s, inset + fadeLen, h - s, fadePaint)
            }

            if (rightFraction > 0.01f) {
                val alphaMax = (255f * rightFraction).roundToInt().coerceIn(0, 255)
                if (rightShader == null || rightShaderAlphaKey != alphaMax) {
                    val alphaMidHigh = (225f * rightFraction).roundToInt().coerceIn(0, 255)
                    val alphaMidLow = (110f * rightFraction).roundToInt().coerceIn(0, 255)
                    rightShader = LinearGradient(
                        w - inset - fadeLen,
                        0f,
                        w - inset,
                        0f,
                        intArrayOf(
                            ColorUtils.setAlphaComponent(fillColor, 0),
                            ColorUtils.setAlphaComponent(fillColor, alphaMidLow),
                            ColorUtils.setAlphaComponent(fillColor, alphaMidHigh),
                            ColorUtils.setAlphaComponent(fillColor, alphaMax),
                        ),
                        floatArrayOf(0f, 0.42f, 0.78f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    rightShaderAlphaKey = alphaMax
                }
                fadePaint.shader = rightShader
                canvas.drawRect(w - inset - fadeLen, s, w - s, h - s, fadePaint)
            }

            canvas.restoreToCount(saveCount)
        }

        if (inset > s) {
            ringPaint.color = fillColor
            canvas.drawPath(ringPath, ringPaint)
        }
    }
}
