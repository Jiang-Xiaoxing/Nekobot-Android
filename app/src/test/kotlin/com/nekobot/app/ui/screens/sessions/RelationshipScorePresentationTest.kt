package com.nekobot.app.ui.screens.sessions

import androidx.compose.ui.graphics.Color
import com.nekobot.app.data.local.ai.CompanionRelationshipPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipScorePresentationTest {
    private val ordinaryKeys = listOf("affection", "trust", "familiarity", "dependency", "security")

    @Test
    fun ordinaryDimensionsShareEveryLowAndMiddleTierIncludingBoundaries() {
        val tiers = listOf(0..19 to Color(0xFFB8C8E8), 20..39 to Color(0xFFA8D5B8),
            40..59 to Color(0xFFF2D49B), 60..79 to Color(0xFFF4A9B8))
        for (key in ordinaryKeys) for ((range, color) in tiers) for (score in range) {
            val actual = relationshipScorePresentation(key, score)
            assertEquals("$key at $score", color, actual.color)
            assertFalse("$key at $score", actual.showSparkle)
        }
    }

    @Test
    fun allFiveHighScoreColorsAndSparklesBeginAtEighty() {
        val colors = mapOf("affection" to Color(0xFFE5738F), "trust" to Color(0xFF6FA8DC),
            "familiarity" to Color(0xFFE8B84A), "dependency" to Color(0xFFF0A060), "security" to Color(0xFF9B7FD4))
        for ((key, color) in colors) for (score in 80..100) {
            val actual = relationshipScorePresentation(key, score)
            assertEquals("$key at $score", color, actual.color)
            assertTrue("$key at $score", actual.showSparkle)
        }
    }

    @Test
    fun jealousyUsesReverseTiersAndNeverShowsHighScoreSparkles() {
        val tiers = listOf(0..39 to Color(0xFFB8C8E8), 40..69 to Color(0xFFE8A0C8), 70..100 to Color(0xFFD9668A))
        for ((range, color) in tiers) for (score in range) {
            val actual = relationshipScorePresentation("jealousy", score)
            assertEquals("jealousy at $score", color, actual.color)
            assertFalse(actual.showSparkle)
        }
    }

    @Test
    fun approvedPreviewExamplesKeepTheirColorsScoresAndFillLengths() {
        val examples = listOf(Triple("affection", 88, Color(0xFFE5738F)), Triple("trust", 45, Color(0xFFF2D49B)),
            Triple("familiarity", 84, Color(0xFFE8B84A)), Triple("dependency", 30, Color(0xFFA8D5B8)),
            Triple("security", 66, Color(0xFFF4A9B8)), Triple("jealousy", 15, Color(0xFFB8C8E8)))
        assertEquals(CompanionRelationshipPolicy.dimensions.keys.toList(), examples.map { it.first })
        for ((key, score, color) in examples) {
            val actual = relationshipScorePresentation(key, score)
            assertEquals(score, actual.score)
            assertEquals(color, actual.color)
            assertEquals(score / 100f, actual.fraction, 0.00001f)
            assertEquals(key in listOf("affection", "familiarity"), actual.showSparkle)
        }
    }

    @Test
    fun missingScoresRemainUnknownAndOnlyShowTheEmptyTrack() {
        assertEquals(Color(0xFFE6DEF6), RelationshipScoreTrackColor)
        for (key in CompanionRelationshipPolicy.dimensions.keys) {
            val actual = relationshipScorePresentation(key, null)
            assertNull(actual.score)
            assertEquals(0f, actual.fraction, 0f)
            assertEquals(RelationshipScoreTrackColor, actual.color)
            assertFalse(actual.showSparkle)
        }
    }

    @Test
    fun fillAndDisplayedScoreStayWithinTheZeroToHundredRange() {
        for (key in CompanionRelationshipPolicy.dimensions.keys) {
            val low = relationshipScorePresentation(key, Int.MIN_VALUE)
            val zero = relationshipScorePresentation(key, 0)
            val high = relationshipScorePresentation(key, Int.MAX_VALUE)
            val full = relationshipScorePresentation(key, 100)
            assertEquals(zero, low)
            assertEquals(full, high)
            assertEquals(0f, low.fraction, 0f)
            assertEquals(1f, high.fraction, 0f)
        }
    }
}
