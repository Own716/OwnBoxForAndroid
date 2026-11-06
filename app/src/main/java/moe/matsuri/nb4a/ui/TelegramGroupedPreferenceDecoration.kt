package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

/**
 * TelegramGroupedPreferenceDecoration
 *
 * 为 PreferenceFragmentCompat 的 RecyclerView 呈现纯正 Telegram 风格的聚合卡片：
 * 1. 24dp 极致圆角容器 + 1dp 轮廓描边（全主题与 AMOLED 纯黑完美自适应）
 * 2. 卡片内项与项之间带有文字对齐的内嵌细线分割（Hairline Divider，最后一项不画线）
 * 3. 单元行水波纹点击反馈在卡片四个角按 24dp 圆角平滑裁切
 * 4. 分类小标题置于卡片外部，采用主题主色加粗显示
 */
class TelegramGroupedPreferenceDecoration(private val context: Context) : RecyclerView.ItemDecoration() {

    private val cardRadius = context.resources.getDimension(R.dimen.telegram_card_corner_radius)
    private val strokeWidth = context.resources.getDimension(R.dimen.card_stroke_width)
    private val marginHorizontal = context.resources.getDimensionPixelSize(R.dimen.telegram_card_margin_horizontal)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = this@TelegramGroupedPreferenceDecoration.strokeWidth
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = dp2px(1).toFloat()
    }

    private val cardRect = RectF()

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val pref = adapter.getItem(pos) ?: return

        if (pref is PreferenceCategory) {
            outRect.left = dp2px(16)
            outRect.right = dp2px(16)
            outRect.top = if (pos == 0) dp2px(8) else dp2px(10)
            outRect.bottom = dp2px(2)
            view.background = null

            // 动态染色分类小标题
            val titleView = view.findViewById<TextView>(android.R.id.title)
            titleView?.setTextColor(Theme.getPrimaryColor(context))
        } else {
            outRect.left = marginHorizontal
            outRect.right = marginHorizontal

            val isFirst = pos == 0 || adapter.getItem(pos - 1) is PreferenceCategory
            val isLast = pos == adapter.itemCount - 1 || adapter.getItem(pos + 1) is PreferenceCategory

            outRect.top = if (isFirst) dp2px(2) else 0
            outRect.bottom = if (isLast) dp2px(8) else 0

            applyGroupRipple(view, isFirst, isLast)

            view.minimumHeight = dp2px(48)
            val targetPadV = dp2px(4)
            if (view.paddingTop != targetPadV || view.paddingBottom != targetPadV) {
                view.setPaddingRelative(view.paddingStart, targetPadV, view.paddingEnd, targetPadV)
            }

            val titleView = view.findViewById<TextView>(android.R.id.title)
            val textContainer = titleView?.parent as? View
            if (textContainer != null && textContainer !== view) {
                val targetTextPadV = dp2px(4)
                if (textContainer.paddingTop > targetTextPadV || textContainer.paddingBottom > targetTextPadV) {
                    textContainer.setPaddingRelative(
                        textContainer.paddingStart,
                        targetTextPadV,
                        textContainer.paddingEnd,
                        targetTextPadV
                    )
                }
            }
        }
    }

    private fun applyGroupRipple(view: View, isFirst: Boolean, isLast: Boolean) {
        val rippleColor = context.getColorAttr(R.attr.colorControlHighlight)
        val radii = FloatArray(8)
        if (isFirst && isLast) {
            radii.fill(cardRadius)
        } else if (isFirst) {
            radii[0] = cardRadius; radii[1] = cardRadius
            radii[2] = cardRadius; radii[3] = cardRadius
        } else if (isLast) {
            radii[4] = cardRadius; radii[5] = cardRadius
            radii[6] = cardRadius; radii[7] = cardRadius
        }

        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(Color.WHITE)
        }
        view.background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        if (parent.childCount == 0) return

        val surfaceColor = context.getColorAttr(R.attr.cardElevatedSurfaceColor)
        val strokeColor = context.getColor(R.color.card_stroke)

        fillPaint.color = surfaceColor
        strokePaint.color = strokeColor

        var groupFirstChild: View? = null
        var groupLastChild: View? = null
        var isGroupFirstOnScreen = false
        var isGroupLastOnScreen = false

        fun flushGroup() {
            val first = groupFirstChild ?: return
            val last = groupLastChild ?: return

            val left = first.left.toFloat()
            val right = first.right.toFloat()

            // 滚动到屏幕边缘时的平滑边界延展处理
            val top = if (isGroupFirstOnScreen) first.top.toFloat() else (first.top.toFloat() - cardRadius * 2)
            val bottom = if (isGroupLastOnScreen) last.bottom.toFloat() else (last.bottom.toFloat() + cardRadius * 2)

            cardRect.set(left, top, right, bottom)
            c.drawRoundRect(cardRect, cardRadius, cardRadius, fillPaint)
            c.drawRoundRect(cardRect, cardRadius, cardRadius, strokePaint)

            groupFirstChild = null
            groupLastChild = null
            isGroupFirstOnScreen = false
            isGroupLastOnScreen = false
        }

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val pref = adapter.getItem(pos) ?: continue

            if (pref is PreferenceCategory) {
                flushGroup()
                continue
            }

            val isFirst = pos == 0 || adapter.getItem(pos - 1) is PreferenceCategory
            val isLast = pos == adapter.itemCount - 1 || adapter.getItem(pos + 1) is PreferenceCategory

            if (groupFirstChild == null) {
                groupFirstChild = child
                isGroupFirstOnScreen = isFirst
            }
            groupLastChild = child
            isGroupLastOnScreen = isLast

            if (isLast) {
                flushGroup()
            }
        }
        flushGroup()
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        dividerPaint.color = context.getColor(R.color.card_stroke)

        val insetStart = dp2px(60).toFloat()
        val insetEnd = dp2px(16).toFloat()

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val pref = adapter.getItem(pos) ?: continue
            if (pref is PreferenceCategory) continue

            val isLast = pos == adapter.itemCount - 1 || adapter.getItem(pos + 1) is PreferenceCategory
            if (!isLast) {
                val y = child.bottom.toFloat()
                val startX = child.left.toFloat() + insetStart
                val endX = child.right.toFloat() - insetEnd
                c.drawLine(startX, y, endX, y, dividerPaint)
            }
        }
    }

    companion object {
        fun applyTo(recyclerView: RecyclerView) {
            // 避免重复添加
            for (i in 0 until recyclerView.itemDecorationCount) {
                if (recyclerView.getItemDecorationAt(i) is TelegramGroupedPreferenceDecoration) {
                    return
                }
            }
            recyclerView.addItemDecoration(TelegramGroupedPreferenceDecoration(recyclerView.context))
        }
    }
}
