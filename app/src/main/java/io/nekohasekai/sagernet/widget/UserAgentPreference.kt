package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

class UserAgentPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context, androidx.preference.R.attr.preferenceStyle, android.R.attr.preferenceStyle
    )
) : Preference(context, attrs, defStyle) {

    companion object {
        val PRESETS = listOf(
            "默认 (使用全局默认)",
            "sing-box/1.14",
            "sing-box/1.14.0",
            "ClashMeta",
            "Surge",
            "v2rayN/7.8.2",
            "Throne/1.0.0",
            "NekoBox/Android/1.3.1 (sing-box v1.14.0)"
        )
    }

    init {
        key = Key.SUBSCRIPTION_USER_AGENT
    }

    override fun getSummary(): CharSequence? {
        val lockPrefix = if (DataStore.subscriptionLockUserAgent) "[已锁定] " else ""
        val custom = DataStore.subscriptionUserAgent?.trim()
        val ua = if (custom.isNullOrBlank()) {
            DataStore.defaultSubscriptionUserAgent
        } else {
            custom
        }
        return "$lockPrefix$ua"
    }

    override fun onClick() {
        val builder = MaterialAlertDialogBuilder(context)
        val dialogContext = builder.context
        val view = LayoutInflater.from(dialogContext).inflate(R.layout.layout_dialog_group_user_agent, null)
        val spinner = view.findViewById<AppCompatSpinner>(R.id.spinner_ua_presets)
        val editUa = view.findViewById<EditText>(R.id.edit_user_agent)
        val switchLock = view.findViewById<SwitchMaterial>(R.id.switch_lock_user_agent)

        val currentUa = DataStore.subscriptionUserAgent?.trim().orEmpty()
        editUa.setText(currentUa)
        if (currentUa.isNotEmpty()) {
            editUa.setSelection(currentUa.length)
        }
        switchLock.isChecked = DataStore.subscriptionLockUserAgent

        val adapter = ArrayAdapter(dialogContext, android.R.layout.simple_spinner_dropdown_item, PRESETS)
        spinner.adapter = adapter

        val presetIndex = PRESETS.indexOf(currentUa)
        if (presetIndex >= 0) {
            spinner.setSelection(presetIndex)
        } else if (currentUa.isEmpty()) {
            spinner.setSelection(0)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isFirst = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isFirst) {
                    isFirst = false
                    return
                }
                if (position == 0) {
                    editUa.setText("")
                } else if (position in PRESETS.indices) {
                    val chosen = PRESETS[position]
                    editUa.setText(chosen)
                    editUa.setSelection(chosen.length)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        builder
            .setTitle(R.string.subscription_user_agent)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUa = editUa.text?.toString()?.trim().orEmpty()
                DataStore.subscriptionUserAgent = newUa
                DataStore.subscriptionLockUserAgent = switchLock.isChecked
                notifyChanged()
                callChangeListener(newUa)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}