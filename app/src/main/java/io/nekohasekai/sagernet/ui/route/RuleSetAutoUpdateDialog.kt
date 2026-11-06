package io.nekohasekai.sagernet.ui.route

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.route.RuleSets

object RuleSetAutoUpdateDialog {

    private val PRESET_VALUES = listOf("0", "15m", "30m", "1h", "3h", "6h", "12h", "24h")

    fun show(context: Context, onChanged: ((String) -> Unit)? = null) {
        val currentRaw = DataStore.rulesUpdateInterval
        val normalized = RuleSets.normalizeUpdateInterval(currentRaw) ?: "0"

        val labels = arrayOf(
            context.getString(R.string.rule_set_auto_update_off),
            context.getString(R.string.rule_set_auto_update_every_minutes, 15L),
            context.getString(R.string.rule_set_auto_update_every_minutes, 30L),
            context.getString(R.string.rule_set_auto_update_every_hours, 1L),
            context.getString(R.string.rule_set_auto_update_every_hours, 3L),
            context.getString(R.string.rule_set_auto_update_every_hours, 6L),
            context.getString(R.string.rule_set_auto_update_every_hours, 12L),
            context.getString(R.string.rule_set_auto_update_every_hours, 24L),
            context.getString(R.string.rule_set_auto_update_custom)
        )

        val presetIndex = PRESET_VALUES.indexOf(normalized)
        val checkedItem = if (presetIndex >= 0) presetIndex else labels.lastIndex

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rule_set_auto_update_title)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                if (which == labels.lastIndex) {
                    showCustomDialog(context, normalized, onChanged)
                } else {
                    applyInterval(PRESET_VALUES[which], onChanged)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomDialog(
        context: Context,
        currentNormalized: String,
        onChanged: ((String) -> Unit)?
    ) {
        val isHours = currentNormalized.endsWith("h", ignoreCase = true)
        val initialNumber = currentNormalized.dropLast(1).toLongOrNull()?.takeIf { it > 0L }?.toString() ?: "60"

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
        }

        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.rule_set_auto_update_custom_hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialNumber)
            setSelection(text?.length ?: 0)
            isSingleLine = true
        }
        inputLayout.addView(input)
        container.addView(inputLayout)

        val unitGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp2px(12), 0, 0)
        }
        val radioMinutes = RadioButton(context).apply {
            id = android.view.View.generateViewId()
            text = context.getString(R.string.rule_set_unit_minutes)
            setPadding(0, 0, dp2px(16), 0)
        }
        val radioHours = RadioButton(context).apply {
            id = android.view.View.generateViewId()
            text = context.getString(R.string.rule_set_unit_hours)
        }
        unitGroup.addView(radioMinutes)
        unitGroup.addView(radioHours)
        unitGroup.check(if (isHours) radioHours.id else radioMinutes.id)
        container.addView(unitGroup)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rule_set_auto_update_custom)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val num = input.text?.toString()?.trim()?.toLongOrNull()
            if (num == null || num <= 0L) {
                inputLayout.error = context.getString(R.string.rule_set_auto_update_invalid)
                return@setOnClickListener
            }
            val unit = if (unitGroup.checkedRadioButtonId == radioHours.id) "h" else "m"
            val value = "$num$unit"
            dialog.dismiss()
            applyInterval(value, onChanged)
        }
    }

    private fun applyInterval(interval: String, onChanged: ((String) -> Unit)?) {
        DataStore.rulesUpdateInterval = interval
        onChanged?.invoke(interval)
        if (DataStore.serviceState.started) {
            runOnDefaultDispatcher {
                runCatching { SagerNet.reloadService() }
            }
        }
    }
}
