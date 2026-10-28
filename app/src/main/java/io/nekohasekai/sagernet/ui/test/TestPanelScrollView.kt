package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import kotlin.math.min

/** The expanded panel body: wraps its content up to [maxHeight] pixels, then scrolls. */
class TestPanelScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    var maxHeight = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var spec = heightMeasureSpec
        if (maxHeight > 0) {
            val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maxHeight
            } else {
                min(MeasureSpec.getSize(heightMeasureSpec), maxHeight)
            }
            spec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
