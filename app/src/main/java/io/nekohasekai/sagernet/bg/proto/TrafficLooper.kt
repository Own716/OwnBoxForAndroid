package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.ActiveOutboundTracker
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import libcore.Libcore

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    companion object {
        private const val TRAFFIC_BATCH_SIZE = 500
    }

    private var job: Job? = null
    private var lastSpeedSnapshot: SpeedDisplayData? = null
    private var wakeupSignal: CompletableDeferred<Unit>? = null

    fun triggerWakeup() {
        wakeupSignal?.complete(Unit)
    }

    suspend fun postLastSnapshotSpeed() {
        val speed = lastSpeedSnapshot ?: return
        data.notification?.apply {
            if (listenPostSpeed) postNotificationSpeedUpdate(speed)
        }
    }
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data
    private val stateMutex = Mutex()
    private var trafficUpdater: TrafficUpdater? = null

    private data class LoopSnapshot(
        val speed: SpeedDisplayData,
        val trafficUpdates: ArrayList<TrafficData>,
    )

    private suspend fun <T> withStateLock(block: suspend () -> T): T {
        stateMutex.lock()
        return try {
            block()
        } finally {
            stateMutex.unlock()
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        // finally traffic post
        if (!DataStore.profileTrafficStatistics) return
        withStateLock {
            val traffic = mutableMapOf<Long, TrafficData>()
            data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
                for (ent in ents) {
                    val item = idMap[ent.id] ?: return@forEach
                    ent.rx = item.rx
                    ent.tx = item.tx
                    ProfileManager.updateTraffic(ent.id, ent.rx, ent.tx)
                    traffic[ent.id] = TrafficData(
                        id = ent.id,
                        rx = ent.rx,
                        tx = ent.tx,
                    )
                }
            }
            if (traffic.isNotEmpty()) {
                val batches = traffic.values.chunked(TRAFFIC_BATCH_SIZE).map {
                    TrafficDataBatch(ArrayList(it))
                }
                data.binder.broadcast { callback ->
                    batches.forEach { callback.cbTrafficUpdate(it) }
                }
            }
        }
        Logs.d("finally traffic post done")
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    fun getActiveTransmittingMember(memberIds: List<Long>): Long? {
        return memberIds.firstOrNull { id ->
            val item = idMap[id]
            item != null && (item.hasTrafficDelta || item.rxRate > 0 || item.txRate > 0)
        }
    }

    suspend fun selectMain(id: Long) = withStateLock {
        selectMainLocked(id)
    }

    private suspend fun selectMainLocked(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.safeConfig?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    ProfileManager.updateTraffic(it.id, it.rx, it.tx)
                }
            }
        }
        val currentConfig = data.proxy?.safeConfig
        val balancerMembers = currentConfig?.balancerMemberMap
        // If old proxy was balancer, set old members to ignore = true
        balancerMembers?.get(selectorNowId)?.forEach { memberId ->
            idMap[memberId]?.ignore = true
        }

        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }

        // If new proxy is balancer, set new members to ignore = false
        balancerMembers?.get(id)?.forEach { memberId ->
            idMap[memberId]?.ignore = false
        }
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        val targetIds = profileIds.asSequence().filter { it > 0L }.toHashSet()
        if (targetIds.isEmpty()) return

        withStateLock {
            trafficUpdater?.updateAll()
            val changed = linkedMapOf<Long, TrafficData>()
            idMap.forEach { (id, item) ->
                if (id > 0L && id !in targetIds && item.hasTrafficDelta) {
                    changed[id] = TrafficData(id = id, rx = item.rx, tx = item.tx)
                }
            }

            data.proxy?.safeConfig?.trafficMap?.values?.forEach { entities ->
                entities.forEach { entity ->
                    if (entity.id in targetIds) {
                        entity.tx = 0L
                        entity.rx = 0L
                    }
                }
            }
            targetIds.forEach { id ->
                idMap[id]?.apply {
                    tx = 0L
                    rx = 0L
                    txBase = 0L
                    rxBase = 0L
                    txRate = 0L
                    rxRate = 0L
                    hasTrafficDelta = false
                }
                changed[id] = TrafficData(id = id, rx = 0L, tx = 0L)
            }
            ProfileManager.resetTraffic(targetIds.toLongArray())
            val batches = changed.values.chunked(TRAFFIC_BATCH_SIZE).map {
                TrafficDataBatch(ArrayList(it))
            }
            data.binder.broadcast { callback ->
                if (data.binder.callbackIdMap[callback] ==
                    SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                ) {
                    batches.forEach { callback.cbTrafficUpdate(it) }
                }
            }
        }
    }

    private suspend fun loop() {
        val baseDelayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        if (baseDelayMs == 0L) return

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)
        var idleSeconds = 0
        var lastDelayMs = 3000L

        while (currentCoroutineContext().isActive) {
            val isForegroundUI = data.binder.callbackIdMap.containsValue(
                SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
            )
            val isInteractive = SagerNet.power.isInteractive

            val proxy = data.proxy
            if (proxy == null) {
                delay(if (isForegroundUI) baseDelayMs else 3000L)
                continue
            }
            if (!proxy.isInitialized()) continue

            val snapshot = withStateLock {
                val currentConfig = proxy.safeConfig ?: return@withStateLock null
                val balancerMemberMap = currentConfig.balancerMemberMap

                if (trafficUpdater == null) {
                    idMap.clear()
                    tagMap.clear()
                    idMap[-1] = itemBypass
                    //
                    val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                    currentConfig.trafficMap.forEach { (tag, ents) ->
                        tags.add(tag)
                        for (ent in ents) {
                            val item = TrafficUpdater.TrafficLooperData(
                                tag = tag,
                                rx = ent.rx,
                                tx = ent.tx,
                                rxBase = ent.rx,
                                txBase = ent.tx,
                                ignore = currentConfig.selectorGroupId >= 0L,
                            )
                            idMap[ent.id] = item
                            tagMap[tag] = item
                            Logs.d("traffic count $tag to ${ent.id}")
                        }
                    }
                    if (currentConfig.selectorGroupId >= 0L) {
                        selectMainLocked(currentConfig.mainEntId)
                    } else {
                        balancerMemberMap.values.forEach { memberIds ->
                            memberIds.forEach { idMap[it]?.ignore = false }
                        }
                    }
                    //
                    trafficUpdater = TrafficUpdater(
                        box = proxy.box, items = idMap.values.toList()
                    )
                    proxy.box.setV2rayStats(tags.joinToString("\n"))
                }

                trafficUpdater!!.updateAll()
                currentCoroutineContext().ensureActive()

                // Accumulate member traffic into Balancer entity
                balancerMemberMap.forEach { (balancerId, memberIds) ->
                    val balancerItem = idMap[balancerId] ?: return@forEach
                    val members = memberIds.filter { it != balancerId }.mapNotNull { idMap[it] }
                    if (members.isNotEmpty()) {
                        var sumTxRate = 0L
                        var sumRxRate = 0L
                        var sumTxDelta = 0L
                        var sumRxDelta = 0L
                        var hasDelta = false
                        for (m in members) {
                            if (m === balancerItem) continue
                            sumTxRate += m.txRate
                            sumRxRate += m.rxRate
                            sumTxDelta += (m.tx - m.txBase)
                            sumRxDelta += (m.rx - m.rxBase)
                            if (m.hasTrafficDelta) hasDelta = true
                        }
                        if (sumTxRate > 0L || sumRxRate > 0L || sumTxDelta > 0L || sumRxDelta > 0L) {
                            balancerItem.txRate = sumTxRate
                            balancerItem.rxRate = sumRxRate
                            balancerItem.tx = balancerItem.txBase + sumTxDelta
                            balancerItem.rx = balancerItem.rxBase + sumRxDelta
                            if (hasDelta) {
                                balancerItem.hasTrafficDelta = true
                            }
                        } else if (balancerItem.tag == TAG_PROXY || balancerId == selectorNowId) {
                            // Active Balancer proxy: keep real rates queried from TAG_PROXY
                        }
                    }
                }

                // add all non-bypass to "main"
                var mainTxRate = 0L
                var mainRxRate = 0L
                var mainTx = 0L
                var mainRx = 0L
                val balancerMemberIds = balancerMemberMap.entries.flatMap { (bId, mIds) ->
                    mIds.filter { it != bId }
                }.toSet()
                idMap.forEach { (id, it) ->
                    if (id > 0L && id !in balancerMemberIds) {
                        if (!it.ignore) {
                            mainTxRate += it.txRate
                            mainRxRate += it.rxRate
                        }
                        mainTx += it.tx - it.txBase
                        mainRx += it.rx - it.rxBase
                    }
                }

                val trafficUpdates = arrayListOf<TrafficData>()
                if (profileTrafficStatistics) {
                    idMap.forEach { (id, item) ->
                        if (id > 0L && item.hasTrafficDelta) {
                            trafficUpdates.add(TrafficData(id = id, rx = item.rx, tx = item.tx))
                        }
                    }
                }
                val loopSnapshot = LoopSnapshot(
                    speed = SpeedDisplayData(
                        mainTxRate,
                        mainRxRate,
                        if (showDirectSpeed) itemBypass.txRate else 0L,
                        if (showDirectSpeed) itemBypass.rxRate else 0L,
                        mainTx,
                        mainRx
                    ),
                    trafficUpdates = trafficUpdates,
                )
                if (data.state == BaseService.State.Connected
                    && data.binder.callbackIdMap.containsValue(
                        SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                    )
                ) {
                    data.binder.broadcast { callback ->
                        if (data.binder.callbackIdMap[callback] ==
                            SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
                        ) {
                            callback.cbSpeedUpdate(loopSnapshot.speed)
                            if (loopSnapshot.trafficUpdates.isNotEmpty()) {
                                loopSnapshot.trafficUpdates.chunked(TRAFFIC_BATCH_SIZE).forEach {
                                    callback.cbTrafficUpdate(TrafficDataBatch(ArrayList(it)))
                                }
                            }
                        }
                    }
                }
                if (ActiveOutboundTracker.checkAndUpdate(data)) {
                    val newTitle = ActiveOutboundTracker.formatNotificationTitle(proxy.profile)
                    proxy.displayProfileName = newTitle
                    data.notification?.postNotificationTitle(newTitle)
                }
                loopSnapshot
            }
            if (snapshot == null) {
                delay(if (isForegroundUI) baseDelayMs else 3000L)
                continue
            }
            currentCoroutineContext().ensureActive()

            lastSpeedSnapshot = snapshot.speed

            // ServiceNotification: Only post if screen is interactive (saves CPU wakeups while screen is off)
            data.notification?.apply {
                if (listenPostSpeed && isInteractive) {
                    postNotificationSpeedUpdate(snapshot.speed)
                }
            }

            // Background low-power battery optimization:
            // Avoid periodic forced GC which causes CPU page faults and prevents deep sleep.
            // When screen is off, suspend polling up to 5 minutes to let SoC enter Doze/C-states;
            // when screen turns on, triggerWakeup() wakes this coroutine immediately.
            val nextDelay = when {
                isForegroundUI -> baseDelayMs
                !isInteractive -> if (DataStore.performancePriorityMode) 60000L else 300000L
                data.notification?.listenPostSpeed == true -> if (DataStore.performancePriorityMode) 3000L else 6000L
                else -> if (DataStore.performancePriorityMode) 5000L else 15000L
            }
            lastDelayMs = nextDelay
            withTimeoutOrNull(nextDelay) {
                val deferred = CompletableDeferred<Unit>().also { wakeupSignal = it }
                try {
                    deferred.await()
                } finally {
                    wakeupSignal = null
                }
            }
        }
    }
}
