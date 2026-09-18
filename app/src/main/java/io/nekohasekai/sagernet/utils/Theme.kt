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
        context.setTheme(getTheme())
        if (!isWhiteTheme() && !isLightGrayTheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme && context is android.app.Activity) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
        if (!isWhiteTheme() && !isLightGrayTheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme && context is android.app.Activity) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(context)
        }
    }

    fun getTheme(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(app)
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

    fun getDialogTheme(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(app)
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

    fun isWhiteTheme(): Boolean = DataStore.appTheme == WHITE
    fun isLightGrayTheme(): Boolean = DataStore.appTheme == LIGHT_GRAY
    fun isBlackTheme(): Boolean = DataStore.appTheme == BLACK

    fun getPrimaryColor(context: Context): Int {
        if (isWhiteTheme()) {
            return Color.parseColor("#212121")
        }
        if (isLightGrayTheme()) {
            return Color.parseColor("#1F2937")
        }
        if (isBlackTheme()) {
            return Color.WHITE
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

    fun usingNightMode(): Boolean {
        if (isWhiteTheme() || isLightGrayTheme()) return false
        if (isBlackTheme()) return true
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        if (isWhiteTheme() || isLightGrayTheme()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            return
        }
        if (isBlackTheme()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            return
        }
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}