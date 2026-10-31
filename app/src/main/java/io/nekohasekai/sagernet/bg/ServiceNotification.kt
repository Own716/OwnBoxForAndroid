package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileOrder
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * User can customize visibility of notification since Android 8.
 */
class ServiceNotification(
    private val service: BaseService.Interface,
    private val title: String,
    channel: String,
    visible: Boolean = false,
) : BroadcastReceiver() {

    companion object {
        const val notificationId = 1
        val flags =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT

        const val ACTION_STOP = "stop"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_NEXT = "next"
        const val ACTION_SWITCH = "switch"
        const val ACTION_RESET = "reset"
        val DEFAULT_ACTIONS = listOf(ACTION_STOP, ACTION_NEXT, ACTION_SWITCH)
        private val ACTION_ORDER = listOf(ACTION_STOP, ACTION_PREVIOUS, ACTION_NEXT, ACTION_SWITCH, ACTION_RESET)

        fun notificationActions(): List<String> {
            val stored = DataStore.configurationStore.getStringSet(Key.NOTIFICATION_ACTIONS)
                ?: DataStore.notificationActions
            if (stored.isEmpty()) return DEFAULT_ACTIONS
            return ACTION_ORDER.filter { it in stored }.take(3)
        }

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                runCatching { SagerDatabase.groupDao.getById(ent.groupId)?.displayName() }.getOrNull() else null
            val profileName = ent.displayName()
            return if (!gn.isNullOrBlank() && !profileName.startsWith("$gn · ")) {
                "$gn · $profileName"
            } else {
                profileName
            }
        }
    }

    var listenPostSpeed = true

    private val connectStartTime = System.currentTimeMillis()
    private var lastStats: SpeedDisplayData = SpeedDisplayData()
    private var currentEgressIp: String? = null
    private var currentEgressCountry: String? = null

    private fun applyLiveUpdateProperties(
        builder: NotificationCompat.Builder,
        showCapsule: Boolean,
        collapsedTitle: String,
        expandedTitle: String,
        contentText: String,
        subText: String?
    ) {
        builder.setOngoing(true)
        if (showCapsule) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
            builder.priority = NotificationCompat.PRIORITY_LOW

            try {
                val method = builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                method.invoke(builder, true)
            } catch (_: Throwable) {}

            builder.addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                putCharSequence("android.shortCriticalText", collapsedTitle)
                putString("capsule_text", collapsedTitle)
                try {
                    val focusParam = JSONObject().apply {
                        put("type", 1)
                        put("baseInfo", JSONObject().apply {
                            put("title", collapsedTitle)
                            put("content", contentText)
                            if (!subText.isNullOrEmpty()) {
                                put("subTitle", subText)
                            }
                        })
                    }
                    putString("miui.focus.param", focusParam.toString())
                } catch (_: Throwable) {}
            })
        } else {
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
            builder.priority = NotificationCompat.PRIORITY_LOW

            try {
                val method = builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                method.invoke(builder, false)
            } catch (_: Throwable) {}

            builder.addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", false)
                remove("miui.focus.param")
                remove("capsule_text")
                remove("android.shortCriticalText")
            })
        }
    }

    suspend fun postStateUpdate(state: BaseService.State) {
        if (DataStore.liveUpdateEnabled) {
            postNotificationSpeedUpdate(lastStats)
            if (state == BaseService.State.Connected) {
                probeEgress()
            }
        }
    }

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        lastStats = stats
        useBuilder {
            if (DataStore.liveUpdateEnabled) {
                val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
                val profile = service.data.proxy?.profile ?: SagerDatabase.proxyDao.getById(profileId)
                val info = LiveUpdateFormatter.buildInfo(
                    service as Context,
                    profile,
                    stats,
                    connectStartTime,
                    currentEgressIp,
                    currentEgressCountry
                )
                val tpls = LiveUpdateFormatter.getTemplates()

                val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { title }
                val formattedTitle = LiveUpdateFormatter.format(tpls.title, info).ifEmpty { title }
                val formattedContent = LiveUpdateFormatter.format(tpls.content, info)
                val formattedSubText = LiveUpdateFormatter.format(tpls.subText, info)

                it.setContentTitle(formattedCollapsed)
                it.setContentText(formattedContent.ifEmpty { null })
                if (formattedSubText.isNotEmpty()) {
                    it.setSubText(formattedSubText)
                } else {
                    it.setSubText(null)
                }

                val bigTextStyle = NotificationCompat.BigTextStyle()
                bigTextStyle.setBigContentTitle(formattedTitle)
                bigTextStyle.bigText(formattedContent.ifEmpty { formattedTitle })
                if (formattedSubText.isNotEmpty()) {
                    bigTextStyle.setSummaryText(formattedSubText)
                } else {
                    bigTextStyle.setSummaryText(null)
                }
                it.setStyle(bigTextStyle)

                applyLiveUpdateProperties(
                    it,
                    DataStore.liveUpdateShowCapsule,
                    formattedCollapsed,
                    formattedTitle,
                    formattedContent,
                    formattedSubText
                )
            } else if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                it.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
                it.setContentText(speedDetail)
                it.setSubText(
                    service.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(service, stats.txTotal),
                        Formatter.formatFileSize(service, stats.rxTotal)
                    )
                )
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                it.setStyle(null)
                it.setContentText(speedSimple)
                it.setSubText(
                    service.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(service, stats.txTotal),
                        Formatter.formatFileSize(service, stats.rxTotal)
                    )
                )
            }
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        useBuilder {
            if (!DataStore.liveUpdateEnabled) {
                it.setContentTitle(newTitle)
            }
        }
        update()
    }

    suspend fun refresh(newTitle: String) {
        currentEgressIp = null
        currentEgressCountry = null
        updateActions()
        useBuilder {
            if (DataStore.liveUpdateEnabled) {
                val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
                val profile = service.data.proxy?.profile ?: SagerDatabase.proxyDao.getById(profileId)
                val info = LiveUpdateFormatter.buildInfo(
                    service as Context,
                    profile,
                    lastStats,
                    connectStartTime,
                    currentEgressIp,
                    currentEgressCountry
                )
                val tpls = LiveUpdateFormatter.getTemplates()

                val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { newTitle }
                val formattedTitle = LiveUpdateFormatter.format(tpls.title, info).ifEmpty { newTitle }
                val formattedContent = LiveUpdateFormatter.format(tpls.content, info)
                val formattedSubText = LiveUpdateFormatter.format(tpls.subText, info)

                it.setContentTitle(formattedCollapsed)
                it.setContentText(formattedContent.ifEmpty { null })
                if (formattedSubText.isNotEmpty()) {
                    it.setSubText(formattedSubText)
                } else {
                    it.setSubText(null)
                }

                val bigTextStyle = NotificationCompat.BigTextStyle()
                bigTextStyle.setBigContentTitle(formattedTitle)
                bigTextStyle.bigText(formattedContent.ifEmpty { formattedTitle })
                if (formattedSubText.isNotEmpty()) {
                    bigTextStyle.setSummaryText(formattedSubText)
                } else {
                    bigTextStyle.setSummaryText(null)
                }
                it.setStyle(bigTextStyle)

                applyLiveUpdateProperties(
                    it,
                    DataStore.liveUpdateShowCapsule,
                    formattedCollapsed,
                    formattedTitle,
                    formattedContent,
                    formattedSubText
                )
            } else {
                it.setContentTitle(newTitle)
                it.setContentText(null)
                it.setSubText(null)
                it.setStyle(null)
            }
        }
        update()
        if (DataStore.liveUpdateEnabled) {
            probeEgress()
        }
    }

    suspend fun refreshLiveUpdate() {
        updateActions()
        postNotificationSpeedUpdate(lastStats)
    }

    suspend fun postConnected() {
        updateActions()
        useBuilder { it.priority = NotificationCompat.PRIORITY_LOW }
        update()
        if (DataStore.liveUpdateEnabled) {
            probeEgress()
        }
    }

    suspend fun postNotificationWakeLockStatus(acquired: Boolean) {
        updateActions()
        useBuilder {
            it.priority =
                if (acquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
        }
        update()
    }

    private fun probeEgress() {
        if (!DataStore.liveUpdateEnabled) return
        runOnDefaultDispatcher {
            val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
            val cached = LandingIpManager.getCachedInfo()
            if (cached != null && cached.ip.isNotEmpty()) {
                currentEgressIp = cached.ip
                if (cached.country.isNotEmpty()) {
                    currentEgressCountry = LiveUpdateFormatter.cleanCountryName(cached.country)
                }
                postNotificationSpeedUpdate(lastStats)
            } else if (service.data.state == BaseService.State.Connected && profileId > 0L) {
                LandingIpManager.queryLandingIp(profileId) { info ->
                    var changed = false
                    if (info.ip.isNotEmpty() && info.ip != currentEgressIp) {
                        currentEgressIp = info.ip
                        changed = true
                    }
                    val c = LiveUpdateFormatter.cleanCountryName(info.country)
                    if (c.isNotEmpty() && c != currentEgressCountry) {
                        currentEgressCountry = c
                        changed = true
                    }
                    if (changed) {
                        runOnDefaultDispatcher {
                            postNotificationSpeedUpdate(lastStats)
                        }
                    }
                }
            }
        }
    }

    private val showDirectSpeed get() = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_throne_tile)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private val buildLock = Mutex()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        })

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private suspend fun updateActions() {
        service as Context
        val actions = notificationActions()
        val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
        val canCycle = (ACTION_NEXT in actions || ACTION_PREVIOUS in actions) && ProfileOrder.canCycle(profileId)

        useBuilder {
            it.clearActions()
            for (action in actions) when (action) {
                ACTION_STOP -> it.addAction(
                    broadcastAction(R.drawable.ic_notification_stop, service.getText(R.string.stop), Action.CLOSE, 1)
                )

                ACTION_PREVIOUS -> if (canCycle) it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_previous, service.getText(R.string.notification_previous),
                        Action.SWITCH_PREVIOUS, 2,
                    )
                )

                ACTION_NEXT -> if (canCycle) it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_next, service.getText(R.string.notification_next),
                        Action.SWITCH_NEXT, 3,
                    )
                )

                ACTION_SWITCH -> it.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification_switch, service.getText(R.string.notification_switch),
                        PendingIntent.getActivity(
                            service, 4,
                            Intent(service, SwitchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            flags,
                        )
                    ).setShowsUserInterface(true).build()
                )

                ACTION_RESET -> it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_reset, service.getText(R.string.reset_connections),
                        Action.RESET_UPSTREAM_CONNECTIONS, 5,
                    )
                )
            }
        }
    }

    private fun broadcastAction(icon: Int, title: CharSequence, action: String, requestCode: Int) =
        NotificationCompat.Action.Builder(
            icon, title, PendingIntent.getBroadcast(
                service as Context, requestCode, Intent(action).setPackage(service.packageName), flags
            )
        ).setShowsUserInterface(false).build()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                listenPostSpeed = true
                if (DataStore.liveUpdateEnabled && (currentEgressIp.isNullOrEmpty() || currentEgressCountry.isNullOrEmpty())) {
                    probeEgress()
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                listenPostSpeed = false
            }
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                runOnMainDispatcher {
                    Theme.apply(app)
                    Theme.apply(service as Context)
                    updateActions()
                    useBuilder {
                        it.setSmallIcon(R.drawable.ic_throne_tile)
                    }
                    update()
                }
            }
        }
    }

    private suspend fun show() =
        useBuilder {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    (service as Service).startForeground(
                        notificationId,
                        it.build(),
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    (service as Service).startForeground(notificationId, it.build())
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private suspend fun update() = useBuilder {
        NotificationManagerCompat.from(service as Service).notify(notificationId, it.build())
    }

    fun destroy() {
        listenPostSpeed = false
        val s = service as? Service
        if (s != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                s.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                s.stopForeground(true)
            }
            NotificationManagerCompat.from(s).cancel(notificationId)
        }
        SagerNet.notification.cancel(notificationId)
        try {
            (service as Context).unregisterReceiver(this)
        } catch (_: Throwable) {
        }
    }
}
