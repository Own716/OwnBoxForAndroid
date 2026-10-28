package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.database.ProxyEntity

object ProfileConfigExport {
    fun export(entity: ProxyEntity): Pair<String, String> {
        val (name, config) = entity.exportConfig()
        return config to name
    }
}
