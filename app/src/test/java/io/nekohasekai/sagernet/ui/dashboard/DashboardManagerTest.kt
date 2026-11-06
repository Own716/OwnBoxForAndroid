package io.nekohasekai.sagernet.ui.dashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardManagerTest {

    @Test
    fun testUrlNormalization() {
        assertEquals("https://board.zash.run.place/", DashboardManager.normalizeUrl("https://board.zash.run.place/"))
        assertEquals("http://127.0.0.1:9090/ui", DashboardManager.normalizeUrl("http://127.0.0.1:9090/ui"))
        assertEquals("http://192.168.1.100:9090/", DashboardManager.normalizeUrl("192.168.1.100:9090/"))
        assertEquals("http://example.com/panel", DashboardManager.normalizeUrl("   example.com/panel   \n"))
        assertEquals("http://example.com/ui", DashboardManager.normalizeUrl("//example.com/ui"))
    }

    @Test
    fun testDangerousUrlSchemesRejected() {
        val dangerousUrls = listOf(
            "javascript:alert(document.cookie)",
            "JAVASCRIPT:void(0)",
            "file:///data/data/com.ownbox.app/databases",
            "content://telephony/siminfo",
            "data:text/html,<html>Evil</html>",
            "intent:#Intent;action=android.intent.action.VIEW;end",
            "about:blank",
            ""
        )
        for (url in dangerousUrls) {
            try {
                DashboardManager.normalizeUrl(url)
                fail("Expected IllegalArgumentException for dangerous/empty URL: $url")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun testDashboardItemSerialization() {
        val item = DashboardItem(
            id = "test-id-123",
            name = "My Custom Panel",
            url = "http://192.168.50.1:9090",
            isPreset = false,
            isDefault = true,
            createdAt = 123456789L
        )

        val json = item.toJsonObject()
        assertEquals("test-id-123", json.getString("id"))
        assertEquals("My Custom Panel", json.getString("name"))
        assertEquals("http://192.168.50.1:9090", json.getString("url"))
        assertFalse(json.getBoolean("isPreset"))
        assertTrue(json.getBoolean("isDefault"))
        assertEquals(123456789L, json.getLong("createdAt"))

        val restored = DashboardItem.fromJsonObject(json)
        assertEquals(item.id, restored.id)
        assertEquals(item.name, restored.name)
        assertEquals(item.url, restored.url)
        assertEquals(item.isPreset, restored.isPreset)
        assertEquals(item.isDefault, restored.isDefault)
        assertEquals(item.createdAt, restored.createdAt)
    }

    @Test
    fun testPresetsIntegrity() {
        assertTrue(DashboardManager.PRESET_ZASHBOARD_URL.contains("zash.run.place"))
        assertEquals("http://127.0.0.1:9090/ui", DashboardManager.PRESET_YACD_URL)
        assertTrue(DashboardManager.PRESET_METACUBEXD_URL.contains("metacubexd"))
    }
}
