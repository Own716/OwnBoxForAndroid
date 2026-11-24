package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHwidManagerTest {

    @Test
    fun deriveHwidIsDeterministicAndMatchesRemnawaveRegex() {
        val hwid1 = DeviceHwidManager.deriveHwid("a1b2c3d4e5f60708") { "unused-fallback-1234" }
        val hwid2 = DeviceHwidManager.deriveHwid("  a1b2c3d4e5f60708  ") { "unused-fallback-1234" }
        val other = DeviceHwidManager.deriveHwid("deadbeefcafebabe") { "unused-fallback-1234" }

        assertEquals(64, hwid1.length)
        assertEquals(hwid1, hwid2)
        assertNotEquals(hwid1, other)
        assertTrue("Expected valid Remnawave HWID format: $hwid1", DeviceHwidManager.isValidHwid(hwid1))
    }

    @Test
    fun deriveHwidFallsBackOnBlankOrLegacyBuggyAndroidId() {
        val fallbackUuid = "c7b3d168-5e4a-4e2b-b998-38c2912a77f2"

        assertEquals(fallbackUuid, DeviceHwidManager.deriveHwid(null) { fallbackUuid })
        assertEquals(fallbackUuid, DeviceHwidManager.deriveHwid("   ") { fallbackUuid })
        assertEquals(fallbackUuid, DeviceHwidManager.deriveHwid("9774d56d682e549c") { fallbackUuid })
        assertEquals(fallbackUuid, DeviceHwidManager.deriveHwid("9774D56D682E549C") { fallbackUuid })

        // Invalid fallback string should be replaced with a valid generated UUID
        val regenerated = DeviceHwidManager.deriveHwid(null) { "bad" }
        assertTrue(DeviceHwidManager.isValidHwid(regenerated))
        assertNotEquals("bad", regenerated)
    }

    @Test
    fun isValidHwidRejectsOutOfSpecValues() {
        assertFalse(DeviceHwidManager.isValidHwid("short"))
        assertFalse(DeviceHwidManager.isValidHwid("a".repeat(65)))
        assertFalse(DeviceHwidManager.isValidHwid("invalid_underscore_123"))
        assertFalse(DeviceHwidManager.isValidHwid("invalid space 12345"))
        assertTrue(DeviceHwidManager.isValidHwid("c7b3d168-5e4a-4e2b-b998-38c2912a77f2"))
        assertTrue(DeviceHwidManager.isValidHwid("aB09=-1234"))
    }

    @Test
    fun formatDeviceModelDeduplicatesManufacturerAndSanitizesHeaderChars() {
        assertEquals("Google Pixel 8 Pro", DeviceHwidManager.formatDeviceModel("Google", "Pixel 8 Pro"))
        assertEquals("Xiaomi 23116PN5BC", DeviceHwidManager.formatDeviceModel("Xiaomi", "Xiaomi 23116PN5BC"))
        assertEquals("Xiaomi 14", DeviceHwidManager.formatDeviceModel("xiaomi", "Xiaomi 14"))
        assertEquals("OnePlus CPH2581", DeviceHwidManager.formatDeviceModel("OnePlus\r\n", "CPH2581\t\u0000"))
        assertEquals("Android", DeviceHwidManager.formatDeviceModel(null, "  "))
    }

    @Test
    fun formatOsVersionSanitizesControlCharsAndHandlesBlank() {
        assertEquals("15", DeviceHwidManager.formatOsVersion("15"))
        assertEquals("14.1", DeviceHwidManager.formatOsVersion(" 14.1\r\n"))
        assertEquals("unknown", DeviceHwidManager.formatOsVersion(""))
        assertEquals("unknown", DeviceHwidManager.formatOsVersion(null))
    }
}
