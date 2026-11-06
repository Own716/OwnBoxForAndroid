package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import io.nekohasekai.sagernet.database.DataStore
import kotlin.math.roundToInt

object UiScaleManager {

    fun ignoreSystemFontScale(): Boolean = runCatching { DataStore.uiIgnoreSystemFontScale }.getOrDefault(false)
    fun globalScalePct(): Int = runCatching { DataStore.uiGlobalScale.coerceIn(75, 125) }.getOrDefault(100)
    fun fontScalePct(): Int = runCatching { DataStore.uiFontScale.coerceIn(75, 130) }.getOrDefault(100)

    fun toolbarTitleScalePct(): Int = runCatching { DataStore.uiToolbarTitleScale.coerceIn(70, 130) }.getOrDefault(100)
    fun toolbarIconScalePct(): Int = runCatching { DataStore.uiToolbarIconScale.coerceIn(70, 130) }.getOrDefault(100)
    fun tabBarScalePct(): Int = runCatching { DataStore.uiTabBarScale.coerceIn(70, 130) }.getOrDefault(100)
    fun drawerScalePct(): Int = runCatching { DataStore.uiDrawerScale.coerceIn(70, 130) }.getOrDefault(100)
    fun profileCardScalePct(): Int = runCatching { DataStore.uiProfileCardScale.coerceIn(70, 130) }.getOrDefault(100)
    fun bottomBarScalePct(): Int = runCatching { DataStore.uiBottomBarScale.coerceIn(70, 130) }.getOrDefault(100)

    fun toolbarTitleFactor(): Float = toolbarTitleScalePct() / 100f
    fun toolbarIconFactor(): Float = toolbarIconScalePct() / 100f
    fun tabBarFactor(): Float = tabBarScalePct() / 100f
    fun drawerFactor(): Float = drawerScalePct() / 100f
    fun profileCardFactor(): Float = profileCardScalePct() / 100f
    fun bottomBarFactor(): Float = bottomBarScalePct() / 100f

    fun getSignature(): String {
        return buildString {
            append(ignoreSystemFontScale()).append(':')
            append(globalScalePct()).append(':')
            append(fontScalePct()).append(':')
            append(toolbarTitleScalePct()).append(':')
            append(toolbarIconScalePct()).append(':')
            append(tabBarScalePct()).append(':')
            append(drawerScalePct()).append(':')
            append(profileCardScalePct()).append(':')
            append(bottomBarScalePct())
        }
    }

    fun isDefault(): Boolean {
        return !ignoreSystemFontScale() &&
            globalScalePct() == 100 &&
            fontScalePct() == 100 &&
            toolbarTitleScalePct() == 100 &&
            toolbarIconScalePct() == 100 &&
            tabBarScalePct() == 100 &&
            drawerScalePct() == 100 &&
            profileCardScalePct() == 100 &&
            bottomBarScalePct() == 100
    }

    fun resetToDefaults() {
        DataStore.uiIgnoreSystemFontScale = false
        DataStore.uiGlobalScale = 100
        DataStore.uiFontScale = 100
        DataStore.uiToolbarTitleScale = 100
        DataStore.uiToolbarIconScale = 100
        DataStore.uiTabBarScale = 100
        DataStore.uiDrawerScale = 100
        DataStore.uiProfileCardScale = 100
        DataStore.uiBottomBarScale = 100
    }

    fun applyPreset(scalePct: Int) {
        val clamped = scalePct.coerceIn(75, 125)
        DataStore.uiGlobalScale = clamped
        DataStore.uiFontScale = clamped
        DataStore.uiToolbarTitleScale = 100
        DataStore.uiToolbarIconScale = 100
        DataStore.uiTabBarScale = 100
        DataStore.uiDrawerScale = 100
        DataStore.uiProfileCardScale = 100
        DataStore.uiBottomBarScale = 100
    }

    fun formatSummary(): String {
        if (isDefault()) {
            return "标准默认 (100%)"
        }
        val parts = mutableListOf<String>()
        parts.add("全局 ${globalScalePct()}%")
        parts.add("字体 ${fontScalePct()}%")
        if (ignoreSystemFontScale()) {
            parts.add("已忽略系统大字体")
        }
        val customRegions = listOf(
            toolbarTitleScalePct(),
            toolbarIconScalePct(),
            tabBarScalePct(),
            drawerScalePct(),
            profileCardScalePct(),
            bottomBarScalePct()
        ).count { it != 100 }
        if (customRegions > 0) {
            parts.add("已微调 ${customRegions} 处分区")
        }
        return parts.joinToString(" · ")
    }

    fun wrapContext(base: Context): Context {
        return try {
            val ignoreSysFont = ignoreSystemFontScale()
            val globalPct = globalScalePct()
            val fontPct = fontScalePct()
            if (!ignoreSysFont && globalPct == 100 && fontPct == 100) {
                return base
            }
            val sysConfig = Resources.getSystem().configuration
            val sysMetrics = Resources.getSystem().displayMetrics
            val baseDensityDpi = sysConfig.densityDpi.takeIf { it > 0 }
                ?: sysMetrics.densityDpi.takeIf { it > 0 }
                ?: base.resources.displayMetrics.densityDpi
            val sysFontScale = sysConfig.fontScale.takeIf { it > 0f } ?: 1.0f
            val baseFontScale = if (ignoreSysFont) 1.0f else sysFontScale

            val uiFactor = globalPct / 100f
            val fontFactor = fontPct / 100f

            val newConfig = Configuration(base.resources.configuration)
            newConfig.densityDpi = (baseDensityDpi * uiFactor).roundToInt().coerceIn(120, 960)
            newConfig.fontScale = (baseFontScale * fontFactor).coerceIn(0.5f, 2.0f)
            base.createConfigurationContext(newConfig)
        } catch (_: Throwable) {
            base
        }
    }
}
