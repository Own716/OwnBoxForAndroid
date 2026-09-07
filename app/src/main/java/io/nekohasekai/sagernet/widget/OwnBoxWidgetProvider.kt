package io.nekohasekai.sagernet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.NodeSelectDialogActivity

class OwnBoxWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.ownbox.app.widget.ACTION_TOGGLE"

        fun updateWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, OwnBoxWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (allWidgetIds.isNotEmpty()) {
                val intent = Intent(context, OwnBoxWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, allWidgetIds)
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

        // 3号位四叶草：已连接通透翡翠绿，未连接冷灰
        val cloverIcon = if (isConnected) {
            R.drawable.ic_clover_connected
        } else {
            R.drawable.ic_clover_disconnected
        }

        // 3号位点击：切换连接
        val toggleIntent = Intent(context, OwnBoxWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, 1001, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2号位点击：打开轻量流体玻璃节点选择弹窗浮层
        val switchIntent = Intent(context, NodeSelectDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val switchPendingIntent = PendingIntent.getActivity(
            context, 1002, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 点击小组件主体：呼出主应用
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            context, 1000, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.layout_widget_ownbox).apply {
                // 1号位官方可莉（红帽子小女孩）
                setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_klee)
                // 节点名与状态
                setTextViewText(R.id.widget_title, profileTitle)
                setTextViewText(R.id.widget_status, statusText)
                // 3号位四叶草
                setImageViewResource(R.id.widget_toggle_btn, cloverIcon)
                // 交互绑定
                setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)
                setOnClickPendingIntent(R.id.widget_switch_btn, switchPendingIntent)
                setOnClickPendingIntent(R.id.widget_info_area, launchPendingIntent)
                setOnClickPendingIntent(R.id.widget_icon, launchPendingIntent)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
