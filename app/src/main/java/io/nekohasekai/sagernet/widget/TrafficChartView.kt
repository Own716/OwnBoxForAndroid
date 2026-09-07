package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.*
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class TrafficChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 60
    private val upHistory = LongArray(maxPoints)
    private val downHistory = LongArray(maxPoints)
    private var pointCount = 0

    var peakUp: Long = 0
        private set
    var peakDown: Long = 0
        private set
    var currentUp: Long = 0
        private set
    var currentDown: Long = 0
        private set

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        textSize = 28f
    }

    private val upLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB74D") // Orange/Amber for Up
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val upFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val downLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DD0E1") // Cyan/Blue for Down
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val downFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val upPath = Path()
    private val downPath = Path()
    private val upFillPath = Path()
    private val downFillPath = Path()

    fun addSpeed(up: Long, down: Long) {
        currentUp = up
        currentDown = down
        if (up > peakUp) peakUp = up
        if (down > peakDown) peakDown = down

        // Shift left and append
        System.arraycopy(upHistory, 1, upHistory, 0, maxPoints - 1)
        upHistory[maxPoints - 1] = up

        System.arraycopy(downHistory, 1, downHistory, 0, maxPoints - 1)
        downHistory[maxPoints - 1] = down

        if (pointCount < maxPoints) pointCount++
        postInvalidate()
    }

    fun clearData() {
        upHistory.fill(0)
        downHistory.fill(0)
        pointCount = 0
        peakUp = 0
        peakDown = 0
        currentUp = 0
        currentDown = 0
        postInvalidate()
    }

    private fun isDarkTheme(): Boolean {
        val mode = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val isDark = isDarkTheme()
        gridPaint.color = if (isDark) Color.parseColor("#26FFFFFF") else Color.parseColor("#1F000000")
        textPaint.color = if (isDark) Color.parseColor("#99FFFFFF") else Color.parseColor("#99000000")

        val paddingBottom = 40f
        val paddingTop = 20f
        val chartHeight = h - paddingTop - paddingBottom

        // Draw 3 horizontal grid lines
        for (i in 0..2) {
            val y = paddingTop + (chartHeight / 2) * i
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Draw time labels
        canvas.drawText("-60s", 10f, h - 10f, textPaint)
        canvas.drawText("-30s", w / 2 - 25f, h - 10f, textPaint)
        canvas.drawText("Now", w - 65f, h - 10f, textPaint)

        // Find max value in history
        var maxSpeed = 1024L * 10 // minimum 10 KB/s scale
        for (i in 0 until maxPoints) {
            if (upHistory[i] > maxSpeed) maxSpeed = upHistory[i]
            if (downHistory[i] > maxSpeed) maxSpeed = downHistory[i]
        }

        // Speed label on top left
        val speedUnit = Formatter.formatFileSize(context, maxSpeed) + "/s"
        canvas.drawText(speedUnit, 10f, paddingTop + 26f, textPaint)

        if (pointCount < 2) return

        val stepX = w / (maxPoints - 1)
        upPath.reset()
        downPath.reset()
        upFillPath.reset()
        downFillPath.reset()

        upFillPath.moveTo(0f, paddingTop + chartHeight)
        downFillPath.moveTo(0f, paddingTop + chartHeight)

        for (i in 0 until maxPoints) {
            val x = i * stepX
            val upY = paddingTop + chartHeight - (upHistory[i].toFloat() / maxSpeed) * chartHeight
            val downY = paddingTop + chartHeight - (downHistory[i].toFloat() / maxSpeed) * chartHeight

            if (i == 0) {
                upPath.moveTo(x, upY)
                downPath.moveTo(x, downY)
            } else {
                upPath.lineTo(x, upY)
                downPath.lineTo(x, downY)
            }
            upFillPath.lineTo(x, upY)
            downFillPath.lineTo(x, downY)
        }

        upFillPath.lineTo(w, paddingTop + chartHeight)
        upFillPath.close()

        downFillPath.lineTo(w, paddingTop + chartHeight)
        downFillPath.close()

        // Gradients
        upFillPaint.shader = LinearGradient(
            0f, paddingTop, 0f, paddingTop + chartHeight,
            Color.parseColor("#4DFFB74D"), Color.parseColor("#05FFB74D"),
            Shader.TileMode.CLAMP
        )
        downFillPaint.shader = LinearGradient(
            0f, paddingTop, 0f, paddingTop + chartHeight,
            Color.parseColor("#4D4DD0E1"), Color.parseColor("#054DD0E1"),
            Shader.TileMode.CLAMP
        )

        // Draw fills then strokes
        canvas.drawPath(downFillPath, downFillPaint)
        canvas.drawPath(upFillPath, upFillPaint)
        canvas.drawPath(downPath, downLinePaint)
        canvas.drawPath(upPath, upLinePaint)
    }
}