package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.UiScaleManager
import kotlin.math.roundToInt

class UiScaleSettingsActivity : ThemedActivity() {

    private lateinit var previewToolbarTitle: TextView
    private val previewToolbarIcons = mutableListOf<ImageView>()
    private lateinit var previewTabText1: TextView
    private lateinit var previewTabText2: TextView
    private lateinit var previewDrawerIcon: ImageView
    private lateinit var previewDrawerText: TextView
    private lateinit var previewDrawerRow: LinearLayout
    private lateinit var previewNodeName: TextView
    private lateinit var previewNodeType: TextView
    private lateinit var previewNodePing: TextView
    private lateinit var previewNodeMenu: ImageView
    private lateinit var previewNodeContainer: LinearLayout
    private lateinit var previewBottomTxRx: TextView
    private lateinit var previewBottomFab: ImageView
    private lateinit var previewBottomLatencyTitle: TextView
    private lateinit var previewBottomLatencyValue: TextView

    private lateinit var ignoreSysFontSwitch: SwitchMaterial
    private val sliderUpdaters = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val primaryColor = getColorAttr(R.attr.colorPrimary)
        val surfaceColor = getColorAttr(R.attr.colorSurface)
        val textPrimary = getColorAttr(android.R.attr.textColorPrimary)
        val textSecondary = getColorAttr(android.R.attr.textColorSecondary)
        val bgColor = getColorAttr(android.R.attr.colorBackground)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgColor)
        }

        val appBar = AppBarLayout(this).apply {
            id = R.id.appbar
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(surfaceColor)
            elevation = 0f
        }

        val toolbar = Toolbar(this).apply {
            id = R.id.toolbar
            layoutParams = AppBarLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp2px(52)
            )
            title = getString(R.string.ui_scale_settings_title)
            setTitleTextColor(textPrimary)
        }
        appBar.addView(toolbar)
        root.addView(appBar)

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.ui_scale_settings_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        toolbar.navigationIcon?.setTint(textPrimary)

        val scroll = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            clipToPadding = false
            setPadding(dp2px(14), dp2px(10), dp2px(14), dp2px(28))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(content)
        root.addView(scroll)

        // 1. Live Preview Card
        content.addView(buildSectionTitle("实时效果预览", primaryColor))
        content.addView(buildLivePreviewCard(surfaceColor, primaryColor, textPrimary, textSecondary))

        // 2. Global Control Section
        content.addView(buildSectionTitle("全局总控（字体与界面整体缩放）", primaryColor))
        val globalCard = buildCard(surfaceColor)
        val globalBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12))
        }

        // Ignore System Font Scale Switch
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp2px(2), 0, dp2px(10))
        }
        val switchTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        switchTextCol.addView(TextView(this).apply {
            text = "忽略手机系统大字体"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        switchTextCol.addView(TextView(this).apply {
            text = "开启后无视手机系统字体放大倍率，APP 内以标准 100% 字号为基准"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(textSecondary)
            setPadding(0, dp2px(2), dp2px(8), 0)
        })
        ignoreSysFontSwitch = SwitchMaterial(this).apply {
            isChecked = UiScaleManager.ignoreSystemFontScale()
            setOnCheckedChangeListener { _, isChecked ->
                DataStore.uiIgnoreSystemFontScale = isChecked
                refreshLivePreview()
            }
        }
        switchRow.addView(switchTextCol)
        switchRow.addView(ignoreSysFontSwitch)
        globalBox.addView(switchRow)

        // Preset Row
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp2px(4), 0, dp2px(10))
        }
        val presets = listOf(
            "极简 85%" to 85,
            "紧凑 90%" to 90,
            "标准 100%" to 100,
            "大号 110%" to 110
        )
        presets.forEachIndexed { index, (label, pct) ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                insetTop = 0
                insetBottom = 0
                minimumHeight = dp2px(34)
                minHeight = dp2px(34)
                setPadding(dp2px(6), 0, dp2px(6), 0)
                layoutParams = LinearLayout.LayoutParams(0, dp2px(34), 1f).apply {
                    if (index > 0) marginStart = dp2px(6)
                }
                setOnClickListener {
                    UiScaleManager.applyPreset(pct)
                    sliderUpdaters.forEach { it.invoke() }
                    refreshLivePreview()
                }
            }
            presetRow.addView(btn)
        }
        globalBox.addView(presetRow)

        globalBox.addView(
            buildScaleSlider(
                title = "全局界面与 Logo 按钮大小",
                subtitle = "同时缩放全软件文字、图标、按钮及控件边距",
                minPct = 75,
                maxPct = 125,
                step = 5,
                getter = { UiScaleManager.globalScalePct() },
                setter = { DataStore.uiGlobalScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        globalBox.addView(
            buildScaleSlider(
                title = "全局文字大小",
                subtitle = "单独调节全软件所有文字的字号比例",
                minPct = 75,
                maxPct = 130,
                step = 5,
                getter = { UiScaleManager.fontScalePct() },
                setter = { DataStore.uiFontScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        globalCard.addView(globalBox)
        content.addView(globalCard)

        // 3. Regional Fine-Tuning Section
        content.addView(buildSectionTitle("分区独立微调（想让哪里变小/变大可单独调）", primaryColor))
        val regionCard = buildCard(surfaceColor)
        val regionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12))
        }

        regionBox.addView(
            buildScaleSlider(
                title = "顶栏品牌名 (OwnBox / 标题) 大小",
                subtitle = "左上角 OwnBox 品牌名及二级页面标题文字大小",
                minPct = 70,
                maxPct = 130,
                step = 5,
                getter = { UiScaleManager.toolbarTitleScalePct() },
                setter = { DataStore.uiToolbarTitleScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        regionBox.addView(
            buildScaleSlider(
                title = "顶栏右上角 Logo 按钮大小",
                subtitle = "顶栏搜索、添加、闪电测速、带宽测速、菜单等图标按钮大小",
                minPct = 70,
                maxPct = 130,
                step = 5,
                getter = { UiScaleManager.toolbarIconScalePct() },
                setter = { DataStore.uiToolbarIconScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        regionBox.addView(
            buildScaleSlider(
                title = "订阅分组切换栏 (Tab) 大小",
                subtitle = "顶部订阅分组标签栏的文字大小与高度",
                minPct = 70,
                maxPct = 130,
                step = 5,
                getter = { UiScaleManager.tabBarScalePct() },
                setter = { DataStore.uiTabBarScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        regionBox.addView(
            buildScaleSlider(
                title = "左侧抽屉菜单大小",
                subtitle = "侧边栏菜单（配置、分组、路由、设置等）图标、文字与行高大小",
                minPct = 70,
                maxPct = 130,
                step = 5,
                getter = { UiScaleManager.drawerScalePct() },
                setter = { DataStore.uiDrawerScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        regionBox.addView(
            buildScaleSlider(
                title = "节点列表卡片与按钮大小",
                subtitle = "节点名称、协议标签、延迟数字、右侧操作按钮与卡片紧凑度",
                minPct = 70,
                maxPct = 130,
                step = 5,
                getter = { UiScaleManager.profileCardScalePct() },
                setter = { DataStore.uiProfileCardScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        regionBox.addView(
            buildScaleSlider(
                title = "底部状态栏与纸飞机按钮大小",
                subtitle = "底部卡片速率、握手延迟、落地 IP 文字及中间连接悬浮球大小",
                minPct = 70,
                maxPct = 130,
                step = 5,
                getter = { UiScaleManager.bottomBarScalePct() },
                setter = { DataStore.uiBottomBarScale = it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                primaryColor = primaryColor
            )
        )

        regionCard.addView(regionBox)
        content.addView(regionCard)

        // 4. Bottom Action Row
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp2px(12), 0, dp2px(16))
        }

        val resetBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "恢复默认 (100%)"
            layoutParams = LinearLayout.LayoutParams(0, dp2px(44), 1f).apply {
                marginEnd = dp2px(8)
            }
            setOnClickListener {
                UiScaleManager.resetToDefaults()
                ignoreSysFontSwitch.isChecked = false
                sliderUpdaters.forEach { it.invoke() }
                refreshLivePreview()
            }
        }

        val applyBtn = MaterialButton(this).apply {
            text = "保存并应用"
            layoutParams = LinearLayout.LayoutParams(0, dp2px(44), 1f).apply {
                marginStart = dp2px(8)
            }
            setOnClickListener {
                finish()
            }
        }

        actionRow.addView(resetBtn)
        actionRow.addView(applyBtn)
        content.addView(actionRow)

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            scroll.setPadding(dp2px(14), dp2px(10), dp2px(14), dp2px(28) + navBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        refreshLivePreview()
    }

    private fun buildSectionTitle(title: String, color: Int): TextView {
        return TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp2px(4), dp2px(10), dp2px(4), dp2px(6))
        }
    }

    private fun buildCard(surfaceColor: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp2px(8)
            }
            radius = dp2px(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp2px(1)
            strokeColor = Color.parseColor("#22888888")
            setCardBackgroundColor(surfaceColor)
        }
    }

    private fun buildLivePreviewCard(
        surfaceColor: Int,
        primaryColor: Int,
        textPrimary: Int,
        textSecondary: Int
    ): MaterialCardView {
        val card = buildCard(surfaceColor)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10))
        }

        // Preview Top Toolbar Row
        val topBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
        }
        val navIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_navigation_menu)
            imageTintList = ColorStateList.valueOf(textPrimary)
            layoutParams = LinearLayout.LayoutParams(dp2px(20), dp2px(20)).apply {
                marginEnd = dp2px(10)
            }
        }
        previewToolbarIcons.add(navIcon)
        topBarRow.addView(navIcon)

        previewToolbarTitle = TextView(this).apply {
            text = "OwnBox"
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBarRow.addView(previewToolbarTitle)

        val iconResIds = listOf(
            R.drawable.ic_baseline_search_24,
            R.drawable.ic_action_note_add,
            R.drawable.ic_electric_bolt_24,
            R.drawable.ic_baseline_speed_24,
            R.drawable.ic_baseline_more_vert_24
        )
        for (resId in iconResIds) {
            val iv = ImageView(this).apply {
                setImageResource(resId)
                imageTintList = ColorStateList.valueOf(textPrimary)
                layoutParams = LinearLayout.LayoutParams(dp2px(20), dp2px(20)).apply {
                    marginStart = dp2px(6)
                }
            }
            previewToolbarIcons.add(iv)
            topBarRow.addView(iv)
        }
        box.addView(topBarRow)

        // Preview Tab Row
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(6), dp2px(2), dp2px(6), dp2px(6))
        }
        previewTabText1 = TextView(this).apply {
            text = "默认分组"
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(0, 0, dp2px(14), 0)
        }
        previewTabText2 = TextView(this).apply {
            text = "主力订阅 (24)"
            setTextColor(textSecondary)
        }
        tabRow.addView(previewTabText1)
        tabRow.addView(previewTabText2)
        box.addView(tabRow)

        // Preview Drawer Row + Node Card Row
        previewDrawerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp2px(10).toFloat()
                setColor(ColorUtils.setAlphaComponent(textPrimary, 15))
            }
            setPadding(dp2px(10), dp2px(6), dp2px(10), dp2px(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp2px(6)
            }
        }
        previewDrawerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_baseline_layers_24)
            imageTintList = ColorStateList.valueOf(textPrimary)
            layoutParams = LinearLayout.LayoutParams(dp2px(18), dp2px(18)).apply {
                marginEnd = dp2px(10)
            }
        }
        previewDrawerText = TextView(this).apply {
            text = "侧边栏菜单示例 · 路由与规则"
            setTextColor(textPrimary)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        }
        previewDrawerRow.addView(previewDrawerIcon)
        previewDrawerRow.addView(previewDrawerText)
        box.addView(previewDrawerRow)

        // Preview Node Card
        previewNodeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp2px(10).toFloat()
                setStroke(dp2px(1), ColorUtils.setAlphaComponent(textPrimary, 45))
                setColor(Color.TRANSPARENT)
            }
            setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp2px(6)
            }
        }
        val nodeLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        previewNodeName = TextView(this).apply {
            text = "🇭🇰 香港 IEPL 专线 01"
            setTextColor(textPrimary)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        previewNodeType = TextView(this).apply {
            text = "VLESS"
            setTextColor(Color.parseColor("#1E88E5"))
            setPadding(0, dp2px(2), 0, 0)
        }
        nodeLeft.addView(previewNodeName)
        nodeLeft.addView(previewNodeType)

        previewNodePing = TextView(this).apply {
            text = "32 ms"
            setTextColor(Color.parseColor("#4CAF50"))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp2px(8), 0, dp2px(6), 0)
        }
        previewNodeMenu = ImageView(this).apply {
            setImageResource(R.drawable.ic_baseline_more_vert_24)
            imageTintList = ColorStateList.valueOf(textSecondary)
            layoutParams = LinearLayout.LayoutParams(dp2px(18), dp2px(18))
        }
        previewNodeContainer.addView(nodeLeft)
        previewNodeContainer.addView(previewNodePing)
        previewNodeContainer.addView(previewNodeMenu)
        box.addView(previewNodeContainer)

        // Preview Bottom Stats Bar
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp2px(10).toFloat()
                setColor(ColorUtils.setAlphaComponent(textPrimary, 14))
            }
            setPadding(dp2px(10), dp2px(6), dp2px(10), dp2px(6))
        }
        previewBottomTxRx = TextView(this).apply {
            text = "▲ 18 KB/s\n▼ 2.4 MB/s"
            setTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        previewBottomFab = ImageView(this).apply {
            setImageResource(R.drawable.ic_service_idle)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#212121"))
            }
            val sz = dp2px(32)
            setPadding(dp2px(6), dp2px(6), dp2px(6), dp2px(6))
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = dp2px(8)
                marginEnd = dp2px(8)
            }
        }
        val bottomRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        previewBottomLatencyTitle = TextView(this).apply {
            text = "HTTPS 握手延迟"
            setTextColor(textSecondary)
            gravity = Gravity.END
        }
        previewBottomLatencyValue = TextView(this).apply {
            text = "168ms"
            setTextColor(textPrimary)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
        }
        bottomRight.addView(previewBottomLatencyTitle)
        bottomRight.addView(previewBottomLatencyValue)

        bottomRow.addView(previewBottomTxRx)
        bottomRow.addView(previewBottomFab)
        bottomRow.addView(bottomRight)
        box.addView(bottomRow)

        card.addView(box)
        return card
    }

    private fun buildScaleSlider(
        title: String,
        subtitle: String,
        minPct: Int,
        maxPct: Int,
        step: Int,
        getter: () -> Int,
        setter: (Int) -> Unit,
        textPrimary: Int,
        textSecondary: Int,
        primaryColor: Int
    ): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp2px(6), 0, dp2px(8))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(this).apply {
            text = "${getter()}%"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryColor)
        }
        headerRow.addView(titleTv)
        headerRow.addView(valueTv)
        wrapper.addView(headerRow)

        val subTv = TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(textSecondary)
            setPadding(0, dp2px(1), 0, dp2px(4))
        }
        wrapper.addView(subTv)

        val stepsCount = (maxPct - minPct) / step
        val seekBar = SeekBar(this).apply {
            max = stepsCount
            progress = ((getter() - minPct) / step).coerceIn(0, stepsCount)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val pct = minPct + progress * step
                    valueTv.text = "${pct}%"
                    if (fromUser) {
                        setter(pct)
                        refreshLivePreview()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sliderUpdaters.add {
            val pct = getter()
            valueTv.text = "${pct}%"
            seekBar.progress = ((pct - minPct) / step).coerceIn(0, stepsCount)
        }
        wrapper.addView(seekBar)
        return wrapper
    }

    private fun refreshLivePreview() {
        val globalFactor = UiScaleManager.globalScalePct() / 100f
        val fontFactor = UiScaleManager.fontScalePct() / 100f
        val textBase = globalFactor * fontFactor

        val titleFactor = UiScaleManager.toolbarTitleFactor()
        val iconFactor = UiScaleManager.toolbarIconFactor()
        val tabFactor = UiScaleManager.tabBarFactor()
        val drawerFactor = UiScaleManager.drawerFactor()
        val cardFactor = UiScaleManager.profileCardFactor()
        val bottomFactor = UiScaleManager.bottomBarFactor()

        previewToolbarTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17.5f * textBase * titleFactor)
        previewToolbarIcons.forEach { iv ->
            val s = (globalFactor * iconFactor).coerceIn(0.6f, 1.5f)
            iv.scaleX = s
            iv.scaleY = s
        }

        previewTabText1.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f * textBase * tabFactor)
        previewTabText2.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f * textBase * tabFactor)

        previewDrawerText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f * textBase * drawerFactor)
        val dScale = (globalFactor * drawerFactor).coerceIn(0.6f, 1.5f)
        previewDrawerIcon.scaleX = dScale
        previewDrawerIcon.scaleY = dScale

        previewNodeName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f * textBase * cardFactor)
        previewNodeType.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f * textBase * cardFactor)
        previewNodePing.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f * textBase * cardFactor)
        val cScale = (globalFactor * cardFactor).coerceIn(0.6f, 1.5f)
        previewNodeMenu.scaleX = cScale
        previewNodeMenu.scaleY = cScale
        val vPad = (dp2px(8) * cScale).roundToInt().coerceAtLeast(dp2px(4))
        previewNodeContainer.setPadding(dp2px(10), vPad, dp2px(10), vPad)

        previewBottomTxRx.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f * textBase * bottomFactor)
        previewBottomLatencyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f * textBase * bottomFactor)
        previewBottomLatencyValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f * textBase * bottomFactor)
        val bScale = (globalFactor * bottomFactor).coerceIn(0.6f, 1.5f)
        previewBottomFab.scaleX = bScale
        previewBottomFab.scaleY = bScale
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
