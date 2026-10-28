package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isGone
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import kotlin.math.max

/**
 * The type line of a profile card: the type, the test result right after it and the traffic at the end. When the
 * three do not fit on one line, the result takes a line of its own above them and wraps there between its items.
 * Decided while measuring, so a bound card is laid out once.
 */
class TypeLineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gap = dp2px(8)
    private val progressGap = dp2px(6)
    private val lineGap = dp2px(4)

    private lateinit var type: TextView
    private lateinit var progress: View
    private lateinit var result: TextView
    private lateinit var traffic: TextView

    /** The last measure kept the result beside the type; else its own line is [resultLineHeight] tall. */
    private var inline = true
    private var resultLineHeight = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        type = findViewById(R.id.profile_type)
        progress = findViewById(R.id.test_progress)
        result = findViewById(R.id.profile_result)
        traffic = findViewById(R.id.traffic_text)
    }

    override fun shouldDelayChildPressedState() = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val content = if (bounded) {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        } else Int.MAX_VALUE
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        fun upTo(size: Int) =
            if (bounded) MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.AT_MOST) else free

        type.measure(upTo(content), free)
        if (!traffic.isGone) traffic.measure(upTo(content - type.measuredWidth - gap), free)
        if (!progress.isGone) progress.measure(free, free)
        if (!result.isGone) result.measure(free, free)
        val trafficWidth = if (traffic.isGone) 0 else gap + traffic.measuredWidth
        val resultWidth = resultWidth()
        inline = resultWidth == 0 || type.measuredWidth + gap + resultWidth + trafficWidth <= content

        val width: Int
        val height: Int
        if (inline) {
            resultLineHeight = 0
            width = type.measuredWidth + (if (resultWidth > 0) gap + resultWidth else 0) + trafficWidth
            height = max(textHeight(type, result, traffic), heightOf(progress))
        } else {
            if (!result.isGone) result.measure(upTo(content - leadWidth()), free)
            resultLineHeight = max(heightOf(progress), heightOf(result))
            width = max(resultWidth(), type.measuredWidth + trafficWidth)
            height = resultLineHeight + lineGap + textHeight(type, traffic)
        }
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val end = r - l - paddingRight
        val contentHeight = b - t - paddingTop - paddingBottom

        // x counts from the start edge, the right one in RTL
        fun place(child: View, x: Int, top: Int) {
            val left = if (rtl) end - x - child.measuredWidth else paddingLeft + x
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }

        val resultX = if (inline) type.measuredWidth + gap else 0
        val resultHeight = if (inline) contentHeight else resultLineHeight
        if (!progress.isGone) place(progress, resultX, paddingTop + (resultHeight - progress.measuredHeight) / 2)
        if (!result.isGone) {
            val offset = if (inline) {
                textTop(result, resultHeight, type, result, traffic)
            } else {
                (resultHeight - result.measuredHeight) / 2
            }
            place(result, resultX + leadWidth(), paddingTop + offset)
        }

        val lineTop = if (inline) paddingTop else paddingTop + resultLineHeight + lineGap
        val lineHeight = if (inline) contentHeight else contentHeight - resultLineHeight - lineGap
        val line = if (inline) arrayOf(type, result, traffic) else arrayOf(type, traffic)
        place(type, 0, lineTop + textTop(type, lineHeight, *line))
        if (!traffic.isGone) {
            val x = end - paddingLeft - traffic.measuredWidth
            place(traffic, x, lineTop + textTop(traffic, lineHeight, *line))
        }
    }

    /** The spinner and its gap before the result text. */
    private fun leadWidth() =
        if (progress.isGone) 0 else progress.measuredWidth + if (result.isGone) 0 else progressGap

    private fun resultWidth() = leadWidth() + if (result.isGone) 0 else result.measuredWidth

    private fun heightOf(view: View) = if (view.isGone) 0 else view.measuredHeight

    /** The height of the shown [views] on one baseline: emoji make a text taller than its neighbours. */
    private fun textHeight(vararg views: TextView): Int {
        val shown = views.filter { !it.isGone }
        if (shown.isEmpty()) return 0
        return shown.maxOf { it.baseline } + shown.maxOf { it.measuredHeight - it.baseline }
    }

    /** The top of [view] in a line [height] tall where the shown [views] share one baseline, centred. */
    private fun textTop(view: TextView, height: Int, vararg views: TextView): Int {
        val shown = views.filter { !it.isGone }
        if (shown.isEmpty()) return 0
        return (height - textHeight(*views)) / 2 + shown.maxOf { it.baseline } - view.baseline
    }
}
