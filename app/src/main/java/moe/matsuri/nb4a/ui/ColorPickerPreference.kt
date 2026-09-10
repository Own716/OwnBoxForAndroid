package moe.matsuri.nb4a.ui

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import kotlin.math.roundToInt

class ColorPickerPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(
    context, attrs, defStyle
) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout
        widgetFrame.removeAllViews()

        val displayColor = if (DataStore.appTheme == Theme.CUSTOM) {
            DataStore.customThemeColor or 0xFF000000.toInt()
        } else {
            context.getColorAttr(R.attr.colorPrimary)
        }

        widgetFrame.addView(
            getNekoImageViewAtColor(
                displayColor,
                48,
                0
            )
        )
        widgetFrame.visibility = View.VISIBLE
    }

    fun getNekoImageViewAtColor(color: Int, sizeDp: Int, paddingDp: Int): ImageView {
        val factor = context.resources.displayMetrics.density
        val size = (sizeDp * factor).roundToInt()
        val paddingSize = (paddingDp * factor).roundToInt()

        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(paddingSize)
            setImageDrawable(getNekoAtColor(resources, color))
        }
    }

    fun getNekoAtColor(res: Resources, color: Int): Drawable {
        val neko = ResourcesCompat.getDrawable(
            res,
            R.drawable.ic_baseline_fiber_manual_record_24,
            null
        )!!
        DrawableCompat.setTint(neko.mutate(), color)
        return neko
    }

    override fun onClick() {
        super.onClick()

        lateinit var dialog: AlertDialog

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(16))
        }

        val scroll = NestedScrollView(context).apply {
            addView(rootLayout)
        }

        // 1. Preset colors grid
        val grid = GridLayout(context).apply {
            columnCount = 4
            val colors = context.resources.getIntArray(R.array.material_colors)
            var i = 0

            for (color in colors) {
                i++ // Theme.kt
                val themeId = i
                val view = getNekoImageViewAtColor(color, 56, 4).apply {
                    setOnClickListener {
                        persistInt(themeId)
                        dialog.dismiss()
                        callChangeListener(themeId)
                        (context as? Activity)?.let {
                            ActivityCompat.recreate(it)
                        }
                    }
                }
                addView(view)
            }
        }
        rootLayout.addView(grid)

        // 2. Divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(1)
            ).apply {
                setMargins(0, dp2px(16), 0, dp2px(12))
            }
            setBackgroundColor(0x33888888)
        }
        rootLayout.addView(divider)

        // 3. Custom Color Header
        val customTitle = TextView(context).apply {
            text = context.getString(R.string.custom_color_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp2px(8))
        }
        rootLayout.addView(customTitle)

        // Quick Preset Chips (White, Dark, Emerald, Cyan, Blue, Purple, Amber, Red)
        val quickPresetColors = intArrayOf(
            0xFFFFFFFF.toInt(), // Pure White
            0xFF1E293B.toInt(), // Dark Slate
            0xFF00E676.toInt(), // Emerald Mint
            0xFF00BCD4.toInt(), // Cyan
            0xFF2196F3.toInt(), // Material Blue
            0xFF9C27B0.toInt(), // Purple
            0xFFFFC107.toInt(), // Amber
            0xFFF44336.toInt()  // Red
        )
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp2px(12))
        }
        rootLayout.addView(quickRow)

        // Initial custom color
        var curColor = DataStore.customThemeColor or 0xFF000000.toInt()
        var curR = Color.red(curColor)
        var curG = Color.green(curColor)
        var curB = Color.blue(curColor)
        val initialHsv = FloatArray(3)
        Color.colorToHSV(curColor, initialHsv)
        var curH = initialHsv[0]
        var curS = initialHsv[1]
        var curV = initialHsv[2]

        // Preview & Hex row (Current vs New preview)
        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp2px(10))
        }

        val originalImage = getNekoImageViewAtColor(curColor, 40, 2)
        val arrowText = TextView(context).apply {
            text = " → "
            textSize = 16f
            setPadding(dp2px(4), 0, dp2px(4), 0)
        }
        val previewImage = getNekoImageViewAtColor(curColor, 44, 2)
        previewRow.addView(originalImage)
        previewRow.addView(arrowText)
        previewRow.addView(previewImage)

        var updatingFromCode = false

        val hexInput = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp2px(12)
            }
            hint = context.getString(R.string.custom_color_hex_hint)
            filters = arrayOf(InputFilter.LengthFilter(7))
            setText(String.format("#%02X%02X%02X", curR, curG, curB))
            textSize = 14f
        }
        previewRow.addView(hexInput)
        rootLayout.addView(previewRow)

        // Sliders
        fun createSlider(label: String, maxVal: Int, initialValue: Int): Pair<TextView, SeekBar> {
            val labelView = TextView(context).apply {
                text = "$label: $initialValue"
                textSize = 12f
                setPadding(0, dp2px(3), 0, 0)
            }
            val seekBar = SeekBar(context).apply {
                max = maxVal
                progress = initialValue
            }
            rootLayout.addView(labelView)
            rootLayout.addView(seekBar)
            return Pair(labelView, seekBar)
        }

        // HSV Sliders
        val (hLabel, hSeek) = createSlider("色相 (Hue)", 360, curH.roundToInt())
        val (sLabel, sSeek) = createSlider("饱和度 (Saturation)", 100, (curS * 100f).roundToInt())
        val (vLabel, vSeek) = createSlider("明度 (Brightness)", 100, (curV * 100f).roundToInt())

        // RGB Sliders
        val (rLabel, rSeek) = createSlider("R (红)", 255, curR)
        val (gLabel, gSeek) = createSlider("G (绿)", 255, curG)
        val (bLabel, bSeek) = createSlider("B (蓝)", 255, curB)

        fun syncAllUI(fromRgb: Boolean) {
            if (fromRgb) {
                curColor = Color.rgb(curR, curG, curB)
                val hsv = FloatArray(3)
                Color.colorToHSV(curColor, hsv)
                curH = hsv[0]
                curS = hsv[1]
                curV = hsv[2]
            } else {
                curColor = Color.HSVToColor(floatArrayOf(curH, curS, curV))
                curR = Color.red(curColor)
                curG = Color.green(curColor)
                curB = Color.blue(curColor)
            }

            previewImage.setImageDrawable(getNekoAtColor(context.resources, curColor))
            hLabel.text = "色相 (Hue): ${curH.roundToInt()}°"
            sLabel.text = "饱和度 (Saturation): ${(curS * 100f).roundToInt()}%"
            vLabel.text = "明度 (Brightness): ${(curV * 100f).roundToInt()}%"
            rLabel.text = "R (红): $curR"
            gLabel.text = "G (绿): $curG"
            bLabel.text = "B (蓝): $curB"

            if (!updatingFromCode) {
                updatingFromCode = true
                hSeek.progress = curH.roundToInt()
                sSeek.progress = (curS * 100f).roundToInt()
                vSeek.progress = (curV * 100f).roundToInt()
                rSeek.progress = curR
                gSeek.progress = curG
                bSeek.progress = curB
                hexInput.setText(String.format("#%02X%02X%02X", curR, curG, curB))
                updatingFromCode = false
            }
        }

        for (qColor in quickPresetColors) {
            val qView = getNekoImageViewAtColor(qColor, 34, 3).apply {
                setOnClickListener {
                    curR = Color.red(qColor)
                    curG = Color.green(qColor)
                    curB = Color.blue(qColor)
                    syncAllUI(true)
                }
            }
            quickRow.addView(qView)
        }

        val hsvSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingFromCode) {
                    curH = hSeek.progress.toFloat()
                    curS = sSeek.progress / 100f
                    curV = vSeek.progress / 100f
                    syncAllUI(false)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        hSeek.setOnSeekBarChangeListener(hsvSeekListener)
        sSeek.setOnSeekBarChangeListener(hsvSeekListener)
        vSeek.setOnSeekBarChangeListener(hsvSeekListener)

        val rgbSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !updatingFromCode) {
                    curR = rSeek.progress
                    curG = gSeek.progress
                    curB = bSeek.progress
                    syncAllUI(true)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        rSeek.setOnSeekBarChangeListener(rgbSeekListener)
        gSeek.setOnSeekBarChangeListener(rgbSeekListener)
        bSeek.setOnSeekBarChangeListener(rgbSeekListener)

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromCode) return
                val str = s?.toString()?.trim().orEmpty()
                if (str.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
                    try {
                        val parsed = Color.parseColor(str)
                        curR = Color.red(parsed)
                        curG = Color.green(parsed)
                        curB = Color.blue(parsed)
                        syncAllUI(true)
                    } catch (_: Throwable) {}
                }
            }
        })

        // Apply Button
        val applyBtn = MaterialButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp2px(12)
            }
            text = context.getString(R.string.custom_color_apply)
            setOnClickListener {
                val finalColor = Color.rgb(curR, curG, curB)
                DataStore.customThemeColor = finalColor
                persistInt(Theme.CUSTOM)
                dialog.dismiss()
                callChangeListener(Theme.CUSTOM)
                (context as? Activity)?.let {
                    ActivityCompat.recreate(it)
                }
            }
        }
        rootLayout.addView(applyBtn)

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
