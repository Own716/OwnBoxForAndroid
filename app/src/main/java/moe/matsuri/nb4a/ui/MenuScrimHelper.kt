package moe.matsuri.nb4a.ui

import android.app.Activity
import android.widget.PopupWindow
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.ui.MainActivity

object MenuScrimHelper {

    const val TOOLBAR_TOKEN = "toolbar_menu"

    fun showPopupMenuWithScrim(
        popup: PopupMenu,
        activity: Activity?,
        onDismiss: (() -> Unit)? = null
    ) {
        (activity as? MainActivity)?.hideMenuScrim()
        if (onDismiss != null) {
            popup.setOnDismissListener { onDismiss.invoke() }
        }
        popup.show()
        clearPopupElevation(popup)
    }

    private fun clearPopupElevation(popup: PopupMenu) {
        try {
            val helperField = PopupMenu::class.java.getDeclaredField("mPopup")
            helperField.isAccessible = true
            val helper = helperField.get(popup) ?: return
            val getPopupMethod = helper.javaClass.getMethod("getPopup")
            val menuPopup = getPopupMethod.invoke(helper) ?: return
            val listPopup = findFieldInHierarchy(menuPopup.javaClass, "mPopup")?.let { f ->
                f.isAccessible = true
                f.get(menuPopup)
            }
            val popupWindow = when (listPopup) {
                is PopupWindow -> listPopup
                is ListPopupWindow -> findFieldInHierarchy(listPopup.javaClass, "mPopup")?.let { f ->
                    f.isAccessible = true
                    f.get(listPopup) as? PopupWindow
                }
                else -> listPopup?.let { lp ->
                    findFieldInHierarchy(lp.javaClass, "mPopup")?.let { f ->
                        f.isAccessible = true
                        f.get(lp) as? PopupWindow
                    }
                }
            }
            popupWindow?.elevation = 0f
        } catch (_: Throwable) {}
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
    fun hookToolbar(toolbar: Toolbar, activity: MainActivity) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun hookActionMenuView(toolbar: Toolbar, amv: ActionMenuView, activity: MainActivity) = Unit
}
