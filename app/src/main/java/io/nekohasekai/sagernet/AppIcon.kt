package io.nekohasekai.sagernet

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class AppIcon(
    val aliasClassName: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @DrawableRes val iconRes: Int,
    @DrawableRes val tileIconRes: Int,
    @DrawableRes val islandIconRes: Int,
    @ColorInt val accentColor: Int,
) {
    NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.NekoBoxPlus",
        R.string.app_icon_dynamic,
        R.string.app_icon_dynamic_desc,
        R.mipmap.ic_launcher,
        R.drawable.ic_throne_tile,
        R.drawable.ic_live_update_dark,
        Color.parseColor("#A855F7"),
    ),
    LIGHT_MODE(
        "io.nekohasekai.sagernet.launcher.LightMode",
        R.string.app_icon_light_mode,
        R.string.app_icon_light_mode_desc,
        R.mipmap.ic_launcher_light,
        R.drawable.ic_throne_tile,
        R.drawable.ic_live_update_light,
        Color.parseColor("#9333EA"),
    ),
    DARK_MODE(
        "io.nekohasekai.sagernet.launcher.DarkMode",
        R.string.app_icon_dark_mode,
        R.string.app_icon_dark_mode_desc,
        R.mipmap.ic_launcher_dark,
        R.drawable.ic_throne_tile,
        R.drawable.ic_live_update_dark,
        Color.parseColor("#A855F7"),
    ),
    TEXT(
        "io.nekohasekai.sagernet.launcher.Text",
        R.string.app_icon_text,
        R.string.app_icon_text_desc,
        R.mipmap.ic_launcher_text,
        R.drawable.ic_tile_ob_plus,
        R.drawable.ic_live_update_ob_plus,
        Color.parseColor("#94A3B8"),
    );

    companion object {
        fun fromName(name: String?): AppIcon? = values().firstOrNull { it.name == name }
    }
}
