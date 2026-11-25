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
        Color.parseColor("#3B82F6"),
    ),
    LIGHT_MODE(
        "io.nekohasekai.sagernet.launcher.LightMode",
        R.string.app_icon_original_light,
        R.string.app_icon_original_light_desc,
        R.mipmap.ic_launcher_light,
        R.drawable.ic_throne_tile,
        R.drawable.ic_live_update_light,
        Color.parseColor("#2563EB"),
    ),
    DARK_MODE(
        "io.nekohasekai.sagernet.launcher.DarkMode",
        R.string.app_icon_original_dark,
        R.string.app_icon_original_dark_desc,
        R.mipmap.ic_launcher_dark,
        R.drawable.ic_throne_tile,
        R.drawable.ic_live_update_dark,
        Color.parseColor("#3B82F6"),
    ),
    PHANTOM_CAT(
        "io.nekohasekai.sagernet.launcher.PhantomCat",
        R.string.app_icon_phantom_cat,
        R.string.app_icon_phantom_cat_desc,
        R.mipmap.ic_launcher_phantom_cat,
        R.drawable.ic_tile_phantom_cat,
        R.drawable.ic_live_update_phantom_cat,
        Color.parseColor("#8B5CF6"),
    ),
    CAT_LIGHT(
        "io.nekohasekai.sagernet.launcher.CatLight",
        R.string.app_icon_cat_light,
        R.string.app_icon_cat_light_desc,
        R.mipmap.ic_launcher_cat_light,
        R.drawable.ic_tile_cat,
        R.drawable.ic_live_update_cat_light,
        Color.parseColor("#9333EA"),
    ),
    CAT_DARK(
        "io.nekohasekai.sagernet.launcher.CatDark",
        R.string.app_icon_cat_dark,
        R.string.app_icon_cat_dark_desc,
        R.mipmap.ic_launcher_cat_dark,
        R.drawable.ic_tile_cat,
        R.drawable.ic_live_update_cat_dark,
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
