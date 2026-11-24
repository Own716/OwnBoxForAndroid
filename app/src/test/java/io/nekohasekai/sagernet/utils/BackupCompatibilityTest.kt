package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompatibilityTest {

    @Test
    fun resolveProfilesKeySupportsCurrentAndLegacySchemas() {
        val modern = JSONObject().put("profiles", JSONArray().put("p1"))
        assertEquals("profiles", BackupCodec.resolveProfilesKey(modern))

        val legacy = JSONObject().put("proxies", JSONArray().put("p1"))
        assertEquals("proxies", BackupCodec.resolveProfilesKey(legacy))

        val empty = JSONObject().put("groups", JSONArray())
        assertNull(BackupCodec.resolveProfilesKey(empty))
    }

    @Test
    fun ruleJsonRoundtripPreservesAllFields() {
        val original = RuleEntity(
            id = 7L,
            name = "Block Mobile Ads",
            config = """{"type":"default","invert":true}""",
            userOrder = 3L,
            enabled = true,
            domains = "domain:gdt.qq.com\nad.example.com",
            ip = "198.51.100.0/24",
            port = "443,8443",
            sourcePort = "10000:20000",
            network = "udp",
            source = "192.168.1.0/24",
            protocol = "quic",
            ruleset = "geosite-category-ads-all",
            outbound = -2L,
            packages = linkedSetOf("com.example.one", "com.example.two"),
        )

        val json = BackupCodec.ruleToJson(original)
        assertEquals("Block Mobile Ads", json.getString("name"))
        assertEquals(-2L, json.getLong("outbound"))

        val restored = BackupCodec.ruleFromJson(json)
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.config, restored.config)
        assertEquals(original.userOrder, restored.userOrder)
        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.domains, restored.domains)
        assertEquals(original.ip, restored.ip)
        assertEquals(original.port, restored.port)
        assertEquals(original.sourcePort, restored.sourcePort)
        assertEquals(original.network, restored.network)
        assertEquals(original.source, restored.source)
        assertEquals(original.protocol, restored.protocol)
        assertEquals(original.ruleset, restored.ruleset)
        assertEquals(original.outbound, restored.outbound)
        assertEquals(original.packages, restored.packages)
    }

    @Test
    fun decodeRulesSupportsReadableJsonAndLegacyBase64Strings() {
        val modernRule = RuleEntity(id = 1L, name = "JSON Rule", enabled = true, domains = "example.com")
        val legacyRule = RuleEntity(id = 2L, name = "Legacy Base64 Rule", enabled = false, outbound = -1L)

        val array = JSONArray()
            .put(BackupCodec.ruleToJson(modernRule))
            .put("LEGACY_PARCEL_BASE64")

        val decoded = BackupCodec.decodeRules(array) { encoded ->
            assertEquals("LEGACY_PARCEL_BASE64", encoded)
            legacyRule
        }

        assertEquals(2, decoded.size)
        assertEquals("JSON Rule", decoded[0].name)
        assertTrue(decoded[0].enabled)
        assertEquals("Legacy Base64 Rule", decoded[1].name)
        assertFalse(decoded[1].enabled)
        assertEquals(-1L, decoded[1].outbound)
    }

    @Test
    fun exportSettingsWithDefaultsMaterializesMissingCoreKeysWithoutOverwritingStoredValues() {
        val explicitPairs = listOf(
            KeyValuePair(Key.BLOCK_QUIC).apply { put(false) },
            KeyValuePair(Key.MTU).apply { put("1400") },
        )
        val defaults = linkedMapOf<String, Any>(
            Key.BLOCK_QUIC to true,
            Key.MTU to "9000",
            Key.BYPASS_LAN to true,
            Key.TRAFFIC_SNIFFING to "1",
            Key.SMART_AUTO_STOP to false,
        )

        val exported = BackupCodec.exportSettingsWithDefaults(explicitPairs, defaults)
        val byKey = exported.associateBy { it.key }

        assertEquals(5, byKey.size)
        // Stored explicit values must remain untouched
        assertEquals(false, byKey.getValue(Key.BLOCK_QUIC).boolean)
        assertEquals("1400", byKey.getValue(Key.MTU).string)
        // Missing defaults must be materialized with exact Preference storage types
        assertEquals(true, byKey.getValue(Key.BYPASS_LAN).boolean)
        assertEquals("1", byKey.getValue(Key.TRAFFIC_SNIFFING).string)
        assertEquals(false, byKey.getValue(Key.SMART_AUTO_STOP).boolean)
    }

    @Test
    fun exportSettingsWithDefaultsMaterializesStringBackedPortAndSpeedIntervalAsStrings() {
        val defaults = linkedMapOf<String, Any>(
            Key.MIXED_PORT to "2080",
            Key.SPEED_INTERVAL to "1000",
        )

        val exported = BackupCodec.exportSettingsWithDefaults(emptyList(), defaults)
        val byKey = exported.associateBy { it.key }

        assertEquals(KeyValuePair.TYPE_STRING, byKey.getValue(Key.MIXED_PORT).valueType)
        assertEquals("2080", byKey.getValue(Key.MIXED_PORT).string)
        assertEquals(KeyValuePair.TYPE_STRING, byKey.getValue(Key.SPEED_INTERVAL).valueType)
        assertEquals("1000", byKey.getValue(Key.SPEED_INTERVAL).string)

        val codecSource = listOf(
            java.io.File("src/main/java/io/nekohasekai/sagernet/utils/BackupCodec.kt"),
            java.io.File("app/src/main/java/io/nekohasekai/sagernet/utils/BackupCodec.kt"),
            java.io.File("../app/src/main/java/io/nekohasekai/sagernet/utils/BackupCodec.kt"),
        ).first { it.isFile }.readText()
        assertTrue(codecSource.contains("Key.MIXED_PORT to DataStore.mixedPort.toString()"))
        assertTrue(codecSource.contains("Key.SPEED_INTERVAL to DataStore.speedInterval.toString()"))
    }
}
