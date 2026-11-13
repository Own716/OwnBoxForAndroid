package io.nekohasekai.sagernet.bg

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches non-VPN physical networks (Wi-Fi / Ethernet / USB) inside the `:bg` process
 * and suspends/resumes the proxy when matching user-configured trusted intranet rules.
 */
class SmartAutoStopController(
    private val service: BaseService.Interface,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var evaluateJob: Job? = null

    @Volatile
    private var smartStopOverride = false

    @Volatile
    private var overrideNetworkKey = ""

    private var lastEvaluatedSignature: String? = null

    private val connectivity: ConnectivityManager
        get() = SagerNet.connectivity

    fun start() {
        refreshConfig(initialStart = true)
    }

    fun refreshConfig(initialStart: Boolean = false) {
        val enabled = DataStore.smartAutoStop
        val rules = SmartAutoStopMatcher.parseRules(DataStore.smartAutoStopNetworks)
        synchronized(lock) {
            evaluateJob?.cancel()
            evaluateJob = null
            if (!enabled || rules.isEmpty()) {
                unregisterCallbackLocked()
                smartStopOverride = false
                overrideNetworkKey = ""
                lastEvaluatedSignature = null
            } else {
                registerCallbackLocked()
                scheduleEvaluationLocked(if (initialStart) 1500L else 250L)
                return
            }
        }
        // If Smart Auto-Stop was turned off or rules were cleared while suspended, resume proxy.
        if (service.data.state == BaseService.State.Suspended || service.data.isSmartStopped) {
            scope.launch {
                service.smartResume(manual = true)
            }
        }
    }

    fun onManualOverride() {
        val snapshot = collectPhysicalSnapshot()
        synchronized(lock) {
            smartStopOverride = true
            overrideNetworkKey = snapshot.networkKey
            Logs.i("SmartAutoStop: manual override recorded for networkKey='${overrideNetworkKey}'")
        }
    }

    fun clearOverride() {
        synchronized(lock) {
            smartStopOverride = false
            overrideNetworkKey = ""
        }
    }

    fun stop() {
        synchronized(lock) {
            evaluateJob?.cancel()
            evaluateJob = null
            unregisterCallbackLocked()
            smartStopOverride = false
            overrideNetworkKey = ""
            lastEvaluatedSignature = null
        }
        scope.cancel()
    }

    private fun registerCallbackLocked() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleEvaluation(1500L)
            }

            override fun onLost(network: Network) {
                scheduleEvaluation(1500L)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scheduleEvaluation(1500L)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                scheduleEvaluation(1500L)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivity.registerNetworkCallback(request, cb)
            callback = cb
            Logs.i("SmartAutoStop: network callback registered")
        }.onFailure {
            Logs.w("SmartAutoStop: failed to register network callback", it)
        }
    }

    private fun unregisterCallbackLocked() {
        val cb = callback ?: return
        callback = null
        runCatching {
            connectivity.unregisterNetworkCallback(cb)
            Logs.i("SmartAutoStop: network callback unregistered")
        }.onFailure {
            Logs.w("SmartAutoStop: failed to unregister network callback", it)
        }
    }

    private fun scheduleEvaluation(delayMs: Long) {
        synchronized(lock) {
            if (!DataStore.smartAutoStop) return
            scheduleEvaluationLocked(delayMs)
        }
    }

    private fun scheduleEvaluationLocked(delayMs: Long) {
        evaluateJob?.cancel()
        evaluateJob = scope.launch {
            delay(delayMs)
            evaluateNow()
        }
    }

    private suspend fun evaluateNow() {
        if (!DataStore.smartAutoStop) return
        val rules = SmartAutoStopMatcher.parseRules(DataStore.smartAutoStopNetworks)
        if (rules.isEmpty()) return

        val snapshot = collectPhysicalSnapshot()
        val key = snapshot.networkKey
        val sig = "${snapshot.hasPhysicalNetwork}|$key|${snapshot.gatewayAddresses.joinToString(",")}"
        val shouldLog = synchronized(lock) {
            val changed = sig != lastEvaluatedSignature
            if (changed) lastEvaluatedSignature = sig
            changed
        }

        if (!snapshot.hasPhysicalNetwork) {
            synchronized(lock) {
                if (smartStopOverride) {
                    smartStopOverride = false
                    overrideNetworkKey = ""
                }
            }
            if (service.data.state == BaseService.State.Suspended || service.data.isSmartStopped) {
                if (shouldLog) Logs.i("SmartAutoStop: no physical LAN/Wi-Fi active, resuming proxy")
                service.smartResume(manual = false)
            }
            return
        }

        val match = SmartAutoStopMatcher.evaluate(rules, snapshot)
        val overridden = synchronized(lock) {
            if (smartStopOverride && key != overrideNetworkKey) {
                smartStopOverride = false
                overrideNetworkKey = ""
            }
            smartStopOverride
        }

        if (match.isMatched) {
            if (overridden) {
                if (shouldLog) {
                    Logs.i("SmartAutoStop: matched rule '${match.matchedRule}' (${match.matchedAddress}), kept active due to manual override")
                }
                return
            }
            val currentState = service.data.state
            if (currentState == BaseService.State.Connecting || currentState == BaseService.State.Connected) {
                val label = match.matchedAddress ?: match.matchedRule.orEmpty()
                Logs.i("SmartAutoStop: matched rule '${match.matchedRule}' ($label), suspending proxy")
                service.smartSuspend(label)
            }
        } else {
            synchronized(lock) {
                if (smartStopOverride) {
                    smartStopOverride = false
                    overrideNetworkKey = ""
                }
            }
            if (service.data.state == BaseService.State.Suspended || service.data.isSmartStopped) {
                Logs.i("SmartAutoStop: left trusted intranet (ips=${snapshot.ipAddresses}), resuming proxy")
                service.smartResume(manual = false)
            }
        }
    }

    fun collectPhysicalSnapshot(): SmartAutoStopMatcher.NetworkSnapshot {
        val ips = mutableListOf<String>()
        val gateways = mutableListOf<String>()
        var hasPhysical = false
        val networks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            runCatching { connectivity.allNetworks }.getOrNull() ?: emptyArray()
        } else {
            emptyArray()
        }

        for (network in networks) {
            val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
            val isPhysicalLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB))
            if (!isPhysicalLan) continue

            hasPhysical = true
            val lp = runCatching { connectivity.getLinkProperties(network) }.getOrNull() ?: continue
            for (la in lp.linkAddresses) {
                val addr = la.address ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) continue
                val host = addr.hostAddress?.substringBefore('%') ?: continue
                if (host.isNotBlank()) ips += host
            }
            for (route in lp.routes) {
                if (!route.isDefaultRoute) continue
                val gw = route.gateway ?: continue
                if (gw.isLoopbackAddress || gw.isAnyLocalAddress) continue
                val host = gw.hostAddress?.substringBefore('%') ?: continue
                if (host.isNotBlank()) gateways += host
            }
        }

        return SmartAutoStopMatcher.NetworkSnapshot(
            ipAddresses = ips.distinct(),
            gatewayAddresses = gateways.distinct(),
            hasPhysicalNetwork = hasPhysical,
        )
    }
}
