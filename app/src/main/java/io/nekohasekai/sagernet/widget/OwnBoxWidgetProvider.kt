package io.nekohasekai.sagernet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class OwnBoxWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.ownbox.app.widget.ACTION_TOGGLE"

        fun updateWidgets(context: Context) {
            OwnBoxWidgetHelper.updateAllWidgets(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            OwnBoxWidgetHelper.handleToggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        OwnBoxWidgetHelper.updateAllWidgets(context)
    }
}
