package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.plugin.Plugins
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedPluginPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    val uidMap = HashMap<Int, HashSet<String>>()
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            reload()
            labelCache.clear()
        }
        loaded.unlock()
    }

    @SuppressLint("InlinedApi")
    fun reload() {
        val installed = if (SagerNet.application.isBgProcess) {
            installedPackages = emptyMap()
            installedPluginPackages = emptyMap()
            try {
                app.packageManager.getInstalledApplications(0)
            } catch (t: Throwable) {
                Logs.e(t)
                emptyList()
            }
        } else {
            val rawPackageInfo = try {
                app.packageManager.getInstalledPackages(
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            or PackageManager.GET_PERMISSIONS
                            or PackageManager.GET_PROVIDERS
                            or PackageManager.GET_META_DATA
                )
            } catch (t: Throwable) {
                Logs.e(t)
                try {
                    app.packageManager.getInstalledPackages(0)
                } catch (t2: Throwable) {
                    Logs.e(t2)
                    emptyList()
                }
            }

            installedPackages = rawPackageInfo.filter {
                when (it.packageName) {
                    "android" -> true
                    else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                }
            }.associateBy { it.packageName }

            installedPluginPackages = rawPackageInfo.filter {
                Plugins.isExe(it)
            }.associateBy { it.packageName }

            try {
                app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (t: Throwable) {
                Logs.e(t)
                try {
                    app.packageManager.getInstalledApplications(0)
                } catch (t2: Throwable) {
                    Logs.e(t2)
                    emptyList()
                }
            }
        }
        installedApps = installed.associateBy { it.packageName }
        packageMap = installed.associate { it.packageName to it.uid }
        uidMap.clear()
        for (info in installed) {
            val uid = info.uid
            uidMap.getOrPut(uid) { HashSet() }.add(info.packageName)
        }
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun awaitLoadSync() {
        if (::packageMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    private val labelCache = ConcurrentHashMap<String, String>()

    fun loadLabel(info: ApplicationInfo, pm: PackageManager = app.packageManager): CharSequence {
        val packageName = info.packageName
        labelCache[packageName]?.let { return it }

        // Fast path: nonLocalizedLabel doesn't trigger HyperOS ThemeZipFile / resource loading threads
        try {
            info.nonLocalizedLabel?.let {
                val str = it.toString()
                if (str.isNotBlank()) {
                    labelCache[packageName] = str
                    return str
                }
            }
        } catch (_: Throwable) {
        }

        val label = try {
            info.loadLabel(pm).toString()
        } catch (_: Throwable) {
            packageName
        }
        labelCache[packageName] = label
        return label
    }

    fun loadLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val info = installedApps[packageName]
            ?: installedPackages[packageName]?.applicationInfo
            ?: installedPluginPackages[packageName]?.applicationInfo
            ?: return packageName
        return loadLabel(info).toString()
    }

    fun loadIcon(info: ApplicationInfo, pm: PackageManager = app.packageManager): Drawable {
        return try {
            info.loadIcon(pm)
        } catch (_: Throwable) {
            try {
                pm.defaultActivityIcon
            } catch (_: Throwable) {
                ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

}