package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Live throughput of the profile in flight: the download curve, then the upload curve, over time. */
class ThroughputChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val line = Path()
    private val area = Path()

    @ColorInt
    var downloadColor = 0xFF2196F3.toInt()

    @ColorInt
    var uploadColor = 0xFFFFB300.toInt()

    @ColorInt
    var gridColor = 0x33808080

    @ColorInt
    var labelColor = 0x8A808080.toInt()

    private var samples: List<SpeedSample> = emptyList()
    private var labelScale = 0.0
    private val gridLabels = arrayOfNulls<String>(GRID_LINES + 1)

    fun setSamples(list: List<SpeedSample>) {
        if (list === samples) return
        samples = list
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (128 * density).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val textHeight = labelPaint.textSize
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + textHeight + 2 * density
        val bottom = height - paddingBottom - textHeight - 4 * density
        if (right <= left || bottom <= top) return

        var maxBps = 0f
        for (sample in samples) maxBps = max(maxBps, sample.bps)
        val scale = niceCeil(max(maxBps * 1.1, MIN_SCALE_BPS))
        val duration = max(samples.lastOrNull()?.t ?: 0f, MIN_SECONDS)

        if (scale != labelScale) {
            labelScale = scale
            for (k in 1..GRID_LINES) gridLabels[k] = TestFormat.axisRate(scale * k / GRID_LINES)
        }
        gridPaint.color = gridColor
        labelPaint.color = labelColor
        labelPaint.textAlign = Paint.Align.LEFT
        for (k in 0..GRID_LINES) {
            val y = bottom - (bottom - top) * k / GRID_LINES
            canvas.drawLine(left, y, right, y, gridPaint)
            gridLabels[k]?.let { canvas.drawText(it, left, y - 2 * density, labelPaint) }
        }
        val axisY = bottom + 4 * density + textHeight
        canvas.drawText("0", left, axisY, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${duration.roundToInt()} s", right, axisY, labelPaint)

        fun x(t: Float) = left + (right - left) * (t / duration)
        fun y(bps: Float) = bottom - (bottom - top) * (bps / scale.toFloat()).coerceIn(0f, 1f)

        for (upload in BOOLEANS) {
            line.rewind()
            area.rewind()
            var first = true
            var lastX = 0f
            for (sample in samples) {
                if (sample.upload != upload) continue
                val px = x(sample.t)
                val py = y(sample.bps)
                if (first) {
                    line.moveTo(px, py)
                    area.moveTo(px, bottom)
                    area.lineTo(px, py)
                    first = false
                } else {
                    line.lineTo(px, py)
                    area.lineTo(px, py)
                }
                lastX = px
            }
            if (first) continue
            area.lineTo(lastX, bottom)
            area.close()
            val color = if (upload) uploadColor else downloadColor
            fillPaint.color = ColorUtils.setAlphaComponent(color, AREA_ALPHA)
            canvas.drawPath(area, fillPaint)
            linePaint.color = color
            canvas.drawPath(line, linePaint)
        }

        samples.lastOrNull()?.let {
            fillPaint.color = if (it.upload) uploadColor else downloadColor
            canvas.drawCircle(x(it.t), y(it.bps), 3.5f * density, fillPaint)
        }
    }

    private companion object {
        const val GRID_LINES = 2
        const val MIN_SCALE_BPS = 1e6
        const val MIN_SECONDS = 5f
        const val AREA_ALPHA = 0x30
        val BOOLEANS = booleanArrayOf(false, true)

        /** 1, 2 or 5 times a power of ten, at least [value]. */
        fun niceCeil(value: Double): Double {
            val base = 10.0.pow(floor(log10(value)))
            val f = value / base
            val nice = when {
                f <= 1 -> 1.0
                f <= 2 -> 2.0
                f <= 5 -> 5.0
                else -> 10.0
            }
            return nice * base
        }
    }
}
