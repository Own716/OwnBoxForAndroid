package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NEKO
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean, val type: String
    ) {

        fun hash(): String {
            if (bean is ConfigBean) {
                return bean.config
            }
            val extra = when (bean) {
                is io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean ->
                    "${bean.uuid}/${bean.path}/${bean.sni}/${bean.realityPubKey}/${bean.security}/${bean.name}"
                is io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean ->
                    "${bean.password}/${bean.method}/${bean.plugin}/${bean.name}"
                is io.nekohasekai.sagernet.fmt.trojan.TrojanBean ->
                    "${bean.password}/${bean.sni}/${bean.name}"
                is io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean ->
                    "${bean.authPayload}/${bean.sni}/${bean.name}"
                is io.nekohasekai.sagernet.fmt.tuic.TuicBean ->
                    "${bean.token}/${bean.uuid}/${bean.sni}/${bean.name}"
                else -> bean.name
            }
            return "${bean.serverAddress}:${bean.serverPort}/$type/$extra"
        }

        override fun hashCode(): Int {
            return hash().toByteArray().contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Deduplication

            return hash() == other.hash()
        }

    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            ProxyEntity.TYPE_BALANCER -> android.graphics.Color.parseColor("#FF6F00")
            ProxyEntity.TYPE_CHAIN -> android.graphics.Color.parseColor("#7E57C2")
            TYPE_NEKO -> getColorAttr(android.R.attr.textColorPrimary)
            else -> getColorAttr(R.attr.accentOrTextSecondary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}
