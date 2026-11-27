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
import java.util.WeakHashMap

object MenuScrimHelper {

    const val TOOLBAR_TOKEN = "toolbar_menu"

    private var activeHandle: SmoothMenuPopup.Handle? = null
    private val toolbarListeners = WeakHashMap<Toolbar, Toolbar.OnMenuItemClickListener>()

    fun dismissActive() {
        activeHandle?.dismiss()
        activeHandle = null
    }

    fun showPopupMenuWithScrim(
        popup: PopupMenu,
        activity: Activity?,
        anchor: View? = null,
        gravity: Int = Gravity.END,
        onItemSelected: PopupMenu.OnMenuItemClickListener? = null,
        onDismiss: (() -> Unit)? = null,
    ) {
        (activity as? MainActivity)?.hideMenuScrim()
        dismissActive()

        if (SmoothMotion.isEnabled() && anchor != null && onItemSelected != null && anchor.isAttachedToWindow) {
            val ctx = activity ?: anchor.context
            var handleRef: SmoothMenuPopup.Handle? = null
            val handle = SmoothMenuPopup.showFromMenu(
                context = ctx,
                anchor = anchor,
                menu = popup.menu,
                gravity = gravity,
                onItemSelected = { item -> onItemSelected.onMenuItemClick(item) },
                preferDirectCallback = true,
                onDismiss = {
                    if (activeHandle === handleRef) {
                        activeHandle = null
                    }
                    onDismiss?.invoke()
                },
            )
            if (handle != null) {
                handleRef = handle
                activeHandle = handle
                return
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
    fun hookToolbar(
        toolbar: Toolbar,
        activity: MainActivity,
        onMenuItemClick: Toolbar.OnMenuItemClickListener? = null,
    ) {
        if (onMenuItemClick != null) {
            toolbarListeners[toolbar] = onMenuItemClick
        }
        toolbar.post {
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i)
                if (child is ActionMenuView) {
                    hookActionMenuView(toolbar, child, activity, onMenuItemClick)
                }
            }
        }
    }

    fun hookActionMenuView(
        toolbar: Toolbar,
        amv: ActionMenuView,
        activity: MainActivity,
        onMenuItemClick: Toolbar.OnMenuItemClickListener? = null,
    ) {
        if (onMenuItemClick != null) {
            toolbarListeners[toolbar] = onMenuItemClick
        }
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
                    var handleRef: SmoothMenuPopup.Handle? = null
                    val handle = SmoothMenuPopup.showFromMenu(
                        context = activity,
                        anchor = view,
                        menu = sub,
                        gravity = Gravity.END,
                        onItemSelected = { item ->
                            toolbarListeners[toolbar]?.onMenuItemClick(item) ?: false
                        },
                        preferDirectCallback = false,
                        onDismiss = {
                            if (activeHandle === handleRef) {
                                activeHandle = null
                            }
                        },
                    )
                    if (handle != null) {
                        handleRef = handle
                        activeHandle = handle
                    } else {
                        (view as? View.OnClickListener)?.onClick(view)
                    }
                }
            }
        }
    }
}
