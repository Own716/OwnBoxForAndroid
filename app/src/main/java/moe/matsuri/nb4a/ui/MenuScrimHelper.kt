package moe.matsuri.nb4a.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.motion.SmoothMenuPopup
import io.nekohasekai.sagernet.ui.motion.SmoothMotion
import io.nekohasekai.sagernet.ui.motion.SmoothMotion.attachSmoothPress

object MenuScrimHelper {

    const val TOOLBAR_TOKEN = "toolbar_menu"

    private var activeHandle: SmoothMenuPopup.Handle? = null

    fun dismissActive() {
        activeHandle?.dismiss()
        activeHandle = null
    }

    fun showPopupMenuWithScrim(
        popup: PopupMenu,
        activity: Activity?,
        onDismiss: (() -> Unit)? = null
    ) {
        (activity as? MainActivity)?.hideMenuScrim()
        dismissActive()

        if (SmoothMotion.isEnabled()) {
            val anchor = findFieldValue(popup, "mAnchor") as? View
            val listener = findFieldValue(popup, "mMenuItemClickListener") as? PopupMenu.OnMenuItemClickListener
            val ctx = activity ?: anchor?.context
            if (ctx != null && anchor != null && anchor.isAttachedToWindow) {
                val handle = SmoothMenuPopup.showFromMenu(
                    context = ctx,
                    anchor = anchor,
                    menu = popup.menu,
                    gravity = Gravity.END,
                    onItemSelected = { item -> listener?.onMenuItemClick(item) ?: false },
                    onDismiss = {
                        activeHandle = null
                        onDismiss?.invoke()
                    },
                )
                if (handle != null) {
                    activeHandle = handle
                    return
                }
            }
        }

        if (onDismiss != null) {
            popup.setOnDismissListener { onDismiss.invoke() }
        }
        popup.show()
        clearPopupElevation(popup)
    }

    private fun clearPopupElevation(popup: PopupMenu) {
        try {
            val helper = findFieldValue(popup, "mPopup") ?: return
            val getPopupMethod = helper.javaClass.getMethod("getPopup")
            val menuPopup = getPopupMethod.invoke(helper) ?: return
            val listPopup = findFieldValue(menuPopup, "mPopup")
            val popupWindow = when (listPopup) {
                is PopupWindow -> listPopup
                is ListPopupWindow -> findFieldValue(listPopup, "mPopup") as? PopupWindow
                else -> listPopup?.let { findFieldValue(it, "mPopup") as? PopupWindow }
            }
            popupWindow?.elevation = 0f
        } catch (_: Throwable) {}
    }

    private fun findFieldValue(target: Any, name: String): Any? {
        val f = findFieldInHierarchy(target.javaClass, name) ?: return null
        f.isAccessible = true
        return f.get(target)
    }

    private fun findFieldInHierarchy(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    fun hookToolbar(toolbar: Toolbar, activity: MainActivity) {
        toolbar.post {
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is ActionMenuView) {
                    hookActionMenuView(toolbar, child, activity)
                }
            }
        }
    }

    fun hookActionMenuView(toolbar: Toolbar, amv: ActionMenuView, activity: MainActivity) {
        for (i in 0 until amv.childCount) {
            val btn = amv.getChildAt(i)
            if (btn is SearchView) continue
            btn.attachSmoothPress(0.90f)

            val id = btn.id
            if ((id == R.id.action_misc || id == R.id.action_add) &&
                btn.getTag(R.id.motion_tag_toolbar_hooked) != true
            ) {
                btn.setTag(R.id.motion_tag_toolbar_hooked, true)
                btn.setOnClickListener { view ->
                    if (!SmoothMotion.isEnabled()) {
                        (view as? View.OnClickListener)?.onClick(view)
                        return@setOnClickListener
                    }
                    val sub = toolbar.menu.findItem(id)?.subMenu
                    if (sub == null) {
                        (view as? View.OnClickListener)?.onClick(view)
                        return@setOnClickListener
                    }
                    dismissActive()
                    val handle = SmoothMenuPopup.showFromMenu(
                        context = activity,
                        anchor = view,
                        menu = sub,
                        gravity = Gravity.END,
                        onDismiss = { activeHandle = null },
                    )
                    if (handle != null) {
                        activeHandle = handle
                    } else {
                        (view as? View.OnClickListener)?.onClick(view)
                    }
                }
            }
        }
    }
}
