package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment.ListSummaryProvider

class DnsSettingsFragment : BaseSettingsPreferenceFragment() {

    override val preferenceResId = R.xml.settings_dns

    private val remotePresets = listOf(
        "Google DoH (8.8.8.8)" to "https://dns.google/dns-query",
        "Cloudflare DoH (1.1.1.1)" to "https://1.1.1.1/dns-query",
        "Cloudflare HTTP/3 (DoH3)" to "h3://cloudflare-dns.com/dns-query",
        "AdGuard DoQ (QUIC 防广告)" to "quic://dns.adguard-dns.com",
        "Quad9 DoH (抗污染)" to "https://dns.quad9.net/dns-query",
        "OpenDNS DoH" to "https://doh.opendns.com/dns-query"
    )

    private val directPresets = listOf(
        "阿里公共 DNS DoH" to "https://223.5.5.5/dns-query",
        "阿里公共 DNS DoQ (QUIC)" to "quic://dns.alidns.com",
        "腾讯 DNSPod DoH" to "https://doh.pub/dns-query",
        "360 安全 DNS DoH" to "https://doh.360.cn/dns-query",
        "114 公共 DNS (UDP)" to "udp://114.114.114.114:53",
        "阿里公共 DNS (UDP)" to "udp://223.5.5.5:53"
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        val remoteDnsPref = findPreference<EditTextPreference>(Key.REMOTE_DNS)
        remoteDnsPref?.onPreferenceChangeListener = reloadListener

        val directDnsPref = findPreference<EditTextPreference>(Key.DIRECT_DNS)
        directDnsPref?.onPreferenceChangeListener = reloadListener

        findPreference<Preference>("domain_strategy_for_remote")?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("domain_strategy_for_direct")?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>("domain_strategy_for_server")?.onPreferenceChangeListener = reloadListener

        findPreference<Preference>(Key.ENABLE_DNS_ROUTING)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.ENABLE_FAKEDNS)?.onPreferenceChangeListener = reloadListener

        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)
        dnsHosts?.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts?.summaryProvider = ListSummaryProvider(maxLines = 1)
        dnsHosts?.onPreferenceChangeListener = reloadListener

        findPreference<Preference>("remoteDnsPresets")?.setOnPreferenceClickListener {
            val names = remotePresets.map { "${it.first}\n${it.second}" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.remote_dns_presets)
                .setItems(names) { _, which ->
                    val chosen = remotePresets[which].second
                    remoteDnsPref?.text = chosen
                    remoteDnsPref?.let { reloadListener.onPreferenceChange(it, chosen) }
                    Toast.makeText(requireContext(), getString(R.string.dns_preset_applied, chosen), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        findPreference<Preference>("directDnsPresets")?.setOnPreferenceClickListener {
            val names = directPresets.map { "${it.first}\n${it.second}" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.direct_dns_presets)
                .setItems(names) { _, which ->
                    val chosen = directPresets[which].second
                    directDnsPref?.text = chosen
                    directDnsPref?.let { reloadListener.onPreferenceChange(it, chosen) }
                    Toast.makeText(requireContext(), getString(R.string.dns_preset_applied, chosen), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }
}
