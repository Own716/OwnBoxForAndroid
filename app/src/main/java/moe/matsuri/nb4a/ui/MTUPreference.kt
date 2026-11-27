package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

class MTUPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : ListPreference(context, attrs, defStyle, 0) {

    companion object {
        const val CUSTOM_MTU_SENTINEL = "__custom__"
        private const val MIN_MTU = 1280
        private const val MAX_MTU = 9000
    }

    init {
        setSummaryProvider {
            val current = preferenceDataStore?.getString(key, null) ?: value
            current?.takeIf { it.isNotBlank() && it != CUSTOM_MTU_SENTINEL } ?: "1400"
        }
        dialogLayoutResource = R.layout.layout_mtu_help
    }

    override fun callChangeListener(newValue: Any?): Boolean {
        if (newValue?.toString() == CUSTOM_MTU_SENTINEL) {
            showCustomMtuDialog()
            return false
        }
        return super.callChangeListener(newValue)
    }

    private fun showCustomMtuDialog() {
        val currentText = (preferenceDataStore?.getString(key, null) ?: value)
            ?.takeIf { it.isNotBlank() && it != CUSTOM_MTU_SENTINEL }
            ?: "1400"
        val view = EditText(context).apply {
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            hint = "$MIN_MTU - $MAX_MTU"
            setText(currentText)
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("自定义 MTU ($MIN_MTU–$MAX_MTU)")
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val mtu = view.text.toString().trim().toIntOrNull()
                if (mtu == null || mtu < MIN_MTU || mtu > MAX_MTU) {
                    Toast.makeText(
                        context,
                        "请输入 $MIN_MTU 到 $MAX_MTU 之间的有效整数",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val strValue = mtu.toString()
                if (super.callChangeListener(strValue)) {
                    value = strValue
                    notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val itemView: View = holder.itemView
        itemView.setOnLongClickListener {
            showCustomMtuDialog()
            true
        }
    }

}
