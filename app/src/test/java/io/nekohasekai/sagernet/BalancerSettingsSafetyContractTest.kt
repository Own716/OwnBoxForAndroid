package io.nekohasekai.sagernet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BalancerSettingsSafetyContractTest {

    private fun sourceFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("app", relative),
            File("../app", relative),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Source file not found for relative path: $relative")
    }

    @Test
    fun selectedGroupForImportDoesNotUseDoubleBang() {
        val dataStoreText = sourceFile("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()
            .replace("\r\n", "\n")
        val functionBlock = dataStoreText.substringAfter("fun selectedGroupForImport(): Long {")
            .substringBefore("var appTLSVersion")
        assertFalse(
            "selectedGroupForImport must not use !! to avoid NPE when no basic groups exist",
            functionBlock.contains("!!")
        )
        assertTrue(
            "selectedGroupForImport should have fallback when basic group is missing",
            functionBlock.contains("createGroup(ProxyGroup(ungrouped = true))")
        )
    }

    @Test
    fun balancerSettingsGuardsLateinitAndAvoidsForcedUnwraps() {
        val text = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerSettingsActivity.kt").readText()
        val orderActivityText = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerNodeOrderActivity.kt").readText()
        val nodePrefText = sourceFile("src/main/java/io/nekohasekai/sagernet/widget/BalancerNodeListPreference.kt").readText()
        val manifestText = sourceFile("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "BalancerSettingsActivity must not use any force-unwrap !! operators",
            text.contains("!!"),
        )
        assertFalse(
            "BalancerNodeOrderActivity must not use any force-unwrap !! operators",
            orderActivityText.contains("!!"),
        )
        assertFalse(
            "BalancerNodeListPreference must not use any force-unwrap !! operators",
            nodePrefText.contains("!!"),
        )
        assertTrue(
            "BalancerSettingsActivity must launch BalancerNodeOrderActivity",
            text.contains("BalancerNodeOrderActivity")
        )
        assertTrue(
            "AndroidManifest.xml must declare BalancerNodeOrderActivity",
            manifestText.contains("io.nekohasekai.sagernet.ui.profile.BalancerNodeOrderActivity")
        )
        assertTrue(
            "BalancerSettingsActivity must guard frontProxyPreference with isInitialized",
            text.contains("::frontProxyPreference.isInitialized")
        )
    }

    @Test
    fun balancerPreferencesExposeOnlyUnifiedAutoSelectControlsAndCollapsibleAdvanced() {
        val prefXml = sourceFile("src/main/res/xml/balancer_preferences.xml").readText()
        val arraysXml = sourceFile("src/main/res/values/arrays.xml").readText()
        val activityText = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/profile/BalancerSettingsActivity.kt").readText()

        assertFalse(
            "legacy balancerStrategy preference key must not remain in balancer_preferences.xml",
            prefXml.contains("balancerStrategy"),
        )
        listOf(
            "balancerRankTopN",
            "balancerToleranceUnit",
            "balancerBalanceHint",
            "balancerRunTopN",
            "balancerResultTrustMinutes",
            "balancerSkipFailedWithinTrust",
            "balancerStandbySize",
            "balancerIntensiveCheckCount",
            "balancerFullScanIntervalSec",
            "balancerWatchIntervalSec",
            "balancerSampleCount",
            "balancerMaxRttMs",
            "balancerDialRetries",
            "balancerFailbackChecks",
            "balancerFailbackDelaySec",
            "balancerInterruptOnFailback",
            "balancerSiteStickyMinutes",
            "balancerConnectivityUrl",
            "balancerUseFrontProxy",
            "balancerUseLandingProxy",
        ).forEach { removed ->
            assertFalse("removed key $removed must not remain in balancer_preferences.xml", prefXml.contains("app:key=\"$removed\""))
        }
        listOf(
            "balancerAddTier",
            "balancerNodeList",
            "balancerSelectionMode",
            "balancerBalanceEnabled",
            "balancerMatchSummary",
            "balancerAdvancedToggle",
            "balancerCountryFilter",
            "balancerNameExclude",
            "balancerInterval",
            "balancerTolerance",
            "balancerInterruptOnSwitch",
            "balancerLearnFromConnections",
            "balancerBalanceMode",
            "balancerBalanceIntervalSec",
            "balancerTestUrl",
            "balancerFrontProxy",
            "balancerLandingProxy",
        ).forEach { key ->
            assertTrue("balancer_preferences.xml must declare key=$key", prefXml.contains("app:key=\"$key\""))
        }

        val addTierIdx = prefXml.indexOf("app:key=\"balancerAddTier\"")
        val nodeListIdx = prefXml.indexOf("app:key=\"balancerNodeList\"")
        assertTrue("balancerAddTier must precede balancerNodeList", addTierIdx in 0 until nodeListIdx)
        assertFalse(
            "balancer_preferences.xml must use compact Preference instead of inline BalancerNodeListPreference",
            prefXml.contains("BalancerNodeListPreference"),
        )

        assertTrue(arraysXml.contains("name=\"balancer_selection_mode_values\""))
        assertTrue(arraysXml.contains("name=\"balancer_balance_mode_values\""))
        assertFalse(arraysXml.contains("name=\"balancer_strategy_entries\""))
        assertFalse(arraysXml.contains("name=\"balancer_strategy_values\""))

        // Defaults and dependency toggles in activity
        assertTrue(activityText.contains("balancerSelectionMode"))
        assertTrue(activityText.contains("shouldShowNodeList"))
        assertTrue(activityText.contains("balancerAddTier"))
        assertTrue(activityText.contains("balancerAdvancedToggle"))
        assertTrue(activityText.contains("BalancerMemberResolver.ResolutionResult") || activityText.contains("reusableRecentCount"))
    }

    @Test
    fun preferencesCheckSpinnerNullability() {
        val simpleMenuText = sourceFile("src/main/java/moe/matsuri/nb4a/ui/SimpleMenuPreference.kt").readText()
        assertTrue(
            "SimpleMenuPreference must handle null spinner safely",
            simpleMenuText.contains("findViewById<Spinner>(R.id.spinner) ?: return")
        )

        val outboundText = sourceFile("src/main/java/io/nekohasekai/sagernet/widget/OutboundPreference.kt").readText()
        assertTrue(
            "OutboundPreference must handle null spinner safely",
            outboundText.contains("findViewById<Spinner>(R.id.spinner) ?: return")
        )
    }

    @Test
    fun profileSettingsActivityProtectsBackgroundInitialization() {
        val text = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt").readText()
            .replace("\r\n", "\n")
        assertTrue(
            "runOnDefaultDispatcher must contain try-catch block for safe error handling",
            text.contains("runOnDefaultDispatcher {\n                try {")
        )
    }
}
