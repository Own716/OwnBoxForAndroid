package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/** Download and upload bars of one ranked speed result, relative to the session's best. */
class SpeedBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    @ColorInt
    var downloadColor = 0xFF2196F3.toInt()

    @ColorInt
    var uploadColor = 0xFFFFB300.toInt()

    @ColorInt
    var trackColor = 0x1F808080

    private var download = -1f
    private var upload = -1f

    /** Fractions in 0..1; a negative fraction hides that bar. */
    fun setFractions(download: Float, upload: Float) {
        if (this.download == download && this.upload == upload) return
        val relayout = (this.download < 0) != (download < 0) || (this.upload < 0) != (upload < 0)
        this.download = download
        this.upload = upload
        if (relayout) requestLayout()
        invalidate()
    }

    private val bars get() = (if (download >= 0) 1 else 0) + (if (upload >= 0) 1 else 0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = bars.coerceAtLeast(1)
        val desired = (count * BAR_DP * density + (count - 1) * GAP_DP * density).roundToInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val h = BAR_DP * density
        var top = paddingTop.toFloat()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0..1) {
            val fraction = if (i == 0) download else upload
            if (fraction < 0) continue
            val radius = h / 2
            rect.set(left, top, right, top + h)
            paint.color = trackColor
            canvas.drawRoundRect(rect, radius, radius, paint)
            val filled = (right - left) * fraction.coerceIn(0f, 1f)
            if (filled > 0f) {
                if (rtl) rect.set(right - filled, top, right, top + h) else rect.set(left, top, left + filled, top + h)
                paint.color = if (i == 0) downloadColor else uploadColor
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            top += h + GAP_DP * density
        }
    }

    private companion object {
        const val BAR_DP = 4
        const val GAP_DP = 2
    }
}
