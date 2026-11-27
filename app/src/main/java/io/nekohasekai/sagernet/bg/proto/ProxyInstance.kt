package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp && (BuildConfig.DEBUG || io.nekohasekai.sagernet.database.DataStore.logLevel >= 4)) {
            Logs.d(config.config)
        }
        if (notTmp && BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        if (BuildConfig.DEBUG || io.nekohasekai.sagernet.database.DataStore.logLevel >= 4) {
            pluginConfigs.forEach { (_, plugin) ->
                val (_, content) = plugin
                Logs.d(content)
            }
        }
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        var closeError: Throwable? = null
        try {
            super.close()
        } catch (error: Throwable) {
            closeError = error
        }
        try {
            runBlocking {
                looper?.stop()
                looper = null
            }
        } catch (error: Throwable) {
            if (closeError == null) {
                closeError = error
            } else if (closeError !== error) {
                closeError?.addSuppressed(error)
            }
        }
        closeError?.let { throw it }
    }
}
