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
import java.util.concurrent.atomic.AtomicLong

/**
 * User can customize visibility of notification since Android 8.
 */
class ServiceNotification(
    private val service: BaseService.Interface,
    private val title: String,
    channel: String,
    visible: Boolean = false,
) : BroadcastReceiver() {

    private val channelId = channel
    private val isVisible = visible

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
    @Volatile
    private var currentTitle: String = title
    @Volatile
    private var currentEgressIp: String? = null
    @Volatile
    private var currentEgressCountry: String? = null
    @Volatile
    private var lastCollapsedText: String? = null
    @Volatile
    private var lastShowCapsule: Boolean? = null
    private val egressProbeGeneration = AtomicLong(0L)

    private fun applyLiveUpdateProperties(
        builder: NotificationCompat.Builder,
        showCapsule: Boolean,
        collapsedTitle: String,
        expandedTitle: String,
        contentText: String,
        subText: String?
    ) {
        builder.setOngoing(true)
        val extras = builder.extras
        if (showCapsule) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
            builder.priority = NotificationCompat.PRIORITY_LOW

            try {
                val method = builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                method.invoke(builder, true)
            } catch (_: Throwable) {}

            extras.putBoolean("android.requestPromotedOngoing", true)
            extras.putCharSequence("android.shortCriticalText", collapsedTitle)
            extras.putString("capsule_text", collapsedTitle)
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
                extras.putString("miui.focus.param", focusParam.toString())
            } catch (_: Throwable) {}
        } else {
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
            builder.priority = NotificationCompat.PRIORITY_LOW

            try {
                val method = builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                method.invoke(builder, false)
            } catch (_: Throwable) {}

            extras.putBoolean("android.requestPromotedOngoing", false)
            extras.remove("capsule_text")
            extras.remove("android.shortCriticalText")
            extras.remove("miui.focus.param")
            try {
                val focusParam = JSONObject().apply {
                    put("type", 0)
                }
                extras.putString("miui.focus.param", focusParam.toString())
            } catch (_: Throwable) {}
        }
    }

    suspend fun postStateUpdate(state: BaseService.State) {
        if (state == BaseService.State.Suspended || service.data.isSmartStopped) return
        if (DataStore.liveUpdateEnabled) {
            postNotificationSpeedUpdate(lastStats)
            if (state == BaseService.State.Connected) {
                probeEgress()
            }
        }
    }

    suspend fun postSuspendedState(matchedDetail: String) {
        listenPostSpeed = false
        val ctx = service as Context
        val content = if (matchedDetail.isNotBlank()) {
            ctx.getString(R.string.service_suspended_detail, matchedDetail)
        } else {
            ctx.getString(R.string.service_suspended)
        }
        useBuilder {
            it.setContentTitle(ctx.getString(R.string.app_name))
            it.setContentText(content)
            it.setSubText(null)
            it.setStyle(null)
            it.priority = NotificationCompat.PRIORITY_LOW
            it.clearActions()
            it.addAction(
                broadcastAction(
                    R.drawable.ic_notification_next,
                    ctx.getText(R.string.smart_auto_stop_resume),
                    Action.SMART_RESUME,
                    6,
                )
            )
            it.addAction(
                broadcastAction(
                    R.drawable.ic_notification_stop,
                    ctx.getText(R.string.stop),
                    Action.CLOSE,
                    1,
                )
            )
            it.extras.remove("capsule_text")
            it.extras.remove("android.shortCriticalText")
            it.extras.remove("miui.focus.param")
        }
        update()
    }

    private fun buildLiveUpdateInfo(stats: SpeedDisplayData): LiveUpdateFormatter.LiveUpdateInfo {
        val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
        val profile = service.data.proxy?.profile ?: SagerDatabase.proxyDao.getById(profileId)
        return LiveUpdateFormatter.buildInfo(
            service as Context,
            profile,
            stats,
            connectStartTime,
            currentEgressIp,
            currentEgressCountry
        )
    }

    private fun applyFormattedLiveUpdate(
        target: NotificationCompat.Builder,
        info: LiveUpdateFormatter.LiveUpdateInfo,
        tpls: LiveUpdateFormatter.Templates,
        showCapsule: Boolean,
        formattedCollapsed: String,
        fallbackTitle: String,
    ) {
        val formattedTitle = LiveUpdateFormatter.format(tpls.title, info).ifEmpty { fallbackTitle }
        val formattedContent = LiveUpdateFormatter.format(tpls.content, info)
        val formattedSubText = LiveUpdateFormatter.format(tpls.subText, info)

        target.setContentTitle(formattedCollapsed)
        target.setContentText(formattedContent.ifEmpty { null })
        target.setSubText(formattedSubText.ifEmpty { null })

        val bigTextStyle = NotificationCompat.BigTextStyle()
        bigTextStyle.setBigContentTitle(formattedTitle)
        bigTextStyle.bigText(formattedContent.ifEmpty { formattedTitle })
        bigTextStyle.setSummaryText(formattedSubText.ifEmpty { null })
        target.setStyle(bigTextStyle)

        applyLiveUpdateProperties(
            target,
            showCapsule,
            formattedCollapsed,
            formattedTitle,
            formattedContent,
            formattedSubText
        )
        lastCollapsedText = formattedCollapsed
        lastShowCapsule = showCapsule
    }

    private fun applyStandardSpeedContent(target: NotificationCompat.Builder, stats: SpeedDisplayData) {
        if (showDirectSpeed) {
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
            target.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
            target.setContentText(speedDetail)
            target.setSubText(
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
            target.setStyle(null)
            target.setContentText(speedSimple)
            target.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
        }
    }

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        if (service.data.state == BaseService.State.Suspended || service.data.isSmartStopped) return
        lastStats = stats
        buildLock.withLock {
            if (DataStore.liveUpdateEnabled) {
                val showCapsule = DataStore.liveUpdateShowCapsule
                val info = buildLiveUpdateInfo(stats)
                val tpls = LiveUpdateFormatter.getTemplates()
                val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { currentTitle }
                val target = if (formattedCollapsed != lastCollapsedText || lastShowCapsule != showCapsule) {
                    rebuildBuilderLocked(currentTitle)
                } else {
                    builder
                }
                applyFormattedLiveUpdate(target, info, tpls, showCapsule, formattedCollapsed, currentTitle)
            } else {
                applyStandardSpeedContent(builder, stats)
            }
            NotificationManagerCompat.from(service as Service).notify(notificationId, builder.build())
        }
    }

    suspend fun postNotificationTitle(newTitle: String) {
        if (DataStore.liveUpdateEnabled) {
            refresh(newTitle)
            return
        }
        currentTitle = newTitle
        buildLock.withLock {
            builder.setContentTitle(newTitle)
            NotificationManagerCompat.from(service as Service).notify(notificationId, builder.build())
        }
    }

    suspend fun refresh(newTitle: String) {
        currentTitle = newTitle
        currentEgressIp = null
        currentEgressCountry = null
        lastCollapsedText = null
        lastShowCapsule = null
        egressProbeGeneration.incrementAndGet()
        LandingIpManager.clearCache()
        buildLock.withLock {
            val target = rebuildBuilderLocked(newTitle)
            if (DataStore.liveUpdateEnabled) {
                val showCapsule = DataStore.liveUpdateShowCapsule
                val info = buildLiveUpdateInfo(lastStats)
                val tpls = LiveUpdateFormatter.getTemplates()
                val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { newTitle }
                applyFormattedLiveUpdate(target, info, tpls, showCapsule, formattedCollapsed, newTitle)
            } else {
                target.setContentTitle(newTitle)
                target.setContentText(null)
                target.setSubText(null)
                target.setStyle(null)
            }
            NotificationManagerCompat.from(service as Service).notify(notificationId, target.build())
        }
        if (DataStore.liveUpdateEnabled) {
            probeEgress(forceRefresh = true)
        }
    }

    suspend fun refreshLiveUpdate() {
        lastCollapsedText = null
        lastShowCapsule = null
        buildLock.withLock {
            rebuildBuilderLocked(currentTitle)
        }
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

    private fun probeEgress(forceRefresh: Boolean = false) {
        if (!DataStore.liveUpdateEnabled || service.data.state == BaseService.State.Suspended || service.data.isSmartStopped) return
        val templates = LiveUpdateFormatter.getTemplates()
        if (!LiveUpdateFormatter.requiresEgressProbe(templates)) return
        val generation = egressProbeGeneration.incrementAndGet()
        runOnDefaultDispatcher {
            if (egressProbeGeneration.get() != generation) return@runOnDefaultDispatcher
            val profile = service.data.proxy?.profile
                ?: runCatching { SagerDatabase.proxyDao.getById(DataStore.selectedProxy) }.getOrNull()
            val effectiveProfileId = LiveUpdateFormatter.resolveEffectiveProfileId(profile, DataStore.selectedProxy)
            if (effectiveProfileId <= 0L) return@runOnDefaultDispatcher

            val cached = if (forceRefresh) null else LandingIpManager.getCachedInfo(expectedProfileId = effectiveProfileId)
            if (cached != null && cached.ip.isNotEmpty()) {
                if (egressProbeGeneration.get() != generation) return@runOnDefaultDispatcher
                currentEgressIp = cached.ip
                if (cached.country.isNotEmpty()) {
                    currentEgressCountry = LiveUpdateFormatter.cleanCountryName(cached.country)
                }
                postNotificationSpeedUpdate(lastStats)
            } else if (service.data.state == BaseService.State.Connected) {
                LandingIpManager.queryLandingIp(effectiveProfileId, forceRefresh = forceRefresh) { info ->
                    if (egressProbeGeneration.get() != generation) return@queryLandingIp
                    val latestProfile = service.data.proxy?.profile
                        ?: runCatching { SagerDatabase.proxyDao.getById(DataStore.selectedProxy) }.getOrNull()
                    if (LiveUpdateFormatter.resolveEffectiveProfileId(latestProfile, DataStore.selectedProxy) != effectiveProfileId) {
                        return@queryLandingIp
                    }
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
                            if (egressProbeGeneration.get() == generation) {
                                postNotificationSpeedUpdate(lastStats)
                            }
                        }
                    }
                }
            }
        }
    }

    private val showDirectSpeed get() = DataStore.showDirectSpeed

    private fun createBaseBuilder(baseTitle: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(service as Context, channelId)
            .setWhen(0)
            .setTicker(service.getString(R.string.forward_success))
            .setContentTitle(baseTitle)
            .setOnlyAlertOnce(true)
            .setContentIntent(SagerNet.configureIntent(service))
            .setSmallIcon(R.drawable.ic_throne_tile)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (isVisible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun rebuildBuilderLocked(baseTitle: String): NotificationCompat.Builder {
        val fresh = createBaseBuilder(baseTitle)
        applyActionsLocked(fresh)
        builder = fresh
        return fresh
    }

    private var builder = createBaseBuilder(title)

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
            buildLock.withLock {
                applyActionsLocked(builder)
                if (DataStore.liveUpdateEnabled) {
                    val showCapsule = DataStore.liveUpdateShowCapsule
                    val info = buildLiveUpdateInfo(lastStats)
                    val tpls = LiveUpdateFormatter.getTemplates()
                    val formattedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, info).ifEmpty { currentTitle }
                    applyFormattedLiveUpdate(builder, info, tpls, showCapsule, formattedCollapsed, currentTitle)
                }
            }
            show()
        }
    }

    private suspend fun updateActions() {
        useBuilder {
            applyActionsLocked(it)
        }
    }

    private fun applyActionsLocked(target: NotificationCompat.Builder) {
        service as Context
        val actions = notificationActions()
        val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
        val canCycle = (ACTION_NEXT in actions || ACTION_PREVIOUS in actions) && ProfileOrder.canCycle(profileId)

        target.clearActions()
        for (action in actions) when (action) {
            ACTION_STOP -> target.addAction(
                broadcastAction(R.drawable.ic_notification_stop, service.getText(R.string.stop), Action.CLOSE, 1)
            )

            ACTION_PREVIOUS -> if (canCycle) target.addAction(
                broadcastAction(
                    R.drawable.ic_notification_previous, service.getText(R.string.notification_previous),
                    Action.SWITCH_PREVIOUS, 2,
                )
            )

            ACTION_NEXT -> if (canCycle) target.addAction(
                broadcastAction(
                    R.drawable.ic_notification_next, service.getText(R.string.notification_next),
                    Action.SWITCH_NEXT, 3,
                )
            )

            ACTION_SWITCH -> target.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_switch, service.getText(R.string.notification_switch),
                    PendingIntent.getActivity(
                        service, 4,
                        Intent(service, SwitchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        flags,
                    )
                ).setShowsUserInterface(true).build()
            )

            ACTION_RESET -> target.addAction(
                broadcastAction(
                    R.drawable.ic_notification_reset, service.getText(R.string.reset_connections),
                    Action.RESET_UPSTREAM_CONNECTIONS, 5,
                )
            )
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
                if (DataStore.liveUpdateEnabled &&
                    LiveUpdateFormatter.requiresEgressProbe(LiveUpdateFormatter.getTemplates()) &&
                    (currentEgressIp.isNullOrEmpty() || currentEgressCountry.isNullOrEmpty())
                ) {
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

    fun detachWithoutCancel() {
        listenPostSpeed = false
        egressProbeGeneration.incrementAndGet()
        try {
            (service as Context).unregisterReceiver(this)
        } catch (_: Throwable) {
        }
    }

    fun destroy() {
        listenPostSpeed = false
        egressProbeGeneration.incrementAndGet()
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
