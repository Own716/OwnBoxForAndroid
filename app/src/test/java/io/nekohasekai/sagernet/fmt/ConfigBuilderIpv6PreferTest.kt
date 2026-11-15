package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigBuilderIpv6PreferTest {

    private fun resolveSource(path: String): File {
        return listOf(File(path), File("..", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path (cwd=${File(".").absolutePath})")
    }

    private fun configBuilderText(): String =
        resolveSource("app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()

    private fun singBoxOptionsUtilText(): String =
        resolveSource("app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt").readText()

    @Test
    fun outboundsDoNotForceTcpFastOpenBlockingHappyEyeballs() {
        val text = configBuilderText()
        assertFalse(
            "ConfigBuilder must not force tcp_fast_open = true because it disables sing-box Happy Eyeballs parallel dual-stack fallback and masks dial failures",
            text.contains("""_hack_config_map["tcp_fast_open"] = true""")
        )
        val removeIdx = text.indexOf("""_hack_config_map.remove("tcp_fast_open")""")
        val customIdx = text.indexOf("_hack_custom_config = bean.customOutboundJson")
        assertTrue("Expected _hack_config_map.remove(\"tcp_fast_open\")", removeIdx >= 0)
        assertTrue("Expected customOutboundJson assignment after remove", customIdx > removeIdx)
    }

    @Test
    fun serverAndDirectKeepPreferIpv6Effectiveness() {
        val utilText = singBoxOptionsUtilText()
        val configText = configBuilderText()

        assertTrue(
            "Server outbound strategy should still prefer IPv6 when IPv6Mode.PREFER is selected",
            utilText.contains("IPv6Mode.PREFER) \"prefer_ipv6\" else \"prefer_ipv4\"")
        )
        assertTrue(
            "Direct outbound should keep prefer_ipv6 when IPv6Mode.PREFER is selected",
            configText.contains("""} else if (ipv6Mode == IPv6Mode.PREFER) {""") &&
                configText.contains("""_hack_config_map["domain_strategy"] = "prefer_ipv6"""")
        )
        assertTrue(
            "FakeDNS should retain fc00::/18 IPv6 range for non-DISABLE IPv6 modes",
            configText.contains("""if (ipv6Mode != IPv6Mode.DISABLE) {""") &&
                configText.contains("""inet6_range = "fc00::/18"""")
        )
    }

    @Test
    fun proxiedDnsAndResolveDefaultSafelyInPreferMode() {
        val configText = configBuilderText()

        assertTrue(
            "autoDnsDomainStrategy must map proxied lookups in PREFER to prefer_ipv4 by default",
            configText.contains("""IPv6Mode.PREFER -> if (isProxied) "prefer_ipv4" else "prefer_ipv6"""")
        )
        assertTrue(
            "dns.strategy in IPv6Mode.PREFER must defer non-test traffic to autoDnsDomainStrategy(dns-remote, isProxied = true)",
            configText.contains("""dns.strategy = if (forTest) {""") &&
                configText.contains("""autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"), isProxied = true)""")
        )
        val resolveIdx = configText.indexOf("if (DataStore.resolveDestination)")
        assertTrue("Expected DataStore.resolveDestination block", resolveIdx >= 0)
        val resolveSnippet = configText.substring(resolveIdx, minOf(configText.length, resolveIdx + 450))
        assertTrue(
            "resolveDestination in IPv6Mode.PREFER must use autoDnsDomainStrategy(dns-remote, isProxied = true)",
            resolveSnippet.contains("if (ipv6Mode == IPv6Mode.PREFER)") &&
                resolveSnippet.contains("autoDnsDomainStrategy(") &&
                resolveSnippet.contains("isProxied = true")
        )
    }

    @Test
    fun googlePlayDnsUsesFakeDnsOnTunAheadOfDomesticDirectRules() {
        val configText = configBuilderText()

        val fakeStart = configText.indexOf("fun addGooglePlayDnsRule")
        assertTrue("Expected addGooglePlayDnsRule helper", fakeStart >= 0)
        val snippet = configText.substring(fakeStart, minOf(configText.length, fakeStart + 900))
        assertTrue("Expected tun-in FakeDNS rule inside addGooglePlayDnsRule", snippet.contains("""inbound = listOf("tun-in")"""))
        assertTrue("Expected dns-fake server inside addGooglePlayDnsRule", snippet.contains("""server = "dns-fake""""))
        assertTrue("Expected disable_cache = true inside addGooglePlayDnsRule", snippet.contains("disable_cache = true"))
        assertTrue("Expected fakeDnsQueryTypes inside addGooglePlayDnsRule", snippet.contains("query_type = fakeDnsQueryTypes"))
        assertTrue("Expected dns-remote fallback inside addGooglePlayDnsRule", snippet.contains("""server = "dns-remote""""))

        val playDnsCall = configText.indexOf("addGooglePlayDnsRule { domain = googlePlayDomains }")
        val domesticIdx = configText.indexOf("val domesticCoreDomainSuffixes")
        assertTrue("Expected Google Play DNS rules before domesticCoreDomainSuffixes", playDnsCall in 0 until domesticIdx)
    }
}
