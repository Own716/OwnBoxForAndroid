package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RouteRuleEditor as Editor
import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RouteRuleEditorTest {
    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test fun fieldEditsPreserveCustomJsonAndOtherState() {
        val original = """{"domain_regex":["a{1,3}\\.example"],"future_match":{"value":7},"invert":true}"""
        val changed = Editor.change(original, "domain_suffix", "example.com\nexample.org")
        assertEquals(7, JSONObject(changed).getJSONObject("future_match").getInt("value"))
        assertEquals("a{1,3}\\.example", Editor.text(changed, "domain_regex"))
        val removed = JSONObject(Editor.change(changed, "domain_suffix", ""))
        assertFalse(removed.has("domain_suffix")); assertTrue(removed.getBoolean("invert"))
        assertEquals(original, RuleEntity(config = original).copy(name = "renamed").config)
    }

    @Test fun addressesPortsAndRangesValidateWithoutDns() {
        listOf("192.0.2.1", "10.0.0.0/8", "2001:db8::/32", "::1").forEach { assertTrue(it, Editor.address(it)) }
        listOf("example.com", "999.0.0.1", "1.2.3", "10.0.0.1/33", "::1/129", "[::1]").forEach { assertFalse(it, Editor.address(it)) }
        listOf("0", "65535", "80:443", ":443", "1024:").forEach { assertTrue(it, Editor.portOrRange(it)) }
        listOf("-1", "65536", "443:80", ":", "abc", "1:2:3").forEach { assertFalse(it, Editor.portOrRange(it)) }
    }

    @Test fun advancedFieldsAndActionOptionsAreValidated() {
        assertTrue(Editor.problems(RuleEntity(domains = "example.com", config = """{"action":"reject","method":"reply","no_drop":true}""")).isEmpty())
        val bad = Editor.problems(RuleEntity(ip = "300.0.0.1", port = "65536", config = """{"action":"reject","method":"drop","no_drop":true}"""))
        assertEquals(setOf("ip_cidr", "port", "method"), bad.map { it.field }.toSet())
        assertTrue(Editor.problems(RuleEntity(config = "{}")).any { it.error == Editor.Error.EMPTY })
        assertTrue(Editor.problems(RuleEntity(config = "[]")).any { it.error == Editor.Error.JSON })
        assertTrue(Editor.problems(RuleEntity(domains = "example.com", config = """{"action":"sniff","strategy":"ipv4_only"}""")).any { it.field == "strategy" })
        for (key in listOf("override_destination", "process_path", "wifi_ssid", "reject_method")) {
            assertTrue(Editor.problems(RuleEntity(domains = "example.com", config = """{"$key":"kept"}""")).any { it.error == Editor.Error.UNSUPPORTED })
        }
    }

    @Test fun unknownSetsAndInboundAreNotSilentlyAccepted() {
        assertTrue(Editor.problems(RuleEntity(domains = "geosite:cn", config = """{"rule_set":["geosite:cn"]}""")).isEmpty())
        assertTrue(Editor.problems(RuleEntity(domains = "example.com", config = """{"rule_set":["unknown"]}""")).any { it.field == "rule_set" })
        assertTrue(Editor.problems(RuleEntity(domains = "example.com", config = """{"inbound":["desktop-tun"]}""")).any { it.field == "inbound" })
        assertTrue(Editor.problems(RuleEntity(ruleset = "rssite:https://example.com/list.srs")).isEmpty())
        assertTrue(Editor.problems(RuleEntity(ruleset = "rssite:http://example.com/list.srs")).any { it.field == "rule_set" })
    }

    @Test fun routeProjectionUsesActionAndOutboundSemantics() {
        for ((id, expected) in listOf(0L to "proxy", -1L to "bypass", 42L to "proxy")) {
            val rule = Editor.validationConfig(RuleEntity(domains = "full:example.com", outbound = id))
            val projected = JSONObject(rule).getJSONObject("route").getJSONArray("rules").getJSONObject(0)
            assertEquals(expected, projected.getString("outbound")); assertEquals("example.com", projected.getJSONArray("domain").getString(0))
        }
        for (action in listOf("sniff", "resolve", "reject", "hijack-dns", "route-options")) {
            val r = SingBoxOptions.Rule_DefaultOptions().apply { outbound = "actual-node-tag" }
            Editor.applyAction(r, """{"action":"$action"}""")
            val projected = JSONObject(r.asMap())
            assertEquals(action, projected.getString("action")); assertFalse(projected.has("outbound"))
        }
        assertEquals("reject", Editor.action("{\"action\":\"route\"}", -2L))
        val rejected = JSONObject(Editor.validationConfig(RuleEntity(domains = "example.com", outbound = -2, config = "{\"action\":\"route\"}")))
            .getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("reject", rejected.getString("action")); assertFalse(rejected.has("outbound"))
    }

    @Test fun inversionWrapsLegacyAlternativesOnceWithoutNestedActions() {
        val domain = SingBoxOptions.Rule_DefaultOptions().apply { domain_suffix = listOf("example.com"); outbound = "node-42"; _hack_custom_config = "{\"invert\":true}" }
        val ip = SingBoxOptions.Rule_DefaultOptions().apply { ip_cidr = listOf("192.0.2.0/24"); outbound = "node-42"; _hack_custom_config = "{\"invert\":true}" }
        val combined = JSONObject(Editor.invertedGroup(listOf(domain, ip))!!.asMap())
        assertEquals("or", combined.getString("mode")); assertTrue(combined.getBoolean("invert")); assertEquals("node-42", combined.getString("outbound"))
        val children = combined.getJSONArray("rules")
        for (i in 0 until children.length()) for (key in listOf("action", "outbound", "invert")) assertFalse(children.getJSONObject(i).has(key))
        assertNull(Editor.invertedGroup(listOf(domain)))
        domain._hack_custom_config = ""; ip._hack_custom_config = ""
        assertNull(Editor.invertedGroup(listOf(domain, ip)))
    }

    @Test fun editorContractsKeepDatabaseBackupAndServiceScope() {
        val source = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt").readText()
        assertTrue(source.contains("?.copy()")); assertTrue(source.contains("newTestSingBoxInstance")); assertFalse(source.contains(".start()"))
        assertTrue(source.indexOf("RouteRuleEditor.problems(candidate)") < source.indexOf("ProfileManager.createRule(candidate)"))
        assertTrue(source.contains("saveAndExit(true)")); assertTrue(source.contains("SagerNet.reloadService()")); assertTrue(source.contains("registerChangeListener(this)"))
        val db = sourceFile("src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt").readText()
        assertTrue(db.contains("version = 10")); assertFalse(db.contains("fallbackToDestructiveMigration"))
        val entity = sourceFile("src/main/java/io/nekohasekai/sagernet/database/RuleEntity.kt").readText()
        assertTrue(entity.contains("var config: String")); assertFalse(entity.contains("routeProfile"))
        val builder = sourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(builder.contains("RouteRuleEditor.applyAction(ruleObj, rule.config)")); assertTrue(builder.contains("RouteRuleEditor.invertedGroup(generatedSubRules)"))
        assertTrue(builder.contains("SagerDatabase.rulesDao.enabledRules()")); assertTrue(builder.contains("DataStore.globalMode"))
    }
}
