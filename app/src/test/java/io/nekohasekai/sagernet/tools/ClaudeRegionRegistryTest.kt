package io.nekohasekai.sagernet.tools

import io.nekohasekai.sagernet.tools.media.ClaudeRegionRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeRegionRegistryTest {

    @Test
    fun hongKongIsStrictlyUnsupported() {
        assertFalse("Hong Kong must not be supported by Claude", ClaudeRegionRegistry.isRegionSupported("HK"))
        assertFalse("Hong Kong lowercase must not be supported by Claude", ClaudeRegionRegistry.isRegionSupported("hk"))
        assertTrue("Hong Kong must be flagged as explicitly unsupported", ClaudeRegionRegistry.isExplicitlyUnsupported("HK"))
    }

    @Test
    fun mainlandChinaAndMacauAreUnsupported() {
        assertFalse("Mainland China must not be supported", ClaudeRegionRegistry.isRegionSupported("CN"))
        assertTrue("Mainland China must be explicitly unsupported", ClaudeRegionRegistry.isExplicitlyUnsupported("CN"))

        assertFalse("Macau must not be supported", ClaudeRegionRegistry.isRegionSupported("MO"))
        assertTrue("Macau must be explicitly unsupported", ClaudeRegionRegistry.isExplicitlyUnsupported("MO"))
    }

    @Test
    fun sanctionedAndRestrictedCountriesAreUnsupported() {
        assertFalse("Russia must not be supported", ClaudeRegionRegistry.isRegionSupported("RU"))
        assertTrue("Russia must be explicitly unsupported", ClaudeRegionRegistry.isExplicitlyUnsupported("RU"))

        assertFalse("Iran must not be supported", ClaudeRegionRegistry.isRegionSupported("IR"))
        assertFalse("North Korea must not be supported", ClaudeRegionRegistry.isRegionSupported("KP"))
    }

    @Test
    fun officialSupportedRegionsAreAccepted() {
        assertTrue("US must be supported", ClaudeRegionRegistry.isRegionSupported("US"))
        assertTrue("Japan must be supported", ClaudeRegionRegistry.isRegionSupported("JP"))
        assertTrue("Singapore must be supported", ClaudeRegionRegistry.isRegionSupported("SG"))
        assertTrue("Taiwan must be supported", ClaudeRegionRegistry.isRegionSupported("TW"))
        assertTrue("United Kingdom must be supported", ClaudeRegionRegistry.isRegionSupported("GB"))
        assertTrue("Australia must be supported", ClaudeRegionRegistry.isRegionSupported("AU"))
        assertTrue("Germany must be supported", ClaudeRegionRegistry.isRegionSupported("DE"))
    }

    @Test
    fun nullAndEmptyRegionsAreRejected() {
        assertFalse("Null must not be supported", ClaudeRegionRegistry.isRegionSupported(null))
        assertFalse("Blank must not be supported", ClaudeRegionRegistry.isRegionSupported("   "))
        assertFalse("Empty string must not be supported", ClaudeRegionRegistry.isRegionSupported(""))
    }
}
