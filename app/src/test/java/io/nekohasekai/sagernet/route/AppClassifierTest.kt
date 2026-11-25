package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppClassifierTest {

    private fun signals(
        pkg: String,
        uid: Int = 10500,
        isSystemApp: Boolean = false,
        permissions: Set<String> = emptySet(),
        providerAuthorities: Set<String> = emptySet(),
        providerNames: Set<String> = emptySet(),
        metaDataKeys: Set<String> = emptySet(),
        installerPackage: String? = null,
    ) = AppClassifier.AppSignals(
        packageName = pkg,
        uid = uid,
        isSystemApp = isSystemApp,
        permissions = permissions,
        providerAuthorities = providerAuthorities,
        providerNames = providerNames,
        metaDataKeys = metaDataKeys,
        installerPackage = installerPackage,
    )

    @Test
    fun selectsAllEdgeVariantsInProxyModeAndHonorsBrowserToggle() {
        val edgeVariants = listOf(
            "com.microsoft.emmx",
            "com.microsoft.emmx.canary",
            "com.microsoft.emmx.beta",
            "com.microsoft.emmx.dev",
        )
        val defaultProxy = AppClassifier.ProxyAutoSelectOptions()
        val noBrowsers = AppClassifier.ProxyAutoSelectOptions(includeInternationalBrowsers = false)
        val defaultBypass = AppClassifier.BypassAutoSelectOptions()

        for (pkg in edgeVariants) {
            val s = signals(pkg)
            assertEquals(AppClassifier.AppCategory.INTERNATIONAL_BROWSER, AppClassifier.classify(s))
            assertTrue("Expected $pkg to be selected in proxy mode", AppClassifier.shouldSelectForProxy(s, options = defaultProxy))
            assertFalse("Expected $pkg toggle off to skip", AppClassifier.shouldSelectForProxy(s, options = noBrowsers))
            assertFalse("Expected $pkg never bypassed", AppClassifier.shouldSelectForBypass(s, options = defaultBypass))
        }
    }

    @Test
    fun selectsMuseAiAndModernAiAppsInProxyModeAndNeverBypasses() {
        val aiApps = listOf(
            "com.facebook.aura",
            "com.app.museai",
            "com.anthropic.claude",
            "com.openai.chatgpt",
            "com.google.android.apps.bard",
            "ai.perplexity.app.android",
            "ai.x.grok",
        )
        for (pkg in aiApps) {
            val s = signals(pkg)
            assertEquals(AppClassifier.AppCategory.FOREIGN_EXCLUSIVE, AppClassifier.classify(s))
            assertTrue("Expected $pkg in proxy mode", AppClassifier.shouldSelectForProxy(s))
            assertFalse("Expected $pkg not in bypass mode", AppClassifier.shouldSelectForBypass(s))
        }
    }

    @Test
    fun selectsNagramNagramXAndTelegramForksIncludingManifestFingerprint() {
        val tgClients = listOf(
            signals("xyz.nextalone.nagram"),
            signals("xyz.nextalone.nagram.beta"),
            signals("xyz.nextalone.nnngram"),
            signals("nu.gpu.nagram"),
            signals("nu.gpu.nagramx"),
            signals("nu.gpu.nagramxturbo"),
            signals("com.exteraless.messenger"),
            signals("uz.unnarsx.cherrygram"),
            signals("com.radolyn.ayugram"),
            signals(
                pkg = "com.custom.chat",
                providerAuthorities = setOf("com.custom.chat.provider", "org.telegram.messenger.provider"),
            ),
        )
        for (s in tgClients) {
            assertEquals(AppClassifier.AppCategory.FOREIGN_EXCLUSIVE, AppClassifier.classify(s))
            assertTrue("Expected ${s.packageName} in proxy mode", AppClassifier.shouldSelectForProxy(s))
            assertFalse("Expected ${s.packageName} not in bypass mode", AppClassifier.shouldSelectForBypass(s))
        }

        // Token boundary safety: unrelated packages ending in -gram should not match TG tokens
        assertEquals(AppClassifier.AppCategory.UNKNOWN, AppClassifier.classify(signals("com.example.hologram")))
    }

    @Test
    fun playStoreHeuristicDefaultsToOffAndNeverSelectsDomesticSdkApps() {
        val defaultOptions = AppClassifier.ProxyAutoSelectOptions()
        assertFalse(defaultOptions.includePlayStoreHeuristic)

        val unlistedPlayApp = signals(
            pkg = "io.example.newforeigntool",
            installerPackage = AppClassifier.PLAY_STORE_PACKAGE,
        )
        // Default OFF
        assertFalse(AppClassifier.shouldSelectForProxy(unlistedPlayApp, options = defaultOptions))
        // Explicit ON
        val enabledOptions = AppClassifier.ProxyAutoSelectOptions(includePlayStoreHeuristic = true)
        assertTrue(AppClassifier.shouldSelectForProxy(unlistedPlayApp, options = enabledOptions))

        // Domestic SDK markers prevent Play heuristic from treating domestic app as foreign
        val playInstalledWithUmeng = signals(
            pkg = "com.example.cnplaytool",
            metaDataKeys = setOf("UMENG_APPKEY"),
            installerPackage = AppClassifier.PLAY_STORE_PACKAGE,
        )
        val playInstalledWithAlipay = signals(
            pkg = "com.example.cnshop",
            providerAuthorities = setOf("com.example.cnshop.com.alipay.sdk.provider"),
            installerPackage = AppClassifier.PLAY_STORE_PACKAGE,
        )
        assertFalse(AppClassifier.shouldSelectForProxy(playInstalledWithUmeng, options = enabledOptions))
        assertFalse(AppClassifier.shouldSelectForProxy(playInstalledWithAlipay, options = enabledOptions))
        assertTrue(AppClassifier.shouldSelectForBypass(playInstalledWithUmeng))
        assertTrue(AppClassifier.shouldSelectForBypass(playInstalledWithAlipay))
    }

    @Test
    fun bypassModeSelectsDomesticAppsAndNeverBypassesUnknownOrProtectedForeignApps() {
        val domesticSamples = listOf(
            signals("com.tencent.mm"),
            signals("com.deepseek.chat"),
            signals("com.icbc.im"),
            signals("com.local.transit", metaDataKeys = setOf("com.amap.api.v2.apikey")),
        )
        for (s in domesticSamples) {
            assertEquals(AppClassifier.AppCategory.DOMESTIC_APP, AppClassifier.classify(s))
            assertTrue("Expected ${s.packageName} in bypass mode", AppClassifier.shouldSelectForBypass(s))
            assertFalse("Expected ${s.packageName} not in proxy mode", AppClassifier.shouldSelectForProxy(s))
        }

        val mustNeverAutoBypass = listOf(
            signals("com.example.brandnew.foreignapp"),
            signals("com.facebook.aura"),
            signals("nu.gpu.nagramx"),
            signals("com.microsoft.emmx.canary"),
            signals("com.android.vending", isSystemApp = true),
            signals("com.android.providers.downloads", isSystemApp = true),
            signals("com.xiaomi.providers.downloads", isSystemApp = true),
        )
        for (s in mustNeverAutoBypass) {
            assertFalse("Must never bypass ${s.packageName}", AppClassifier.shouldSelectForBypass(s))
        }
    }

    @Test
    fun oemBrowsersRespectDomesticBrowserOptionWhileOemServicesRespectSystemOption() {
        val oemBrowsers = listOf("com.heytap.browser", "com.vivo.browser", "com.huawei.browser")
        val defaultBypass = AppClassifier.BypassAutoSelectOptions()
        val withBrowsers = AppClassifier.BypassAutoSelectOptions(includeDomesticBrowsers = true)

        for (pkg in oemBrowsers) {
            val s = signals(pkg, isSystemApp = true)
            assertEquals(AppClassifier.AppCategory.DOMESTIC_BROWSER, AppClassifier.classify(s))
            assertFalse("OEM browser $pkg should stay false when includeDomesticBrowsers=false", AppClassifier.shouldSelectForBypass(s, options = defaultBypass))
            assertTrue("OEM browser $pkg should be true when includeDomesticBrowsers=true", AppClassifier.shouldSelectForBypass(s, options = withBrowsers))
        }

        val oemServices = listOf("com.miui.securitycenter", "com.heytap.market")
        val withoutOemSystem = AppClassifier.BypassAutoSelectOptions(includeChineseOemSystemApps = false)
        for (pkg in oemServices) {
            val s = signals(pkg, uid = 1000, isSystemApp = true)
            assertEquals(AppClassifier.AppCategory.CHINESE_OEM_SYSTEM, AppClassifier.classify(s))
            assertTrue(AppClassifier.shouldSelectForBypass(s, options = defaultBypass))
            assertFalse(AppClassifier.shouldSelectForBypass(s, options = withoutOemSystem))
        }
    }

    @Test
    fun systemUid1000CorePackagesAreNotAutoSelectedInProxyMode() {
        val systemCore = listOf(
            signals("android", uid = 1000, isSystemApp = true),
            signals("com.android.settings", uid = 1000, isSystemApp = true),
        )
        for (s in systemCore) {
            assertEquals(AppClassifier.AppCategory.CORE_ANDROID_SYSTEM, AppClassifier.classify(s))
            assertFalse("Must not auto-proxy ${s.packageName}", AppClassifier.shouldSelectForProxy(s))
            assertFalse("Must not auto-bypass ${s.packageName}", AppClassifier.shouldSelectForBypass(s))
        }
    }
}
