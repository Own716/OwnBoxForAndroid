package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Latency distribution: five bars per desktop colour band (≤100, ≤300, >300 ms, coloured like
 * ProxyEntity.latencyColor), then the failed results and, when present, the VPN connect-only ones.
 */
class LatencyHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val bar = RectF()

    private val binColors = IntArray(LatencyHistogram.BIN_COUNT) {
        TestFormat.latencyColor(LatencyHistogram.sampleOf(it))
    }
    private val failedColor = TestFormat.latencyColor(-1)
    private val connectOnlyColor = TestFormat.latencyColor(ProxyEntity.LATENCY_CONNECT_ONLY)
    private val failedLabel = context.getString(R.string.test_panel_axis_failed)
    private val connectOnlyLabel = context.getString(R.string.test_panel_axis_connect_only)

    @ColorInt
    var labelColor = 0x8A808080.toInt()
        set(value) {
            field = value
            invalidate()
        }

    @ColorInt
    var axisColor = 0x33808080
        set(value) {
            field = value
            invalidate()
        }

    private var data = LatencyHistogram()

    fun setData(histogram: LatencyHistogram) {
        if (histogram == data) return
        data = histogram
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (96 * density).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val bins = data.bins
        val n = bins.size
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val textHeight = labelPaint.textSize
        val baseY = height - paddingBottom - textHeight - 6 * density
        val topY = paddingTop + textHeight + 4 * density
        if (right <= left || baseY <= topY) return

        val extraSlots = if (data.connectOnly > 0) 2 else 1
        val slotW = (right - left) / (n + GAP_SLOTS + extraSlots)
        val barW = slotW * 0.74f
        val radius = min(barW / 2, 2 * density)
        var maxCount = max(data.failed, data.connectOnly)
        for (count in bins) maxCount = max(maxCount, count)
        maxCount = max(maxCount, 1)
        val plotH = baseY - topY

        fun drawBar(slot: Float, count: Int, @ColorInt color: Int) {
            if (count <= 0) return
            val x = left + slot * slotW + (slotW - barW) / 2
            val h = max(plotH * count / maxCount, 2 * density)
            bar.set(x, baseY - h, x + barW, baseY)
            barPaint.color = color
            canvas.drawRoundRect(bar, radius, radius, barPaint)
            val text = count.toString()
            if (labelPaint.measureText(text) <= slotW + 2 * density) {
                labelPaint.color = labelColor
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(text, x + barW / 2, baseY - h - 2 * density, labelPaint)
            }
        }

        for (i in 0 until n) drawBar(i.toFloat(), bins[i], binColors[i])
        val failedSlot = n + GAP_SLOTS
        drawBar(failedSlot, data.failed, failedColor)
        if (data.connectOnly > 0) drawBar(failedSlot + 1, data.connectOnly, connectOnlyColor)

        axisPaint.color = axisColor
        canvas.drawLine(left, baseY, left + n * slotW, baseY, axisPaint)
        canvas.drawLine(left + failedSlot * slotW, baseY, right, baseY, axisPaint)

        // Ticks at the band edges (bins 5 and 10) and at 1 s; the last bin is open ("2s+").
        val labelY = baseY + 4 * density + textHeight
        labelPaint.color = labelColor
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0", left, labelY, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        for ((boundary, text) in TICKS) {
            val x = left + boundary * slotW
            canvas.drawLine(x, baseY, x, baseY + 3 * density, axisPaint)
            canvas.drawText(text, x, labelY, labelPaint)
        }
        canvas.drawText(OPEN_BIN_LABEL, left + (n - 0.5f) * slotW, labelY, labelPaint)
        canvas.drawText(failedLabel, left + (failedSlot + 0.5f) * slotW, labelY, labelPaint)
        if (data.connectOnly > 0) {
            canvas.drawText(connectOnlyLabel, left + (failedSlot + 1.5f) * slotW, labelY, labelPaint)
        }
    }

    private companion object {
        const val GAP_SLOTS = 0.8f
        val TICKS = listOf(5 to "100", 10 to "300", 13 to "1s")
        const val OPEN_BIN_LABEL = "2s+"
    }
}
