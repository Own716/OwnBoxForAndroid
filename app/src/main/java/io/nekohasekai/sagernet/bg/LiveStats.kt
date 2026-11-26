package io.nekohasekai.sagernet.bg

import android.os.Process
import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.LiveStatsBatch
import libcore.BoxInstance
import kotlin.random.Random

/** Fixed-capacity ring of points: one timestamp and [fields] values per point; index 0 is the oldest. */
class LiveSeries(val capacity: Int, val fields: Int) {

    private val times = LongArray(capacity)
    private val values = FloatArray(capacity * fields)
    private var head = 0

    var size = 0
        private set

    private fun slot(i: Int): Int = (head + i).let { if (it >= capacity) it - capacity else it }

    fun time(i: Int): Long = times[slot(i)]

    fun value(field: Int, i: Int): Float = values[slot(i) * fields + field]

    val latestTime: Long get() = if (size == 0) 0L else time(size - 1)

    fun push(t: Long, source: FloatArray, offset: Int = 0) {
        val s: Int
        if (size == capacity) {
            s = head
            head = slot(1)
        } else {
            s = slot(size)
            size++
        }
        times[s] = t
        source.copyInto(values, s * fields, offset, offset + fields)
    }

    /** The first point at or after [t]. */
    fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (time(mid) < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun clear() {
        head = 0
        size = 0
    }
}

/**
 * Keeps the last [CAPACITY] ticks of speed and core process metrics inside the :bg service process,
 * so opening the chart shows history immediately without requiring Clash API.
 */
object LiveStatsRecorder {

    const val CAPACITY = 600

    /** A tick later than this follows a background/doze pause: restart baseline instead of plotting an average. */
    private const val GAP_MS = 2500L

    /** A start within this of an in-place restart is recorded as a switch marker. */
    private const val SWITCH_MS = 30_000L

    /** Keep markers slightly longer than the 10m window. */
    private const val MARKER_AGE_MS = 11 * 60_000L
    private const val MAX_MARKERS = 100

    private class Marker(val t: Long, val kind: Int, val text: String)

    private val session = Random.nextLong(1L, Long.MAX_VALUE)
    private val series = LiveSeries(CAPACITY, LiveStatsBatch.FIELDS)
    private val point = FloatArray(LiveStatsBatch.FIELDS)
    private val markers = ArrayList<Marker>()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var serial = 0L
    private var lastTick = 0L
    private var lastCpuMs = 0L
    private var restartedAt = 0L

    /** Record one tick of the running core; rates are in bytes/sec. */
    @Synchronized
    fun record(proxyDown: Long, proxyUp: Long, directDown: Long, directUp: Long, box: BoxInstance?) {
        val now = SystemClock.elapsedRealtime()
        val cpuMs = Process.getElapsedCpuTime()
        val wall = now - lastTick
        val gap = lastTick == 0L || wall > GAP_MS || wall <= 0L
        val lastCpu = lastCpuMs
        lastTick = now
        lastCpuMs = cpuMs
        if (gap) return

        val memBytes = box?.let { runCatching { it.getCoreMemoryBytes() }.getOrNull() } ?: 0L
        val activeConns = box?.let { runCatching { it.getActiveConnectionCount() }.getOrNull() } ?: -1

        point[LiveStatsBatch.PROXY_DOWN] = proxyDown.toFloat()
        point[LiveStatsBatch.PROXY_UP] = proxyUp.toFloat()
        point[LiveStatsBatch.DIRECT_DOWN] = directDown.toFloat()
        point[LiveStatsBatch.DIRECT_UP] = directUp.toFloat()
        point[LiveStatsBatch.CPU] = ((cpuMs - lastCpu).coerceAtLeast(0L) * 100f / wall / cores).coerceIn(0f, 100f)
        point[LiveStatsBatch.MEMORY] = if (memBytes > 0L) memBytes.toFloat() else Float.NaN
        point[LiveStatsBatch.CONNECTIONS] = if (activeConns >= 0) activeConns.toFloat() else Float.NaN
        series.push(now, point)
        serial++
    }

    @Synchronized
    fun started(name: String) {
        val now = SystemClock.elapsedRealtime()
        settleRestart(now)
        val switched = restartedAt > 0L
        restartedAt = 0L
        addMarker(now, if (switched) LiveStatsBatch.MARKER_SWITCHED else LiveStatsBatch.MARKER_STARTED, name)
    }

    /** [restart]: true when replacing core in place (switch or reload). */
    @Synchronized
    fun stopped(restart: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (restartedAt > 0L) addMarker(restartedAt, LiveStatsBatch.MARKER_STOPPED, "")
        restartedAt = 0L
        if (restart) restartedAt = now else addMarker(now, LiveStatsBatch.MARKER_STOPPED, "")
    }

    @Synchronized
    fun batch(session: Long, after: Long): LiveStatsBatch {
        val now = SystemClock.elapsedRealtime()
        settleRestart(now)
        pruneMarkers(now)
        val since = if (session == this.session) after else 0L
        val count = (serial - since).coerceIn(0L, series.size.toLong()).toInt()
        val first = series.size - count
        val fields = LiveStatsBatch.FIELDS
        val values = FloatArray(count * fields)
        for (i in 0 until count) {
            for (f in 0 until fields) values[i * fields + f] = series.value(f, first + i)
        }
        return LiveStatsBatch(
            session = this.session,
            serial = serial,
            times = LongArray(count) { series.time(first + it) },
            values = values,
            markerTimes = LongArray(markers.size) { markers[it].t },
            markerKinds = IntArray(markers.size) { markers[it].kind },
            markerTexts = markers.map { it.text },
        )
    }

    private fun settleRestart(now: Long) {
        if (restartedAt > 0L && now - restartedAt > SWITCH_MS) {
            addMarker(restartedAt, LiveStatsBatch.MARKER_STOPPED, "")
            restartedAt = 0L
        }
    }

    private fun pruneMarkers(now: Long) {
        while (markers.isNotEmpty() && now - markers[0].t > MARKER_AGE_MS) {
            if (markers[0].kind == LiveStatsBatch.MARKER_STOPPED) {
                val end = markers.firstOrNull { it.kind != LiveStatsBatch.MARKER_STOPPED } ?: return
                if (now - end.t <= MARKER_AGE_MS) return
            }
            markers.removeAt(0)
        }
    }

    private fun addMarker(t: Long, kind: Int, text: String) {
        markers.add(Marker(t, kind, text))
        if (markers.size > MAX_MARKERS) markers.removeAt(0)
    }
}
