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
        assertFalse(
            "frontProxyPreference must not be force-unwrapped with !!",
            text.contains("findPreference(\"balancerFrontProxy\")!!")
        )
        assertFalse(
            "landingProxyPreference must not be force-unwrapped with !!",
            text.contains("findPreference(\"balancerLandingProxy\")!!")
        )
        assertTrue(
            "updateTypeVisibility must guard configurationList with isInitialized",
            text.contains("::configurationList.isInitialized")
        )
        assertTrue(
            "updateTypeVisibility must guard listCell with isInitialized",
            text.contains("::listCell.isInitialized")
        )
        assertTrue(
            "viewCreated must guard configurationAdapter with isInitialized",
            text.contains("::configurationAdapter.isInitialized")
        )
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
