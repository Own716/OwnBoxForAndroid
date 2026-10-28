package io.nekohasekai.sagernet.ui.profiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.ktx.getColorAttr
import java.text.DateFormat
import java.util.Date
import java.util.Locale

val ProxyGroup.isSubscription: Boolean
    get() = type == GroupType.SUBSCRIPTION || (subscription?.link?.isNotEmpty() == true)

val ProxyGroup.subInfo: SubUserInfo
    get() {
        val raw = subscription?.subscriptionUserinfo
        if (!raw.isNullOrBlank()) {
            val parsed = SubUserInfo.parseHeader(raw)
            if (parsed.valid) return parsed
        }
        val used = subscription?.bytesUsed ?: 0L
        val remain = subscription?.bytesRemaining ?: 0L
        val total = if (used > 0L || remain > 0L) used + remain else 0L
        val expire = (subscription?.expiryDate ?: 0).toLong()
        if (total > 0L || expire > 0L) {
            return SubUserInfo(valid = true, hasQuota = total > 0L, upload = 0L, download = used, total = total, expire = expire)
        }
        return SubUserInfo()
    }

val ProxyGroup.subLastUpdate: Long
    get() = (subscription?.lastUpdated ?: 0).toLong()

/**
 * SubscriptionInfoCard (SubscriptionInfoCard.cpp) for the current group: its name and the provider's title, the quota
 * bar, the time left, the portal and support links and the announcement.
 */
class SubscriptionInfoCard(val view: View) {

    companion object {
        private val SIZE_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB")

        // Panels stamping expire as fetch time + N days land on a unit boundary; a count this close to stepping down shows stepped.
        private const val COUNTDOWN_GRACE = 5 * 60L

        /** ReadableSize (Utils.cpp:223-243): 1024-based, two decimals. */
        fun readableSize(size: Long): String {
            var value = size.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < SIZE_UNITS.size - 1) {
                value /= 1024.0
                unit++
            }
            return String.format(Locale.ROOT, "%.2f %s", value, SIZE_UNITS[unit])
        }

