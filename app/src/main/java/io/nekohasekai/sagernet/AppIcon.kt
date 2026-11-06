package io.nekohasekai.sagernet

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class AppIcon(
    val aliasClassName: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
) {
    NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.NekoBoxPlus",
        R.string.app_icon_dynamic,
        R.mipmap.ic_launcher,
    ),
    LIGHT_MODE(
        "io.nekohasekai.sagernet.launcher.LightMode",
        R.string.app_icon_light_mode,
        R.mipmap.ic_launcher_light,
    ),
    DARK_MODE(
        "io.nekohasekai.sagernet.launcher.DarkMode",
        R.string.app_icon_dark_mode,
        R.mipmap.ic_launcher_dark,
    ),
    OLD_NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.OldNekoBoxPlus",
        R.string.app_icon_old_nekobox_plus,
        R.mipmap.ic_launcher_old_nekobox_plus,
    ),
    NEKOBOX(
        "io.nekohasekai.sagernet.launcher.NekoBox",
        R.string.app_icon_nekobox,
        R.mipmap.ic_launcher_nekobox,
    ),
    MIDNIGHT(
        "io.nekohasekai.sagernet.launcher.Midnight",
        R.string.app_icon_midnight,
        R.mipmap.ic_launcher_midnight,
    ),
    HEAVENS(
        "io.nekohasekai.sagernet.launcher.Heavens",
        R.string.app_icon_heavens,
        R.mipmap.ic_launcher_heavens,
    ),
    BLACK_WHITE(
        "io.nekohasekai.sagernet.launcher.BlackWhite",
        R.string.app_icon_black_white,
        R.mipmap.ic_launcher_black_white,
    ),
    TEXT(
        "io.nekohasekai.sagernet.launcher.Text",
        R.string.app_icon_text,
        R.mipmap.ic_launcher_text,
    );

}
