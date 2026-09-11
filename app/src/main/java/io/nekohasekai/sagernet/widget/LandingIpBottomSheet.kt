package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import android.graphics.Color
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.LandingIpInfo

object LandingIpBottomSheet {

    fun show(
        activity: Activity,
        info: LandingIpInfo,
        onRefresh: () -> Unit,
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.layout_landing_ip_details, null)
        dialog.setContentView(view)

        val tvLocation = view.findViewById<TextView>(R.id.tv_location)
        val tvFullIp = view.findViewById<TextView>(R.id.tv_full_ip)
        val tvIsp = view.findViewById<TextView>(R.id.tv_isp)
        val tvAsn = view.findViewById<TextView>(R.id.tv_asn)
        val tvDuration = view.findViewById<TextView>(R.id.tv_duration)
        val btnCopyIp = view.findViewById<MaterialButton>(R.id.btn_copy_ip)
        val btnRetest = view.findViewById<MaterialButton>(R.id.btn_retest)
        val btnDismiss = view.findViewById<MaterialButton>(R.id.btn_dismiss)

        tvLocation.text = info.locationText
        tvFullIp.text = info.ip
        tvIsp.text = if (info.isp.isNotBlank()) info.isp else activity.getString(R.string.unknown)
        tvAsn.text = if (info.asn.isNotBlank()) info.asn else activity.getString(R.string.unknown)
        tvDuration.text = "${info.durationMs} ms"

        val isWhite = io.nekohasekai.sagernet.utils.Theme.isWhiteTheme()
        val isNight = io.nekohasekai.sagernet.utils.Theme.usingNightMode()

        val cardBgColor = when {
            isWhite -> android.graphics.Color.parseColor("#F5F6F8")
            isNight -> android.graphics.Color.parseColor("#1E1E1E")
            else -> null
        }
        val strokeColor = when {
            isWhite -> android.graphics.Color.parseColor("#E0E0E0")
            isNight -> android.graphics.Color.parseColor("#333333")
            else -> null
        }
        val primaryTextColor = when {
            isWhite -> android.graphics.Color.parseColor("#1A1A1A")
            isNight -> android.graphics.Color.parseColor("#F5F5F5")
            else -> null
        }
        val secondaryTextColor = when {
            isWhite -> android.graphics.Color.parseColor("#5A5A5A")
            isNight -> android.graphics.Color.parseColor("#B0B0B0")
            else -> null
        }

        if (cardBgColor != null) {
            view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_ip)?.setCardBackgroundColor(cardBgColor)
            view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_details)?.setCardBackgroundColor(cardBgColor)
        }
        if (strokeColor != null) {
            view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_ip)?.strokeColor = strokeColor
            view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_details)?.strokeColor = strokeColor
            view.findViewById<android.view.View>(R.id.divider1)?.setBackgroundColor(strokeColor)
            view.findViewById<android.view.View>(R.id.divider2)?.setBackgroundColor(strokeColor)
        }
        if (primaryTextColor != null) {
            view.findViewById<TextView>(R.id.tv_title)?.setTextColor(primaryTextColor)
            tvFullIp.setTextColor(primaryTextColor)
            tvIsp.setTextColor(primaryTextColor)
            tvAsn.setTextColor(primaryTextColor)
            tvDuration.setTextColor(primaryTextColor)
        }
        if (secondaryTextColor != null) {
            tvLocation.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_ip)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_isp)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_asn)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_duration)?.setTextColor(secondaryTextColor)
        }

        val primaryColor = activity.getColorAttr(R.attr.colorPrimary)
        val primaryStateList = ColorStateList.valueOf(primaryColor)

        btnCopyIp.setTextColor(primaryStateList)
        btnCopyIp.strokeColor = primaryStateList
        btnCopyIp.iconTint = primaryStateList

        btnRetest.setTextColor(primaryStateList)
        btnRetest.strokeColor = primaryStateList

        btnDismiss.backgroundTintList = primaryStateList
        btnDismiss.setTextColor(Color.WHITE)

        btnCopyIp.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("IP", info.ip)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, activity.getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
        }

        btnRetest.setOnClickListener {
            dialog.dismiss()
            onRefresh()
        }

        btnDismiss.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