        /** DisplayTime(seconds, ShortFormat). */
        fun displayTime(seconds: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

        private fun nowSeconds() = System.currentTimeMillis() / 1000

        /** expiryText (SubscriptionInfoCard.cpp:322-334): the time left, "Expired", "" without an expiry. */
        fun expiryText(context: Context, expire: Long, now: Long = nowSeconds()): String {
            if (expire <= 0) return ""
            val left = expire - now
            val settled = left - COUNTDOWN_GRACE
            return when {
                left < 0 -> context.getString(R.string.subinfo_expired)
                settled >= 86400 -> context.getString(R.string.subinfo_days_left, settled / 86400)
                settled >= 3600 -> context.getString(R.string.subinfo_hours_left, settled / 3600)
                else -> context.getString(R.string.subinfo_minutes_left, maxOf(1L, left / 60))
            }
        }

        /**
         * ParseSubInfo (GroupItem.cpp:15-30) for the groups screen: the quota with the expiry date, then the time left.
         */
        fun summary(context: Context, info: SubUserInfo): String {
            if (!info.valid) return ""
            val parts = ArrayList<String>()
            if (info.hasQuota) {
                val used = readableSize(info.used)
                val remain = if (info.total > 0) readableSize(info.remaining) else "∞"
                parts.add(
                    if (info.expire > 0) context.getString(R.string.grp_sub_info, used, remain, displayTime(info.expire))
                    else context.getString(R.string.grp_sub_info_no_expire, used, remain)
                )
            }
            if (info.expire > 0) parts.add(expiryText(context, info.expire))
            return parts.joinToString(" · ")
        }

        /** linkUrl (SubscriptionInfoCard.cpp:125-130): http(s), and tg: for the support link. */
        private fun linkUri(raw: String, allowTg: Boolean): Uri? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val uri = Uri.parse(text)
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> uri.takeIf { !it.host.isNullOrEmpty() }
                "tg" -> uri.takeIf { allowTg }
                else -> null
            }
        }
    }

    private val context: Context = view.context
    private val title: TextView = view.findViewById(R.id.sub_card_title)
    private val expiry: View = view.findViewById(R.id.sub_card_expiry)
    private val expiryIcon: ImageView = view.findViewById(R.id.sub_card_expiry_icon)
    private val expiryLabel: TextView = view.findViewById(R.id.sub_card_expiry_text)
    private val portal: View = view.findViewById(R.id.sub_card_portal)
    private val support: View = view.findViewById(R.id.sub_card_support)
    private val quota: View = view.findViewById(R.id.sub_card_quota)
    private val quotaBar: LinearProgressIndicator = view.findViewById(R.id.sub_card_quota_bar)
    private val quotaLabel: TextView = view.findViewById(R.id.sub_card_quota_text)
    private val announce: View = view.findViewById(R.id.sub_card_announce)
    private val announceLabel: TextView = view.findViewById(R.id.sub_card_announce_text)

    private val secondary = context.getColorAttr(android.R.attr.textColorSecondary)
    private val danger = context.getColorAttr(R.attr.colorError)
    private val ok = ContextCompat.getColor(context, R.color.material_green_500)

    private var groupName = ""
    private var portalUri: Uri? = null
    private var supportUri: Uri? = null
    private var fullAnnounce = ""

    /** The last [bind] had something to show. */
    var wanted = false
        private set

    init {
        quotaBar.trackColor = ColorUtils.setAlphaComponent(context.getColorAttr(android.R.attr.textColorPrimary), 0x1F)
        portal.setOnClickListener { portalUri?.let(::open) }
        support.setOnClickListener { supportUri?.let(::open) }
        announce.setOnClickListener { showAnnouncement() }
    }

    /** [group]: the current tab's, null for none. Also redraws the time left. */
    fun bind(group: ProxyGroup?) {
        wanted = group != null && group.isSubscription && render(group, group.subInfo)
        view.isVisible = wanted
    }

    /** updateData (SubscriptionInfoCard.cpp:469-544); false when there is nothing to show. */
    private fun render(group: ProxyGroup, info: SubUserInfo): Boolean {
        if (!info.valid) return false
        val now = nowSeconds()
        groupName = group.displayName()
        val provider = info.title.trim().takeIf { it.isNotEmpty() && it != groupName }
        title.text = if (provider == null) groupName else SpannableStringBuilder(groupName).append(" · ")
            .append(provider, ForegroundColorSpan(secondary), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val tip = arrayListOf(groupName)
        if (provider != null) tip.add(context.getString(R.string.subinfo_provider, provider))
        if (group.subLastUpdate > 0) tip.add(context.getString(R.string.grp_last_update, displayTime(group.subLastUpdate)))
        TooltipCompat.setTooltipText(title, tip.joinToString("\n"))

        quota.isVisible = info.hasQuota
        if (info.hasQuota) {
            val percent = info.percentUsed.toInt()
            quotaBar.progress = percent
            val color = if (percent >= 90) danger else ok
            quotaBar.setIndicatorColor(color)
            quotaLabel.text = context.getString(
                if (info.total > 0) R.string.subinfo_quota else R.string.subinfo_quota_unlimited,
                readableSize(info.used),
                readableSize(info.total),
                percent,
            )
        }

        val left = expiryText(context, info.expire, now)
        expiry.isVisible = left.isNotEmpty()
        if (left.isNotEmpty()) {
            expiryLabel.text = left
            val expired = info.isExpired(now)
            val color = if (expired) danger else secondary
            expiryLabel.setTextColor(color)
            expiryIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            val expires = displayTime(info.expire)
            TooltipCompat.setTooltipText(expiry, context.getString(R.string.subinfo_expires, expires))
        }

        portalUri = linkUri(info.webUrl, allowTg = false)
        portal.isVisible = portalUri != null
        portalUri?.let { TooltipCompat.setTooltipText(portal, context.getString(R.string.subinfo_portal_tip, it.toString())) }

        supportUri = linkUri(info.supportUrl, allowTg = true)
        support.isVisible = supportUri != null
        supportUri?.let { TooltipCompat.setTooltipText(support, context.getString(R.string.subinfo_support_tip, it.toString())) }

        fullAnnounce = info.announce.trim()
        val oneliner = fullAnnounce.replace(Regex("\\s+"), " ")
        announce.isVisible = oneliner.isNotEmpty()
        announceLabel.text = oneliner
        return true
    }

    /** QDesktopServices::openUrl: the browser, or the app of a tg: link. */
    private fun open(uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.subinfo_no_app, uri.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAnnouncement() {
        if (fullAnnounce.isEmpty()) return
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.subinfo_announcement_title, groupName))
            .setMessage(fullAnnounce)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }
}
