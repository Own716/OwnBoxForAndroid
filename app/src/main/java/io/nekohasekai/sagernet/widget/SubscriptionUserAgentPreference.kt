package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs

class SubscriptionUserAgentPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, androidx.preference.R.attr.preferenceStyle, android.R.attr.preferenceStyle
    ),
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        val PRESETS = listOf(
            "clash-meta",
            "v2rayN/7.8.2",
            "Throne/1.0.0",
            "sing-box/1.14.0",
            "NekoBox/Android/1.3.1 (sing-box v1.14.0)",
        )
    }

    init {
        key = Key.DEFAULT_SUBSCRIPTION_USER_AGENT
        isPersistent = false
    }

    override fun onAttached() {
        super.onAttached()
        summary = DataStore.defaultSubscriptionUserAgent
    }

    override fun onClick() {
        try {
            showCustomDialog()
        } catch (e: Throwable) {
            Logs.w(e)
            showFallbackDialog()
        }
    }

    private fun showCustomDialog() {
        val builder = MaterialAlertDialogBuilder(context)
        val dialogContext = builder.context
        val view = LayoutInflater.from(dialogContext).inflate(R.layout.layout_dialog_user_agent, null)
        val spinner = view.findViewById<AutoCompleteTextView>(R.id.spinner_ua_presets)
        val editUa = view.findViewById<EditText>(R.id.edit_user_agent)
        val cbUpdateAll = view.findViewById<CompoundButton>(R.id.cb_update_all_subs)

        val currentUa = DataStore.defaultSubscriptionUserAgent
        editUa.setText(currentUa)
        editUa.setSelection(currentUa.length)

        val adapter = ArrayAdapter(dialogContext, android.R.layout.simple_dropdown_item_1line, PRESETS)
        spinner.setAdapter(adapter)

        val presetIndex = PRESETS.indexOf(currentUa)
        if (presetIndex >= 0) {
            spinner.setText(PRESETS[presetIndex], false)
        }

        spinner.setOnClickListener {
            spinner.showDropDown()
        }

        spinner.setOnItemClickListener { _, _, position, _ ->
            if (position in PRESETS.indices) {
                val chosen = PRESETS[position]
                editUa.setText(chosen)
                editUa.setSelection(chosen.length)
            }
        }

        builder
            .setTitle(R.string.default_subscription_user_agent)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUa = editUa.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: PRESETS[0]
                DataStore.defaultSubscriptionUserAgent = newUa
                summary = newUa
                if (cbUpdateAll?.isChecked == true) {
                    DataStore.migrateSubscriptionUserAgents(targetUa = newUa, forceAll = true)
                }
                Toast.makeText(dialogContext, R.string.ua_updated_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset_to_default) { _, _ ->
                val defaultUa = PRESETS[0]
                DataStore.defaultSubscriptionUserAgent = defaultUa
                summary = defaultUa
                if (cbUpdateAll?.isChecked == true) {
                    DataStore.migrateSubscriptionUserAgents(targetUa = defaultUa, forceAll = true)
                }
                Toast.makeText(dialogContext, R.string.ua_updated_toast, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showFallbackDialog() {
        val currentUa = DataStore.defaultSubscriptionUserAgent
        val input = EditText(context).apply {
            setText(currentUa)
            setSelection(currentUa.length)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.default_subscription_user_agent)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newUa = input.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: PRESETS[0]
                DataStore.defaultSubscriptionUserAgent = newUa
                summary = newUa
                DataStore.migrateSubscriptionUserAgents(targetUa = newUa, forceAll = true)
                Toast.makeText(context, R.string.ua_updated_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset_to_default) { _, _ ->
                val defaultUa = PRESETS[0]
                DataStore.defaultSubscriptionUserAgent = defaultUa
                summary = defaultUa
                DataStore.migrateSubscriptionUserAgents(targetUa = defaultUa, forceAll = true)
                Toast.makeText(context, R.string.ua_updated_toast, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
