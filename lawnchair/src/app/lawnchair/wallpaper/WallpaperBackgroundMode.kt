package app.lawnchair.wallpaper

import androidx.annotation.StringRes
import com.android.launcher3.R

enum class WallpaperBackgroundMode(
    val value: String,
    @StringRes val labelRes: Int,
) {
    SYSTEM("system", R.string.wallpaper_background_system),
    SOLID_BLACK("solid_black", R.string.wallpaper_background_solid_black),
    CUSTOM_COLOR("custom_color", R.string.wallpaper_background_custom_color),
    ;

    override fun toString() = value

    companion object {
        fun fromString(value: String): WallpaperBackgroundMode =
            entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}
