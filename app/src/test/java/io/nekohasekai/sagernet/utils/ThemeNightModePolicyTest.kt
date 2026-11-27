package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeNightModePolicyTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun resolveNightModeEnabled_respectsAllModes() {
        // 0 = Follow System
        assertTrue(Theme.resolveNightModeEnabled(nightTheme = 0, systemNight = true, powerSaveMode = false))
        assertFalse(Theme.resolveNightModeEnabled(nightTheme = 0, systemNight = false, powerSaveMode = true))

        // 1 = Always Enable
        assertTrue(Theme.resolveNightModeEnabled(nightTheme = 1, systemNight = false, powerSaveMode = false))

        // 2 = Always Disable
        assertFalse(Theme.resolveNightModeEnabled(nightTheme = 2, systemNight = true, powerSaveMode = true))

        // 3 = Auto Battery (Power Save Mode)
        assertTrue(Theme.resolveNightModeEnabled(nightTheme = 3, systemNight = false, powerSaveMode = true))
        assertFalse(Theme.resolveNightModeEnabled(nightTheme = 3, systemNight = true, powerSaveMode = false))
    }

    @Test
    fun resolveEffectiveThemeId_switchesNeutralThemesWithNightState() {
        assertEquals(Theme.BLACK, Theme.resolveEffectiveThemeId(Theme.LIGHT_GRAY, nightActive = true))
        assertEquals(Theme.BLACK, Theme.resolveEffectiveThemeId(Theme.WHITE, nightActive = true))
        assertEquals(Theme.BLACK, Theme.resolveEffectiveThemeId(Theme.BLACK, nightActive = true))

        assertEquals(Theme.LIGHT_GRAY, Theme.resolveEffectiveThemeId(Theme.BLACK, nightActive = false))
        assertEquals(Theme.LIGHT_GRAY, Theme.resolveEffectiveThemeId(Theme.LIGHT_GRAY, nightActive = false))
        assertEquals(Theme.WHITE, Theme.resolveEffectiveThemeId(Theme.WHITE, nightActive = false))

        assertEquals(Theme.BLUE, Theme.resolveEffectiveThemeId(Theme.BLUE, nightActive = true))
        assertEquals(Theme.MONET, Theme.resolveEffectiveThemeId(Theme.MONET, nightActive = false))
    }

    @Test
    fun themeAndUiScaleManager_doNotMaskSystemNightMode() {
        val themeSrc = sourceFile("src/main/java/io/nekohasekai/sagernet/utils/Theme.kt").readText()
        assertFalse(
            "Theme.kt must not hardcode appTheme == BLACK override in usingNightMode/applyNightTheme",
            themeSrc.contains("if (DataStore.appTheme == BLACK) return true") ||
                themeSrc.contains("AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)\n            return")
        )
        assertTrue(
            "Theme.isSystemNight must inspect Resources.getSystem() before App context",
            themeSrc.indexOf("Resources.getSystem().configuration.uiMode") <
                themeSrc.indexOf("context.applicationContext?.resources?.configuration?.uiMode")
        )

        val scaleSrc = sourceFile("src/main/java/io/nekohasekai/sagernet/utils/UiScaleManager.kt").readText()
        assertTrue(
            "UiScaleManager.wrapContext should construct empty Configuration() so uiMode stays unfrozen",
            scaleSrc.contains("val newConfig = Configuration()")
        )
        assertFalse(
            "UiScaleManager.wrapContext must not copy base.resources.configuration",
            scaleSrc.contains("Configuration(base.resources.configuration)")
        )
    }
}
