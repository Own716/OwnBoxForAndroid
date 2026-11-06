package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.LandingIpInfo
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LandingIpBottomSheet {

    fun show(
        activity: Activity,
        initialInfo: LandingIpInfo,
        onRefresh: () -> Unit = {},
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.layout_landing_ip_details, null)
        dialog.setContentView(view)

        val btnClose = view.findViewById<ImageView>(R.id.btn_close)
        val btnRefresh = view.findViewById<ImageView>(R.id.btn_refresh)
        val tvIpAddress = view.findViewById<TextView>(R.id.tv_ip_address)
        val layoutRowIp = view.findViewById<LinearLayout>(R.id.layout_row_ip)
        val tvLevel = view.findViewById<TextView>(R.id.tv_level)
        val tvType = view.findViewById<TextView>(R.id.tv_type)
        val tvHitTag = view.findViewById<TextView>(R.id.tv_hit_tag)
        val tvOrg = view.findViewById<TextView>(R.id.tv_org)
        val tvAsn = view.findViewById<TextView>(R.id.tv_asn)
        val tvSource = view.findViewById<TextView>(R.id.tv_source)
        val switchHideIp = view.findViewById<SwitchMaterial>(R.id.switch_hide_ip)

        var currentInfo = initialInfo

        val isNight = Theme.usingNightMode(activity)
        val isWhite = Theme.isWhiteTheme(activity)

        val cardBgColor = when {
            isNight -> Color.parseColor("#1E1E1E")
            isWhite -> Color.parseColor("#F5F6F8")
            else -> null
        }
        val strokeColor = when {
            isNight -> Color.parseColor("#333333")
            isWhite -> Color.parseColor("#E0E0E0")
            else -> null
        }
        val primaryTextColor = when {
            isNight -> Color.parseColor("#F5F5F5")
            isWhite -> Color.parseColor("#1A1A1A")
            else -> null
        }
        val secondaryTextColor = when {
            isNight -> Color.parseColor("#B0B0B0")
            isWhite -> Color.parseColor("#5A5A5A")
            else -> null
        }

        if (cardBgColor != null) {
            view.findViewById<MaterialCardView>(R.id.card_basic_info)?.setCardBackgroundColor(cardBgColor)
            view.findViewById<MaterialCardView>(R.id.card_network)?.setCardBackgroundColor(cardBgColor)
            view.findViewById<MaterialCardView>(R.id.card_other)?.setCardBackgroundColor(cardBgColor)
        }
        if (strokeColor != null) {
            view.findViewById<MaterialCardView>(R.id.card_basic_info)?.strokeColor = strokeColor
            view.findViewById<MaterialCardView>(R.id.card_network)?.strokeColor = strokeColor
            view.findViewById<MaterialCardView>(R.id.card_other)?.strokeColor = strokeColor
            view.findViewById<View>(R.id.divider_basic_1)?.setBackgroundColor(strokeColor)
            view.findViewById<View>(R.id.divider_basic_2)?.setBackgroundColor(strokeColor)
            view.findViewById<View>(R.id.divider_basic_3)?.setBackgroundColor(strokeColor)
            view.findViewById<View>(R.id.divider_network_1)?.setBackgroundColor(strokeColor)
            view.findViewById<View>(R.id.divider_network_2)?.setBackgroundColor(strokeColor)
        }
        if (primaryTextColor != null) {
            view.findViewById<TextView>(R.id.tv_title)?.setTextColor(primaryTextColor)
            tvType.setTextColor(primaryTextColor)
            tvHitTag.setTextColor(primaryTextColor)
            tvOrg.setTextColor(primaryTextColor)
            tvAsn.setTextColor(primaryTextColor)
            tvSource.setTextColor(primaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_hide_ip)?.setTextColor(primaryTextColor)
        }
        if (secondaryTextColor != null) {
            view.findViewById<TextView>(R.id.tv_disclaimer)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_header_basic)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_header_network)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_header_other)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_ip_address)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_level)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_type)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_hit_tag)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_org)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_asn)?.setTextColor(secondaryTextColor)
            view.findViewById<TextView>(R.id.tv_label_source)?.setTextColor(secondaryTextColor)
        }

        fun bindInfo(info: LandingIpInfo) {
            currentInfo = info
            val isHidden = DataStore.hideLandingIpAddress
            tvIpAddress.text = if (isHidden) info.getMaskedIp() else info.ip
            tvLevel.text = info.levelLabel.ifBlank { "普通" }
            tvType.text = info.typeLabel.ifBlank { "机房" }
            tvHitTag.text = info.hitTag.ifBlank { "无" }
            tvOrg.text = info.org.ifBlank { activity.getString(R.string.unknown) }
            tvAsn.text = info.asn.ifBlank { activity.getString(R.string.unknown) }
            tvSource.text = info.source.ifBlank { "iplocate.io" }

            // 对齐 FlClash 图二：等级为“优”时，IP 地址和等级显示绿色 #10B981；风险显示红色；普通显示主题色
            when (info.levelLabel) {
                "优" -> {
                    val greenColor = Color.parseColor("#10B981")
                    tvIpAddress.setTextColor(greenColor)
                    tvLevel.setTextColor(greenColor)
                }
                "风险" -> {
                    val redColor = Color.parseColor("#EF4444")
                    tvIpAddress.setTextColor(redColor)
                    tvLevel.setTextColor(redColor)
                }
                else -> {
                    val defaultColor = primaryTextColor ?: Color.parseColor("#1A1A1A")
                    tvIpAddress.setTextColor(defaultColor)
                    tvLevel.setTextColor(defaultColor)
                }
            }
        }

        bindInfo(initialInfo)

        // Switch Hide IP
        switchHideIp.isChecked = DataStore.hideLandingIpAddress
        switchHideIp.setOnCheckedChangeListener { _, isChecked ->
            DataStore.hideLandingIpAddress = isChecked
            tvIpAddress.text = if (isChecked) currentInfo.getMaskedIp() else currentInfo.ip
        }

        // Copy IP on clicking IP row
        val copyListener = View.OnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("IP", currentInfo.ip)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, activity.getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
        }
        layoutRowIp.setOnClickListener(copyListener)
        tvIpAddress.setOnClickListener(copyListener)

        // Close button
        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        // Refresh button
        btnRefresh.setOnClickListener {
            val rotate = RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 600
                repeatCount = Animation.INFINITE
            }
            btnRefresh.startAnimation(rotate)
            btnRefresh.isEnabled = false

            val scope = (activity as? LifecycleOwner)?.lifecycleScope
            if (scope != null) {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        LandingIpManager.queryLandingIp(DataStore.selectedProxy, forceRefresh = true)
                    }
                    btnRefresh.clearAnimation()
                    btnRefresh.isEnabled = true
                    result.onSuccess { newInfo ->
                        bindInfo(newInfo)
                        onRefresh()
                    }.onFailure {
                        Toast.makeText(activity, activity.getString(R.string.landing_ip_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                btnRefresh.clearAnimation()
                btnRefresh.isEnabled = true
                onRefresh()
            }
        }

        dialog.show()
    }
}
