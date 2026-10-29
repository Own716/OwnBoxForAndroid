package io.nekohasekai.sagernet.utils

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr

object Theme {

    const val MONET = 0
    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21
    const val VERDANT_MINT = 22
    const val WHITE = 23
    const val LIGHT_GRAY = 24
    const val CUSTOM = 99

    private fun defaultTheme() = LIGHT_GRAY

    fun getClosestThemeForColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val sat = hsv[1]
        val value = hsv[2]

        return when {
            sat < 0.18f && value >= 0.88f -> WHITE
            sat < 0.18f && value <= 0.25f -> BLACK
            else -> LIGHT_GRAY
        }
    }

    fun getSystemWallpaperColor(context: Context): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val colors = wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    val primary = colors?.primaryColor?.toArgb()
                    if (primary != null && primary != 0) {
                        return primary
                    }
                }
                val sysAccent = context.getColor(android.R.color.system_accent1_600)
                if (sysAccent != 0) return sysAccent
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun apply(context: Context) {
        context.setTheme(getTheme(context))
        if (!isWhiteTheme(context) && !isLightGrayTheme(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme && context is android.app.Activity) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme(context))
        if (!isWhiteTheme(context) && !isLightGrayTheme(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme && context is android.app.Activity) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun getTheme(context: Context = app): Int {
        if (usingNightMode(context)) {
            return R.style.Theme_SagerNet_Black
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                val closest = getClosestThemeForColor(wallpaperColor)
                if (closest == WHITE) R.style.Theme_SagerNet_White else getTheme(closest)
            } else {
                getTheme(MONET)
            }
        } else {
            getTheme(DataStore.appTheme)
        }
    }

    fun getDialogTheme(context: Context = app): Int {
        if (usingNightMode(context)) {
            return R.style.Theme_SagerNet_Dialog_Black
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                val closest = getClosestThemeForColor(wallpaperColor)
                if (closest == WHITE) R.style.Theme_SagerNet_Dialog_White else getDialogTheme(closest)
            } else {
                getDialogTheme(MONET)
            }
        } else {
            getDialogTheme(DataStore.appTheme)
        }
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            BLACK -> R.style.Theme_SagerNet_Black
            WHITE -> R.style.Theme_SagerNet_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_LightGray
            else -> {
                if (DataStore.appTheme !in setOf(BLACK, WHITE, LIGHT_GRAY)) {
                    DataStore.appTheme = LIGHT_GRAY
                }
                R.style.Theme_SagerNet_LightGray
            }
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            BLACK -> R.style.Theme_SagerNet_Dialog_Black
            WHITE -> R.style.Theme_SagerNet_Dialog_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_Dialog_LightGray
            else -> {
                if (DataStore.appTheme !in setOf(BLACK, WHITE, LIGHT_GRAY)) {
                    DataStore.appTheme = LIGHT_GRAY
                }
                R.style.Theme_SagerNet_Dialog_LightGray
            }
        }
    }

    fun isSystemNight(context: Context = app): Boolean {
        val ctxUiMode = (context as? android.app.Activity)?.resources?.configuration?.uiMode
            ?: context.resources?.configuration?.uiMode
        if (ctxUiMode != null && (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return (ctxUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val sysUiMode = android.content.res.Resources.getSystem().configuration.uiMode
        return (sysUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun isWhiteTheme(context: Context = app): Boolean = !usingNightMode(context) && DataStore.appTheme == WHITE
    fun isLightGrayTheme(context: Context = app): Boolean = !usingNightMode(context) && DataStore.appTheme == LIGHT_GRAY
    fun isBlackTheme(context: Context = app): Boolean = usingNightMode(context) || DataStore.appTheme == BLACK

    fun getPrimaryColor(context: Context): Int {
        if (usingNightMode(context) || isBlackTheme(context)) {
            return Color.WHITE
        }
        if (isWhiteTheme(context)) {
            return Color.parseColor("#212121")
        }
        if (isLightGrayTheme(context)) {
            return Color.parseColor("#1F2937")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                return wallpaperColor
            }
        }
        return context.getColorAttr(R.attr.colorPrimary)
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(context: Context = app): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> isSystemNight(context)
        }
    }

    fun applyNightTheme() {
        if (DataStore.nightTheme == 0) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else if (DataStore.nightTheme == 1 || DataStore.appTheme == BLACK) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else if (DataStore.nightTheme == 2) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

}