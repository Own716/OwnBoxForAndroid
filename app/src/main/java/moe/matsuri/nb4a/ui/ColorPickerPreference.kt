package moe.matsuri.nb4a.ui

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import kotlin.math.roundToInt

class ColorPickerPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(context, attrs, defStyle) {

    data class PresetTheme(
        val id: Int,
        val name: String,
        val color: Int,
        val subtitle: String? = null
    )

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout
        widgetFrame.removeAllViews()

        val displayColor = when {
            Theme.isWhiteTheme() -> Color.WHITE
            Theme.isLightGrayTheme() -> Color.parseColor("#F5F5F7")
            Theme.isBlackTheme() -> Color.BLACK
            else -> context.getColorAttr(R.attr.colorPrimary)
        }

        val factor = context.resources.displayMetrics.density
        val size = (44 * factor).roundToInt()
        val widgetIv = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageDrawable(getColorBadgeDrawable(context.resources, displayColor, false))
        }
        widgetFrame.addView(widgetIv)
        widgetFrame.visibility = View.VISIBLE
    }

    private fun getColorBadgeDrawable(res: Resources, color: Int, isSelected: Boolean): Drawable {
        val factor = res.displayMetrics.density
        val strokeColor = when (color) {
            Color.WHITE -> Color.parseColor("#CCCCCC")
            Color.parseColor("#F5F5F7") -> Color.parseColor("#CBD5E1")
            Color.BLACK -> Color.parseColor("#444444")
            else -> Color.parseColor("#33000000")
        }

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * factor).roundToInt().coerceAtLeast(1), strokeColor)
        }

        if (!isSelected) {
            return circle
        }

        val checkmark = ResourcesCompat.getDrawable(res, R.drawable.ic_baseline_check_circle_24, null)!!.mutate()
        val checkTint = if (color == Color.WHITE || color == Color.parseColor("#F5F5F7")) {
            Color.parseColor("#212121")
        } else {
            Color.WHITE
        }
        DrawableCompat.setTint(checkmark, checkTint)

        val checkInset = (8 * factor).roundToInt()
        val layer = LayerDrawable(arrayOf(circle, checkmark))
        layer.setLayerInset(1, checkInset, checkInset, checkInset, checkInset)
        return layer
    }

    override fun onClick() {
        super.onClick()

        lateinit var dialog: AlertDialog

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(10), dp2px(16), dp2px(16))
        }

        val scroll = NestedScrollView(context).apply {
            addView(rootLayout)
        }

        val currentThemeId = if (DataStore.appTheme == Theme.CUSTOM) Theme.GREEN else DataStore.appTheme

        fun applyTheme(themeId: Int) {
            persistInt(themeId)
            DataStore.appTheme = themeId
            dialog.dismiss()
            callChangeListener(themeId)
            (context as? Activity)?.let {
                ActivityCompat.recreate(it)
            }
        }

        // 1. Core Base Themes Section
        val baseTitle = TextView(context).apply {
            text = "核心基础规范主题"
            textSize = 13f
            setTextColor(context.getColorAttr(R.attr.primaryOrTextSecondary))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp2px(4), dp2px(4), 0, dp2px(8))
        }
        rootLayout.addView(baseTitle)

        val baseThemes = listOf(
            PresetTheme(Theme.BLACK, "纯黑 (AMOLED Black)", Color.BLACK, "纯黑底色 #000000 · 极致省电高对比"),
            PresetTheme(Theme.WHITE, "纯白 (Pure White)", Color.WHITE, "纯白底色 #FFFFFF · 极简黑白高反差"),
            PresetTheme(Theme.LIGHT_GRAY, "浅灰 (Light Gray)", Color.parseColor("#F5F5F7"), "柔灰底色 #F5F5F7 · 优雅层次悬浮感")
        )

        for (base in baseThemes) {
            val isSelected = currentThemeId == base.id
            val card = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp2px(8))
                }
                radius = dp2px(12).toFloat()
                cardElevation = 0f
                strokeWidth = if (isSelected) dp2px(2) else dp2px(1)
                strokeColor = if (isSelected) context.getColorAttr(R.attr.colorPrimary) else Color.parseColor("#25888888")
                setCardBackgroundColor(if (isSelected) Color.parseColor("#0F2196F3") else Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    applyTheme(base.id)
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10))

                    val iv = ImageView(context).apply {
                        val sz = dp2px(36)
                        layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                            marginEnd = dp2px(12)
                        }
                        setImageDrawable(getColorBadgeDrawable(context.resources, base.color, isSelected))
                    }
                    addView(iv)

                    val textCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                        val titleView = TextView(context).apply {
                            text = base.name
                            textSize = 14f
                            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                            setTextColor(context.getColorAttr(android.R.attr.textColorPrimary))
                        }
                        addView(titleView)

                        val subtitleView = TextView(context).apply {
                            text = base.subtitle
                            textSize = 11.5f
                            setTextColor(context.getColorAttr(android.R.attr.textColorSecondary))
                            setPadding(0, dp2px(2), 0, 0)
                        }
                        addView(subtitleView)
                    }
                    addView(textCol)
                }
                addView(row)
            }
            rootLayout.addView(card)
        }

        // 2. Divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(1)
            ).apply {
                setMargins(0, dp2px(12), 0, dp2px(12))
            }
            setBackgroundColor(Color.parseColor("#22888888"))
        }
        rootLayout.addView(divider)

        // 3. Vetted Color Themes Section
        val colorTitle = TextView(context).apply {
            text = "精选经典色彩预设"
            textSize = 13f
            setTextColor(context.getColorAttr(R.attr.primaryOrTextSecondary))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp2px(4), dp2px(4), 0, dp2px(8))
        }
        rootLayout.addView(colorTitle)

        val presetColors = listOf(
            PresetTheme(Theme.BLUE, "经典蓝", Color.parseColor("#2196F3")),
            PresetTheme(Theme.VERDANT_MINT, "薄荷绿", Color.parseColor("#00E676")),
            PresetTheme(Theme.CYAN, "青色", Color.parseColor("#00BCD4")),
            PresetTheme(Theme.PINK_SSR, "樱花粉", Color.parseColor("#FF4081")),
            PresetTheme(Theme.ORANGE, "活力橙", Color.parseColor("#FF9800")),
            PresetTheme(Theme.AMBER, "琥珀金", Color.parseColor("#FFC107")),
            PresetTheme(Theme.DEEP_PURPLE, "深紫", Color.parseColor("#673AB7")),
            PresetTheme(Theme.LIGHT_BLUE, "晴空蓝", Color.parseColor("#03A9F4")),
            PresetTheme(Theme.GREEN, "自然绿", Color.parseColor("#4CAF50")),
            PresetTheme(Theme.RED, "热情红", Color.parseColor("#F44336")),
            PresetTheme(Theme.BLUE_GREY, "蓝灰", Color.parseColor("#607D8B")),
            PresetTheme(Theme.TEAL, "鸭翅绿", Color.parseColor("#009688"))
        )

        val grid = GridLayout(context).apply {
            columnCount = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            for (preset in presetColors) {
                val isSelected = currentThemeId == preset.id
                val itemLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp2px(4), dp2px(8), dp2px(4), dp2px(8))
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        applyTheme(preset.id)
                    }

                    val badge = ImageView(context).apply {
                        val sz = dp2px(44)
                        layoutParams = LinearLayout.LayoutParams(sz, sz)
                        setImageDrawable(getColorBadgeDrawable(context.resources, preset.color, isSelected))
                    }
                    addView(badge)

                    val label = TextView(context).apply {
                        text = preset.name
                        textSize = 11.5f
                        gravity = Gravity.CENTER
                        setTextColor(
                            if (isSelected) context.getColorAttr(R.attr.colorPrimary)
                            else context.getColorAttr(android.R.attr.textColorPrimary)
                        )
                        setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        setPadding(0, dp2px(5), 0, 0)
                    }
                    addView(label)
                }

                val gridParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                addView(itemLayout, gridParams)
            }
        }
        rootLayout.addView(grid)

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
