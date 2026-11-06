package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GoogleRouteRuleTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun testConfigBuilderDecomposesDomainIpAndAppWithOrSemantics() {
        val configBuilderSource = sourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()

        // 1. Verify hasDomain, hasIp, and hasApp are independently evaluated
        assertTrue("ConfigBuilder must define hasApp flag", configBuilderSource.contains("val hasApp = uidList.isNotEmpty() || rule.packages.isNotEmpty()"))
        assertTrue("ConfigBuilder must have if (hasDomain) block", configBuilderSource.contains("if (hasDomain) {"))
        assertTrue("ConfigBuilder must have if (hasIp) block", configBuilderSource.contains("if (hasIp) {"))
        assertTrue("ConfigBuilder must have if (hasApp) block", configBuilderSource.contains("if (hasApp) {"))

        // 2. Verify appSubRule does not force domain restrictions
        assertTrue("ConfigBuilder must generate appSubRule for app criteria", configBuilderSource.contains("val appSubRule = Rule_DefaultOptions()"))
        assertTrue("ConfigBuilder must add appSubRule when non-empty", configBuilderSource.contains("if (!appSubRule.checkEmpty()) generatedSubRules.add(appSubRule)"))

        // 3. Verify RouteRuleEditor action and invertedGroup contracts are preserved
        assertTrue("RouteRuleEditor.applyAction must be retained", configBuilderSource.contains("RouteRuleEditor.applyAction(ruleObj, rule.config)"))
        assertTrue("RouteRuleEditor.invertedGroup must be retained", configBuilderSource.contains("RouteRuleEditor.invertedGroup(generatedSubRules)"))
    }

    @Test
    fun testProfileManagerGooglePlayRuleEnrichment() {
        val profileManagerSource = sourceFile("src/main/java/io/nekohasekai/sagernet/database/ProfileManager.kt").readText()

        // Verify Play Store rule includes DownloadManager and related packages
        assertTrue(profileManagerSource.contains("\"com.android.providers.downloads\""))
        assertTrue(profileManagerSource.contains("\"com.android.providers.downloads.ui\""))
        assertTrue(profileManagerSource.contains("\"com.google.android.gsf\""))
        assertTrue(profileManagerSource.contains("\"com.android.vending\""))
        assertTrue(profileManagerSource.contains("\"com.google.android.gms\""))

        // Verify Play Store CDN and gateway domains are present
        assertTrue(profileManagerSource.contains("playstoregatewayadapter-pa.googleapis.com"))
        assertTrue(profileManagerSource.contains("firebaselogging-pa.googleapis.com"))
        assertTrue(profileManagerSource.contains("ggpht.com"))
        assertTrue(profileManagerSource.contains("gvt1.com"))
    }

    @Test
    fun testVpnServiceWhitelistIncludesDownloadManager() {
        val vpnServiceSource = sourceFile("src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt").readText()

        // Verify VpnService auto-includes DownloadManager when Google Play Store is whitelisted
        assertTrue(vpnServiceSource.contains("if (proxyApps && !bypass && individual.contains(\"com.android.vending\"))"))
        assertTrue(vpnServiceSource.contains("individual.add(\"com.android.providers.downloads\")"))
    }
}
