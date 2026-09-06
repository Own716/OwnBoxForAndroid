package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage() {
            val processed = CompletableDeferred<Unit>()
        }
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    private val networkActor = GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        val pendingRequests = arrayListOf<NetworkMessage.Get>()
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                try {
                    if (listeners.isEmpty()) register()
                    listeners[message.key] = message.listener
                    if (network != null) message.listener(network)
                    message.processed.complete(Unit)
                } catch (error: Throwable) {
                    val removed = listeners.remove(message.key) != null
                    if (removed && listeners.isEmpty()) runCatching { unregister() }
                    message.processed.completeExceptionally(error)
                }
            }
            is NetworkMessage.Get -> {
                check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                if (network == null) pendingRequests += message else message.response.complete(
                    network
                )
            }
            is NetworkMessage.Stop -> if (listeners.isNotEmpty() && // was not empty
                listeners.remove(message.key) != null && listeners.isEmpty()
            ) {
                network = null
                unregister()
            }

            is NetworkMessage.Put -> {
                // 诊断：夜间/飞行模式网络事件时间线（与 Go UpdateDefaultInterface 对照）
                // elapsed 覆盖全部 listener 同步执行耗时（Unconfined actor 内联在回调线程跑）
                val start = SystemClock.elapsedRealtime()
                network = message.network
                pendingRequests.forEach { it.response.complete(message.network) }
                pendingRequests.clear()
                listeners.values.forEach { it(network) }
                Logs.i("DefaultNetworkListener Put network=${message.network} listeners=${listeners.size} elapsed=${SystemClock.elapsedRealtime() - start}ms thread=${Thread.currentThread().name}")
            }
            is NetworkMessage.Update -> if (network == message.network) {
                // 切网/信号抖动时此事件会风暴（onCapabilitiesChanged），降为 debug
                val start = SystemClock.elapsedRealtime()
                listeners.values.forEach {
                    it(
                        network
                    )
                }
                Logs.d("DefaultNetworkListener Update network=${message.network} listeners=${listeners.size} elapsed=${SystemClock.elapsedRealtime() - start}ms thread=${Thread.currentThread().name}")
            }
            is NetworkMessage.Lost -> if (network == message.network) {
                val start = SystemClock.elapsedRealtime()
                network = null
                listeners.values.forEach { it(null) }
                Logs.i("DefaultNetworkListener Lost network=${message.network} listeners=${listeners.size} elapsed=${SystemClock.elapsedRealtime() - start}ms thread=${Thread.currentThread().name}")
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) {
        val message = NetworkMessage.Start(key, listener)
        networkActor.send(message)
        // send 只保证消息进入 actor，不保证 Start 分支和缓存网络的首次回调已经完成。
        // 必须等待 processed，否则并发创建测试 Box 时 Go monitor 可能以 default=nil 返回，
        // 随后的首拨会抢在 updateDefaultInterface(wlan0, index) 之前并立即失败。
        message.processed.await()
    }

    suspend fun get() = if (fallback) @TargetApi(23) {
        SagerNet.connectivity.activeNetwork
            ?: throw UnknownHostException() // failed to listen, return current if available
    } else NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: API 26 以下跑在 ConnectivityThread；26+ 经 register 传 callbackHandler
    // 派发到专用 worker 线程（曾用 mainHandler 派发到进程主线程，
    // 测速多监听器 + onCapabilitiesChanged 风暴时主线程被秒级阻塞 → 界面卡死）
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Logs.d("DefaultNetworkListener onAvailable enter network=$network thread=${Thread.currentThread().name}")
            runBlocking { networkActor.send(NetworkMessage.Put(network)) }
        }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) { // it's a good idea to refresh capabilities
            // 风暴源：逐条 debug（Info 会刷屏）
            Logs.d("DefaultNetworkListener onCapabilitiesChanged enter network=$network thread=${Thread.currentThread().name}")
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLost(network: Network) {
            Logs.d("DefaultNetworkListener onLost enter network=$network thread=${Thread.currentThread().name}")
            runBlocking { networkActor.send(NetworkMessage.Lost(network)) }
        }
    }

    private var fallback = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val callbackThread = HandlerThread("DefaultNetworkListener").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, Callback, callbackHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, Callback, callbackHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback, callbackHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
        }
    }

    private fun unregister() = SagerNet.connectivity.unregisterNetworkCallback(Callback)
}
