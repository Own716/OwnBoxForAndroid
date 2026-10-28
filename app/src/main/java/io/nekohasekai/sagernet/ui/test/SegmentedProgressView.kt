package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rounded bar split into working / failed / testing segments over a grey pending track. With a [TestingProgress]
 * the testing segment fills its slot over time instead of showing whole.
 */
class SegmentedProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private val minSegment = 2 * density

    @ColorInt
    var okColor = 0xFF43A047.toInt()

    @ColorInt
    var failedColor = 0xFFE53935.toInt()

    @ColorInt
    var testingColor = 0x66808080

    @ColorInt
    var trackColor = 0x1F808080

    private var ok = 0
    private var failed = 0
    private var testing = 0
    private var total = 0
    private var indeterminate = false
    private var progress: TestingProgress? = null

    fun setCounts(
        ok: Int, failed: Int, testing: Int, total: Int, indeterminate: Boolean, progress: TestingProgress? = null,
    ) {
        if (this.ok == ok && this.failed == failed && this.testing == testing && this.total == total &&
            this.indeterminate == indeterminate && this.progress == progress
        ) return
        this.ok = ok
        this.failed = failed
        this.testing = testing
        this.total = total
        this.indeterminate = indeterminate
        this.progress = progress
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (6 * density).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val w = right - left
        if (w <= 0f || bottom <= top) return
        bounds.set(left, top, right, bottom)
        val radius = (bottom - top) / 2
        clip.rewind()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, bounds.centerX(), bounds.centerY())
        paint.color = trackColor
        canvas.drawRect(bounds, paint)
        if (indeterminate) {
            val phase = (SystemClock.uptimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
            val segment = w * 0.3f
            val x = left - segment + (w + segment) * phase
            paint.color = testingColor
            canvas.drawRect(x, top, x + segment, bottom, paint)
            postInvalidateOnAnimation()
        } else if (total > 0) {
            var x = left
            x = segment(canvas, x, right, top, bottom, ok, w * ok / total, okColor)
            x = segment(canvas, x, right, top, bottom, failed, w * failed / total, failedColor)
            val slot = w * testing / total
            val p = progress
            val now = SystemClock.elapsedRealtime()
            segment(canvas, x, right, top, bottom, testing, if (p == null) slot else slot * p.at(now), testingColor)
            if (p != null && testing > 0 && !p.settled(now)) {
                // Redrawn once the growing part has moved by about a pixel.
                val pixelsPerMs = slot * (p.to - p.from) / p.durationMs
                postInvalidateDelayed(if (pixelsPerMs > 0f) max(FRAME_MS, (1f / pixelsPerMs).toLong()) else FRAME_MS)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun segment(
        canvas: Canvas, x: Float, right: Float, top: Float, bottom: Float, count: Int, share: Float,
        @ColorInt color: Int,
    ): Float {
        if (count <= 0 || x >= right) return x
        val end = min(x + max(share, minSegment), right)
        paint.color = color
        canvas.drawRect(x, top, end, bottom, paint)
        return end
    }

    private companion object {
        const val SWEEP_MS = 1400L
        const val FRAME_MS = 16L
    }
}
