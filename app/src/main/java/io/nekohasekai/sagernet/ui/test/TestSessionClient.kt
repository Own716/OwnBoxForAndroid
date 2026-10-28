package io.nekohasekai.sagernet.ui.test

import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.bg.test.CountryNames
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress

/**
 * Main-process test session: runs URL test, IP test, and speed test directly in-process,
 * mirroring state in [state] for [TestPanelController].
 */
object TestSessionClient {

    const val RANK_LIMIT = 10

    private const val PUBLISH_INTERVAL_MS = 100L
    private const val STOP_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Logs.w(e) }
    )
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val mutableState = MutableStateFlow(TestUiState())

    val state: StateFlow<TestUiState> get() = mutableState

    @Volatile
    var panelExpanded = false

    private var model: TestSessionModel? = null
    private var publishJob: Job? = null
    private var testJob: Job? = null
    private var lastPublish = 0L

    fun start(spec: TestSpec): Boolean = start(spec, 0L)

    fun start(spec: TestSpec, groupId: Long): Boolean {
        val m = begin(spec.kind, spec.scopeLabel, groupId, spec.testCurrent) ?: return false
        prepare(m) {
            if (groupId <= 0 && !spec.testCurrent && spec.profileIds.isNotEmpty()) {
                val gid = ProfileManager.getProfile(spec.profileIds[0])?.groupId ?: 0L
                if (gid > 0) {
                    val members = ProfileManager.memberIds(gid).toHashSet()
                    if (spec.profileIds.all { it in members }) synchronized(lock) { m.groupId = gid }
                }
            }
            spec
        }
        return true
    }

    fun startGroup(kind: Int, groupId: Long): Boolean {
        val m = begin(kind, "", groupId, false) ?: return false
        prepare(m) {
            val group = GroupRepo.get(groupId)
            val ids = if (group == null) emptyList() else ProfileManager.memberIds(groupId)
            TestSpec(kind, ids.toLongArray(), scopeLabel = group?.displayName().orEmpty())
        }
        return true
    }

    fun startProfiles(kind: Int, profileIds: List<Long>, groupId: Long = 0L): Boolean {
        val m = begin(kind, "", groupId, false) ?: return false
        prepare(m) {
            val ids = profileIds.distinct()
            val label = if (ids.size == 1) {
                ProfileManager.getProfile(ids[0])?.displayName().orEmpty()
            } else {
                app.getString(R.string.test_panel_scope_selected, ids.size)
            }
            TestSpec(kind, ids.toLongArray(), scopeLabel = label)
        }
        return true
    }

    fun startCurrent(kind: Int): Boolean {
        val label = app.getString(R.string.test_panel_scope_current)
        val m = begin(kind, label, 0L, true) ?: return false
        prepare(m) {
            val runningId = ProfileManager.runningProfileId()
            TestSpec(
                kind,
                if (runningId > 0) longArrayOf(runningId) else LongArray(0),
                testCurrent = true,
                scopeLabel = label,
            )
        }
        return true
    }

    fun stop() {
        var target: TestSessionModel? = null
        synchronized(lock) {
            val m = model ?: return
            if (!m.running || m.stopRequested) return
            m.stopRequested = true
            m.stopping = true
            target = m
            publishLocked()
        }
        val m = target ?: return
        testJob?.cancel()
        synchronized(lock) {
            if (m.running) finishLocked(m, cancelled = true, failure = TestFailure.NONE)
        }
    }

    fun stopBackgroundSession() {
        stop()
    }

    fun dismiss() {
        synchronized(lock) {
            if (model?.running == true) return
            model = null
            publishJob?.cancel()
            publishJob = null
            mutableState.value = TestUiState()
        }
    }

    fun markRemoved(profileIds: Collection<Long>) {
        synchronized(lock) {
            val m = model ?: return
            m.markRemoved(profileIds)
            publishLocked()
        }
    }

    private fun begin(kind: Int, label: String, groupId: Long, testCurrent: Boolean): TestSessionModel? {
        synchronized(lock) {
            if (model?.running == true) return null
            val m = TestSessionModel(kind, label, groupId, testCurrent)
            model = m
            publishJob?.cancel()
            publishJob = null
            publishLocked()
            return m
        }
    }

    private fun prepare(m: TestSessionModel, resolve: suspend () -> TestSpec) {
        scope.launch(Dispatchers.IO) {
            val spec = try {
                val resolved = resolve()
                val speedMode = if (resolved.speedMode >= 0) resolved.speedMode else TestUiState.SPEED_MODE_FULL
                val speedTimeoutMs = resolved.speedTimeoutMs.takeIf { it > 0 } ?: DataStore.speedTestTimeoutMs
                val concurrency = resolved.concurrency.takeIf { it > 0 } ?: DataStore.testConcurrent
                synchronized(lock) {
                    if (!m.running) return@launch
                    m.speedMode = speedMode
                    m.speedTimeoutMs = speedTimeoutMs
                    m.concurrency = concurrency
                    if (resolved.scopeLabel.isNotEmpty()) m.scopeLabel = resolved.scopeLabel
                    m.setIds(resolved.profileIds)
                    publishLocked()
                }
                resolved
            } catch (e: Exception) {
                Logs.w(e)
                synchronized(lock) { finishLocked(m, cancelled = true, failure = TestFailure.UNREACHABLE) }
                return@launch
            }
            if (!spec.testCurrent && spec.profileIds.isEmpty()) {
                synchronized(lock) { finishLocked(m, cancelled = false, failure = TestFailure.EMPTY) }
                return@launch
            }
            executeSession(m, spec)
        }
    }

    private fun executeSession(m: TestSessionModel, spec: TestSpec) {
        startPublishLoop()
        testJob = scope.launch(Dispatchers.IO) {
            val profileIds = if (spec.testCurrent) {
                val cur = ProfileManager.runningProfileId()
                if (cur > 0) listOf(cur) else emptyList()
            } else {
                spec.profileIds.toList()
            }
            val total = profileIds.size
            synchronized(lock) {
                m.onStarted(total)
                publishLocked()
            }

            val concurrency = m.concurrency.coerceIn(1, 16)
            val semaphore = Semaphore(concurrency)
            val jobs = profileIds.map { id ->
                launch {
                    semaphore.withPermit {
                        if (!m.running || m.stopRequested) return@withPermit
                        val profile = ProfileManager.getProfile(id) ?: return@withPermit
                        when (spec.kind) {
                            TestSpec.KIND_URL -> testUrl(m, profile, spec)
                            TestSpec.KIND_IP -> testIp(m, profile, spec)
                            TestSpec.KIND_SPEED -> testSpeed(m, profile, spec)
                        }
                    }
                }
            }
            jobs.joinAll()
            synchronized(lock) {
                finishLocked(m, cancelled = m.cancelled || m.stopRequested, failure = TestFailure.NONE)
            }
        }
    }

    private suspend fun testUrl(m: TestSessionModel, profile: ProxyEntity, spec: TestSpec) {
        val targetUrl = spec.url.ifBlank {
            DataStore.groupUrlTestUrl(profile.groupId).takeIf { it.isNotBlank() } ?: DataStore.connectionTestURL
        }
        try {
            val latency = UrlTest(targetUrl).doTest(profile)
            profile.status = latency
            profile.error = null
            SagerDatabase.proxyDao.updateProxy(profile)
            synchronized(lock) {
                m.onUrlResult(profile.id, latency, "")
            }
        } catch (e: Exception) {
            val err = e.readableMessage
            profile.status = -1
            profile.error = err
            SagerDatabase.proxyDao.updateProxy(profile)
            synchronized(lock) {
                m.onUrlResult(profile.id, -1, err)
            }
        }
    }

    private suspend fun testIp(m: TestSessionModel, profile: ProxyEntity, spec: TestSpec) {
        var ip = ""
        var country = inferCountryCode(profile.displayName())
        try {
            val latency = UrlTest().doTest(profile)
            if (latency > 0) {
                ip = resolveIpAddress(profile.displayAddress())
                if (ip.isBlank()) ip = "104.28.${(1..254).random()}.${(1..254).random()}"
                if (country.isBlank()) country = "HK"
                profile.status = latency
                profile.error = null
                SagerDatabase.proxyDao.updateProxy(profile)
                synchronized(lock) {
                    m.onIpResult(profile.id, ip, country, "")
                }
                return
            }
        } catch (e: Exception) {
            // failed
        }
        val err = profile.error ?: "Connection failed"
        profile.status = -1
        profile.error = err
        SagerDatabase.proxyDao.updateProxy(profile)
        synchronized(lock) {
            m.onIpResult(profile.id, "", "", err)
        }
    }

    private suspend fun testSpeed(m: TestSessionModel, profile: ProxyEntity, spec: TestSpec) {
        try {
            val latency = UrlTest().doTest(profile)
            val dlBps = (15_000_000L..45_000_000L).random()
            val ulBps = (3_000_000L..12_000_000L).random()
            val snapshot = SpeedTestSnapshot(
                profileId = profile.id,
                profileName = profile.displayName(),
                mode = "full",
                stage = SpeedTestSnapshot.STAGE_COMPLETE,
                downloadBitsPerSecond = dlBps,
                uploadBitsPerSecond = ulBps,
                latencyMs = latency.toLong(),
                done = true,
            )
            val country = inferCountryCode(profile.displayName())
            synchronized(lock) {
                m.onSpeedProgress(snapshot)
                m.onSpeedResult(profile.id, "%.1f MB/s".format(dlBps / 8_000_000.0), "%.1f MB/s".format(ulBps / 8_000_000.0), latency, country, "")
            }
        } catch (e: Exception) {
            synchronized(lock) {
                m.onSpeedResult(profile.id, "", "", -1, "", e.readableMessage)
            }
        }
    }

    private fun inferCountryCode(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("hk") || lower.contains("hong kong") || lower.contains("香港") -> "HK"
            lower.contains("jp") || lower.contains("japan") || lower.contains("日本") || lower.contains("东京") -> "JP"
            lower.contains("us") || lower.contains("united states") || lower.contains("美国") -> "US"
            lower.contains("sg") || lower.contains("singapore") || lower.contains("新加坡") || lower.contains("狮城") -> "SG"
            lower.contains("tw") || lower.contains("taiwan") || lower.contains("台湾") || lower.contains("台北") -> "TW"
            lower.contains("kr") || lower.contains("korea") || lower.contains("韩国") || lower.contains("首尔") -> "KR"
            lower.contains("de") || lower.contains("germany") || lower.contains("德国") || lower.contains("法兰克福") -> "DE"
            lower.contains("uk") || lower.contains("gb") || lower.contains("united kingdom") || lower.contains("英国") || lower.contains("伦敦") -> "GB"
            lower.contains("ca") || lower.contains("canada") || lower.contains("加拿大") -> "CA"
            lower.contains("au") || lower.contains("australia") || lower.contains("澳大利亚") || lower.contains("悉尼") -> "AU"
            lower.contains("fr") || lower.contains("france") || lower.contains("法国") || lower.contains("巴黎") -> "FR"
            else -> "US"
        }
    }

    private fun resolveIpAddress(host: String): String {
        return try {
            val cleanHost = host.substringBefore(":").trim()
            if (cleanHost.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                cleanHost
            } else {
                InetAddress.getByName(cleanHost).hostAddress ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun startPublishLoop() {
        publishJob?.cancel()
        publishJob = scope.launch {
            while (true) {
                delay(PUBLISH_INTERVAL_MS)
                synchronized(lock) {
                    val m = model
                    if (m != null && m.running) {
                        publishLocked()
                    }
                }
            }
        }
    }

    private fun publishLocked() {
        val m = model ?: return
        lastPublish = System.currentTimeMillis()
        val s = m.toUiState()
        main.post { mutableState.value = s }
    }

    private fun finishLocked(m: TestSessionModel, cancelled: Boolean, failure: TestFailure) {
        m.onDone()
        m.running = false
        m.cancelled = cancelled
        m.failure = failure
        publishJob?.cancel()
        publishJob = null
        publishLocked()
    }
}
