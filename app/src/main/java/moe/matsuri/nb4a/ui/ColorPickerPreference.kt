package moe.matsuri.nb4a.ui

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.ColorUtils
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

    private fun resolveSwatchColor(themeId: Int): Int {
        return when (themeId) {
            Theme.BLACK -> Color.BLACK
            Theme.WHITE -> Color.WHITE
            Theme.LIGHT_GRAY -> Color.parseColor("#F5F5F7")
            Theme.MONET -> Theme.getSystemWallpaperColor(context) ?: Color.parseColor("#3664E8")
            Theme.VERDANT_MINT -> Color.parseColor("#00B96B")
            Theme.BLUE -> Color.parseColor("#2196F3")
            Theme.INDIGO -> Color.parseColor("#3F51B5")
            Theme.LIGHT_BLUE -> Color.parseColor("#03A9F4")
            Theme.CYAN -> Color.parseColor("#00BCD4")
            Theme.TEAL -> Color.parseColor("#009688")
            Theme.GREEN -> Color.parseColor("#4CAF50")
            Theme.LIGHT_GREEN -> Color.parseColor("#8BC34A")
            Theme.LIME -> Color.parseColor("#CDDC39")
            Theme.YELLOW -> Color.parseColor("#FFEB3B")
            Theme.AMBER -> Color.parseColor("#FFC107")
            Theme.ORANGE -> Color.parseColor("#FF9800")
            Theme.DEEP_ORANGE -> Color.parseColor("#FF5722")
            Theme.RED -> Color.parseColor("#F44336")
            Theme.PINK_SSR -> Color.parseColor("#FB7299")
            Theme.PINK -> Color.parseColor("#E91E63")
            Theme.PURPLE -> Color.parseColor("#9C27B0")
            Theme.DEEP_PURPLE -> Color.parseColor("#673AB7")
            Theme.BROWN -> Color.parseColor("#795548")
            Theme.BLUE_GREY -> Color.parseColor("#607D8B")
            Theme.GREY -> Color.parseColor("#9E9E9E")
            else -> context.getColorAttr(R.attr.colorPrimary)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout
        widgetFrame.removeAllViews()

        val displayColor = when {
            Theme.isBlackTheme(context) -> Color.BLACK
            Theme.isWhiteTheme(context) -> Color.WHITE
            Theme.isLightGrayTheme(context) -> Color.parseColor("#F5F5F7")
            else -> resolveSwatchColor(DataStore.appTheme)
        }

        val factor = context.resources.displayMetrics.density
        val size = dp2px(26)
        val widgetIv = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp2px(6)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageDrawable(getColorBadgeDrawable(context.resources, displayColor, false, size))
        }
        widgetFrame.addView(widgetIv)
        widgetFrame.visibility = View.VISIBLE
    }

    private fun getColorBadgeDrawable(res: Resources, color: Int, isSelected: Boolean, badgeSize: Int = 0): Drawable {
        val factor = res.displayMetrics.density
        val strokeColor = when (color) {
            Color.WHITE -> Color.parseColor("#CBD5E1")
            Color.parseColor("#F5F5F7") -> Color.parseColor("#CBD5E1")
            Color.BLACK -> Color.parseColor("#444444")
            else -> Color.parseColor("#26000000")
        }

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (badgeSize > 0) {
                setSize(badgeSize, badgeSize)
            }
            setStroke((1.5f * factor).roundToInt().coerceAtLeast(1), strokeColor)
        }

        if (!isSelected) {
            return circle
        }

        val checkmark = ResourcesCompat.getDrawable(res, R.drawable.ic_baseline_check_circle_24, null)!!.mutate()
        val checkTint = if (ColorUtils.calculateLuminance(color) > 0.65) {
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

        val currentThemeId = DataStore.appTheme

        fun applyTheme(themeId: Int) {
            persistInt(themeId)
            DataStore.appTheme = themeId
            DataStore.useSystemTheme = (themeId == Theme.MONET)
            if (themeId in setOf(Theme.WHITE, Theme.LIGHT_GRAY) && Theme.usingNightMode(context)) {
                DataStore.nightTheme = 2
                Theme.currentNightMode = 2
            }
            Theme.applyNightTheme()
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

        val baseThemes = mutableListOf(
            PresetTheme(Theme.BLACK, "纯黑 (AMOLED Black)", Color.BLACK, "纯黑底色 #000000 · 极致省电高对比"),
            PresetTheme(Theme.WHITE, "纯白 (Pure White)", Color.WHITE, "纯白底色 #FFFFFF · 极简黑白高反差"),
            PresetTheme(Theme.LIGHT_GRAY, "浅灰 (Light Gray)", Color.parseColor("#F5F5F7"), "柔灰底色 #F5F5F7 · 优雅层次悬浮感")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            baseThemes.add(
                PresetTheme(
                    Theme.MONET,
                    "莫奈动态取色 (Material You)",
                    resolveSwatchColor(Theme.MONET),
                    "Android 12+ 自动跟随系统壁纸主题色"
                )
            )
        }

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

        // 2. Colorful Material Themes Section
        val colorTitle = TextView(context).apply {
            text = "精选多彩主题"
            textSize = 13f
            setTextColor(context.getColorAttr(R.attr.primaryOrTextSecondary))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp2px(4), dp2px(10), 0, dp2px(8))
        }
        rootLayout.addView(colorTitle)

        val colorThemes = listOf(
            PresetTheme(Theme.VERDANT_MINT, "薄荷绿", resolveSwatchColor(Theme.VERDANT_MINT)),
            PresetTheme(Theme.BLUE, "经典蓝", resolveSwatchColor(Theme.BLUE)),
            PresetTheme(Theme.INDIGO, "靛青蓝", resolveSwatchColor(Theme.INDIGO)),
            PresetTheme(Theme.LIGHT_BLUE, "天际蓝", resolveSwatchColor(Theme.LIGHT_BLUE)),
            PresetTheme(Theme.CYAN, "青碧色", resolveSwatchColor(Theme.CYAN)),
            PresetTheme(Theme.TEAL, "水鸭青", resolveSwatchColor(Theme.TEAL)),
            PresetTheme(Theme.GREEN, "森林绿", resolveSwatchColor(Theme.GREEN)),
            PresetTheme(Theme.DEEP_PURPLE, "深邃紫", resolveSwatchColor(Theme.DEEP_PURPLE)),
            PresetTheme(Theme.PURPLE, "罗兰紫", resolveSwatchColor(Theme.PURPLE)),
            PresetTheme(Theme.PINK_SSR, "樱花粉", resolveSwatchColor(Theme.PINK_SSR)),
            PresetTheme(Theme.PINK, "玫红粉", resolveSwatchColor(Theme.PINK)),
            PresetTheme(Theme.RED, "绯炎红", resolveSwatchColor(Theme.RED)),
            PresetTheme(Theme.DEEP_ORANGE, "珊瑚橙", resolveSwatchColor(Theme.DEEP_ORANGE)),
            PresetTheme(Theme.ORANGE, "活力橙", resolveSwatchColor(Theme.ORANGE)),
            PresetTheme(Theme.AMBER, "琥珀金", resolveSwatchColor(Theme.AMBER)),
            PresetTheme(Theme.BROWN, "摩卡棕", resolveSwatchColor(Theme.BROWN)),
            PresetTheme(Theme.BLUE_GREY, "蓝调灰", resolveSwatchColor(Theme.BLUE_GREY)),
            PresetTheme(Theme.GREY, "极简灰", resolveSwatchColor(Theme.GREY))
        )

        val grid = GridLayout(context).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        for (preset in colorThemes) {
            val isSelected = currentThemeId == preset.id
            val itemCard = MaterialCardView(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                }
                radius = dp2px(10).toFloat()
                cardElevation = 0f
                strokeWidth = if (isSelected) dp2px(2) else dp2px(1)
                strokeColor = if (isSelected) preset.color else Color.parseColor("#22888888")
                setCardBackgroundColor(
                    if (isSelected) ColorUtils.setAlphaComponent(preset.color, 24)
                    else Color.TRANSPARENT
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    applyTheme(preset.id)
                }

                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp2px(8), dp2px(10), dp2px(8), dp2px(10))

                    val swatch = ImageView(context).apply {
                        val sz = dp2px(32)
                        layoutParams = LinearLayout.LayoutParams(sz, sz)
                        setImageDrawable(getColorBadgeDrawable(context.resources, preset.color, isSelected))
                    }
                    addView(swatch)

                    val label = TextView(context).apply {
                        text = preset.name
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(context.getColorAttr(android.R.attr.textColorPrimary))
                        setPadding(0, dp2px(6), 0, 0)
                    }
                    addView(label)
                }
                addView(col)
            }
            grid.addView(itemCard)
        }
        rootLayout.addView(grid)

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
