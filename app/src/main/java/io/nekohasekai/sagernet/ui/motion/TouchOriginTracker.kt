package io.nekohasekai.sagernet.ui.motion

import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Records the latest touch coordinate on screen so popups and dialogs
 * can expand cleanly from where the user tapped.
 */
object TouchOriginTracker {

    private const val MAX_ORIGIN_AGE_MS = 1500L

    @Volatile
    private var lastX: Float = Float.NaN

    @Volatile
    private var lastY: Float = Float.NaN

    @Volatile
    private var lastTimestamp: Long = 0L

    fun recordTouchEvent(event: MotionEvent?) {
        if (event == null) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP -> {
                lastX = event.rawX
                lastY = event.rawY
                lastTimestamp = SystemClock.uptimeMillis()
            }
        }
    }

    fun capture(view: View?) {
        if (view == null || !view.isAttachedToWindow) return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = loc[0] + view.width * 0.5f
        val cy = loc[1] + view.height * 0.5f
        if (cx > 0f || cy > 0f) {
            lastX = cx
            lastY = cy
            lastTimestamp = SystemClock.uptimeMillis()
        }
    }

    fun peekRecentPoint(): PointF? {
        val age = SystemClock.uptimeMillis() - lastTimestamp
        if (age > MAX_ORIGIN_AGE_MS || !lastX.isFinite() || !lastY.isFinite()) {
            return null
        }
        return PointF(lastX, lastY)
    }
}
