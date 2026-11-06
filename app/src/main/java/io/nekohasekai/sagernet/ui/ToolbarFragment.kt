package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.utils.UiScaleManager

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
            val customTitle = view.findViewById<TextView>(R.id.toolbar_title_view)
            val titleFactor = UiScaleManager.toolbarTitleFactor()
            val iconFactor = UiScaleManager.toolbarIconFactor()
            if (customTitle != null) {
                toolbar.contentInsetStartWithNavigation = 0
                toolbar.setContentInsetsRelative(0, dp2px(4))
                toolbar.title = null
                toolbar.setBackgroundColor(Color.TRANSPARENT)
                customTitle.text = getString(R.string.app_name)
                customTitle.visibility = View.VISIBLE
                customTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17.5f * titleFactor)
                for (i in 0 until toolbar.childCount) {
                    val child = toolbar.getChildAt(i)
                    if (child is android.widget.ImageButton) {
                        child.minimumWidth = (dp2px(40) * iconFactor).toInt().coerceAtLeast(dp2px(28))
                        child.setPadding(dp2px(8), 0, dp2px(8), 0)
                        child.scaleX = 0.9f * iconFactor
                        child.scaleY = 0.9f * iconFactor
                    }
                }
            } else {
                toolbar.contentInsetStartWithNavigation = dp2px(48)
                toolbar.setContentInsetsRelative(dp2px(48), dp2px(4))
            }
            val primaryTextColor = requireContext().getColorAttr(android.R.attr.textColorPrimary)
            toolbar.setTitleTextColor(primaryTextColor)
            customTitle?.setTextColor(primaryTextColor)
            toolbar.navigationIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, primaryTextColor)
                toolbar.navigationIcon = tinted
            }
            val overflow = toolbar.overflowIcon ?: androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_more_vert_24)
            overflow?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, primaryTextColor)
                toolbar.overflowIcon = tinted
            }
            (activity as? MainActivity)?.let { main ->
                moe.matsuri.nb4a.ui.MenuScrimHelper.hookToolbar(toolbar, main)
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

    override fun onDestroyView() {
        (activity as? MainActivity)?.hideMenuScrim()
        super.onDestroyView()
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
