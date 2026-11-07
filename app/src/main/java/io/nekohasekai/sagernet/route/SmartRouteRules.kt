package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.utils.PackageCache
import java.io.File

/**
 * 智能分流引擎（Smart Routing Engine）：
 * 根据用户在设置中所选的国家/地区（如 cn / ir / ru / tr / id / in / vn / br），
 * 自动执行「应用级 (App) + 域名级 (GeoSite & DNS) + IP 级 (GeoIP)」三维智能国内外分流。
 */
object SmartRouteRules {

    /** 浏览器与通用混合型宿主应用：不在应用层一刀切锁定，放行至下层按访问的目标域名与 IP 动态分流 */
    val BROWSER_AND_HYBRID_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.opera.touch",
        "com.vivaldi.browser",
        "com.duckduckgo.mobile.android",
        "mark.via",
        "mark.via.gp",
        "com.kiwibrowser.browser",
        "com.UCMobile",
        "com.UCMobile.intl",
        "com.tencent.mtt",
        "com.quark.browser",
        "com.baidu.searchbox",
        "com.android.browser",
        "com.mi.globalbrowser",
        "com.sec.android.app.sbrowser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.huawei.browser",
        "com.google.android.webview",
        "com.android.webview",
        "idm.internet.download.manager",
        "idm.internet.download.manager.plus"
    )

    /** 系统底层核心组件：不参与应用层强绑 */
    private val SYSTEM_EXCLUDED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.telephony",
        "com.android.phone",
        "com.android.nfc",
        "com.android.bluetooth",
        "com.google.android.inputmethod.latin",
        "com.google.android.inputmethod.pinyin",
        BuildConfig.APPLICATION_ID
    )

    /** 核心海外专属应用（始终优先走代理隧道与远程抗污染 DNS） */
    val CORE_FOREIGN_PACKAGES = setOf(
        // Google 核心套件
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.youtube.kids",
        "app.revanced.android.youtube",
        "com.bvanced.android.youtube",
        "com.google.android.gm",
        "com.google.android.apps.maps",
        "com.google.android.apps.photos",
        "com.google.android.apps.translate",
        "com.google.android.apps.docs",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard",
        // 国际即时通讯与社交
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "tw.nekomimi.nekogram",
        "uz.unnarsx.cherrygram",
        "com.radolyn.ayugram",
        "xyz.nextalone.nagram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.discord",
        "com.twitter.android",
        "com.instagram.android",
        "com.instagram.barcelona",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.facebook.lite",
        "com.reddit.frontpage",
        "jp.naver.line.android",
        "com.snapchat.android",
        "com.pinterest",
        "com.tumblr",
        "com.linkedin.android",
        "org.thoughtcrime.securesms",
        "com.viber.voip",
        "com.kakao.talk",
        // AI 与生产力工具
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "ai.perplexity.app.android",
        "com.microsoft.copilot",
        "com.github.android",
        "io.notion.app",
        // 海外流媒体、短视频与国际服游戏
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.netflix.mediaclient",
        "com.spotify.music",
        "tv.twitch.android.app",
        "com.valvesoftware.android.steam.community",
        "com.roblox.client",
        "com.tencent.ig",
        "com.pubg.krmobile",
        "com.supercell.clashofclans",
        "com.supercell.brawlstars",
        "com.Supercell.clashroyale",
        "com.nianticlabs.pokemongo",
        "com.binance.dev",
        "com.okinc.okex.gp"
    )

    private val FOREIGN_PACKAGE_PREFIXES = listOf(
        "com.google.android.apps.",
        "org.telegram.",
        "com.openai.",
        "com.anthropic.",
        "com.facebook.",
        "com.instagram.",
        "com.twitter.",
        "com.netflix.",
        "com.spotify.",
        "com.valvesoftware.",
        "com.supercell."
    )

    /** 中国大陆核心本土应用白名单 */
    private val CN_EXPLICIT_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.wework",
        "com.tencent.qqmusic",
        "com.tencent.qqlive",
        "com.tencent.news",
        "com.tencent.WBlog",
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.pubgmhd",
        "com.tencent.jkchess",
        "com.tencent.lolm",
        "com.eg.android.AlipayGphone",
        "com.taobao.taobao",
        "com.taobao.litetao",
        "com.taobao.idlefish",
        "com.taobao.trip",
        "com.alibaba.wireless",
        "com.alibaba.android.rimet",
        "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo",
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.ss.android.article.news",
        "com.ss.android.lark",
        "com.ss.android.auto",
        "com.dragon.read",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.xingin.xhs",
        "com.sankuai.meituan",
        "com.sankuai.meituan.takeoutnew",
        "com.dianping.v1",
        "me.ele",
        "com.netease.cloudmusic",
        "com.netease.newsreader.activity",
        "com.netease.mail",
        "com.kugou.android",
        "cn.kuwo.player",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.baidu.tieba",
        "com.baidu.netdisk",
        "com.zhihu.android",
        "com.sina.weibo",
        "com.sina.weibolite",
        "com.coolapk.market",
        "com.MobileTicket",
        "com.sdu.didi.psnger",
        "com.unionpay",
        "ctrip.android.view",
        "com.Qunar",
        "com.shizhuang.duapp",
        "com.cubic.autohome",
        "com.douban.frodo",
        "com.hupu.games",
        "com.sf.activity",
        "com.cainiao.wireless",
        "com.greenpoint.android.mc10086.activity",
        "com.sinovatech.unicom.ui",
        "com.ct.client",
        "cn.gov.tax.its",
        "com.tmri.app.main",
        "com.qiyi.video",
        "com.youku.phone",
        "com.hunantv.imgo.activity",
        "com.wudaokou.hippo",
        "com.achievo.vipshop",
        "com.yinxiang",
        "cn.wps.moffice_eng"
    )

    private val CN_PACKAGE_PREFIXES = listOf(
        "cn.",
        "com.cn.",
        "com.tencent.",
        "com.alibaba.",
        "com.taobao.",
        "com.alipay.",
        "com.autonavi.",
        "com.baidu.",
        "com.bytedance.",
        "com.ss.android.",
        "com.netease.",
        "com.sina.",
        "com.weibo.",
        "com.sankuai.",
        "com.meituan.",
        "com.jingdong.",
        "com.jd.",
        "com.xunmeng.",
        "com.youku.",
        "com.qiyi.",
        "com.iqiyi.",
        "com.hunantv.",
        "com.xiaomi.",
        "com.miui.",
        "com.huawei.",
        "com.hihonor.",
        "com.heytap.",
        "com.coloros.",
        "com.oppo.",
        "com.vivo.",
        "com.bbk.",
        "com.icbc.",
        "com.cmbchina.",
        "com.chinamworld.",
        "com.abchina.",
        "com.bankcomm.",
        "com.cebbank.",
        "com.pingan.",
        "com.spdb.",
        "com.cib.",
        "com.ecitic.",
        "com.cmbc.",
        "com.psbc."
    )

    private val RU_EXPLICIT_PACKAGES = setOf(
        "com.vkontakte.android",
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android",
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "com.avito.android",
        "ru.dublgis.dgismobile",
        "ru.rostel",
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.yandex.taxi",
        "ru.yandex.Maps",
        "ru.yandex.music",
        "ru.yandex.mail",
        "ru.beru.android"
    )

    private val RU_PACKAGE_PREFIXES = listOf(
        "ru.",
        "com.yandex.",
        "ru.yandex.",
        "com.vkontakte.",
        "com.vk."
    )

    private val IR_EXPLICIT_PACKAGES = setOf(
        "cab.snapp.passenger",
        "com.digikala",
        "ir.divar",
        "ir.torob",
        "com.farsitel.bazaar",
        "ir.mci.ecareapp",
        "com.myirancell",
        "ir.nasim",
        "ir.eitaa.messenger",
        "ir.rubika.app",
        "mobi.bale.messenger",
        "ir.gap.messenger",
        "mobi.plus.messenger"
    )

    private val IR_PACKAGE_PREFIXES = listOf(
        "ir.",
        "cab.snapp.",
        "com.digikala"
    )

    fun domesticExplicitPackages(country: String): Set<String> = when (country) {
        "cn" -> CN_EXPLICIT_PACKAGES
        "ru" -> RU_EXPLICIT_PACKAGES
        "ir" -> IR_EXPLICIT_PACKAGES
        "tr" -> setOf("com.turkcell.bip", "com.ziraat.ziraatmobil", "com.garanti.cepsubesi", "com.sahibinden", "trendyol.com", "com.getir")
        "id" -> setOf("com.gojek.app", "com.tokopedia.tkpd", "com.shopee.id", "id.dana", "ovo.id")
        "in" -> setOf("net.one97.paytm", "com.phonepe.app", "com.flipkart.android", "in.amazon.mShop.android.shopping", "com.jio.myjio")
        "vn" -> setOf("com.zing.zalo", "com.vng.zalopay", "com.shopee.vn", "vn.tiki.app.tikiandroid")
        "br" -> setOf("com.nu.production", "com.mercadolivre", "br.com.bb.android", "com.itau", "br.gov.caixa.tem")
        else -> CN_EXPLICIT_PACKAGES
    }

    fun domesticPackagePrefixes(country: String): List<String> = when (country) {
        "cn" -> CN_PACKAGE_PREFIXES
        "ru" -> RU_PACKAGE_PREFIXES
        "ir" -> IR_PACKAGE_PREFIXES
        "tr" -> listOf("tr.", "com.turkcell.", "com.ziraat.")
        "id" -> listOf("id.", "co.id.", "com.gojek.", "com.tokopedia.")
        "in" -> listOf("in.", "co.in.")
        "vn" -> listOf("vn.", "com.zing.", "com.vng.")
        "br" -> listOf("br.", "com.br.")
        else -> listOf("$country.")
    }

    /** 各国家/地区的核心直连域名后缀保底列表 */
    fun domesticCoreDomainSuffixes(country: String): List<String> = when (country) {
        "cn" -> listOf(
            "cn",
            "xn--fiqs8s", // .中国
            "douyin.com", "iesdouyin.com", "bytedance.com", "snssdk.com", "pstatp.com", "bytecdn.cn",
            "qq.com", "tencent.com", "weixin.com", "qpic.cn", "gtimg.cn",
            "taobao.com", "alipay.com", "alicdn.com", "tmall.com", "alibaba.com", "aliyun.com",
            "baidu.com", "bdstatic.com", "bilibili.com", "bilivideo.com", "hdslb.com",
            "jd.com", "360buyimg.com", "pinduoduo.com", "yangkeduo.com",
            "meituan.com", "dianping.com", "xiaohongshu.com", "xhscdn.com",
            "amap.com", "sina.com.cn", "weibo.com", "zhihu.com", "163.com", "126.net", "sohu.com"
        )
        "ir" -> listOf(
            "ir",
            "digikala.com", "snapp.ir", "divar.ir", "shaparak.ir", "aparat.com", "cafebazaar.ir", "torob.com"
        )
        "ru" -> listOf(
            "ru", "su", "xn--p1ai", // .рф
            "yandex.ru", "yandex.net", "yandex.com", "ya.ru", "vk.com", "userapi.com",
            "mail.ru", "sberbank.ru", "tbank.ru", "tinkoff.ru", "ozon.ru", "wb.ru", "wildberries.ru", "avito.ru", "gosuslugi.ru"
        )
        "tr" -> listOf("tr", "com.tr", "gov.tr", "sahibinden.com", "trendyol.com", "hepsiburada.com", "turkcell.com.tr")
        "id" -> listOf("id", "co.id", "go.id", "tokopedia.com", "gojek.com", "shopee.co.id", "bukalapak.com")
        "in" -> listOf("in", "co.in", "gov.in", "flipkart.com", "paytm.com", "phonepe.com", "jio.com", "hotstar.com")
        "vn" -> listOf("vn", "com.vn", "gov.vn", "zalo.me", "zing.vn", "vnexpress.net", "shopee.vn", "tiki.vn")
        "br" -> listOf("br", "com.br", "gov.br", "globo.com", "uol.com.br", "mercadolivre.com.br", "nubank.com.br")
        else -> listOf(country)
    }

    private fun loadBundledForeignPackages(): Set<String> {
        return runCatching {
            SagerNet.application.assets.open("proxy_packagename.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toSet()
                }
        }.getOrDefault(emptySet())
    }

    /**
     * 对本机已安装应用（或预置集合）进行国内/国外智能分类。
     * 返回 Pair(domesticPackages, foreignPackages)，浏览器与系统核心被排除在外以走下层域名/IP 分流。
     */
    fun classifyApps(
        country: String,
        installedPackages: Set<String> = runCatching {
            if (PackageCache.installedPackages.isEmpty()) PackageCache.awaitLoadSync()
            PackageCache.installedPackages.keys.toSet()
        }.getOrDefault(emptySet()),
        bundledForeign: Set<String> = loadBundledForeignPackages()
    ): Pair<Set<String>, Set<String>> {
        val normCountry = country.trim().lowercase().ifBlank { "cn" }
        val explicitDomestic = domesticExplicitPackages(normCountry)
        val domesticPrefixes = domesticPackagePrefixes(normCountry)

        val foreignCandidatePool = LinkedHashSet<String>()
        foreignCandidatePool.addAll(CORE_FOREIGN_PACKAGES)
        foreignCandidatePool.addAll(bundledForeign)

        val resolvedForeign = LinkedHashSet<String>()
        val resolvedDomestic = LinkedHashSet<String>()

        if (installedPackages.isNotEmpty()) {
            for (pkg in installedPackages) {
                if (pkg in SYSTEM_EXCLUDED_PACKAGES || pkg in BROWSER_AND_HYBRID_PACKAGES) continue

                val isExplicitForeign = pkg in CORE_FOREIGN_PACKAGES ||
                    (pkg in foreignCandidatePool && pkg !in explicitDomestic)
                val isPrefixForeign = FOREIGN_PACKAGE_PREFIXES.any { pkg.startsWith(it) } &&
                    pkg !in explicitDomestic

                if (isExplicitForeign || isPrefixForeign) {
                    resolvedForeign.add(pkg)
                    continue
                }

                val isExplicitDom = pkg in explicitDomestic
                val isPrefixDom = domesticPrefixes.any { pkg.startsWith(it) }
                if (isExplicitDom || isPrefixDom) {
                    resolvedDomestic.add(pkg)
                }
            }
        }

        // 确保核心常用应用即使在未完成全量包扫描时也有保底覆盖
        for (pkg in CORE_FOREIGN_PACKAGES) {
            if (pkg !in SYSTEM_EXCLUDED_PACKAGES && pkg !in BROWSER_AND_HYBRID_PACKAGES) {
                if (installedPackages.isEmpty() || pkg in installedPackages) {
                    resolvedForeign.add(pkg)
                }
            }
        }
        for (pkg in explicitDomestic) {
            if (pkg !in SYSTEM_EXCLUDED_PACKAGES && pkg !in BROWSER_AND_HYBRID_PACKAGES && pkg !in resolvedForeign) {
                if (installedPackages.isEmpty() || pkg in installedPackages) {
                    resolvedDomestic.add(pkg)
                }
            }
        }

        return Pair(resolvedDomestic, resolvedForeign)
    }

    private fun hasLocalDb(fileName: String): Boolean = runCatching {
        val extDir = SagerNet.application.getExternalFilesDir(null) ?: SagerNet.application.filesDir
        val filesDir = SagerNet.application.filesDir
        File(extDir, fileName).isFile || File(filesDir, fileName).isFile
    }.getOrDefault(false)

    /**
     * 构建当前国家/地区的完整四维智能分流规则列表：
     * 1. Google Play 与核心下载服务防误伤规则 -> 代理
     * 2. 国外专属软件 (App) -> 代理 + 远程 DNS
     * 3. 本国专属软件 (App) -> 直连 + 本地直连 DNS
     * 4. 本国域名 (geosite:<country> + 核心本国域名后缀) -> 直连 + 本地直连 DNS
     * 5. 本国 IP 段 (geoip:<country>) -> 直连
     * 6. 其余未命中流量由 route.final 兜底走主代理节点
     */
    fun buildSmartRules(rawCountry: String = "cn"): List<RuleEntity> {
        val country = rawCountry.trim().lowercase().ifBlank { "cn" }
        val rules = ArrayList<RuleEntity>()

        // 1. Google Play 商店专线保护（防止被 .cn 等后缀直连规则误伤）
        rules.add(
            RuleEntity(
                name = "Smart-GooglePlay",
                domains = listOf(
                    "geosite:google-play",
                    "domain:googleapis.cn",
                    "domain:gvt1.com",
                    "domain:gvt2.com",
                    "domain:gvt3.com",
                    "domain:gvt1-cn.com",
                    "domain:gvt2-cn.com",
                    "domain:googleusercontent.com",
                    "domain:play.googleapis.com",
                    "domain:android.clients.google.com",
                    "domain:xn--ngstr-lra8j.com",
                    "domain:xn--ngstr-cn-8za9o.com"
                ).joinToString("\n"),
                outbound = 0L,
                enabled = true
            )
        )

        // 2. 应用级智能识别：国外软件走代理，本国软件走直连（浏览器与混合应用自动穿透到域名/IP 层）
        val (domesticApps, foreignApps) = classifyApps(country)
        if (foreignApps.isNotEmpty()) {
            rules.add(
                RuleEntity(
                    name = "Smart-Foreign-Apps",
                    packages = foreignApps,
                    outbound = 0L,
                    enabled = true
                )
            )
        }
        if (domesticApps.isNotEmpty()) {
            rules.add(
                RuleEntity(
                    name = "Smart-Domestic-Apps-$country",
                    packages = domesticApps,
                    outbound = -1L,
                    enabled = true
                )
            )
        }

        // 3. 域名级智能分流：本国 GeoSite 规则集 + 本国核心域名后缀走直连与本地抗污染 DNS
        val catalog = runCatching { RuleSetCatalog.current() }.getOrNull()
        val domainEntries = LinkedHashSet<String>()
        val geositeTag = "geosite:$country"
        if (catalog?.urlOf(geositeTag) != null || hasLocalDb("geosite.db")) {
            domainEntries.add(geositeTag)
        }
        domesticCoreDomainSuffixes(country).forEach { suffix ->
            domainEntries.add("domain:$suffix")
        }
        if (domainEntries.isNotEmpty()) {
            rules.add(
                RuleEntity(
                    name = "Smart-Domestic-Domains-$country",
                    domains = domainEntries.joinToString("\n"),
                    outbound = -1L,
                    enabled = true
                )
            )
        }

        // 4. IP 级智能分流：本国 GeoIP 地址段走直连
        val ipEntries = LinkedHashSet<String>()
        val geoipTag = "geoip:$country"
        if (catalog?.urlOf(geoipTag) != null || hasLocalDb("geoip.db")) {
            ipEntries.add(geoipTag)
        }
        if (ipEntries.isNotEmpty()) {
            rules.add(
                RuleEntity(
                    name = "Smart-Domestic-IP-$country",
                    ip = ipEntries.joinToString("\n"),
                    outbound = -1L,
                    enabled = true
                )
            )
        }

        return rules
    }
}
