package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression source-contract tests for Issue #88 fixes while keeping Smart Routing
 * (`SmartRouteRules` / `isSmartRouting`) and default speed test parameters intact.
 */
class ConfigBuilderRouteIssuesTest {

    private fun resolveSourceFile(relativeFromApp: String): File {
        val direct = File(relativeFromApp)
        if (direct.isFile) return direct
        val fromRoot = File("app", relativeFromApp)
        if (fromRoot.isFile) return fromRoot
        error("Unable to locate source file: $relativeFromApp (cwd=${File(".").absolutePath})")
    }

    @Test
    fun multicastRejectRuleOmitsSourceIpCidrAndDeadDnsProxyRulesAreRemoved() {
        val text = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()

        val mcastIndex = text.indexOf("listOf(\"224.0.0.0/3\", \"ff00::/8\")")
        assertTrue("Expected multicast block rule in ConfigBuilder.kt", mcastIndex >= 0)
        val mcastWindow = text.substring(maxOf(0, mcastIndex - 200), minOf(text.length, mcastIndex + 200))
        assertFalse("Multicast reject rule must not include source_ip_cidr", mcastWindow.contains("source_ip_cidr"))

        // Hijack-DNS handles port 53 / dns protocol; dead mainProxyTag port-53 route rules should be gone
        assertFalse(
            "Dead DNS port 53 -> mainProxyTag rule should be removed",
            text.contains("port = listOf(53)\n                outbound = mainProxyTag") ||
                text.contains("protocol = listOf(\"dns\")\n                outbound = mainProxyTag"),
        )
    }

    @Test
    fun topRouteRulesAndPlayAndDomesticPriorityPreserveCustomRules() {
        val text = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        val topRouteStart = text.indexOf("val topRouteRules =")
        assertTrue("Expected topRouteRules builder block", topRouteStart >= 0)

        val blockQuicIf = text.indexOf("if (DataStore.blockQuic)", topRouteStart)
        val sniffIf = text.indexOf("if (needSniff)", topRouteStart)
        val rejectBucketAdd = text.indexOf("topRouteRules.addAll(earlyRejectUserRules)", topRouteStart)
        val playSuffixAdd = text.indexOf("domain_suffix = GOOGLE_PLAY_DOMAIN_SUFFIXES", topRouteStart)
        val firstUserDirectLookup = text.indexOf("val firstUserDirectIdx =", topRouteStart)
        val smartDomesticGuard = text.indexOf("if (isSmartRouting)", topRouteStart)
        val domesticAdd = text.indexOf("domain_suffix = domesticCoreDomainSuffixes", smartDomesticGuard)

        assertTrue("Expected DataStore.blockQuic toggle guard", blockQuicIf >= 0)
        assertTrue("Expected needSniff guard in topRouteRules", sniffIf >= 0)
        assertTrue("Expected earlyRejectUserRules injection in topRouteRules", rejectBucketAdd >= 0)
        assertTrue("Expected suffix-based Google Play protection rule", playSuffixAdd >= 0)
        assertTrue("Expected firstUserDirectIdx lookup so user proxy rules precede Play fallback", firstUserDirectLookup >= 0)
        assertTrue("Expected domesticCoreDomainSuffixes scoped to isSmartRouting", smartDomesticGuard >= 0 && domesticAdd > smartDomesticGuard)

        assertTrue("needSniff must precede Google Play rules", sniffIf < playSuffixAdd)
        assertTrue("Google Play protection must precede smart domestic fallback", playSuffixAdd < domesticAdd)
        assertTrue(
            "Smart routing must keep its own ordering without routing reject rules into earlyRejectUserRules",
            text.contains("if (!isSmartRouting && routingAction == \"reject\")"),
        )
        assertTrue(
            "ConfigBuilder must sync rule_set download_detour tags against built outbounds",
            text.contains("syncRuleSetDownloadDetours(route.rule_set, availableOutboundTags, mainProxyTag)"),
        )
        assertTrue(
            "ConfigBuilder must answer HTTPS/SVCB DNS queries via predefined NOERROR action",
            text.contains("query_type = listOf(\"HTTPS\", \"SVCB\")") && text.contains("action = \"predefined\""),
        )
        assertFalse(
            "ConfigBuilder must not force tcp_fast_open=true globally",
            text.contains("_hack_config_map[\"tcp_fast_open\"] = true"),
        )
    }

    @Test
    fun downloadManagerRemovedFromRoutingRulesWhilePreservedInTunWhitelist() {
        val configBuilder = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        val profileManager = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/database/ProfileManager.kt").readText()
        val vpnService = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt").readText()

        assertFalse(
            "ConfigBuilder must not force com.android.providers.downloads into user rule packages",
            configBuilder.contains("com.android.providers.downloads"),
        )
        val beforeStripSection = profileManager.substringBefore("Strip legacy injected system download manager packages")
        assertFalse(
            "ProfileManager default rule definitions/upgrades must not inject com.android.providers.downloads",
            beforeStripSection.contains("com.android.providers.downloads"),
        )
        assertTrue(
            "ProfileManager must actively strip legacy com.android.providers.downloads from existing Play Store rules",
            profileManager.contains("rule.packages = rule.packages - setOf(") &&
                profileManager.contains("\"com.android.providers.downloads\""),
        )
        assertTrue(
            "VpnService TUN whitelist must keep DownloadManager allowed so Play Store downloads keep working",
            vpnService.contains("com.android.providers.downloads"),
        )
    }

