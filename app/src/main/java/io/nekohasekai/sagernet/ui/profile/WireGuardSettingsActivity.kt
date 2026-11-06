package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

import io.nekohasekai.sagernet.ktx.applyDefaultValues

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val localAddress = pbm.add(PreferenceBinding(Type.Text, "localAddress"))
    private val privateKey = pbm.add(PreferenceBinding(Type.Text, "privateKey"))
    private val peerPublicKey = pbm.add(PreferenceBinding(Type.Text, "peerPublicKey"))
    private val peerPreSharedKey = pbm.add(PreferenceBinding(Type.Text, "peerPreSharedKey"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val reserved = pbm.add(PreferenceBinding(Type.Text, "reserved"))
    private val listenPort = pbm.add(PreferenceBinding(Type.TextToInt, "listenPort"))
    private val persistentKeepaliveInterval =
        pbm.add(PreferenceBinding(Type.TextToInt, "persistentKeepaliveInterval"))

    private val jc = pbm.add(PreferenceBinding(Type.TextToInt, "jc"))
    private val jmin = pbm.add(PreferenceBinding(Type.TextToInt, "jmin"))
    private val jmax = pbm.add(PreferenceBinding(Type.TextToInt, "jmax"))
    private val s1 = pbm.add(PreferenceBinding(Type.TextToInt, "s1"))
    private val s2 = pbm.add(PreferenceBinding(Type.TextToInt, "s2"))
    private val h1 = pbm.add(PreferenceBinding(Type.TextToLong, "h1"))
    private val h2 = pbm.add(PreferenceBinding(Type.TextToLong, "h2"))
    private val h3 = pbm.add(PreferenceBinding(Type.TextToLong, "h3"))
    private val h4 = pbm.add(PreferenceBinding(Type.TextToLong, "h4"))

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (listenPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (persistentKeepaliveInterval.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (jc.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (jmin.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (jmax.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (s1.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (s2.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (h1.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (h2.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (h3.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (h4.preference as EditTextPreference).setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    }

}
