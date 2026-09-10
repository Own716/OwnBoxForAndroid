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

        // Initial custom color
        var curColor = DataStore.customThemeColor or 0xFF000000.toInt()
        var curR = Color.red(curColor)
        var curG = Color.green(curColor)
        var curB = Color.blue(curColor)

        // Preview & Hex row
        val previewRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp2px(8))
        }

        val previewImage = getNekoImageViewAtColor(curColor, 44, 2)
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

        // RGB Sliders
        fun createSlider(label: String, initialValue: Int): Pair<TextView, SeekBar> {
            val labelView = TextView(context).apply {
                text = "$label: $initialValue"
                textSize = 12f
                setPadding(0, dp2px(4), 0, 0)
            }
            val seekBar = SeekBar(context).apply {
                max = 255
                progress = initialValue
            }
            rootLayout.addView(labelView)
            rootLayout.addView(seekBar)
            return Pair(labelView, seekBar)
        }

        val (rLabel, rSeek) = createSlider("R", curR)
        val (gLabel, gSeek) = createSlider("G", curG)
        val (bLabel, bSeek) = createSlider("B", curB)

        fun updateColorFromRgb(r: Int, g: Int, b: Int) {
            curR = r
            curG = g
            curB = b
            curColor = Color.rgb(r, g, b)
            previewImage.setImageDrawable(getNekoAtColor(context.resources, curColor))
            rLabel.text = "R: $r"
            gLabel.text = "G: $g"
            bLabel.text = "B: $b"
            if (!updatingFromCode) {
                updatingFromCode = true
                hexInput.setText(String.format("#%02X%02X%02X", r, g, b))
                updatingFromCode = false
            }
        }

        val seekChangeListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateColorFromRgb(rSeek.progress, gSeek.progress, bSeek.progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        rSeek.setOnSeekBarChangeListener(seekChangeListener)
        gSeek.setOnSeekBarChangeListener(seekChangeListener)
        bSeek.setOnSeekBarChangeListener(seekChangeListener)

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromCode) return
                val str = s?.toString()?.trim().orEmpty()
                if (str.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
                    try {
                        val parsed = Color.parseColor(str)
                        updatingFromCode = true
                        rSeek.progress = Color.red(parsed)
                        gSeek.progress = Color.green(parsed)
                        bSeek.progress = Color.blue(parsed)
                        updatingFromCode = false
                        updateColorFromRgb(Color.red(parsed), Color.green(parsed), Color.blue(parsed))
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