    @Test
    fun groupSettingsUsesRawUrlTestPreferenceToAvoidBakingGlobalDefault() {
        val groupSettings = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt").readText()
        val dataStore = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()

        assertTrue(
            "GroupSettingsActivity must initialize groupUrlTestUrl from rawGroupUrlTestUrl(id)",
            groupSettings.contains("DataStore.groupUrlTestUrl = DataStore.rawGroupUrlTestUrl(id)"),
        )
        assertTrue(
            "DataStore.groupUrlTestUrl must fallback to DataStore.connectionTestURL when blank or default",
            dataStore.contains("stored.takeIf { it.isNotBlank() && it != defaultUrl } ?: connectionTestURL"),
        )
    }

    @Test
    fun echQueryDomainsExemptFromPredefinedHttpsBlockAndRoutedToDirectDns() {
        val outboundMaps: List<Map<String, Any?>> = listOf(
            // 1. Explicit ech.query_server_name (mixed case + trailing dot -> normalized)
            mapOf(
                "tag" to "proxy-explicit",
                "server" to "8.216.61.208",
                "tls" to mapOf(
                    "enabled" to true,
                    "server_name" to "cf.worker.example",
                    "ech" to mapOf(
                        "enabled" to true,
                        "query_server_name" to "WWW.Britannica.COM.",
                    ),
                ),
            ),
            // 2. Fallback to tls.server_name when query_server_name omitted
            mapOf(
                "tag" to "proxy-sni-fallback",
                "server" to "8.216.61.209",
                "tls" to mapOf(
                    "enabled" to true,
                    "server_name" to "cdn.example.org",
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 3. Fallback to server when both query_server_name and tls.server_name omitted
            mapOf(
                "tag" to "proxy-server-fallback",
                "server" to "edge.example.net",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 4. Static ech.config as List<String> -> skipped
            mapOf(
                "tag" to "proxy-static-list",
                "server" to "static-list.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "config" to listOf("-----BEGIN ECH CONFIGS-----", "AAA="),
                    ),
                ),
            ),
            // 5. Static ech.config as String -> skipped
            mapOf(
                "tag" to "proxy-static-string",
                "server" to "static-str.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "config" to "-----BEGIN ECH CONFIGS-----\nAAA=",
                    ),
                ),
            ),
            // 6. Static ech.config_path -> skipped
            mapOf(
                "tag" to "proxy-static-path",
                "server" to "static-path.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "config_path" to "/data/local/tmp/ech.pem",
                    ),
                ),
            ),
            // 7. ech.enabled = false -> skipped
            mapOf(
                "tag" to "proxy-ech-disabled",
                "server" to "no-ech.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to false),
                ),
            ),
            // 8. tls.enabled = false -> skipped
            mapOf(
                "tag" to "proxy-tls-disabled",
                "server" to "no-tls.example.com",
                "tls" to mapOf(
                    "enabled" to false,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 9. IPv4 literal server without domain SNI -> skipped
            mapOf(
                "tag" to "proxy-ipv4-only",
                "server" to "8.216.61.208",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 10. IPv6 literal server without domain SNI -> skipped
            mapOf(
                "tag" to "proxy-ipv6-only",
                "server" to "[2606:4700::1]",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 11. Duplicate domain -> deduplicated
            mapOf(
                "tag" to "proxy-dup",
                "server" to "1.1.1.1",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "query_server_name" to "www.britannica.com",
                    ),
                ),
            ),
        )

        val domains = extractEchQueryDomains(outboundMaps)
        assertEquals(listOf("www.britannica.com", "cdn.example.org", "edge.example.net"), domains)

        val text = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        val predefinedIdx = text.indexOf("action = \"predefined\"")
        val echExtractIdx = text.indexOf("val echQueryDomains = extractEchQueryDomains(outboundMaps)")
        val echRuleIdx = text.indexOf("domain = echQueryDomains", echExtractIdx)
        val echDirectIdx = text.indexOf("server = \"dns-direct\"", echRuleIdx)

        assertTrue("Expected predefined HTTPS/SVCB DNS rule", predefinedIdx >= 0)
        assertTrue("Expected ECH domain extraction after predefined rule so add(0, ...) prepends before it", echExtractIdx > predefinedIdx)
        assertTrue("Expected ECH exemption rule referencing domain = echQueryDomains", echRuleIdx > echExtractIdx)
        assertTrue("Expected ECH exemption rule routed explicitly via server = \"dns-direct\"", echDirectIdx > echRuleIdx)
    }
}
