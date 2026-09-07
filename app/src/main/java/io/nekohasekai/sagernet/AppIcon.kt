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
    HALLOWEEN(
        "io.nekohasekai.sagernet.launcher.Halloween",
        R.string.app_icon_halloween,
        R.mipmap.ic_launcher_halloween,
    ),
    CYBERPUNK(
        "io.nekohasekai.sagernet.launcher.Cyberpunk",
        R.string.app_icon_cyberpunk,
        R.mipmap.ic_launcher_cyberpunk,
    ),
    BLACK_WHITE(
        "io.nekohasekai.sagernet.launcher.BlackWhite",
        R.string.app_icon_black_white,
        R.mipmap.ic_launcher_black_white,
    ),
    PINK(
        "io.nekohasekai.sagernet.launcher.Pink",
        R.string.app_icon_pink,
        R.mipmap.ic_launcher_pink,
    ),
    DRUID(
        "io.nekohasekai.sagernet.launcher.Druid",
        R.string.app_icon_druid,
        R.mipmap.ic_launcher_druid,
    ),
    RED(
        "io.nekohasekai.sagernet.launcher.Red",
        R.string.app_icon_red,
        R.mipmap.ic_launcher_red,
    ),
    RUSSIAN(
        "io.nekohasekai.sagernet.launcher.Russian",
        R.string.app_icon_russian,
        R.mipmap.ic_launcher_russian,
    ),
    TEXT(
        "io.nekohasekai.sagernet.launcher.Text",
        R.string.app_icon_text,
        R.mipmap.ic_launcher_text,
    ),
    HU_TAO(
        "io.nekohasekai.sagernet.launcher.HuTao",
        R.string.app_icon_hutao,
        R.mipmap.ic_launcher_hutao,
    ),
    FURINA(
        "io.nekohasekai.sagernet.launcher.Furina",
        R.string.app_icon_furina,
        R.mipmap.ic_launcher_furina,
    ),
    RAIDEN(
        "io.nekohasekai.sagernet.launcher.Raiden",
        R.string.app_icon_raiden,
        R.mipmap.ic_launcher_raiden,
    ),
    GANYU(
        "io.nekohasekai.sagernet.launcher.Ganyu",
        R.string.app_icon_ganyu,
        R.mipmap.ic_launcher_ganyu,
    );

}
