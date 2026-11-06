package moe.matsuri.nb4a.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.view.menu.MenuPresenter
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children
import io.nekohasekai.sagernet.ui.MainActivity

object MenuScrimHelper {

    const val TOOLBAR_TOKEN = "toolbar_menu"

    fun showPopupMenuWithScrim(
        popup: PopupMenu,
        activity: Activity?,
        onDismiss: (() -> Unit)? = null
    ) {
        val main = activity as? MainActivity
        if (main != null) {
            val token = "popup_${System.identityHashCode(popup)}"
            main.showMenuScrim(token) {
                try {
                    popup.dismiss()
                } catch (_: Throwable) {}
            }
            popup.setOnDismissListener {
                onDismiss?.invoke()
                main.hideMenuScrim(token)
            }
        } else if (onDismiss != null) {
            popup.setOnDismissListener { onDismiss.invoke() }
        }
        popup.show()
    }

    fun hookToolbar(toolbar: Toolbar, activity: MainActivity) {
        toolbar.post {
            val amv = toolbar.children.filterIsInstance<ActionMenuView>().firstOrNull() ?: return@post
            hookActionMenuView(toolbar, amv, activity)
        }
    }

    fun hookActionMenuView(toolbar: Toolbar, amv: ActionMenuView, activity: MainActivity) {
        wrapPresenterCallback(toolbar, amv, activity)

        val touchListener = View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                scheduleToolbarCheck(toolbar, amv, activity)
            }
            false
        }

        amv.setOnTouchListener(touchListener)
        for (i in 0 until amv.childCount) {
            amv.getChildAt(i).setOnTouchListener(touchListener)
        }
    }

    fun wrapPresenterCallback(toolbar: Toolbar, amv: ActionMenuView, activity: MainActivity) {
        try {
            val presenterField = ActionMenuView::class.java.getDeclaredField("mPresenter")
            presenterField.isAccessible = true
            val presenter = presenterField.get(amv) as? MenuPresenter ?: return

            val callbackField = try {
                presenter.javaClass.getDeclaredField("mCallback")
            } catch (_: Throwable) {
                presenter.javaClass.superclass?.getDeclaredField("mCallback")
            } ?: return
            callbackField.isAccessible = true
            val originalCallback = callbackField.get(presenter) as? MenuPresenter.Callback

            if (originalCallback is ScrimPresenterCallback) return

            val wrapped = ScrimPresenterCallback(toolbar, amv, activity, originalCallback)
            presenter.setCallback(wrapped)
        } catch (_: Throwable) {}
    }

    fun scheduleToolbarCheck(toolbar: Toolbar, amv: ActionMenuView, activity: MainActivity) {
        toolbar.post {
            checkAndHookActiveToolbarPopups(toolbar, amv, activity)
        }
        toolbar.postDelayed({
            checkAndHookActiveToolbarPopups(toolbar, amv, activity)
        }, 60)
        toolbar.postDelayed({
            checkAndHookActiveToolbarPopups(toolbar, amv, activity)
        }, 150)
    }

    fun isAnyToolbarMenuShowing(amv: ActionMenuView, toolbar: Toolbar): Boolean {
        if (toolbar.isOverflowMenuShowing) return true
        try {
            val presenterField = ActionMenuView::class.java.getDeclaredField("mPresenter")
            presenterField.isAccessible = true
            val presenter = presenterField.get(amv) ?: return false

            try {
                val overflowField = presenter.javaClass.getDeclaredField("mOverflowPopup")
                overflowField.isAccessible = true
                val overflowPopup = overflowField.get(presenter) as? MenuPopupHelper
                if (overflowPopup != null && overflowPopup.isShowing) return true
            } catch (_: Throwable) {}

            try {
                val actionButtonPopupField = presenter.javaClass.getDeclaredField("mActionButtonPopup")
                actionButtonPopupField.isAccessible = true
                val actionButtonPopup = actionButtonPopupField.get(presenter) as? MenuPopupHelper
                if (actionButtonPopup != null && actionButtonPopup.isShowing) return true
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        return false
    }

    fun checkAndHookActiveToolbarPopups(toolbar: Toolbar, amv: ActionMenuView, activity: MainActivity) {
        try {
            val presenterField = ActionMenuView::class.java.getDeclaredField("mPresenter")
            presenterField.isAccessible = true
            val presenter = presenterField.get(amv) ?: return

            var showingHelper: MenuPopupHelper? = null

            // 1. Check mOverflowPopup
            try {
                val overflowField = presenter.javaClass.getDeclaredField("mOverflowPopup")
                overflowField.isAccessible = true
                val overflowPopup = overflowField.get(presenter) as? MenuPopupHelper
                if (overflowPopup != null && overflowPopup.isShowing) {
                    showingHelper = overflowPopup
                }
            } catch (_: Throwable) {}

            // 2. Check mActionButtonPopup (e.g. action_misc or action_add and their submenus)
            if (showingHelper == null) {
                try {
                    val actionButtonPopupField = presenter.javaClass.getDeclaredField("mActionButtonPopup")
                    actionButtonPopupField.isAccessible = true
                    val actionButtonPopup = actionButtonPopupField.get(presenter) as? MenuPopupHelper
                    if (actionButtonPopup != null && actionButtonPopup.isShowing) {
                        showingHelper = actionButtonPopup
                    }
                } catch (_: Throwable) {}
            }

            if (showingHelper != null || toolbar.isOverflowMenuShowing) {
                activity.showMenuScrim(TOOLBAR_TOKEN) {
                    try {
                        toolbar.dismissPopupMenus()
                    } catch (_: Throwable) {}
                }
                showingHelper?.setOnDismissListener {
                    toolbar.postDelayed({
                        if (!isAnyToolbarMenuShowing(amv, toolbar)) {
                            activity.hideMenuScrim(TOOLBAR_TOKEN)
                        } else {
                            checkAndHookActiveToolbarPopups(toolbar, amv, activity)
                        }
                    }, 120)
                }
            }
        } catch (_: Throwable) {}
    }

    private class ScrimPresenterCallback(
        private val toolbar: Toolbar,
        private val amv: ActionMenuView,
        private val activity: MainActivity,
        private val originalCallback: MenuPresenter.Callback?
    ) : MenuPresenter.Callback {
        override fun onOpenSubMenu(subMenu: MenuBuilder): Boolean {
            activity.showMenuScrim(TOOLBAR_TOKEN) {
                try {
                    toolbar.dismissPopupMenus()
                } catch (_: Throwable) {}
            }
            scheduleToolbarCheck(toolbar, amv, activity)
            return originalCallback?.onOpenSubMenu(subMenu) ?: true
        }

        override fun onCloseMenu(menu: MenuBuilder, allMenusAreClosing: Boolean) {
            originalCallback?.onCloseMenu(menu, allMenusAreClosing)
            if (allMenusAreClosing) {
                activity.hideMenuScrim(TOOLBAR_TOKEN)
            } else {
                scheduleToolbarCheck(toolbar, amv, activity)
            }
        }
    }
}
