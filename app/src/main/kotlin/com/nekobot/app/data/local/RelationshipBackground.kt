package com.nekobot.app.data.local

/** Stable identifiers only: a background is never derived from the global theme colour. */
enum class RelationshipBackground(val id: String, val title: String) {
    SAKURA("sakura", "樱花来信"),
    STARLIGHT("starlight", "星夜山丘"),
    COAST("coast", "晴空海岸"),
    WATER_GARDEN("watergarden", "雨后水庭"),
    WOODLAND("woodland", "林间书屋"),
    SUNSET("sunset", "落日原野"),
    LANTERN("lantern", "冬日灯巷");

    companion object {
        fun fromId(id: String?): RelationshipBackground = entries.firstOrNull { it.id == id } ?: COAST
    }
}
