package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import kotlin.math.max

/**
 * Compact stacked bar chart for daily traffic trends (proxy bottom, direct top),
 * supporting tap-to-inspect a day bar and dynamic OwnBox theming.
 */
class DailyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class BarItem(
        val day: String,
        val shortLabel: String,
        val proxyBytes: Long,
        val directBytes: Long,
    ) {
        val totalBytes: Long get() = proxyBytes + directBytes
    }

    var onBarSelected: ((BarItem) -> Unit)? = null

    private var items: List<BarItem> = emptyList()
    private var proxyColor: Int = 0xFF2A78D6.toInt()
    private var directColor: Int = 0xFF7D8796.toInt()
    private var selectedIndex: Int = -1
    private val barTouchRects = ArrayList<RectF>()

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val rectTmp = RectF()

    fun setData(items: List<BarItem>, proxyColor: Int, directColor: Int) {
        this.items = items
        this.proxyColor = proxyColor
        this.directColor = directColor
        if (selectedIndex >= items.size) selectedIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val onSurface = MaterialColors.getColor(context, R.attr.colorOnSurface, Color.BLACK)
        val secondary = MaterialColors.getColor(context, android.R.attr.textColorSecondary, Color.GRAY)
        gridPaint.color = ColorUtils.setAlphaComponent(secondary, 48)
        textPaint.color = secondary
        selectPaint.color = onSurface

        val topPad = 16f * density
        val bottomPad = 18f * density
        val sidePad = 4f * density
        val plotLeft = sidePad
        val plotRight = w - sidePad
        val plotTop = topPad
        val plotBottom = h - bottomPad
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)

        canvas.drawLine(plotLeft, plotTop, plotRight, plotTop, gridPaint)
        canvas.drawLine(plotLeft, plotTop + plotH * 0.5f, plotRight, plotTop + plotH * 0.5f, gridPaint)
        gridPaint.pathEffect = null
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gridPaint)
        gridPaint.pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)

        if (items.isEmpty()) return

        val peak = max(items.maxOf { it.totalBytes }, 1024L).toFloat()
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(Formatter.formatShortFileSize(context, peak.toLong()), plotLeft, topPad - 4f * density, textPaint)

        val count = items.size
        val slotW = plotW / count
        val barW = (slotW * 0.64f).coerceIn(3f * density, 18f * density)
        val radius = (barW * 0.25f).coerceAtMost(3f * density)

        barTouchRects.clear()
        val labelStep = when {
            count <= 7 -> 1
            count <= 15 -> 3
            else -> 6
        }

        for (i in items.indices) {
            val item = items[i]
            val cx = plotLeft + slotW * (i + 0.5f)
            val left = cx - barW / 2f
            val right = cx + barW / 2f
            barTouchRects.add(RectF(plotLeft + slotW * i, 0f, plotLeft + slotW * (i + 1), h))

            val pH = (item.proxyBytes / peak) * plotH
            val dH = (item.directBytes / peak) * plotH
            val totalH = (pH + dH).let { if (item.totalBytes > 0L) it.coerceAtLeast(2f * density) else 0f }

            if (totalH > 0f) {
                val barTop = (plotBottom - totalH).coerceAtLeast(plotTop)
                if (item.proxyBytes > 0L) {
                    val proxyTop = if (item.directBytes > 0L) (plotBottom - pH).coerceAtLeast(barTop) else barTop
                    barPaint.color = proxyColor
                    rectTmp.set(left, proxyTop, right, plotBottom)
                    canvas.drawRoundRect(rectTmp, if (item.directBytes == 0L) radius else 0f, if (item.directBytes == 0L) radius else 0f, barPaint)
                }
                if (item.directBytes > 0L) {
                    val directBottom = if (item.proxyBytes > 0L) (plotBottom - pH).coerceAtLeast(barTop) else plotBottom
                    barPaint.color = directColor
                    rectTmp.set(left, barTop, right, directBottom)
                    canvas.drawRoundRect(rectTmp, radius, radius, barPaint)
                }
                if (i == selectedIndex) {
                    rectTmp.set(left - 1.5f * density, barTop - 1.5f * density, right + 1.5f * density, plotBottom)
                    canvas.drawRoundRect(rectTmp, radius, radius, selectPaint)
                }
            }

            if (i % labelStep == 0 || i == count - 1) {
                // Skip last label if too close to previous label
                if (i == count - 1 && count > 1 && i % labelStep != 0 && (i % labelStep) < labelStep / 2) {
                    continue
                }
                textPaint.textAlign = when (i) {
                    0 -> Paint.Align.LEFT
                    count - 1 -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                val lx = when (i) {
                    0 -> plotLeft
                    count - 1 -> plotRight
                    else -> cx
                }
                canvas.drawText(item.shortLabel, lx, h - 3f * density, textPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val hit = barTouchRects.indexOfFirst { x in it.left..it.right }
                if (hit in items.indices) {
                    selectedIndex = hit
                    invalidate()
                    onBarSelected?.invoke(items[hit])
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
