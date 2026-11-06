package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSpinner

class ReselectableSpinner @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatSpinner(context, attrs) {

    var onPopupClosed: (() -> Unit)? = null

    init {
        try {
            val popupField = AppCompatSpinner::class.java.getDeclaredField("mPopup")
            popupField.isAccessible = true
            val popup = popupField.get(this)
            val setElevationMethod = popup?.javaClass?.getMethod("setElevation", Float::class.javaPrimitiveType)
            setElevationMethod?.invoke(popup, 12f * context.resources.displayMetrics.density)
        } catch (_: Throwable) {
        }
    }


    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) onPopupClosed?.invoke()
    }

    override fun setSelection(position: Int) {
        val reselected = position == selectedItemPosition
        super.setSelection(position)
        if (reselected) notifyReselected(position)
    }

    override fun setSelection(position: Int, animate: Boolean) {
        val reselected = position == selectedItemPosition
        super.setSelection(position, animate)
        if (reselected) notifyReselected(position)
    }

    private fun notifyReselected(position: Int) {
        if (position < 0) return
        onItemSelectedListener?.onItemSelected(
            this, selectedView, position, adapter?.getItemId(position) ?: 0L
        )
    }
}
