package io.nekohasekai.sagernet.bg.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTunManagerTest {

    private fun baseParams(
        ownUid: Int = 10123,
        socksPort: Int = 34567,
        socksUsername: String = "user123",
        socksPassword: String = "pass456",
        mtu: Int = 1360,
        enableIpv6: Boolean = false,
        bypassLan: Boolean = true,
        rootLanSharing: Boolean = false,
        perAppEnabled: Boolean = false,
        perAppBypass: Boolean = true,
        perAppUids: Set<Int> = emptySet(),
    ) = RootTunManager.StartParams(
        ownUid = ownUid,
        socksPort = socksPort,
        socksUsername = socksUsername,
        socksPassword = socksPassword,
        mtu = mtu,
        enableIpv6 = enableIpv6,
        bypassLan = bypassLan,
        rootLanSharing = rootLanSharing,
        perAppEnabled = perAppEnabled,
        perAppBypass = perAppBypass,
        perAppUids = perAppUids,
        hevtunBinPath = "/data/app/lib/arm64/libhevsockstun.so",
        yamlPath = "/data/user/0/com.ownbox.app/no_backup/ownbox_hevtun.yaml",
        pidPath = "/data/user/0/com.ownbox.app/no_backup/ownbox_hevtun.pid",
    )

    @Test
    fun buildHevConfig_usesIsolatedTunCidrAndClampsMtu() {
        val v4Only = RootTunManager.buildHevConfig(baseParams(mtu = 100, enableIpv6 = false))
        assertTrue(v4Only.contains("name: 'ownbox0'"))
        assertTrue(v4Only.contains("mtu: 576"))
        assertTrue(v4Only.contains("ipv4: 172.19.0.1"))
        assertFalse("Must never collide with OwnBox FakeDNS 198.18.0.0/15", v4Only.contains("198.18."))
        assertFalse(v4Only.contains("ipv6:"))
        assertTrue(v4Only.contains("port: 34567"))
        assertTrue(v4Only.contains("address: 127.0.0.1"))
        assertTrue(v4Only.contains("username: 'user123'"))
        assertTrue(v4Only.contains("password: 'pass456'"))

        val dualStack = RootTunManager.buildHevConfig(
            baseParams(
                mtu = 99999,
                enableIpv6 = true,
                socksUsername = "a'b",
            )
        )
        assertTrue(dualStack.contains("mtu: 65535"))
        assertTrue(dualStack.contains("ipv6: 'fdfe:dcba:9876::1'"))
        assertTrue(dualStack.contains("username: 'a''b'"))
    }

    @Test
    fun buildSetupScript_exemptsSelfLoopbackBeforeDnsAndLanBypass() {
        val script = RootTunManager.buildSetupScript(baseParams(bypassLan = true))

        val uidExemptIdx = script.indexOf("-A OWNBOX_ROOT_OUT -m owner --uid-owner 10123 -j RETURN")
        val loExemptIdx = script.indexOf("-A OWNBOX_ROOT_OUT -o lo -j RETURN")
        val loopbackExemptIdx = script.indexOf("-A OWNBOX_ROOT_OUT -d 127.0.0.0/8 -j RETURN")
        val tunIfaceExemptIdx = script.indexOf("-A OWNBOX_ROOT_OUT -o \"\$TUN\" -j RETURN")
        val dnsMarkIdx = script.indexOf("-A OWNBOX_ROOT_OUT -p udp --dport 53 -j MARK --set-mark 0x2024")
        val lanBypassIdx = script.indexOf("-A OWNBOX_ROOT_OUT -d 192.168.0.0/16 -j RETURN")
        val finalMarkIdx = script.indexOf("-A OWNBOX_ROOT_OUT -j MARK --set-mark 0x2024")

        assertTrue(uidExemptIdx > 0)
        assertTrue(loExemptIdx > uidExemptIdx)
        assertTrue(loopbackExemptIdx > loExemptIdx)
        assertTrue(tunIfaceExemptIdx > loopbackExemptIdx)
        assertTrue(dnsMarkIdx > tunIfaceExemptIdx)
        assertTrue("DNS port 53 must be captured before LAN CIDR bypass", lanBypassIdx > dnsMarkIdx)
        assertTrue(finalMarkIdx > lanBypassIdx)

        // Ensure nat OUTPUT DNAT exempts ownUid before rewriting :53 to 172.19.0.2:53
        val natSelfIdx = script.indexOf("-A OWNBOX_ROOT_DNS -m owner --uid-owner 10123 -j RETURN")
        val natDnatIdx = script.indexOf("-A OWNBOX_ROOT_DNS -p udp --dport 53 -j DNAT --to-destination 172.19.0.2:53")
        assertTrue(natSelfIdx > 0 && natDnatIdx > natSelfIdx)
    }

    @Test
    fun buildSetupScript_blacklistExemptsSelectedUids() {
        val script = RootTunManager.buildSetupScript(
            baseParams(
                ownUid = 10123,
                perAppEnabled = true,
                perAppBypass = true,
                perAppUids = setOf(10200, 10201, 10123),
            )
        )
        assertTrue(script.contains("-A OWNBOX_ROOT_OUT -m owner --uid-owner 10200 -j RETURN"))
        assertTrue(script.contains("-A OWNBOX_ROOT_OUT -m owner --uid-owner 10201 -j RETURN"))
        assertTrue(script.contains("-A OWNBOX_ROOT_DNS -m owner --uid-owner 10200 -j RETURN"))
        assertTrue(script.contains("-A OWNBOX_ROOT_OUT -j MARK --set-mark 0x2024"))
    }

    @Test
    fun buildSetupScript_whitelistMarksOnlyAllowedUidsAndEmptyWhitelistFailsClosed() {
        val whitelist = RootTunManager.buildSetupScript(
            baseParams(
                ownUid = 10123,
                perAppEnabled = true,
                perAppBypass = false,
                perAppUids = setOf(10300, 10301),
            )
        )
        assertFalse(whitelist.contains("-A OWNBOX_ROOT_OUT -j MARK --set-mark 0x2024"))
        assertTrue(whitelist.contains("-A OWNBOX_ROOT_OUT -m owner --uid-owner 10300 -j MARK --set-mark 0x2024"))
        assertTrue(whitelist.contains("-A OWNBOX_ROOT_OUT -m owner --uid-owner 10301 -j MARK --set-mark 0x2024"))

        val emptyWhitelist = RootTunManager.buildSetupScript(
            baseParams(
                ownUid = 10123,
                perAppEnabled = true,
                perAppBypass = false,
                perAppUids = setOf(10123), // ownUid filtered out => effectively empty
            )
        )
        assertFalse("Empty whitelist must not mark any traffic", emptyWhitelist.contains("-j MARK --set-mark 0x2024"))
        assertFalse("Empty whitelist must not DNAT netd DNS", emptyWhitelist.contains("-j DNAT --to-destination"))
    }

    @Test
    fun buildSetupScript_ipv6ToggleSwitchesBetweenBlackholeAndDualStack() {
        val v6Disabled = RootTunManager.buildSetupScript(baseParams(enableIpv6 = false))
        assertTrue(v6Disabled.contains("ip6tables -w 5 -t filter -N OWNBOX_ROOT_BLK6"))
        assertTrue(v6Disabled.contains("-j REJECT --reject-with icmp6-adm-prohibited"))
        assertFalse(v6Disabled.contains("ip -6 rule add fwmark 0x2024"))

        val v6Enabled = RootTunManager.buildSetupScript(baseParams(enableIpv6 = true))
        assertTrue(v6Enabled.contains("ip -6 addr replace fdfe:dcba:9876::1/126 dev \"\$TUN\""))
        assertTrue(v6Enabled.contains("ip -6 rule add fwmark 0x2024 lookup 2024 pref 9000"))
        assertTrue(v6Enabled.contains("ip6tables -w 5 -t mangle -N OWNBOX_ROOT_OUT"))
        assertFalse(v6Enabled.contains("ip6tables -w 5 -t filter -N OWNBOX_ROOT_BLK6"))
    }

    @Test
    fun buildSetupScript_rootLanSharingAddsForwardAndNatChainsOnlyWhenEnabled() {
        val off = RootTunManager.buildSetupScript(baseParams(rootLanSharing = false))
        assertFalse(off.contains("-N OWNBOX_ROOT_PRE"))
        assertFalse(off.contains("-N OWNBOX_ROOT_FWD"))
        assertFalse(off.contains("-N OWNBOX_ROOT_NAT"))
        assertFalse(off.contains("-j MASQUERADE"))
        assertFalse(off.contains("net.ipv4.ip_forward=1"))

        val on = RootTunManager.buildSetupScript(baseParams(rootLanSharing = true, enableIpv6 = false))
        assertTrue(on.contains("net.ipv4.ip_forward=1"))
        assertTrue(on.contains("iptables -w 5 -t mangle -N OWNBOX_ROOT_PRE"))
        assertTrue(on.contains("iptables -w 5 -t filter -N OWNBOX_ROOT_FWD"))
        assertTrue(on.contains("iptables -w 5 -t nat -N OWNBOX_ROOT_NAT"))
        assertTrue(on.contains("-o \"\$TUN\" -j MASQUERADE"))
        assertTrue(on.contains("ip6tables -w 5 -t filter -N OWNBOX_ROOT_BLK6_FWD"))
    }

    @Test
    fun buildTeardownScript_removesAllCustomChainsRulesRoutesAndInterface() {
        val teardown = RootTunManager.buildTeardownScript(
            tunName = "ownbox0",
            pidPath = "/tmp/ownbox.pid",
            yamlPath = "/tmp/ownbox.yaml",
        )
        listOf(
            "OWNBOX_ROOT_OUT",
            "OWNBOX_ROOT_PRE",
            "OWNBOX_ROOT_DNS",
            "OWNBOX_ROOT_NAT",
            "OWNBOX_ROOT_FWD",
            "OWNBOX_ROOT_BLK6",
            "OWNBOX_ROOT_BLK6_FWD",
        ).forEach { chain ->
            assertTrue("Missing cleanup for chain $chain", teardown.contains(chain))
        }
        assertTrue(teardown.contains("ip -4 rule del fwmark 0x2024 lookup 2024"))
        assertTrue(teardown.contains("ip -4 route flush table 2024"))
        assertTrue(teardown.contains("ip -6 rule del fwmark 0x2024 lookup 2024"))
        assertTrue(teardown.contains("ip link del dev 'ownbox0'"))
        assertTrue(teardown.contains("rm -f \"\$YAML_PATH\""))
    }

    @Test
    fun expandPackageNames_expandsGooglePlaySupportPackages() {
        val withPlay = RootTunManager.expandPackageNames("\ncom.android.vending\norg.telegram.messenger\n")
        assertTrue("com.android.vending" in withPlay)
        assertTrue("com.android.providers.downloads" in withPlay)
        assertTrue("com.android.providers.downloads.ui" in withPlay)
        assertTrue("com.xiaomi.providers.downloads" in withPlay)
        assertTrue("com.google.android.gsf" in withPlay)
        assertTrue("com.google.android.gms" in withPlay)
        assertTrue("org.telegram.messenger" in withPlay)

        val withoutPlay = RootTunManager.expandPackageNames("org.telegram.messenger")
        assertEquals(setOf("org.telegram.messenger"), withoutPlay)
    }
}
