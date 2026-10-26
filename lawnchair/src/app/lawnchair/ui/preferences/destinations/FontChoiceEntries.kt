package app.lawnchair.ui.preferences.destinations

import android.content.Context
import app.lawnchair.font.FontCache
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import com.android.launcher3.R

fun fixedFontEntries(
    context: Context,
    systemLabel: String,
): List<ListPreferenceEntry<FontCache.Font>> = listOf(
    ListPreferenceEntry(
        value = FontCache.SystemFont("sans-serif"),
        label = { systemLabel },
    ),
    ListPreferenceEntry(
        value = FontCache.ResourceFont(context, R.font.clockopia, "Clockopia"),
        label = { "Clockopia" },
    ),
    ListPreferenceEntry(
        value = FontCache.ResourceFont(context, R.font.orbitron_regular, "Orbitron"),
        label = { "Orbitron" },
    ),
)
