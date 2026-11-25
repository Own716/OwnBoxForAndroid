package io.nekohasekai.sagernet.route

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.BuildConfig

/**
 * Unified 4-layer offline application classification engine shared by:
 * - Per-App Proxy auto-selection (Proxy & Bypass modes in AppManagerActivity)
 * - Route Rule App picker presets (AppListActivity)
 *
 * All classification rules run strictly on-device using package identifiers,
 * curated prefix/token matchers, and Android PackageInfo metadata/SDK fingerprints.
 */
object AppClassifier {

    data class AppSignals(
        val packageName: String,
        val uid: Int = 0,
        val isSystemApp: Boolean = false,
        val permissions: Set<String> = emptySet(),
        val providerAuthorities: Set<String> = emptySet(),
        val providerNames: Set<String> = emptySet(),
        val metaDataKeys: Set<String> = emptySet(),
        val installerPackage: String? = null,
    )

    data class ProxyAutoSelectOptions(
        val includeInternationalBrowsers: Boolean = true,
        val includeGoogleCoreServices: Boolean = true,
        val includePlayStoreHeuristic: Boolean = false,
    )

    data class BypassAutoSelectOptions(
        val includeDomesticApps: Boolean = true,
        val includeChineseOemSystemApps: Boolean = true,
        val includeDomesticBrowsers: Boolean = false,
    )

    enum class AppCategory {
        SELF_OR_EXCLUDED,
        GOOGLE_CORE_SYSTEM,
        FOREIGN_EXCLUSIVE,
        INTERNATIONAL_BROWSER,
        DOMESTIC_BROWSER,
        DOMESTIC_APP,
        CHINESE_OEM_SYSTEM,
        CORE_ANDROID_SYSTEM,
        PLAY_HEURISTIC_FOREIGN,
        UNKNOWN,
    }

    const val PLAY_STORE_PACKAGE = "com.android.vending"

    @Volatile
    private var cachedBundledForeign: Set<String>? = null

    fun loadBundledForeignPackages(context: Context): Set<String> {
        cachedBundledForeign?.let { return it }
        val loaded = runCatching {
            context.assets.open("proxy_packagename.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toSet()
                }
        }.getOrDefault(emptySet())
        if (loaded.isNotEmpty()) {
            cachedBundledForeign = loaded
        }
        return loaded
    }

