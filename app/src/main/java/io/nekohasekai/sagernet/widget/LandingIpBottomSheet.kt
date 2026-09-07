package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
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
