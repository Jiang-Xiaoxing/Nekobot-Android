package com.nekobot.app.data.local

import com.nekobot.app.R

/** Stable identifiers only: a background is never derived from the global theme colour. */
enum class RelationshipBackground(val id: String, @androidx.annotation.StringRes val titleRes: Int) {
    SAKURA("sakura", R.string.single_role_skin_sakura),
    STARLIGHT("starlight", R.string.single_role_skin_starlight),
    COAST("coast", R.string.single_role_skin_coast),
    WATER_GARDEN("watergarden", R.string.single_role_skin_watergarden),
    WOODLAND("woodland", R.string.single_role_skin_woodland),
    SUNSET("sunset", R.string.single_role_skin_sunset),
    LANTERN("lantern", R.string.single_role_skin_lantern);

    companion object {
        fun fromId(id: String?): RelationshipBackground = entries.firstOrNull { it.id == id } ?: COAST
    }
}
