package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar
    val isToolbarInitialized: Boolean get() = ::toolbar.isInitialized

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tb = view.findViewById<Toolbar?>(R.id.toolbar)
        if (tb != null) {
            toolbar = tb
            toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
            toolbar.setNavigationOnClickListener {
                (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
            }
            val primaryTextColor = requireContext().getColorAttr(android.R.attr.textColorPrimary)
            toolbar.setTitleTextColor(primaryTextColor)
            toolbar.navigationIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, primaryTextColor)
                toolbar.navigationIcon = tinted
            }
            toolbar.overflowIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, primaryTextColor)
                toolbar.overflowIcon = tinted
            }
        }
        val appbar = view.findViewById<AppBarLayout?>(R.id.appbar)
        if (appbar != null) {
            ViewCompat.setOnApplyWindowInsetsListener(appbar) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(top = bars.top)
                insets
            }
            ViewCompat.requestApplyInsets(appbar)
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
