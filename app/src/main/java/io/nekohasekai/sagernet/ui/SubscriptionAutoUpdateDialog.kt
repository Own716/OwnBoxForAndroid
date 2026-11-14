package io.nekohasekai.sagernet.ui

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
import io.nekohasekai.sagernet.ktx.dp2px

object SubscriptionAutoUpdateDialog {

    val PRESET_MINUTES = listOf(15, 30, 60, 180, 360, 720, 1440)

    fun formatSummary(context: Context, minutes: Int, enabled: Boolean): String {
        if (!enabled) {
            return context.getString(R.string.subscription_auto_update_disabled)
        }
        return formatInterval(context, minutes)
    }

    fun formatInterval(context: Context, minutes: Int): String {
        return when {
            minutes <= 0 -> context.getString(R.string.subscription_auto_update_disabled)
            minutes % 1440 == 0 -> {
                val days = minutes / 1440
                if (days == 1) {
                    context.getString(R.string.subscription_auto_update_1_day)
                } else {
                    context.getString(R.string.subscription_auto_update_days, days)
                }
            }
            minutes % 60 == 0 -> {
                val hours = minutes / 60
                if (hours == 1) {
                    context.getString(R.string.subscription_auto_update_1_hour)
                } else {
                    context.getString(R.string.subscription_auto_update_hours, hours)
                }
            }
            else -> {
                context.getString(R.string.subscription_auto_update_minutes, minutes)
            }
        }
    }

    fun show(
        context: Context,
        currentMinutes: Int,
        onSelected: (Int) -> Unit
    ) {
        val labels = arrayOf(
            context.getString(R.string.subscription_auto_update_minutes, 15),
            context.getString(R.string.subscription_auto_update_minutes, 30),
            context.getString(R.string.subscription_auto_update_1_hour),
            context.getString(R.string.subscription_auto_update_hours, 3),
            context.getString(R.string.subscription_auto_update_hours, 6),
            context.getString(R.string.subscription_auto_update_hours, 12),
            context.getString(R.string.subscription_auto_update_1_day),
            context.getString(R.string.subscription_auto_update_custom)
        )

        val presetIndex = PRESET_MINUTES.indexOf(currentMinutes)
        val checkedItem = if (presetIndex >= 0) presetIndex else labels.lastIndex

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.auto_update_delay)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                if (which == labels.lastIndex) {
                    showCustomDialog(context, currentMinutes, onSelected)
                } else {
                    onSelected(PRESET_MINUTES[which])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomDialog(
        context: Context,
        currentMinutes: Int,
        onSelected: (Int) -> Unit
    ) {
        val (initVal, initUnit) = when {
            currentMinutes % 1440 == 0 -> Pair((currentMinutes / 1440).toString(), "d")
            currentMinutes % 60 == 0 -> Pair((currentMinutes / 60).toString(), "h")
            else -> Pair(currentMinutes.coerceAtLeast(15).toString(), "m")
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
        }

        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.subscription_auto_update_custom_hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initVal)
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
            text = context.getString(R.string.subscription_unit_minutes)
            setPadding(0, 0, dp2px(16), 0)
        }
        val radioHours = RadioButton(context).apply {
            id = android.view.View.generateViewId()
            text = context.getString(R.string.subscription_unit_hours)
            setPadding(0, 0, dp2px(16), 0)
        }
        val radioDays = RadioButton(context).apply {
            id = android.view.View.generateViewId()
            text = context.getString(R.string.subscription_unit_days)
        }
        unitGroup.addView(radioMinutes)
        unitGroup.addView(radioHours)
        unitGroup.addView(radioDays)

        val checkedId = when (initUnit) {
            "d" -> radioDays.id
            "h" -> radioHours.id
            else -> radioMinutes.id
        }
        unitGroup.check(checkedId)
        container.addView(unitGroup)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.subscription_auto_update_custom)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val num = input.text?.toString()?.trim()?.toIntOrNull()
            if (num == null || num <= 0) {
                inputLayout.error = context.getString(R.string.subscription_auto_update_invalid)
                return@setOnClickListener
            }
            val totalMinutes = when (unitGroup.checkedRadioButtonId) {
                radioDays.id -> num * 1440
                radioHours.id -> num * 60
                else -> num
            }
            if (totalMinutes < 15) {
                inputLayout.error = context.getString(R.string.subscription_auto_update_min_15m)
                return@setOnClickListener
            }
            dialog.dismiss()
            onSelected(totalMinutes)
        }
    }
}
