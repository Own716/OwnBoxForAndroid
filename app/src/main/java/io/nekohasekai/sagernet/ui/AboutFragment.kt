package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.Properties

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutAboutBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        binding.license.maxLines = LICENSE_COLLAPSED_MAX_LINES
        var isLicenseExpanded = false
        binding.licenseToggle.setOnClickListener {
            val scrollY = binding.aboutScroll.scrollY
            isLicenseExpanded = !isLicenseExpanded
            binding.license.maxLines = if (isLicenseExpanded) {
                Int.MAX_VALUE
            } else {
                LICENSE_COLLAPSED_MAX_LINES
            }
            binding.licenseIndicator.text = if (isLicenseExpanded) "▲" else "▼"
            binding.aboutScroll.doOnPreDraw {
                binding.aboutScroll.scrollTo(0, scrollY)
            }
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()

        runOnDefaultDispatcher {
            val license = view.context.assets.open("LICENSE").bufferedReader().readText()
            onMainDispatcher {
                binding.license.text = license
                Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
            }
        }
    }

    companion object {
        private const val LICENSE_COLLAPSED_MAX_LINES = 8
    }

    class AboutContent : MaterialAboutFragment() {

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            if (resultCode == Activity.RESULT_OK) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.about_fragment_holder, AboutContent())
                    .commitAllowingStateLoss()
            }
        }

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/Own716/OwnBoxForAndroid/releases"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_release)
                                .setOnClickAction {
                                    checkUpdate(targetIsPreview = false)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_preview)
                                .setOnClickAction {
                                    checkUpdate(targetIsPreview = true)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(getString(R.string.version_x, "sing-box"))
                                .subText(Libcore.versionBox())
                                .setOnClickAction { }
                                .build())
                        .apply {
                            PackageCache.awaitLoadSync()
                            for ((_, pkg) in PackageCache.installedPluginPackages) {
                                try {
                                    val pluginId =
                                        pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                                    if (pluginId.isNullOrBlank()) continue
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_nfc_24)
                                            .text(
                                                getString(
                                                    R.string.version_x,
                                                    pluginId
                                                ) + " (${Plugins.displayExeProvider(pkg.packageName)})"
                                            )
                                            .subText("v" + pkg.versionName)
                                            .setOnClickAction {
                                                startActivity(Intent().apply {
                                                    action =
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    data = Uri.fromParts(
                                                        "package", pkg.packageName, null
                                                    )
                                                })
                                            }
                                            .build())
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                        }
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_running_with_errors_24)
                                            .text(R.string.ignore_battery_optimizations)
                                            .subText(R.string.ignore_battery_optimizations_sum)
                                            .setOnClickAction {
                                                requestIgnoreBatteryOptimizations.launch(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        "package:${app.packageName}".toUri()
                                                    )
                                                )
                                            }
                                            .build())
                                }
                            }
                        }
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .title(R.string.project)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_sanitizer_24)
                                .text(R.string.github)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/Own716/OwnBoxForAndroid"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_qu_shadowsocks_foreground)
                                .text(R.string.telegram)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://t.me/OwnBoxs"
                                    )
                                }
                                .build())
                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            view.findViewById<RecyclerView>(R.id.mal_recyclerview)?.apply {
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = (layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )).apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }

                val cardStrokeWidth = resources.getDimensionPixelSize(
                    R.dimen.card_stroke_width
                )
                val cardStrokeColor = ContextCompat.getColor(
                    requireContext(),
                    R.color.card_stroke
                )
                val cardCornerRadius = resources.getDimension(
                    R.dimen.card_corner_radius
                )
                fun applyApplicationCardStyle(child: View) {
                    (child as? MaterialCardView)?.apply {
                        strokeWidth = cardStrokeWidth
                        strokeColor = cardStrokeColor
                        radius = cardCornerRadius
                        cardElevation = 0f
                    }
                }

                addOnChildAttachStateChangeListener(
                    object : RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(child: View) {
                            applyApplicationCardStyle(child)
                        }

                        override fun onChildViewDetachedFromWindow(child: View) = Unit
                    }
                )
                for (index in 0 until childCount) {
                    applyApplicationCardStyle(getChildAt(index))
                }
            }
        }

        fun checkUpdate(targetIsPreview: Boolean = false) {
            runOnIoDispatcher {
                try {
                    val client = Libcore.newHttpClient().apply {
                        modernTLS()
                        tryProxyOutbound()
                    }
                    val isCurrentlyPreview = isPreview
                    val currentCompareVersion = if (isCurrentlyPreview && BuildConfig.PRE_VERSION_NAME.isNotBlank()) {
                        BuildConfig.PRE_VERSION_NAME
                    } else {
                        BuildConfig.VERSION_NAME
                    }

                    var releaseName: String? = null
                    var releaseUrl: String? = null

                    // 1. 首先尝试 GitHub Releases API
                    try {
                        if (targetIsPreview) {
                            // 检查预览版更新：抓取 releases 列表，精准匹配首个 prerelease
                            val response = client.newRequest().apply {
                                setURL("https://api.github.com/repos/Own716/OwnBoxForAndroid/releases")
                                setUserAgent("OwnBox/${BuildConfig.VERSION_NAME}")
                                setHeader("Accept", "application/vnd.github.v3+json")
                            }.execute()
                            val responseStr = Util.getStringBox(response.contentString)
                            val arr = JSONArray(responseStr)
                            for (i in 0 until arr.length()) {
                                val rel = arr.getJSONObject(i)
                                if (rel.optBoolean("draft", false)) continue
                                val tag = rel.optString("tag_name")
                                val name = rel.optString("name")
                                val isPre = rel.optBoolean("prerelease", false) ||
                                        tag.contains("preview", ignoreCase = true) ||
                                        tag.contains("beta", ignoreCase = true) ||
                                        tag.contains("alpha", ignoreCase = true) ||
                                        name.contains("preview", ignoreCase = true)
                                if (isPre) {
                                    releaseName = name.ifBlank { tag }
                                    releaseUrl = rel.optString("html_url")
                                    break
                                }
                            }
                        } else {
                            // 检查正式版更新：首先尝试 /releases/latest (官方稳定版接口)
                            try {
                                val response = client.newRequest().apply {
                                    setURL("https://api.github.com/repos/Own716/OwnBoxForAndroid/releases/latest")
                                    setUserAgent("OwnBox/${BuildConfig.VERSION_NAME}")
                                    setHeader("Accept", "application/vnd.github.v3+json")
                                }.execute()
                                val responseStr = Util.getStringBox(response.contentString)
                                val release = JSONObject(responseStr)
                                if (!release.optBoolean("draft", false) && !release.optBoolean("prerelease", false)) {
                                    releaseName = release.optString("name").ifBlank { release.optString("tag_name") }
                                    releaseUrl = release.optString("html_url")
                                }
                            } catch (e: Exception) {
                                Logs.w("GitHub /releases/latest query failed, trying /releases", e)
                            }

                            // 若 /releases/latest 未取到，在 /releases 列表中查找首个非预发布的正式版本
                            if (releaseName.isNullOrBlank()) {
                                val response = client.newRequest().apply {
                                    setURL("https://api.github.com/repos/Own716/OwnBoxForAndroid/releases")
                                    setUserAgent("OwnBox/${BuildConfig.VERSION_NAME}")
                                    setHeader("Accept", "application/vnd.github.v3+json")
                                }.execute()
                                val responseStr = Util.getStringBox(response.contentString)
                                val arr = JSONArray(responseStr)
                                for (i in 0 until arr.length()) {
                                    val rel = arr.getJSONObject(i)
                                    if (rel.optBoolean("draft", false)) continue
                                    val tag = rel.optString("tag_name")
                                    val name = rel.optString("name")
                                    val isPre = rel.optBoolean("prerelease", false) ||
                                            tag.contains("preview", ignoreCase = true) ||
                                            tag.contains("beta", ignoreCase = true) ||
                                            tag.contains("alpha", ignoreCase = true) ||
                                            name.contains("preview", ignoreCase = true)
                                    if (!isPre) {
                                        releaseName = name.ifBlank { tag }
                                        releaseUrl = rel.optString("html_url")
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Logs.w("GitHub API check failed, falling back to static metadata", e)
                    }

                    // 2. 若 API 受到 403 限流或网络异常，无缝降级到静态 properties 源 (全球高速 CDN 托管，无 API 速率限制)
                    if (releaseName.isNullOrBlank() || releaseUrl.isNullOrBlank()) {
                        val staticUrls = listOf(
                            "https://cdn.jsdelivr.net/gh/Own716/OwnBoxForAndroid@main/nb4a.properties",
                            "https://fastly.jsdelivr.net/gh/Own716/OwnBoxForAndroid@main/nb4a.properties",
                            "https://raw.githubusercontent.com/Own716/OwnBoxForAndroid/main/nb4a.properties"
                        )
                        var propsContent: String? = null
                        for (staticUrl in staticUrls) {
                            try {
                                val resp = client.newRequest().apply {
                                    setURL(staticUrl)
                                    setUserAgent("OwnBox/${BuildConfig.VERSION_NAME}")
                                }.execute()
                                val body = Util.getStringBox(resp.contentString)
                                if (body.contains("VERSION_NAME=")) {
                                    propsContent = body
                                    break
                                }
                            } catch (e: Exception) {
                                Logs.w("Failed to fetch $staticUrl: ${e.message}")
                            }
                        }
                        if (!propsContent.isNullOrBlank()) {
                            val props = Properties().apply {
                                load(StringReader(propsContent))
                            }
                            val targetVer = if (targetIsPreview) {
                                (props.getProperty("PRE_VERSION_NAME") ?: props.getProperty("VERSION_NAME")).orEmpty().trim()
                            } else {
                                (props.getProperty("LATEST_RELEASE_VERSION") ?: props.getProperty("VERSION_NAME")).orEmpty().trim()
                            }
                            if (targetVer.isNotBlank()) {
                                releaseName = "v$targetVer"
                                releaseUrl = "https://github.com/Own716/OwnBoxForAndroid/releases/tag/v$targetVer"
                            }
                        }
                    }

                    if (releaseName.isNullOrBlank() || releaseUrl.isNullOrBlank()) {
                        throw IllegalStateException("无法获取版本信息，请检查网络或稍后重试")
                    }

                    val cleanReleaseName = releaseName.replace(Regex("(?i)^Ownbox\\s*"), "")
                    val cmp = compareVersionNames(cleanReleaseName, currentCompareVersion)
                    val haveUpdate = cleanReleaseName.isNotBlank() && cmp > 0

                    runOnMainDispatcher {
                        val context = context ?: return@runOnMainDispatcher
                        if (haveUpdate) {
                            val dialogTitle = if (targetIsPreview) {
                                R.string.update_dialog_title_preview
                            } else {
                                R.string.update_dialog_title_release
                            }
                            MaterialAlertDialogBuilder(context)
                                .setTitle(dialogTitle)
                                .setMessage(
                                    context.getString(
                                        R.string.update_dialog_message,
                                        SagerNet.appVersionNameForDisplay,
                                        releaseName
                                    )
                                )
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    val intent = Intent(Intent.ACTION_VIEW, releaseUrl.toUri())
                                    context.startActivity(intent)
                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        } else {
                            if (!targetIsPreview && isCurrentlyPreview) {
                                Toast.makeText(
                                    app,
                                    context.getString(
                                        R.string.check_update_release_no_but_preview_newer,
                                        releaseName,
                                        SagerNet.appVersionNameForDisplay
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else if (targetIsPreview) {
                                Toast.makeText(app, R.string.check_update_preview_no, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(app, R.string.check_update_release_no, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    runOnMainDispatcher {
                        Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        companion object {

            private val numberRegex = Regex("\\d+")

            /**
             * Compares two version strings according to Semantic Versioning principles:
             * 1. Strips leading 'v', 'V', or 'OwnBox ' prefix.
             * 2. Extracts base version numbers (before '-') and compares them numerically segment by segment.
             * 3. When numeric segments are identical:
             *    - A version without a prerelease suffix (e.g. "26.11.2") is HIGHER than a version with a prerelease suffix (e.g. "26.11.2-preview").
             *    - If both have prerelease suffixes, compares the numbers in the suffix.
             *
             * Returns > 0 if [a] is newer than [b], < 0 if older, and 0 if equal.
             */
            fun compareVersionNames(a: String, b: String): Int {
                val cleanA = cleanVersion(a)
                val cleanB = cleanVersion(b)

                val baseA = cleanA.substringBefore("-")
                val baseB = cleanB.substringBefore("-")

                val numsA = extractVersionNumbers(baseA)
                val numsB = extractVersionNumbers(baseB)

                val maxLen = maxOf(numsA.size, numsB.size)
                for (i in 0 until maxLen) {
                    val na = numsA.getOrElse(i) { 0L }
                    val nb = numsB.getOrElse(i) { 0L }
                    if (na != nb) {
                        return if (na > nb) 1 else -1
                    }
                }

                // Base numbers are completely identical
                val isPreA = cleanA.contains("-") || isPreKeyword(cleanA)
                val isPreB = cleanB.contains("-") || isPreKeyword(cleanB)

                if (!isPreA && isPreB) {
                    // a is a stable release, b is a preview of the same base -> a is newer!
                    return 1
                }
                if (isPreA && !isPreB) {
                    // a is a preview, b is a stable release of the same base -> a is older!
                    return -1
                }
                if (isPreA && isPreB) {
                    // Both are prereleases: compare numbers in the suffix if any
                    val suffixA = cleanA.substringAfter("-", "")
                    val suffixB = cleanB.substringAfter("-", "")
                    val sufNumsA = extractNumbers(suffixA)
                    val sufNumsB = extractNumbers(suffixB)
                    val maxSufLen = maxOf(sufNumsA.size, sufNumsB.size)
                    if (maxSufLen > 0) {
                        for (i in 0 until maxSufLen) {
                            val sa = sufNumsA.getOrElse(i) { 0L }
                            val sb = sufNumsB.getOrElse(i) { 0L }
                            if (sa != sb) {
                                return if (sa > sb) 1 else -1
                            }
                        }
                    }
                    return suffixA.compareTo(suffixB)
                }

                return 0
            }

            private fun cleanVersion(v: String): String =
                v.trim()
                    .replace(Regex("(?i)^Ownbox\\s*"), "")
                    .replace(Regex("(?i)^v"), "")
                    .trim()

            private fun isPreKeyword(s: String): Boolean =
                s.contains("preview", ignoreCase = true) ||
                        s.contains("beta", ignoreCase = true) ||
                        s.contains("alpha", ignoreCase = true) ||
                        s.contains("rc", ignoreCase = true)

            private fun extractVersionNumbers(base: String): List<Long> =
                base.split(".").mapNotNull { it.trim().toLongOrNull() }

            private fun extractNumbers(segment: String): List<Long> =
                numberRegex.findAll(segment).mapNotNull { it.value.toLongOrNull() }.toList()

        }

    }

}
