package io.nekohasekai.sagernet.bg.root

import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTunManagerTest {

    private fun baseParams(
        mode: RootTunManager.RootMode = RootTunManager.RootMode.TUN,
        ownUid: Int = 10123,
        corePid: Int = 4321,
        socksPort: Int = 34567,
        socksUsername: String = "user123",
        socksPassword: String = "pass456",
        redirectPort: Int = 34568,
        dnsRedirectPort: Int = 34569,
        lanDnsRedirectPort: Int = 0,
        mtu: Int = 1360,
        enableIpv6: Boolean = false,
        bypassLan: Boolean = true,
        rootLanSharing: Boolean = false,
        perAppEnabled: Boolean = false,
        perAppBypass: Boolean = true,
        perAppUids: Set<Int> = emptySet(),
        directBypassUids: Set<Int> = emptySet(),
    ) = RootTunManager.StartParams(
        mode = mode,
        ownUid = ownUid,
        corePid = corePid,
        socksPort = socksPort,
        socksUsername = socksUsername,
        socksPassword = socksPassword,
        redirectPort = redirectPort,
        dnsRedirectPort = dnsRedirectPort,
        lanDnsRedirectPort = lanDnsRedirectPort,
        mtu = mtu,
        enableIpv6 = enableIpv6,
        bypassLan = bypassLan,
        rootLanSharing = rootLanSharing,
        perAppEnabled = perAppEnabled,
        perAppBypass = perAppBypass,
        perAppUids = perAppUids,
        directBypassUids = directBypassUids,
        hevtunBinPath = "/data/app/lib/arm64/libhevsockstun.so",
        yamlPath = "/data/user/0/com.ownbox.app/no_backup/ownbox_hevtun.yaml",
        pidPath = "/data/user/0/com.ownbox.app/no_backup/ownbox_hevtun.pid",
        logPath = "/data/user/0/com.ownbox.app/no_backup/ownbox_hevtun.log",
        oomGuardPidPath = "/data/user/0/com.ownbox.app/no_backup/ownbox_oomguard.pid",
    )

    @Test
    fun buildHevConfig_usesIsolatedSubnetAndV2rayNgOptions() {
        val cfg = RootTunManager.buildHevConfig(baseParams(mtu = 100, enableIpv6 = false, rootLanSharing = false))
        assertTrue(cfg.contains("name: 'ownbox0'"))
        assertTrue(cfg.contains("mtu: 576"))
        assertTrue(cfg.contains("multi-queue: true"))
        assertTrue(cfg.contains("ipv4: '172.20.0.1'"))
        assertFalse("Must not collide with VpnService 172.19.0.0/30", cfg.contains("172.19."))
        assertFalse("Must not collide with FakeDNS 198.18.0.0/15", cfg.contains("198.18."))
        assertFalse(cfg.contains("ipv6:"))
        assertFalse(cfg.contains("offload:"))
        assertFalse("YAML should not fork independently when using nohup", cfg.contains("pid-file:"))
        assertTrue(cfg.contains("port: 34567"))
        assertTrue(cfg.contains("tcp-fastopen: true"))

        val enhanceDual = RootTunManager.buildHevConfig(
            baseParams(
                mode = RootTunManager.RootMode.ENHANCE,
                mtu = 99999,
                enableIpv6 = true,
                socksUsername = "a'b",
            )
        )
        assertTrue(enhanceDual.contains("mtu: 65535"))
        assertTrue(enhanceDual.contains("ipv6: 'fdfe:dcba:9877::1'"))
        assertTrue(enhanceDual.contains("jumbo: false"))
        assertTrue(enhanceDual.contains("tso: false"))
        assertTrue(enhanceDual.contains("username: 'a''b'"))
    }

    @Test
    fun buildSetupScript_tunModeUsesAppendOrderPriority1000AllRpFilterAndMarkedLanDns() {
        val script = RootTunManager.buildSetupScript(baseParams(mode = RootTunManager.RootMode.TUN, bypassLan = true))

        assertTrue(script.contains("echo -1000 > /proc/4321/oom_score_adj"))
        assertTrue(script.contains("nohup \"\$HEV_BIN\" \"\$YAML_PATH\" >\"\$LOG_PATH\" 2>&1 &"))
        assertTrue(script.contains("echo 0 > /proc/sys/net/ipv4/conf/\"\$TUN\"/rp_filter"))
        assertTrue("Global rp_filter must be disabled alongside TUN", script.contains("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter"))
        assertTrue(script.contains("ip -4 rule add fwmark 0x2024 table 2024 priority 1000"))
        assertTrue("Mangle chain must append at end of OUTPUT", script.contains("iptables -w 5 -t mangle -A OUTPUT -j OWNBOX_ROOT_OUT"))
        assertFalse("Must never prepend before netd chains", script.contains("iptables -w 5 -t mangle -I OUTPUT 1"))

        val dnsMarkIdx = script.indexOf("-A OWNBOX_ROOT_OUT -p udp --dport 53 -j MARK --set-xmark 0x2024")
        val lanBypassIdx = script.indexOf("-A OWNBOX_ROOT_OUT -d 192.168.0.0/16 -j RETURN")
        assertTrue(dnsMarkIdx > 0 && lanBypassIdx > dnsMarkIdx)

        assertTrue(script.contains("-A OWNBOX_ROOT_DNS -m mark --mark 0x2024 -d 192.168.0.0/16 -p udp --dport 53 -j DNAT --to-destination 1.1.1.1"))
        assertFalse("Must never DNAT unmarked DNS packets", script.contains("-A OWNBOX_ROOT_DNS -p udp --dport 53 -j DNAT"))
        assertFalse("Must never DNAT to VpnService peer IP", script.contains("172.19.0.2"))
        assertTrue(script.contains("iptables -w 5 -t nat -A OUTPUT -j OWNBOX_ROOT_DNS"))
    }

    @Test
    fun buildSetupScript_redirectModeRoutesTcpAndDnsWithoutHevtun() {
        val script = RootTunManager.buildSetupScript(
            baseParams(
                mode = RootTunManager.RootMode.REDIRECT,
                redirectPort = 41001,
                dnsRedirectPort = 41002,
            )
        )
        assertFalse(script.contains("nohup \"\$HEV_BIN\""))
        assertFalse(script.contains("iptables -w 5 -t mangle -N OWNBOX_ROOT_OUT"))
        assertFalse(script.contains("iptables -w 5 -t mangle -A OUTPUT -j OWNBOX_ROOT_OUT"))
        assertTrue(script.contains("echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter"))
        assertTrue(script.contains("iptables -w 5 -t nat -N OWNBOX_ROOT_REDIR"))
        assertTrue(script.contains("-A OWNBOX_ROOT_REDIR -m owner --uid-owner 10123 -j RETURN"))
        assertTrue(script.contains("-A OWNBOX_ROOT_REDIR -p udp --dport 53 -j REDIRECT --to-ports 41002"))
        assertTrue(script.contains("-A OWNBOX_ROOT_REDIR -p tcp --dport 53 -j REDIRECT --to-ports 41002"))
        assertTrue(script.contains("-A OWNBOX_ROOT_REDIR -p tcp -j REDIRECT --to-ports 41001"))
        assertTrue(script.contains("iptables -w 5 -t nat -A OUTPUT -j OWNBOX_ROOT_REDIR"))
        assertTrue(script.contains("ip6tables -w 5 -t filter -A OUTPUT -j OWNBOX_ROOT_BLK6"))

        val lanRedirect = RootTunManager.buildSetupScript(
            baseParams(
                mode = RootTunManager.RootMode.REDIRECT,
                redirectPort = 41001,
                dnsRedirectPort = 41002,
                lanDnsRedirectPort = 34570,
                rootLanSharing = true,
            )
        )
        assertTrue(lanRedirect.contains("-A OWNBOX_ROOT_DNS_PRE -s 192.168.0.0/16 -p udp --dport 53 -j REDIRECT --to-ports 34570"))
        assertTrue(lanRedirect.contains("-A OWNBOX_ROOT_DNS_PRE -m addrtype --dst-type LOCAL -j RETURN"))
        assertTrue(lanRedirect.contains("-A OWNBOX_ROOT_DNS_PRE -s 192.168.0.0/16 -p tcp -j REDIRECT --to-ports 41001"))
    }

    @Test
    fun buildSetupScript_mixedModeSplitsTcpToRedirectAndUdpToTun() {
        val script = RootTunManager.buildSetupScript(
            baseParams(
                mode = RootTunManager.RootMode.MIXED,
                redirectPort = 41001,
                dnsRedirectPort = 0,
            )
        )
        assertTrue(script.contains("nohup \"\$HEV_BIN\" \"\$YAML_PATH\""))
        assertTrue(script.contains("-A OWNBOX_ROOT_REDIR -p tcp --dport 53 -j REDIRECT --to-ports 41001"))
        assertTrue(script.contains("-A OWNBOX_ROOT_REDIR -p tcp -j REDIRECT --to-ports 41001"))
        assertTrue(script.contains("-A OWNBOX_ROOT_OUT -p udp -j MARK --set-xmark 0x2024"))
        assertFalse("Mixed mode must not mark IPv4 TCP into mangle OUTPUT", script.contains("-A OWNBOX_ROOT_OUT -j MARK --set-xmark 0x2024"))
    }

    @Test
    fun buildSetupScript_enhanceModeForcesLanSharingEvenWhenSwitchOff() {
        val scriptV4 = RootTunManager.buildSetupScript(
            baseParams(
                mode = RootTunManager.RootMode.ENHANCE,
                rootLanSharing = false,
                enableIpv6 = false,
                redirectPort = 41001,
            )
        )
        assertTrue(scriptV4.contains("ip_forward"))
        assertTrue(scriptV4.contains("OWNBOX_ROOT_FWD"))
        assertTrue(scriptV4.contains("TCPMSS --set-mss 1350"))
        assertTrue(scriptV4.contains("iptables -w 5 -t mangle -N OWNBOX_ROOT_PRE"))
        assertTrue(scriptV4.contains("-A OWNBOX_ROOT_PRE -i \"\$TUN\" -j RETURN"))
        assertTrue(scriptV4.contains("-A OWNBOX_ROOT_PRE -m addrtype --dst-type LOCAL -j RETURN"))
        assertTrue(scriptV4.contains("-A OWNBOX_ROOT_PRE -s 192.168.0.0/16 -j MARK --set-xmark 0x2024"))
        assertTrue(scriptV4.contains("OWNBOX_ROOT_DNS_PRE"))
        assertFalse("Fragile goto 6000 ip rules must not be emitted", scriptV4.contains("goto 6000"))
        assertFalse("Fragile source ip rule must not be emitted", scriptV4.contains("from 192.168.0.0/16 lookup 2024"))
        assertTrue(scriptV4.contains("OWNBOX_ROOT_BLK6_FWD"))

        val scriptV6 = RootTunManager.buildSetupScript(
            baseParams(
                mode = RootTunManager.RootMode.ENHANCE,
                rootLanSharing = false,
                enableIpv6 = true,
                redirectPort = 41001,
            )
        )
        val v6PreStart = scriptV6.indexOf("ip6tables -w 5 -t mangle -N OWNBOX_ROOT_PRE")
        val v6LocalIdx = scriptV6.indexOf("ip6tables -w 5 -t mangle -A OWNBOX_ROOT_PRE -m addrtype --dst-type LOCAL -j RETURN", v6PreStart)
        val v6MarkIdx = scriptV6.indexOf("ip6tables -w 5 -t mangle -A OWNBOX_ROOT_PRE -j MARK --set-xmark 0x2024", v6PreStart)
        assertTrue("IPv6 OWNBOX_ROOT_PRE must bypass LOCAL before any MARK rule", v6PreStart > 0 && v6LocalIdx > v6PreStart && v6MarkIdx > v6LocalIdx)
    }

    @Test
    fun buildSetupScript_exemptsDirectBypassAndMultiUserUidsAndFailsClosedOnEmptyWhitelist() {
        val mixedBypass = RootTunManager.buildSetupScript(
            baseParams(
                mode = RootTunManager.RootMode.MIXED,
                perAppEnabled = false,
                directBypassUids = setOf(10200, 99910200),
            )
        )
        assertTrue(mixedBypass.contains("-A OWNBOX_ROOT_OUT -m owner --uid-owner 10200 -j RETURN"))
        assertTrue(mixedBypass.contains("-A OWNBOX_ROOT_OUT -m owner --uid-owner 99910200 -j RETURN"))
        assertTrue(mixedBypass.contains("-A OWNBOX_ROOT_REDIR -m owner --uid-owner 10200 -j RETURN"))
        assertTrue(mixedBypass.contains("-A OWNBOX_ROOT_REDIR -m owner --uid-owner 99910200 -j RETURN"))

        val emptyWhitelist = RootTunManager.buildSetupScript(
            baseParams(
                mode = RootTunManager.RootMode.MIXED,
                perAppEnabled = true,
                perAppBypass = false,
                perAppUids = setOf(10123), // ownUid filtered out => empty
            )
        )
        assertFalse(emptyWhitelist.contains("-j MARK --set-xmark 0x2024"))
        assertFalse(emptyWhitelist.contains("-j REDIRECT --to-ports"))
        assertFalse(emptyWhitelist.contains("iptables -w 5 -t nat -N OWNBOX_ROOT_DNS"))
        assertFalse(emptyWhitelist.contains("-j DNAT"))
    }

    @Test
    fun multiUserAndDirectPackageRuleHelpers_behaveAccurately() {
        val users = RootTunManager.parseUserIdsFromPmOutput(
            "Users:\n\tUserInfo{0:Owner:4c13} running\n\tUserInfo{999:MultiApp:4001010} running\n"
        )
        assertEquals(setOf(0, 999), users)

        val expanded = RootTunManager.expandMultiUserUids(setOf(10250, 99910250), users, ownUid = 10123)
        assertEquals(setOf(10250, 99910250), expanded)

        val rules = listOf(
            RuleEntity(id = 1, name = "Smart-Domestic-Apps-CN", outbound = -1L, enabled = true, packages = setOf("com.tencent.mobileqq")),
            RuleEntity(id = 2, name = "Custom-Direct-App", outbound = -1L, enabled = true, packages = setOf("com.tencent.mm")),
            RuleEntity(id = 3, name = "Disabled-Direct", outbound = -1L, enabled = false, packages = setOf("com.disabled.app")),
            RuleEntity(id = 4, name = "Smart-Foreign-Apps", outbound = 0L, enabled = true, packages = setOf("org.telegram.messenger")),
            RuleEntity(id = 5, name = "Mixed-Domain-And-App", outbound = -1L, enabled = true, packages = setOf("com.android.chrome"), domains = "baidu.com"),
        )
        assertEquals(setOf("com.tencent.mobileqq", "com.tencent.mm"), RootTunManager.collectPureDirectRoutePackages(rules))

        val withPlay = RootTunManager.expandPackageNames("\ncom.android.vending\norg.telegram.messenger\n")
        assertTrue("com.google.android.gms" in withPlay)
        assertTrue("com.android.providers.downloads" in withPlay)
    }

    @Test
    fun extractPlainDnsIp_and_buildTeardownScript_coverAllArtifactsAndLegacyRules() {
        assertEquals(
            "1.0.0.1",
            RootTunManager.extractPlainDnsIp("# comment\nhttps://dns.google/dns-query\n1.0.0.1\n", ipv6 = false),
        )
        assertNull(
            RootTunManager.extractPlainDnsIp("https://dns.google/dns-query\ntls://1.1.1.1\n", ipv6 = false),
        )
        assertEquals(
            "2606:4700:4700::1111",
            RootTunManager.extractPlainDnsIp("https://dns.google/dns-query\n2606:4700:4700::1111\n", ipv6 = true),
        )

        val teardown = RootTunManager.buildTeardownScript(
            tunName = "ownbox0",
            pidPath = "/tmp/ownbox.pid",
            oomGuardPidPath = "/tmp/oom.pid",
            corePid = 4321,
            yamlPath = "/tmp/ownbox.yaml",
            logPath = "/tmp/ownbox.log",
        )
        listOf(
            "OWNBOX_ROOT_OUT",
            "OWNBOX_ROOT_PRE",
            "OWNBOX_ROOT_DNS",
            "OWNBOX_ROOT_DNS_PRE",
            "OWNBOX_ROOT_REDIR",
            "OWNBOX_ROOT_NAT",
            "OWNBOX_ROOT_FWD",
            "OWNBOX_ROOT_BLK6",
            "OWNBOX_ROOT_BLK6_FWD",
        ).forEach { chain ->
            assertTrue("Missing teardown for chain $chain", teardown.contains(chain))
        }
        assertTrue(teardown.contains("priority 1000"))
        assertTrue("Must also purge legacy priority 9000 from v26.11.8.29", teardown.contains("priority 9000"))
        assertTrue(teardown.contains("pref 5000"))
        assertTrue(teardown.contains("pref 6000"))
        assertTrue(teardown.contains("echo 0 > /proc/4321/oom_score_adj"))
        assertTrue(teardown.contains("ip link del dev 'ownbox0'"))
    }

    @Test
    fun hasSuBinary_detectsExistingBinaryPassivelyWithoutSpawningSu() {
        val fakeSu = java.io.File.createTempFile("ownbox_fake_su_", "")
        try {
            assertTrue(RootShell.hasSuBinary(extraPaths = listOf(fakeSu.absolutePath)))
        } finally {
            fakeSu.delete()
        }
        // Must handle missing / non-existent extra paths gracefully without throwing
        RootShell.hasSuBinary(extraPaths = listOf(fakeSu.absolutePath, "/non/existent/path/su"))
    }
}
