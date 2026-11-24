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
    @DrawableRes val islandIconRes: Int,
    @ColorInt val accentColor: Int,
) {
    NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.NekoBoxPlus",
        R.string.app_icon_dynamic,
        R.string.app_icon_dynamic_desc,
        R.mipmap.ic_launcher,
        R.drawable.ic_live_update_dark,
        Color.parseColor("#A855F7"),
    ),
    LIGHT_MODE(
        "io.nekohasekai.sagernet.launcher.LightMode",
        R.string.app_icon_light_mode,
        R.string.app_icon_light_mode_desc,
        R.mipmap.ic_launcher_light,
        R.drawable.ic_live_update_light,
        Color.parseColor("#9333EA"),
    ),
    DARK_MODE(
        "io.nekohasekai.sagernet.launcher.DarkMode",
        R.string.app_icon_dark_mode,
        R.string.app_icon_dark_mode_desc,
        R.mipmap.ic_launcher_dark,
        R.drawable.ic_live_update_dark,
        Color.parseColor("#A855F7"),
    ),
    OLD_NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.OldNekoBoxPlus",
        R.string.app_icon_old_nekobox_plus,
        R.string.app_icon_old_nekobox_plus_desc,
        R.mipmap.ic_launcher_old_nekobox_plus,
        R.mipmap.ic_launcher_old_nekobox_plus,
        Color.parseColor("#F43F5E"),
    ),
    NEKOBOX(
        "io.nekohasekai.sagernet.launcher.NekoBox",
        R.string.app_icon_nekobox,
        R.string.app_icon_nekobox_desc,
        R.mipmap.ic_launcher_nekobox,
        R.mipmap.ic_launcher_nekobox,
        Color.parseColor("#E11D48"),
    ),
    MIDNIGHT(
        "io.nekohasekai.sagernet.launcher.Midnight",
        R.string.app_icon_midnight,
        R.string.app_icon_midnight_desc,
        R.mipmap.ic_launcher_midnight,
        R.mipmap.ic_launcher_midnight,
        Color.parseColor("#38BDF8"),
    ),
    HEAVENS(
        "io.nekohasekai.sagernet.launcher.Heavens",
        R.string.app_icon_heavens,
        R.string.app_icon_heavens_desc,
        R.mipmap.ic_launcher_heavens,
        R.mipmap.ic_launcher_heavens,
        Color.parseColor("#0284C7"),
    ),
    BLACK_WHITE(
        "io.nekohasekai.sagernet.launcher.BlackWhite",
        R.string.app_icon_black_white,
        R.string.app_icon_black_white_desc,
        R.mipmap.ic_launcher_black_white,
        R.mipmap.ic_launcher_black_white,
        Color.parseColor("#64748B"),
    ),
    TEXT(
        "io.nekohasekai.sagernet.launcher.Text",
        R.string.app_icon_text,
        R.string.app_icon_text_desc,
        R.mipmap.ic_launcher_text,
        R.mipmap.ic_launcher_text,
        Color.parseColor("#6B7280"),
    );

    companion object {
        fun fromName(name: String?): AppIcon? = values().firstOrNull { it.name == name }
    }
}
