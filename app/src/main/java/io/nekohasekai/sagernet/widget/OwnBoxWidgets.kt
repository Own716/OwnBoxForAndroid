package io.nekohasekai.sagernet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.format.Formatter
import android.widget.RemoteViews
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.NodeSelectDialogActivity

object OwnBoxWidgetHelper {
    const val ACTION_TOGGLE = "com.ownbox.app.widget.ACTION_TOGGLE"

    @Volatile
    var lastSpeedDisplayData: SpeedDisplayData? = null

    fun handleToggle(context: Context) {
        if (DataStore.serviceState.canStop) {
            SagerNet.stopService()
        } else {
            SagerNet.startService()
        }
        updateAllWidgets(context)
    }

    fun updateAllWidgets(context: Context, speedData: SpeedDisplayData? = null) {
        if (speedData != null) {
            lastSpeedDisplayData = speedData
        }
        val appWidgetManager = AppWidgetManager.getInstance(context)

        update1x1(context, appWidgetManager)
        update2x2(context, appWidgetManager)
        update4x1(context, appWidgetManager)
        update4x2(context, appWidgetManager)
        updateClassic(context, appWidgetManager)
    }

    private fun getPendingIntents(context: Context): Triple<PendingIntent, PendingIntent, PendingIntent> {
        val toggleIntent = Intent(context, OwnBoxWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePending = PendingIntent.getBroadcast(
            context, 1001, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val switchIntent = Intent(context, NodeSelectDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val switchPending = PendingIntent.getActivity(
            context, 1002, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val launchPending = PendingIntent.getActivity(
            context, 1000, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Triple(togglePending, switchPending, launchPending)
    }

    private fun getCommonData(context: Context): WidgetCommonData {
        val state = DataStore.serviceState
        val isConnected = state == BaseService.State.Connected
        val isConnecting = state == BaseService.State.Connecting

        val currentProfile = ProfileManager.getProfile(DataStore.selectedProxy)
        val profileTitle = currentProfile?.displayName() ?: context.getString(R.string.app_name)
        val profileType = currentProfile?.displayType() ?: ""

        val statusText = when {
            isConnected -> context.getString(R.string.vpn_connected)
            isConnecting -> context.getString(R.string.connecting)
            state.canStop -> context.getString(R.string.stopping)
            else -> context.getString(R.string.not_connected)
        }

        val cloverIcon = if (isConnected) {
            R.drawable.ic_clover_connected
        } else {
            R.drawable.ic_clover_disconnected
        }

        val dotColor = when {
            isConnected -> Color.parseColor("#10B981")
            isConnecting -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#94A3B8")
        }

        val latencyText = when {
            currentProfile != null && currentProfile.status > 0 -> "🟢 ${currentProfile.status} ms"
            currentProfile != null && currentProfile.status < 0 -> "🔴 失败"
            else -> "-- ms"
        }

        val speed = lastSpeedDisplayData
        val speedText = if (isConnected && speed != null) {
            val txStr = Formatter.formatFileSize(context, speed.txRateProxy) + "/s"
            val rxStr = Formatter.formatFileSize(context, speed.rxRateProxy) + "/s"
            "↑ $txStr  ↓ $rxStr"
        } else {
            "↑ 0 B/s  ↓ 0 B/s"
        }

        val trafficTotalText = if (isConnected && speed != null && (speed.txTotal > 0 || speed.rxTotal > 0)) {
            "总计: " + Formatter.formatFileSize(context, speed.txTotal + speed.rxTotal)
        } else {
            "总流量: 0 B"
        }

        return WidgetCommonData(
            profileTitle, profileType, statusText, cloverIcon, dotColor, latencyText, speedText, trafficTotalText
        )
    }

    data class WidgetCommonData(
        val title: String,
        val type: String,
        val statusText: String,
        val cloverIcon: Int,
        val dotColor: Int,
        val latencyText: String,
        val speedText: String,
        val trafficTotalText: String,
    )

    private fun update1x1(context: Context, manager: AppWidgetManager) {
        try {
            val ids = manager.getAppWidgetIds(ComponentName(context, OwnBoxWidget1x1::class.java))
            if (ids.isEmpty()) return
            val (togglePending, _, _) = getPendingIntents(context)
            val data = getCommonData(context)

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_proxy_1x1).apply {
                    setImageViewResource(R.id.widget_icon, data.cloverIcon)
                    setInt(R.id.widget_status_dot, "setColorFilter", data.dotColor)
                    setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)
                }
                manager.updateAppWidget(id, views)
            }
        } catch (e: Throwable) {
            Logs.w("Failed to update 1x1 widget: ${e.message}")
        }
    }

    private fun update2x2(context: Context, manager: AppWidgetManager) {
        try {
            val ids = manager.getAppWidgetIds(ComponentName(context, OwnBoxWidget2x2::class.java))
            if (ids.isEmpty()) return
            val (togglePending, switchPending, launchPending) = getPendingIntents(context)
            val data = getCommonData(context)

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_proxy_2x2).apply {
                    setTextViewText(R.id.widget_title, data.title)
                    setTextViewText(R.id.widget_status, data.statusText)
                    setTextViewText(R.id.widget_latency, data.latencyText)
                    setTextViewText(R.id.widget_speed, data.speedText)
                    setImageViewResource(R.id.widget_toggle_btn, data.cloverIcon)
                    setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)
                    setOnClickPendingIntent(R.id.widget_switch_btn, switchPending)
                    setOnClickPendingIntent(R.id.widget_info_area, launchPending)
                    setOnClickPendingIntent(R.id.widget_header_area, launchPending)
                }
                manager.updateAppWidget(id, views)
            }
        } catch (e: Throwable) {
            Logs.w("Failed to update 2x2 widget: ${e.message}")
        }
    }

    private fun update4x1(context: Context, manager: AppWidgetManager) {
        try {
            val ids = manager.getAppWidgetIds(ComponentName(context, OwnBoxWidget4x1::class.java))
            if (ids.isEmpty()) return
            val (togglePending, switchPending, launchPending) = getPendingIntents(context)
            val data = getCommonData(context)

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_proxy_4x1).apply {
                    setTextViewText(R.id.widget_title, data.title)
                    setTextViewText(R.id.widget_status, data.statusText)
                    setInt(R.id.widget_status_dot, "setColorFilter", data.dotColor)
                    setImageViewResource(R.id.widget_toggle_btn, data.cloverIcon)
                    setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)
                    setOnClickPendingIntent(R.id.widget_switch_btn, switchPending)
                    setOnClickPendingIntent(R.id.widget_info_area, launchPending)
                    setOnClickPendingIntent(R.id.widget_icon, launchPending)
                }
                manager.updateAppWidget(id, views)
            }
        } catch (e: Throwable) {
            Logs.w("Failed to update 4x1 widget: ${e.message}")
        }
    }

    private fun update4x2(context: Context, manager: AppWidgetManager) {
        try {
            val ids = manager.getAppWidgetIds(ComponentName(context, OwnBoxWidget4x2::class.java))
            if (ids.isEmpty()) return
            val (togglePending, switchPending, launchPending) = getPendingIntents(context)
            val data = getCommonData(context)

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_proxy_4x2).apply {
                    setTextViewText(R.id.widget_title, data.title)
                    setTextViewText(R.id.widget_status, data.statusText)
                    setTextViewText(R.id.widget_type, data.type)
                    setTextViewText(R.id.widget_latency, data.latencyText)
                    setTextViewText(R.id.widget_speed, data.speedText)
                    setTextViewText(R.id.widget_traffic_total, data.trafficTotalText)
                    setImageViewResource(R.id.widget_toggle_btn, data.cloverIcon)
                    setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)
                    setOnClickPendingIntent(R.id.widget_switch_btn, switchPending)
                    setOnClickPendingIntent(R.id.widget_info_area, launchPending)
                    setOnClickPendingIntent(R.id.widget_header_area, launchPending)
                }
                manager.updateAppWidget(id, views)
            }
        } catch (e: Throwable) {
            Logs.w("Failed to update 4x2 widget: ${e.message}")
        }
    }

    private fun updateClassic(context: Context, manager: AppWidgetManager) {
        try {
            val ids = manager.getAppWidgetIds(ComponentName(context, OwnBoxWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val (togglePending, switchPending, launchPending) = getPendingIntents(context)
            val data = getCommonData(context)

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.layout_widget_ownbox).apply {
                    setImageViewResource(R.id.widget_icon, R.drawable.ic_widget_klee)
                    setTextViewText(R.id.widget_title, data.title)
                    setTextViewText(R.id.widget_status, data.statusText)
                    setImageViewResource(R.id.widget_toggle_btn, data.cloverIcon)
                    setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)
                    setOnClickPendingIntent(R.id.widget_switch_btn, switchPending)
                    setOnClickPendingIntent(R.id.widget_info_area, launchPending)
                    setOnClickPendingIntent(R.id.widget_icon, launchPending)
                }
                manager.updateAppWidget(id, views)
            }
        } catch (e: Throwable) {
            Logs.w("Failed to update classic widget: ${e.message}")
        }
    }
}

class OwnBoxWidget1x1 : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OwnBoxWidgetHelper.ACTION_TOGGLE) {
            OwnBoxWidgetHelper.handleToggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        OwnBoxWidgetHelper.updateAllWidgets(context)
    }
}

class OwnBoxWidget2x2 : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OwnBoxWidgetHelper.ACTION_TOGGLE) {
            OwnBoxWidgetHelper.handleToggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        OwnBoxWidgetHelper.updateAllWidgets(context)
    }
}

class OwnBoxWidget4x1 : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OwnBoxWidgetHelper.ACTION_TOGGLE) {
            OwnBoxWidgetHelper.handleToggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        OwnBoxWidgetHelper.updateAllWidgets(context)
    }
}

class OwnBoxWidget4x2 : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OwnBoxWidgetHelper.ACTION_TOGGLE) {
            OwnBoxWidgetHelper.handleToggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        OwnBoxWidgetHelper.updateAllWidgets(context)
    }
}
