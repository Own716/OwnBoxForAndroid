package io.nekohasekai.sagernet.ui.motion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.motion.SmoothMotion.performSubtleHaptic
import io.nekohasekai.sagernet.ui.motion.SmoothMotion.playTapPop
import io.nekohasekai.sagernet.ui.motion.SmoothMotion.showSmooth
import io.nekohasekai.sagernet.utils.Theme

/**
 * Interactive 5-bar rising stepped intensity picker dialog (0 = Off, 1..5 stepped bars)
 * with live tap-to-preview bounce feedback directly inside the dialog.
 */
object MotionIntensityDialog {

    private const val RECOMMENDED_LEVEL = 3

    fun formatSummary(context: Context, level: Int = SmoothMotion.level()): String {
        val clamped = level.coerceIn(0, 5)
        val labels = context.resources.getStringArray(R.array.motion_intensity_levels)
        val label = labels.getOrElse(clamped) { labels[0] }
        if (clamped == 0) return label
        val bars = buildString(5) {
            for (i in 1..5) append(if (i <= clamped) '▮' else '▯')
        }
        return "$bars  $label"
    }

    fun show(
        context: Context,
        anchor: View? = null,
        onConfirm: (level: Int) -> Unit,
    ) {
        val primary = context.getColorAttr(R.attr.colorPrimary)
        val textPrimary = context.getColorAttr(android.R.attr.textColorPrimary)
        val textSecondary = context.getColorAttr(android.R.attr.textColorSecondary)
        val surfaceTray = context.getColorAttr(R.attr.popupMenuSurfaceColor)
        val strokeTray = context.getColorAttr(R.attr.popupMenuStrokeColor)
        val isDark = Theme.isBlackTheme(context) || Theme.usingNightMode(context)
        val density = context.resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        val labels = context.resources.getStringArray(R.array.motion_intensity_levels)
        val stepLabels = context.resources.getStringArray(R.array.motion_intensity_short_labels)
        var selected = DataStore.motionEffectLevel.coerceIn(0, 5)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        }

        val hintView = TextView(context).apply {
            text = context.getString(R.string.motion_picker_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(12f))
        }
        root.addView(hintView)

        val statusView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12f))
        }
        root.addView(statusView)

        val barRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(dp(10f), dp(16f), dp(10f), dp(12f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(surfaceTray)
                setStroke(dp(1f).coerceAtLeast(1), strokeTray)
            }
        }

        val barViews = ArrayList<View>(5)
        val barCaptions = ArrayList<TextView>(5)
        val offPill = TextView(context)

        fun updateVisuals() {
            val labelText = labels.getOrElse(selected) { labels[0] }
            statusView.text = if (selected == 0) {
                labelText
            } else {
                "$selected 档 · $labelText"
            }
            statusView.setTextColor(if (selected == 0) textSecondary else primary)

            val inactiveColor = if (isDark) Color.argb(58, 255, 255, 255) else Color.argb(38, 15, 23, 42)
            for (i in 0 until 5) {
                val lvl = i + 1
                val active = selected >= lvl
                val isExact = selected == lvl
                val fill = when {
                    !active -> inactiveColor
                    isExact -> primary
                    else -> ColorUtils.setAlphaComponent(primary, 190)
                }
                barViews[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 9f * density
                    setColor(fill)
                }
                barCaptions[i].setTextColor(if (isExact) primary else if (active) textPrimary else textSecondary)
                barCaptions[i].setTypeface(null, if (isExact) Typeface.BOLD else Typeface.NORMAL)
            }

            val isOff = selected == 0
            val offBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 99f * density
                if (isOff) {
                    setColor(ColorUtils.setAlphaComponent(primary, if (isDark) 52 else 28))
                    setStroke(dp(1.2f).coerceAtLeast(1), primary)
                } else {
                    setColor(inactiveColor)
                }
            }
            offPill.background = offBg
            offPill.setTextColor(if (isOff) primary else textSecondary)
            offPill.setTypeface(null, if (isOff) Typeface.BOLD else Typeface.NORMAL)
        }

        val heightsDp = floatArrayOf(22f, 34f, 46f, 60f, 74f)
        for (i in 0 until 5) {
            val lvl = i + 1
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                setPadding(dp(4f), dp(6f), dp(4f), dp(2f))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val bar = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28f), dp(heightsDp[i])).apply {
                    bottomMargin = dp(8f)
                }
            }
            val caption = TextView(context).apply {
                text = stepLabels.getOrElse(i) { "$lvl" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            col.addView(bar)
            col.addView(caption)
            col.setOnClickListener {
                selected = lvl
                updateVisuals()
                it.performSubtleHaptic()
                bar.playTapPop(dipFactor = 0.74f, levelOverride = lvl)
                barRow.playTapPop(dipFactor = 0.95f, levelOverride = lvl)
            }
            barViews.add(bar)
            barCaptions.add(caption)
            barRow.addView(col)
        }
        root.addView(barRow)

        offPill.apply {
            text = context.getString(R.string.motion_turn_off_btn)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12f)
            }
            setOnClickListener {
                selected = 0
                updateVisuals()
            }
        }
        root.addView(offPill)

        updateVisuals()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.motion_effect_level)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(selected)
            }
            .showSmooth(anchor)
    }
}
