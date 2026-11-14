package io.nekohasekai.sagernet.bg.autoselector

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ActiveOutboundTracker
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AutoSelectorPlanner
import io.nekohasekai.sagernet.fmt.SelectorMember
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates the live lifecycle of an active AutoSelector in `:bg`:
 * - Polls Go core status (`QueryAutoSelectorsJSON`), pushes updates over AIDL,
 *   and keeps `ActiveOutboundTracker` & notifications in sync.
 * - Background-ranks unmeasured/stale pool candidates via `UrlTest` (respecting `expected`
 *   quorum for early start and avoiding restart loops even when `resultValidityMins == 0`).
 * - Persists probe health periodically into `AutoSelectorHealthStore` / Room.
 * - Watches for total pool exhaustion and subscription replacements to trigger clean rebuilds.
 */
object AutoSelectorRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var installed = false

    @Volatile
    private var activeInstance: ProxyInstance? = null

    @Volatile
    private var activeBuild: AutoSelectorBuild? = null

    @Volatile
    private var latestStatus: AutoSelectorStatus = AutoSelectorStatus.IDLE

    @Volatile
    private var lastStatusJson: String = ""

    private var pollJob: Job? = null
    private var rankingJob: Job? = null

    @Volatile
    private var rankingActive = false

    @Volatile
    private var rankingDone = 0

    @Volatile
    private var rankingTotal = 0

    // Tracks which selector already completed a ranking cycle for the current start/rebuild
    // so `resultValidityMins == 0` ("always retest") doesn't cause an infinite restart loop.
    @Volatile
    private var skipNextRankingForSelectorId: Long = 0L

    @Volatile
    private var restartingNow = false

    private var exhaustedSinceElapsedMs = 0L
    private var lastExhaustRebuildElapsedMs = 0L
    private var exhaustBackoffMs = 60_000L

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            GroupManager.addListener(groupListener)
        }
    }

    private val groupListener = object : GroupManager.Listener {
        override suspend fun groupAdd(group: ProxyGroup) {}

        override suspend fun groupUpdated(group: ProxyGroup) {
            onTrackedGroupChanged(group.id)
        }

        override suspend fun groupUpdated(groupId: Long) {
            onTrackedGroupChanged(groupId)
        }

        override suspend fun groupRemoved(groupId: Long) {
            onTrackedGroupChanged(groupId)
        }
    }

    fun beforeStart(instance: ProxyInstance) {
        if (!instance.notTmp) return
        stopWorkersLocked(keepInstance = false)
        activeInstance = instance
        activeBuild = null
        exhaustedSinceElapsedMs = 0L
        if (!restartingNow) {
            skipNextRankingForSelectorId = 0L
            exhaustBackoffMs = 60_000L
        }
        restartingNow = false
    }

    fun onStarted(instance: ProxyInstance) {
        if (!instance.notTmp) return
        val build = instance.safeConfig?.autoSelector
        if (build == null) {
            activeInstance = null
            activeBuild = null
            publishStatus(instance, AutoSelectorStatus.IDLE, force = true)
            return
        }

        activeInstance = instance
        activeBuild = build
        exhaustedSinceElapsedMs = 0L
        lastExhaustRebuildElapsedMs = SystemClock.elapsedRealtime()

        val initialNowSec = System.currentTimeMillis() / 1000L
        scope.launch {
            runCatching {
                AutoSelectorProfiles.recordPlanOutcome(
                    selectorId = build.selectorId,
                    poolIds = build.memberIds,
                    buildIds = build.memberIds,
                    nowSec = initialNowSec,
                )
            }
        }

        pollJob = scope.launch {
            runPollingLoop(instance, build)
        }

        val skipRanking = skipNextRankingForSelectorId == build.selectorId
        skipNextRankingForSelectorId = 0L
        if (!skipRanking) {
            rankingJob = scope.launch {
                runBackgroundRanking(instance, build)
            }
        }
    }

    fun onClosed(instance: ProxyInstance) {
        if (activeInstance !== instance && activeInstance != null) return
        stopWorkersLocked(keepInstance = false)
        if (!restartingNow) {
            skipNextRankingForSelectorId = 0L
        }
        publishStatus(instance, AutoSelectorStatus.IDLE, force = true)
    }

    fun serviceStopped() {
        val prevInstance = activeInstance
        stopWorkersLocked(keepInstance = false)
        restartingNow = false
        skipNextRankingForSelectorId = 0L
        exhaustBackoffMs = 60_000L
        publishStatus(prevInstance, AutoSelectorStatus.IDLE, force = true)
    }

    fun restart(service: BaseService.Interface, block: () -> Unit) {
        restartingNow = true
        stopWorkersLocked(keepInstance = true)
        block()
    }

    fun currentStatusJson(withMembers: Boolean): String {
        val instance = activeInstance
        val build = activeBuild
        if (instance == null || build == null || !instance.isInitialized()) {
            return AutoSelectorStatus.IDLE.toJson(withMembers)
        }
        val status = fetchMergedStatus(instance, build, withMembers)
        latestStatus = status
        return status.toJson(withMembers)
    }

    fun recheck(service: BaseService.Interface?) {
        val instance = activeInstance ?: service?.data?.proxy ?: return
        val build = activeBuild ?: return
        if (!instance.isInitialized()) return
        scope.launch {
            runCatching {
                AutoSelectorCore.recheck(instance, build.groupTag)
                delay(250)
                val status = fetchMergedStatus(instance, build, withMembers = true)
                publishStatus(instance, status, force = true)
            }.onFailure {
                Logs.w("AutoSelector recheck failed: ${it.readableMessage}")
            }
        }
    }

    fun select(service: BaseService.Interface?, memberProfileId: Long) {
        val instance = activeInstance ?: service?.data?.proxy
        val build = activeBuild
        scope.launch {
            val selectorId = build?.selectorId ?: DataStore.selectedProxy
            if (selectorId > 0L) {
                AutoSelectorProfiles.setPinned(selectorId, memberProfileId)
            }
            if (instance == null || build == null || !instance.isInitialized()) return@launch

            if (memberProfileId <= 0L) {
                runCatching {
                    AutoSelectorCore.select(instance, build.groupTag, "")
                    delay(150)
                    val status = fetchMergedStatus(instance, build, withMembers = true)
                    publishStatus(instance, status, force = true)
                }.onFailure {
                    Logs.w("AutoSelector clear pin failed: ${it.readableMessage}")
                }
                return@launch
            }

            val memberTag = build.members.entries.firstOrNull { it.value == memberProfileId }?.key
            if (memberTag != null) {
                runCatching {
                    AutoSelectorCore.select(instance, build.groupTag, memberTag)
                    delay(150)
                    val status = fetchMergedStatus(instance, build, withMembers = true)
                    publishStatus(instance, status, force = true)
                }.onFailure {
                    Logs.w("AutoSelector select($memberProfileId) failed: ${it.readableMessage}")
                }
            } else {
                // Selected profile is in the larger pool but not built yet: rebuild with it pinned at front.
                Logs.i("AutoSelectorRuntime: pinned member $memberProfileId not in running build, triggering rebuild")
                (service ?: instance.service)?.let { svc ->
                    triggerRebuild(svc, skipNextRanking = true)
                }
            }
        }
    }

    private fun stopWorkersLocked(keepInstance: Boolean) {
        pollJob?.cancel()
        pollJob = null
        rankingJob?.cancel()
        rankingJob = null
        rankingActive = false
        rankingDone = 0
        rankingTotal = 0
        if (!keepInstance) {
            activeInstance = null
            activeBuild = null
        }
    }

    private suspend fun runPollingLoop(instance: ProxyInstance, build: AutoSelectorBuild) {
        var lastPersistElapsed = SystemClock.elapsedRealtime()
        var lastSeenRounds = -1
        var lastLeafId = -1L

        while (coroutineScopeActive(instance)) {
            val status = fetchMergedStatus(instance, build, withMembers = true)
            latestStatus = status

            if (status.selectedId > 0L && status.selectedId != lastLeafId) {
                lastLeafId = status.selectedId
                syncActiveLeafNotification(instance, status.selectedId)
            }

            publishStatus(instance, status, force = false)

            val nowElapsed = SystemClock.elapsedRealtime()
            val roundsAdvanced = status.roundsCompleted > lastSeenRounds && status.roundsCompleted > 0
            if (roundsAdvanced || nowElapsed - lastPersistElapsed >= 60_000L) {
                lastSeenRounds = status.roundsCompleted
                lastPersistElapsed = nowElapsed
                persistCoreHealth(status)
            }

            checkExhaustionRebuild(instance, build, status, nowElapsed)

            val interactive = runCatching { SagerNet.power.isInteractive }.getOrDefault(true)
            delay(if (interactive) 2_000L else 10_000L)
        }
    }

    private suspend fun runBackgroundRanking(instance: ProxyInstance, build: AutoSelectorBuild) {
        val (entity, bean) = AutoSelectorProfiles.findAutoSelector(build.selectorId) ?: return
        val startNowSec = System.currentTimeMillis() / 1000L
        val plan = AutoSelectorProfiles.buildPlan(bean, excludeProfileId = entity.id, nowSec = startNowSec)
        val toMeasure = AutoSelectorPlanner.rankingCandidates(plan.pool, bean, startNowSec)
        if (toMeasure.isEmpty()) {
            AutoSelectorProfiles.recordPlanOutcome(
                selectorId = bean.let { entity.id },
                poolIds = plan.pool.map { it.id },
                buildIds = plan.build.map { it.id },
                nowSec = startNowSec,
            )
            return
        }

        rankingActive = true
        rankingTotal = toMeasure.size
        rankingDone = 0

        val urlTest = UrlTest(build.testUrl.ifBlank { null })
        val concurrency = DataStore.connectionTestConcurrent.coerceIn(1, 8)
        val semaphore = Semaphore(concurrency)
        val completedCount = AtomicInteger(0)
        val workingCount = AtomicInteger(0)
        val updates = ConcurrentHashMap<Long, AutoSelectorProfiles.HealthUpdate>()
        val measureSessionEpochSec = startNowSec + 1L
        val quorumTarget = AutoSelectorPlanner.startQuorum(
            totalUnmeasured = toMeasure.size,
            expected = bean.expected,
            buildSize = bean.buildLimit.coerceIn(1, plan.pool.size.coerceAtLeast(1)),
        )
        var earlyRestarted = false

        try {
            coroutineScope {
                val jobs = toMeasure.map { member ->
                    async {
                        if (!isActive || activeInstance !== instance) return@async
                        semaphore.withPermit {
                            if (!isActive || activeInstance !== instance) return@withPermit
                            val memberEntity = runCatching { SagerDatabase.proxyDao.getById(member.id) }.getOrNull()
                            val atSec = System.currentTimeMillis() / 1000L
                            if (memberEntity == null) {
                                updates[member.id] = AutoSelectorProfiles.HealthUpdate(member.id, 0, atSec, "missing")
                            } else {
                                try {
                                    val rtt = urlTest.doTest(memberEntity).coerceAtLeast(1)
                                    updates[member.id] = AutoSelectorProfiles.HealthUpdate(member.id, rtt, atSec, null)
                                    workingCount.incrementAndGet()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    updates[member.id] = AutoSelectorProfiles.HealthUpdate(
                                        member.id,
                                        0,
                                        atSec,
                                        e.readableMessage.take(160),
                                    )
                                }
                            }

                            val done = completedCount.incrementAndGet()
                            rankingDone = done

                            // Save in small batches so progress survives even if user stops early.
                            if (done % 6 == 0) {
                                AutoSelectorProfiles.saveHealth(updates.values.toList())
                            }

                            // Cold-start quorum check: if nothing in the initial build had fresh latency
                            // and we just found enough working servers while full ranking would still take a while,
                            // wait briefly so concurrent fast nodes land too, or finish full sweep when small.
                            if (!earlyRestarted &&
                                plan.reusedCount == 0 &&
                                quorumTarget > 0 &&
                                workingCount.get() >= quorumTarget &&
                                toMeasure.size > bean.buildLimit &&
                                done < toMeasure.size - 10
                            ) {
                                earlyRestarted = true
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }

            if (!coroutineScopeActive(instance)) return

            AutoSelectorProfiles.saveHealth(updates.values.toList())

            // Re-rank pool using fresh measurements (treating `measureSessionEpochSec` as "now" with >= 60s window
            // so even if `resultValidityMins == 0`, our freshly ranked pool comparison works cleanly).
            val updatedMembers = plan.pool.map { m ->
                val u = updates[m.id]
                if (u != null) {
                    m.copy(
                        latency = u.latencyMs,
                        latencyAt = u.atSec.coerceAtLeast(measureSessionEpochSec),
                        error = u.error,
                    )
                } else m
            }
            val effectiveBeanForRerank = bean.clone().apply {
                if (resultValidityMins <= 0) resultValidityMins = 1
            }
            val rerankedPool = AutoSelectorPlanner.rerank(
                pool = updatedMembers,
                bean = effectiveBeanForRerank,
                nowSec = System.currentTimeMillis() / 1000L,
            )
            val buildLimit = bean.buildLimit.coerceIn(1, io.nekohasekai.sagernet.fmt.internal.BalancerBean.MAX_BUILD_LIMIT)
                .coerceAtMost(rerankedPool.size.coerceAtLeast(1))
            val nextBuildIds = rerankedPool.take(buildLimit).map { it.id }

            AutoSelectorProfiles.recordPlanOutcome(
                selectorId = entity.id,
                poolIds = rerankedPool.map { it.id },
                buildIds = nextBuildIds,
            )

            // Only rebuild if the best built node set actually changed (e.g. a faster/working node outside
            // the original build was discovered, or the top nodes improved significantly).
            if (AutoSelectorPlanner.buildChanged(build.memberIds, nextBuildIds) &&
                nextBuildIds.isNotEmpty() &&
                hasSignificantBuildImprovement(rerankedPool, build.memberIds, nextBuildIds, effectiveBeanForRerank)
            ) {
                Logs.i("AutoSelectorRuntime: background ranking found better candidate set (${build.memberIds.size} -> ${nextBuildIds.size}), rebuilding")
                instance.service?.let { svc ->
                    triggerRebuild(svc, skipNextRanking = true)
                }
            }
        } catch (_: CancellationException) {
            // Normal shutdown or service reload.
        } catch (e: Exception) {
            Logs.w("AutoSelector background ranking error: ${e.readableMessage}")
        } finally {
            if (updates.isNotEmpty()) {
                runCatching { AutoSelectorProfiles.saveHealth(updates.values.toList()) }
            }
            rankingActive = false
            if (coroutineScopeActive(instance)) {
                val status = fetchMergedStatus(instance, build, withMembers = true)
                publishStatus(instance, status, force = true)
            }
        }
    }

    private fun hasSignificantBuildImprovement(
        rerankedPool: List<SelectorMember>,
        oldBuildIds: List<Long>,
        nextBuildIds: List<Long>,
        bean: io.nekohasekai.sagernet.fmt.internal.BalancerBean,
    ): Boolean {
        val byId = rerankedPool.associateBy { it.id }
        val oldSet = oldBuildIds.toSet()
        // If new members entered `build` that weren't built at all before, rebuild!
        if (nextBuildIds.any { it !in oldSet && (byId[it]?.latency ?: 0) > 0 }) {
            return true
        }
        // Or if the current core build has no working nodes yet while newly ranked nodes work!
        val currentCoreAlive = latestStatus.membersAlive
        if (currentCoreAlive == 0 && nextBuildIds.any { AutoSelectorPlanner.memberWorks(byId[it] ?: return@any false) }) {
            return true
        }
        // Or if the #1 best node changed meaningfully beyond tolerance.
        val oldBest = oldBuildIds.mapNotNull { byId[it] }.filter { AutoSelectorPlanner.memberWorks(it) }.minOfOrNull { it.latency } ?: Int.MAX_VALUE
        val newBest = nextBuildIds.mapNotNull { byId[it] }.filter { AutoSelectorPlanner.memberWorks(it) }.minOfOrNull { it.latency } ?: Int.MAX_VALUE
        val tolMs = bean.calculateToleranceMs().toInt().coerceAtLeast(30)
        return newBest < Int.MAX_VALUE && (oldBest == Int.MAX_VALUE || oldBest - newBest >= tolMs)
    }

    private suspend fun checkExhaustionRebuild(
        instance: ProxyInstance,
        build: AutoSelectorBuild,
        status: AutoSelectorStatus,
        nowElapsed: Long,
    ) {
        val networkAvailable = SagerNet.underlyingNetwork != null
        val isExhausted = status.running &&
            !status.rankingActive &&
            !status.suspended &&
            networkAvailable &&
            status.phase == "degraded" &&
            status.membersAlive == 0 &&
            status.membersProbed >= status.membersTotal &&
            status.membersTotal > 0

        if (!isExhausted) {
            exhaustedSinceElapsedMs = 0L
            if (status.membersAlive > 0) {
                exhaustBackoffMs = 60_000L
            }
            return
        }

        if (exhaustedSinceElapsedMs == 0L) {
            exhaustedSinceElapsedMs = nowElapsed
            return
        }

        if (nowElapsed - exhaustedSinceElapsedMs < 30_000L) return
        if (nowElapsed - lastExhaustRebuildElapsedMs < exhaustBackoffMs) return

        val (_, bean) = AutoSelectorProfiles.findAutoSelector(build.selectorId) ?: return
        val plan = AutoSelectorProfiles.buildPlan(bean, excludeProfileId = build.selectorId)
        val hasMoreCandidates = plan.pool.size > build.memberIds.size ||
            plan.build.map { it.id } != build.memberIds
        if (!hasMoreCandidates) return

        lastExhaustRebuildElapsedMs = nowElapsed
        exhaustBackoffMs = (exhaustBackoffMs * 2L).coerceAtMost(600_000L)
        Logs.w("AutoSelectorRuntime: running pool exhausted, triggering rebuild (next backoff ${exhaustBackoffMs / 1000}s)")

        val failedNowSec = System.currentTimeMillis() / 1000L
        val failedUpdates = build.memberIds.map { id ->
            AutoSelectorProfiles.HealthUpdate(id, 0, failedNowSec, "exhausted")
        }
        AutoSelectorProfiles.saveHealth(failedUpdates)
        instance.service?.let { svc ->
            triggerRebuild(svc, skipNextRanking = false)
        }
    }

    private fun onTrackedGroupChanged(groupId: Long) {
        val instance = activeInstance ?: return
        val build = activeBuild ?: return
        if (!instance.isInitialized()) return
        if (groupId !in build.trackedGroups) return

        scope.launch {
            val (entity, bean) = AutoSelectorProfiles.findAutoSelector(build.selectorId) ?: return@launch
            val newPlan = AutoSelectorProfiles.buildPlan(bean, excludeProfileId = entity.id)
            val newIds = newPlan.build.map { it.id }
            val anyRunningDeleted = build.memberIds.any { SagerDatabase.proxyDao.getById(it) == null }
            if (anyRunningDeleted || newIds.toSet() != build.memberIds.toSet()) {
                Logs.i("AutoSelectorRuntime: tracked group $groupId updated, rebuilding AutoSelector ${entity.id}")
                instance.service?.let { svc ->
                    triggerRebuild(svc, skipNextRanking = false)
                }
            }
        }
    }

    private fun triggerRebuild(service: BaseService.Interface, skipNextRanking: Boolean) {
        val build = activeBuild ?: return
        if (service.data.state != BaseService.State.Connected) return
        if (skipNextRanking) {
            skipNextRankingForSelectorId = build.selectorId
        }
        restart(service) {
            service.stopRunner(restart = true)
        }
    }

    private fun fetchMergedStatus(
        instance: ProxyInstance,
        build: AutoSelectorBuild,
        withMembers: Boolean,
    ): AutoSelectorStatus {
        val buildsByTag = mapOf(build.groupTag to build)
        val coreStatus = AutoSelectorCore.queryStatuses(instance, withMembers, buildsByTag).firstOrNull()
        val effective = coreStatus ?: AutoSelectorStatus(
            running = true,
            selectorId = build.selectorId,
            selectorName = build.selectorName,
            tag = build.groupTag,
            phase = if (rankingActive) "ranking" else "probing",
            balance = build.balance,
            membersTotal = build.members.size,
        )
        return effective.copy(
            running = true,
            selectorId = build.selectorId,
            selectorName = build.selectorName,
            rankingActive = rankingActive,
            rankingDone = rankingDone,
            rankingTotal = rankingTotal,
            exhausted = effective.phase == "degraded" && effective.membersAlive == 0 && !effective.suspended,
        )
    }

    private fun publishStatus(instance: ProxyInstance?, status: AutoSelectorStatus, force: Boolean) {
        val json = status.toJson(withMembers = true)
        if (!force && json == lastStatusJson) return
        lastStatusJson = json
        latestStatus = status
        val binder = instance?.service?.data?.binder ?: return
        runOnDefaultDispatcher {
            binder.broadcast { cb -> cb.cbAutoSelectorUpdate(json) }
        }
    }

    private suspend fun persistCoreHealth(status: AutoSelectorStatus) {
        if (status.members.isEmpty()) return
        val updates = ArrayList<AutoSelectorProfiles.HealthUpdate>()
        val nowSec = System.currentTimeMillis() / 1000L
        for (m in status.members) {
            if (m.profileId <= 0L || m.probes <= 0) continue
            val atSec = when {
                m.lastOkMs > 0L -> m.lastOkMs / 1000L
                m.lastProbeMs > 0L -> m.lastProbeMs / 1000L
                else -> nowSec
            }
            when (m.state) {
                "ok", "degraded" -> {
                    if (m.averageMs > 0) {
                        updates.add(AutoSelectorProfiles.HealthUpdate(m.profileId, m.averageMs, atSec, null))
                    }
                }
                "dead", "cooldown" -> {
                    updates.add(
                        AutoSelectorProfiles.HealthUpdate(
                            m.profileId,
                            0,
                            atSec,
                            m.lastError.ifBlank { "probe failed" },
                        )
                    )
                }
            }
        }
        if (updates.isNotEmpty()) {
            AutoSelectorProfiles.saveHealth(updates)
        }
    }

    private fun syncActiveLeafNotification(instance: ProxyInstance, leafId: Long) {
        val leafName = runCatching { SagerDatabase.proxyDao.getById(leafId)?.displayName() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        if (ActiveOutboundTracker.activeLeafProfileId == leafId &&
            ActiveOutboundTracker.activeLeafProfileName == leafName
        ) {
            return
        }
        ActiveOutboundTracker.updateActiveLeaf(leafId, leafName)
        runOnDefaultDispatcher {
            val svc = instance.service ?: return@runOnDefaultDispatcher
            val notif = svc.data.notification ?: return@runOnDefaultDispatcher
            val title = ActiveOutboundTracker.formatNotificationTitle(instance.profile)
            runCatching {
                notif.postNotificationTitle(title)
                notif.refreshLiveUpdate()
            }
        }
    }

    private fun coroutineScopeActive(instance: ProxyInstance): Boolean {
        return activeInstance === instance && instance.isInitialized()
    }
}
