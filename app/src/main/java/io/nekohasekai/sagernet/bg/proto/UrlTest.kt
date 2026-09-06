package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int {
        Logs.d("URLTest ${profile.displayName()}: start, link=$link, timeout=${timeout}ms")
        return TestInstance(profile, link, timeout).doTest()
    }

}
