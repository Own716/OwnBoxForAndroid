package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs

class UrlTest(private val overrideLink: String? = null) {

    private val timeout = DataStore.connectionTestTimeout

    fun resolveLink(profile: ProxyEntity): String {
        if (!overrideLink.isNullOrBlank()) return overrideLink
        val groupUrl = DataStore.groupUrlTestUrl(profile.groupId).trim()
        if (groupUrl.isNotBlank()) return groupUrl
        return DataStore.connectionTestURL
    }

    suspend fun doTest(profile: ProxyEntity): Int {
        val link = resolveLink(profile)
        Logs.d("URLTest ${profile.displayName()}: start, link=$link, timeout=${timeout}ms")
        return TestInstance(profile, link, timeout).doTest()
    }

}
