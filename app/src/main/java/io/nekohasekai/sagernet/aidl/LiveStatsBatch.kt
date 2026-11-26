package io.nekohasekai.sagernet.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The live graphs' points after a serial (bg.LiveStatsRecorder): [times] in elapsedRealtime ms,
 * [values] [FIELDS] per point (NaN where a point has no value), and every profile event still in the window.
 */
@Parcelize
class LiveStatsBatch(
    val session: Long = 0L,
    val serial: Long = 0L,
    val times: LongArray = LongArray(0),
    val values: FloatArray = FloatArray(0),
    val markerTimes: LongArray = LongArray(0),
    val markerKinds: IntArray = IntArray(0),
    val markerTexts: List<String> = emptyList(),
) : Parcelable {

    companion object {
        /** Bytes per second. */
        const val PROXY_DOWN = 0
        const val PROXY_UP = 1
        const val DIRECT_DOWN = 2
        const val DIRECT_UP = 3

        /** Core service process metrics: CPU %, core heap/stack bytes, active non-DNS connections. */
        const val CPU = 4
        const val MEMORY = 5
        const val CONNECTIONS = 6
        const val FIELDS = 7

        const val MARKER_STARTED = 0
        const val MARKER_SWITCHED = 1
        const val MARKER_STOPPED = 2
    }
}
