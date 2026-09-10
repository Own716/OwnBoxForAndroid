package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener {
            (activity as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        if (Theme.isWhiteTheme()) {
            toolbar.setBackgroundColor(Color.WHITE)
            toolbar.setTitleTextColor(Color.parseColor("#212121"))
            toolbar.navigationIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, Color.parseColor("#212121"))
                toolbar.navigationIcon = tinted
            }
            toolbar.overflowIcon?.let {
                val tinted = it.mutate()
                DrawableCompat.setTint(tinted, Color.parseColor("#212121"))
                toolbar.overflowIcon = tinted
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
