package io.nekohasekai.sagernet

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.appcompat.content.res.AppCompatResources

internal object AppIconStatePolicy {
    fun current(states: Map<AppIcon, Int>): AppIcon {
        return AppIcon.values().drop(1).firstOrNull {
            states[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIcon.NEKOBOX_PLUS
    }

    fun desired(selected: AppIcon): Map<AppIcon, Int> = AppIcon.values().associateWith {
        if (it == selected) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    }

    fun selectionForDevice(selected: AppIcon, isTelevision: Boolean): AppIcon =
        if (isTelevision) AppIcon.NEKOBOX_PLUS else selected

    fun previewUiMode(appUiMode: Int, systemUiMode: Int): Int {
        return (appUiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (systemUiMode and Configuration.UI_MODE_NIGHT_MASK)
    }
}

object AppIconManager {
    fun current(context: Context): AppIcon {
        val packageManager = context.packageManager
        val states = AppIcon.values().associateWith {
            packageManager.getComponentEnabledSetting(it.componentName(context))
        }
        return AppIconStatePolicy.current(states)
    }

    fun set(context: Context, selected: AppIcon) {
        val effectiveSelection = AppIconStatePolicy.selectionForDevice(
            selected,
            context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
                Configuration.UI_MODE_TYPE_TELEVISION,
        )
        if (current(context) == effectiveSelection) return

        val packageManager = context.packageManager
        val desiredStates = AppIconStatePolicy.desired(effectiveSelection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                desiredStates.map { (icon, state) ->
                    PackageManager.ComponentEnabledSetting(
                        icon.componentName(context),
                        state,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            )
            return
        }

        packageManager.setComponentEnabledSetting(
            effectiveSelection.componentName(context),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        desiredStates.forEach { (icon, state) ->
            if (icon == effectiveSelection) return@forEach
            packageManager.setComponentEnabledSetting(
                icon.componentName(context),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun loadIcon(context: Context, icon: AppIcon): Drawable? {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = AppIconStatePolicy.previewUiMode(
                uiMode,
                Resources.getSystem().configuration.uiMode,
            )
        }
        val launcherContext = context.createConfigurationContext(configuration)
        return AppCompatResources.getDrawable(launcherContext, icon.iconRes)
    }

    private fun AppIcon.componentName(context: Context) =
        ComponentName(context.packageName, aliasClassName)
}