    fun extractSignals(
        packageName: String,
        packageInfo: PackageInfo,
        pm: PackageManager? = null,
    ): AppSignals {
        val appInfo = packageInfo.applicationInfo
        val isSystemApp = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val permissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        val providerAuthorities = packageInfo.providers
            ?.mapNotNull { it.authority }
            ?.flatMap { it.split(';') }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val providerNames = packageInfo.providers
            ?.mapNotNull { it.name }
            ?.toSet()
            .orEmpty()
        val metaDataKeys = runCatching {
            appInfo?.metaData?.keySet()?.toSet()
        }.getOrNull().orEmpty()

        val installerPackage = if (pm != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(packageName)
                }
            }.getOrNull()
        } else {
            null
        }

        return AppSignals(
            packageName = packageName,
            uid = appInfo?.uid ?: 0,
            isSystemApp = isSystemApp,
            permissions = permissions,
            providerAuthorities = providerAuthorities,
            providerNames = providerNames,
            metaDataKeys = metaDataKeys,
            installerPackage = installerPackage,
        )
    }

    private val GOOGLE_CORE_SYSTEM_PACKAGES = setOf(
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.google.android.gms.setup",
        "com.google.android.googlequicksearchbox",
        "com.google.android.partnersetup",
        "com.google.android.syncadapters.calendar",
        "com.google.android.syncadapters.contacts",
        "com.google.android.backuptransport",
        "com.google.android.play.games",
        "com.mgoogle.android.gms",
        "app.revanced.android.gms",
    )

    private val CORE_ANDROID_SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.telephony",
        "com.android.providers.downloads",
        "com.android.providers.downloads.ui",
        "com.xiaomi.providers.downloads",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.externalstorage",
        "com.android.shell",
        "com.android.phone",
        "com.android.nfc",
        "com.android.bluetooth",
        "com.android.mms",
        "com.android.server.telecom",
        "com.android.cellbroadcastreceiver",
        "com.android.se",
        "com.android.localtransport",
        "com.google.android.inputmethod.latin",
        "com.google.android.inputmethod.pinyin",
    )

    private val LOCAL_BYPASS_SYSTEM_PACKAGES = setOf(
        "com.android.captiveportallogin",
    )

    private val INTERNATIONAL_BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.microsoft.emmx",
        "com.microsoft.emmx.canary",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fenix.nightly",
        "org.mozilla.fennec_aurora",
        "org.mozilla.focus",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.touch",
        "com.opera.gx",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.duckduckgo.mobile.android",
        "com.kiwibrowser.browser",
        "com.yandex.browser",
        "com.yandex.browser.beta",
        "com.yandex.browser.alpha",
        "org.torproject.torbrowser",
        "org.torproject.torbrowser_alpha",
        "app.vanadium.browser",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "mark.via.gp",
        "org.cromite.cromite",
        "us.spotco.fennec_dos",
        "com.alohamobile.browser",
    )

    private val INTERNATIONAL_BROWSER_PREFIXES = listOf(
        "com.microsoft.emmx.",
        "com.chrome.",
        "org.chromium.",
        "org.mozilla.",
        "com.brave.browser",
        "com.vivaldi.browser",
        "com.opera.",
        "com.yandex.browser",
        "org.torproject.torbrowser",
        "app.vanadium.",
        "com.sec.android.app.sbrowser",
        "org.cromite.",
        "com.kiwibrowser.",
    )

    private val DOMESTIC_BROWSER_PACKAGES = setOf(
        "com.UCMobile",
        "com.UCMobile.intl",
        "com.tencent.mtt",
        "com.quark.browser",
        "com.baidu.searchbox",
        "com.baidu.searchbox.lite",
        "com.mi.globalbrowser",
        "com.android.browser",
        "com.heytap.browser",
        "com.coloros.browser",
        "com.vivo.browser",
        "com.huawei.browser",
        "com.hihonor.browser",
        "mark.via",
        "com.qihoo.browser",
        "sogou.mobile.explorer",
        "com.mx.browser",
        "com.xbrowser.play",
        "com.mmbox.xbrowser",
    )

    private val FOREIGN_EXCLUSIVE_PACKAGES = SmartRouteRules.CORE_FOREIGN_PACKAGES + setOf(
        // AI & Productivity
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",
        "com.facebook.aura",
        "com.app.museai",
        "com.museai.aiart.genai",
        "ai.perplexity.app.android",
        "ai.x.grok",
        "com.microsoft.copilot",
        "com.quora.poe",
        "ai.character.app",
        "com.github.android",
        "io.notion.app",
        "com.deepl.mobiletranslator",
        "com.midjourney.android",
        "com.manus.im.app",
        // Telegram Official & Forks
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
        "org.telegram.multi",
        "org.thunderdog.challegram",
        "tw.nekomimi.nekogram",
        "nekox.messenger",
        "uz.unnarsx.cherrygram",
        "com.radolyn.ayugram",
        "xyz.nextalone.nagram",
        "xyz.nextalone.nnngram",
        "nu.gpu.nagram",
        "nu.gpu.nagramx",
        "nu.gpu.nagramxturbo",
        "com.exteragram.messenger",
        "com.exteraless.messenger",
        "org.forkgram.messenger",
        "it.octogram.android",
        "top.nkbe.niagram",
        "com.iMe.android",
        "org.vidogram.messenger",
        "org.aka.messenger",
        "com.nicegram.android",
        "app.nicegram",
        "com.turrit.messenger",
        // Streaming, Social, Finance, Dev & Utilities
        "com.disney.disneyplus",
        "com.amazon.avod.thirdpartyclient",
        "com.wbd.stream",
        "com.coinbase.android",
        "com.bybit.app",
        "com.kraken.trade",
        "com.bitget.exchange",
        "com.kubi.kucoin",
        "io.metamask",
        "com.wallet.crypto.trustapp",
        "com.paypal.android.p2pmobile",
        "com.slack",
        "com.medium.reader",
        "org.wikipedia",
        "com.dropbox.android",
        "mega.privacy.android.app",
        "ch.protonmail.android",
        "me.proton.android.drive",
        "com.aurora.store",
        "org.fdroid.fdroid",
        "com.apkpure.aegon",
        "com.termux",
        "com.duolingo",
        "com.patreon.android",
        "jp.pxv.android",
        "com.perol.asdpl.pixivez",
        "com.taptap.global",
        "com.tradingview.tradingviewapp",
        "com.authy.authy",
        "com.x8bit.bitwarden",
        "com.bitwarden.authenticator",
        "com.onepassword.android",
        "com.readdle.spark",
        "org.schabi.newpipe",
        "com.deniscerri.ytdl",
        "com.metrolist.music",
        "im.vector.app",
        "chat.simplex.app",
        "network.loki.messenger",
        "org.joinmastodon.android",
        "xyz.blueskyweb.app",
    )

    private val FOREIGN_PACKAGE_PREFIXES = listOf(
        "com.google.android.apps.",
        "org.telegram.",
        "xyz.nextalone.",
        "nu.gpu.nagram",
        "com.exteragram.",
        "com.exteraless.",
        "com.radolyn.ayugram",
        "uz.unnarsx.cherrygram",
        "tw.nekomimi.nekogram",
        "org.forkgram.",
        "it.octogram.",
        "com.openai.",
        "com.anthropic.",
        "ai.perplexity.",
        "ai.character.",
        "com.museai.",
        "com.facebook.",
        "com.instagram.",
        "com.twitter.",
        "com.whatsapp",
        "com.netflix.",
        "com.spotify.",
        "com.valvesoftware.",
        "com.supercell.",
        "app.revanced.",
        "app.rvx.",
        "ch.proton",
        "me.proton",
        "com.binance.",
        "com.coinbase.",
        "com.bybit.",
        "com.okinc.",
        "com.discord",
    )

    private val TELEGRAM_PACKAGE_TOKENS = setOf(
        "nagram",
        "nagramx",
        "nagramxturbo",
        "nnngram",
        "niagram",
        "nekogram",
        "cherrygram",
        "exteragram",
        "exteraless",
        "ayugram",
        "octogram",
        "forkgram",
        "nicegram",
        "turrit",
        "challegram",
        "telegram",
    )

    private val CHINESE_OEM_SYSTEM_PREFIXES = setOf(
        "com.xiaomi.",
        "com.miui.",
        "com.mi.",
        "com.huawei.",
        "com.hihonor.",
        "com.honor.",
        "com.heytap.",
        "com.coloros.",
        "com.oppo.",
        "com.oplus.",
        "com.nearme.",
        "com.oneplus.",
        "com.vivo.",
        "com.bbk.",
        "com.iqoo.",
        "com.meizu.",
        "com.flyme.",
        "com.nubia.",
        "com.zte.",
        "com.lenovo.",
        "com.zui.",
        "com.transsion.",
        "com.realme.",
    )

    private val DOMESTIC_EXPLICIT_PACKAGES = SmartRouteRules.domesticExplicitPackages("cn") + setOf(
        // Chinese AI apps
        "com.deepseek.chat",
        "com.moonshot.kimichat",
        "com.larus.nova",
        "com.tencent.hunyuan.app.chat",
        "com.baidu.newapp",
        "com.aliyun.tongyi",
        "com.zhipuai.qingyan",
        "com.minimaxi.m1",
        // Major CN banks & tools
        "com.tencent.weread",
        "com.ximalaya.ting.android",
        "com.gotokeep.keep",
        "com.chaoxing.mobile",
        "com.xueqiu.android",
        "com.hexin.plat.android",
        "com.eastmoney.android.berlin",
        "com.chinamworld.main",
        "com.chinamworld.bocmbci",
        "com.icbc",
        "com.icbc.im",
        "com.icbc.elife",
        "cmb.pb",
        "com.android.bankabc",
        "com.bankcomm.Bankcomm",
        "com.yitong.mbank.psbc",
        "com.pingan.paces.ccms",
        "com.spdbccc.app",
        "com.cib.cibmb",
        "com.ecitic.bank.mobile",
        "cn.com.cmbc.newmbank",
        "com.cebbank.mobile.cemb",
        "com.alicloud.databox",
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.tencent.wetype",
        "com.hpbr.bosszhipin",
        "com.lianjia.beike",
        "com.hellobike.app",
    )

    private val DOMESTIC_NON_OEM_PREFIXES = (
        SmartRouteRules.domesticPackagePrefixes("cn")
            .filterNot { it in CHINESE_OEM_SYSTEM_PREFIXES } +
            listOf(
                "com.deepseek.",
                "com.moonshot.",
                "com.xingin.",
                "com.zhihu.",
                "com.douban.",
                "com.kugou.",
                "com.kuaishou.",
                "com.bilibili.",
                "tv.danmaku.",
                "com.ctrip.",
                "ctrip.",
                "com.sdu.didi.",
                "com.sf.",
                "com.cainiao.",
                "com.unionpay.",
                "com.chinaums.",
                "com.iflytek.",
                "com.sogou.",
                "com.wps.",
                "cn.wps.",
            )
        ).distinct()

    private val DOMESTIC_META_DATA_KEYS = setOf(
        "UMENG_APPKEY",
        "UMENG_CHANNEL",
        "JPUSH_APPKEY",
        "JPUSH_CHANNEL",
        "GETUI_APP_ID",
        "GETUI_APPID",
        "com.amap.api.v2.apikey",
        "com.baidu.lbsapi.API_KEY",
        "TencentMapSDK",
        "com.tencent.rdm.uuid",
        "TA_APPKEY",
        "TD_APP_ID",
        "BDAPPID",
        "ALIPAY_APPID",
    )

    private val DOMESTIC_SDK_COMPONENT_PREFIXES = listOf(
        "com.tencent.mm.opensdk",
        "com.alipay.sdk",
        "com.umeng.",
        "cn.jpush.",
        "cn.jiguang.",
        "com.igexin.",
        "com.getui.",
        "com.amap.api",
        "com.baidu.lbsapi",
        "com.baidu.mobads",
        "com.bytedance.sdk.openadsdk",
        "com.qq.e.",
        "com.tencent.tauth",
        "com.sina.weibo.sdk",
        "com.xiaomi.mipush",
        "com.heytap.msp.push",
        "com.vivo.push",
        "com.meizu.cloud.pushsdk",
        "com.taobao.accs",
        "com.alibaba.sdk.android",
    )

    private val DOMESTIC_PERMISSION_MARKERS = listOf(
        ".permission.MIPUSH_RECEIVE",
        ".permission.JPUSH_MESSAGE",
        "cn.jpush.",
        "com.coloros.mcs.permission.RECIEVE_MCS_MESSAGE",
        "com.heytap.mcs.permission.RECIEVE_MCS_MESSAGE",
    )

    fun hasTelegramPackageToken(packageName: String): Boolean {
        val tokens = packageName.lowercase().split('.', '_')
        return tokens.any { it in TELEGRAM_PACKAGE_TOKENS }
    }

    fun hasTelegramManifestFingerprint(signals: AppSignals): Boolean {
        return signals.permissions.any { it.contains("org.telegram.", ignoreCase = true) } ||
            signals.providerAuthorities.any { it.contains("org.telegram.", ignoreCase = true) } ||
            signals.providerNames.any { it.contains("org.telegram.", ignoreCase = true) } ||
            signals.metaDataKeys.any { it.contains("org.telegram.", ignoreCase = true) }
    }

    fun hasDomesticSdkFingerprint(signals: AppSignals): Boolean {
        if (signals.metaDataKeys.any { it in DOMESTIC_META_DATA_KEYS }) {
            return true
        }
        val providerEntries = signals.providerAuthorities + signals.providerNames
        if (providerEntries.any { entry ->
                DOMESTIC_SDK_COMPONENT_PREFIXES.any { prefix -> entry.contains(prefix) }
            }
        ) {
            return true
        }
        if (signals.permissions.any { perm ->
                DOMESTIC_PERMISSION_MARKERS.any { marker ->
                    perm.endsWith(marker) || perm.contains(marker)
                }
            }
        ) {
            return true
        }
        return false
    }

    fun classify(
        signals: AppSignals,
        bundledForeign: Set<String> = emptySet(),
        selfPackageName: String = BuildConfig.APPLICATION_ID,
    ): AppCategory {
        val pkg = signals.packageName

        if (pkg == selfPackageName) {
            return AppCategory.SELF_OR_EXCLUDED
        }
        if (pkg in GOOGLE_CORE_SYSTEM_PACKAGES) {
            return AppCategory.GOOGLE_CORE_SYSTEM
        }
        if (pkg in CORE_ANDROID_SYSTEM_PACKAGES) {
            return AppCategory.CORE_ANDROID_SYSTEM
        }
        if (pkg in INTERNATIONAL_BROWSER_PACKAGES ||
            INTERNATIONAL_BROWSER_PREFIXES.any { pkg.startsWith(it) }
        ) {
            return AppCategory.INTERNATIONAL_BROWSER
        }
        if (pkg in DOMESTIC_BROWSER_PACKAGES) {
            return AppCategory.DOMESTIC_BROWSER
        }
        if (pkg in FOREIGN_EXCLUSIVE_PACKAGES ||
            FOREIGN_PACKAGE_PREFIXES.any { pkg.startsWith(it) } ||
            hasTelegramPackageToken(pkg) ||
            hasTelegramManifestFingerprint(signals)
        ) {
            return AppCategory.FOREIGN_EXCLUSIVE
        }
        if (pkg in DOMESTIC_EXPLICIT_PACKAGES ||
            DOMESTIC_NON_OEM_PREFIXES.any { pkg.startsWith(it) }
        ) {
            return AppCategory.DOMESTIC_APP
        }
        if (CHINESE_OEM_SYSTEM_PREFIXES.any { pkg.startsWith(it) } ||
            pkg in LOCAL_BYPASS_SYSTEM_PACKAGES
        ) {
            return AppCategory.CHINESE_OEM_SYSTEM
        }
        val domesticSdk = hasDomesticSdkFingerprint(signals)
        if (pkg in bundledForeign && !domesticSdk) {
            return AppCategory.FOREIGN_EXCLUSIVE
        }
        if (domesticSdk) {
            return AppCategory.DOMESTIC_APP
        }
        if (!signals.isSystemApp && signals.installerPackage == PLAY_STORE_PACKAGE) {
            return AppCategory.PLAY_HEURISTIC_FOREIGN
        }
        return AppCategory.UNKNOWN
    }

    fun shouldSelectForProxy(
        signals: AppSignals,
        bundledForeign: Set<String> = emptySet(),
        options: ProxyAutoSelectOptions = ProxyAutoSelectOptions(),
        selfPackageName: String = BuildConfig.APPLICATION_ID,
    ): Boolean {
        return when (classify(signals, bundledForeign, selfPackageName)) {
            AppCategory.FOREIGN_EXCLUSIVE -> true
            AppCategory.INTERNATIONAL_BROWSER -> options.includeInternationalBrowsers
            AppCategory.GOOGLE_CORE_SYSTEM -> options.includeGoogleCoreServices
            AppCategory.PLAY_HEURISTIC_FOREIGN -> options.includePlayStoreHeuristic
            else -> false
        }
    }

    fun shouldSelectForBypass(
        signals: AppSignals,
        bundledForeign: Set<String> = emptySet(),
        options: BypassAutoSelectOptions = BypassAutoSelectOptions(),
        selfPackageName: String = BuildConfig.APPLICATION_ID,
    ): Boolean {
        return when (classify(signals, bundledForeign, selfPackageName)) {
            AppCategory.DOMESTIC_APP -> options.includeDomesticApps
            AppCategory.CHINESE_OEM_SYSTEM -> options.includeChineseOemSystemApps
            AppCategory.DOMESTIC_BROWSER -> options.includeDomesticBrowsers
            else -> false
        }
    }

    /** Collects distinct UIDs whose PackageInfo satisfies [predicate] using offline metadata. */
    fun collectMatchingUids(
        context: Context,
        packages: Map<String, PackageInfo>,
        predicate: (AppSignals, Set<String>) -> Boolean,
    ): Set<Int> {
        val pm = context.packageManager
        val bundledForeign = loadBundledForeignPackages(context)
        val matched = HashSet<Int>()
        for ((pkg, pkgInfo) in packages) {
            val uid = pkgInfo.applicationInfo?.uid ?: continue
            val signals = extractSignals(pkg, pkgInfo, pm)
            if (predicate(signals, bundledForeign)) {
                matched.add(uid)
            }
        }
        return matched
    }
}
