package io.nekohasekai.sagernet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ui.MainActivity

class OwnBoxWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "io.nekohasekai.sagernet.widget.ACTION_TOGGLE"

        fun updateWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, OwnBoxWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isNotEmpty()) {
                val intent = Intent(context, OwnBoxWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                SagerNet.startService()
            }
            updateWidgets(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = DataStore.serviceState
        val isConnected = state == BaseService.State.Connected
        val isConnecting = state == BaseService.State.Connecting

        val currentProfile = ProfileManager.getProfile(DataStore.selectedProxy)
        val profileTitle = currentProfile?.displayName() ?: context.getString(R.string.app_name)

        val statusText = when {
            isConnected -> context.getString(R.string.vpn_connected)
            isConnecting -> context.getString(R.string.connecting)
            state.canStop -> context.getString(R.string.stopping)
            else -> context.getString(R.string.not_connected)
        }

        val buttonIcon = when {
            isConnected -> R.drawable.ic_service_active
            isConnecting -> R.drawable.ic_service_busy
            else -> R.drawable.ic_service_idle
        }

        val toggleIntent = Intent(context, OwnBoxWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = Intent(context, MainActivity::class.java)
        val launchPendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.layout_widget_ownbox).apply {
                setTextViewText(R.id.widget_title, profileTitle)
                setTextViewText(R.id.widget_status, statusText)
                setImageViewResource(R.id.widget_toggle_btn, buttonIcon)
                setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)
                setOnClickPendingIntent(R.id.widget_root, launchPendingIntent)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
