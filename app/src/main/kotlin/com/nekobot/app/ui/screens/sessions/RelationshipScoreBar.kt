package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val RelationshipScoreTrackColor = Color(0xFFE6DEF6)

internal data class RelationshipScorePresentation(
    val score: Int?,
    val color: Color,
    val showSparkle: Boolean,
) {
    val fraction: Float get() = (score ?: 0) / 100f
}

/** 只控制显示；缺失的旧版分数不当作零分，也不回写评估结果。 */
internal fun relationshipScorePresentation(key: String, score: Int?): RelationshipScorePresentation {
    val value = score?.coerceIn(0, 100)
    val color = when {
        value == null -> RelationshipScoreTrackColor
        key == "jealousy" -> when {
            value < 40 -> Color(0xFFB8C8E8)
            value < 70 -> Color(0xFFE8A0C8)
            else -> Color(0xFFD9668A)
        }
        value < 20 -> Color(0xFFB8C8E8)
        value < 40 -> Color(0xFFA8D5B8)
        value < 60 -> Color(0xFFF2D49B)
        value < 80 -> Color(0xFFF4A9B8)
        else -> when (key) {
            "affection" -> Color(0xFFE5738F)
            "trust" -> Color(0xFF6FA8DC)
            "familiarity" -> Color(0xFFE8B84A)
            "dependency" -> Color(0xFFF0A060)
            "security" -> Color(0xFF9B7FD4)
            else -> error("Unknown relationship dimension: $key")
        }
    }
    return RelationshipScorePresentation(value, color, value != null && value >= 80 && key != "jealousy")
}

@Composable
internal fun RelationshipScoreBar(presentation: RelationshipScorePresentation) {
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(RelationshipScoreTrackColor, cornerRadius = radius)
        if (presentation.fraction > 0f) {
            drawRoundRect(presentation.color, size = Size(size.width * presentation.fraction, size.height),
                cornerRadius = radius)
        }
    }
}
