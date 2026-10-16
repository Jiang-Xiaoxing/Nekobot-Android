package com.nekobot.app.data.local.ai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionRelationshipScoresTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private val row = LocalMessageEntity("u", "s", "user", "测试原话", timestamp = "0", createdAt = "2026-10-16T00:00:00Z")
    private fun field(score: Int = 50, text: String = "我愿意把心事交给他，但还会留一点。") =
        RelationshipDescription(text, "", emptyList(), score = score)
    private fun previous(score: Int = 50) = RelationshipNarrative(CompanionRelationshipScores.FORMAT,
        CompanionRelationshipPolicy.dimensions.mapValues { field(score) }, null, 1)
    private fun json(changes: String = "{}", feelings: String = "null"): JsonObject = JsonParser.parseString(
        """{"format":"relationship-score-v2","relationships":$changes,"feelings":$feelings}""").asJsonObject
    private fun parse(changes: String = "{}", previous: RelationshipNarrative? = previous()) =
        CompanionRelationshipScores.parse(json(changes), previous, null, listOf(row), 2)
    private fun all(score: Int) = CompanionRelationshipPolicy.dimensions.keys.joinToString(",", "{", "}") {
        """"$it":{"score":$score,"description":"我仍会按照自己的心意慢慢靠近他。"}"""
    }

    @Test fun positiveIsAtMostFive() = assertEquals(55, CompanionRelationshipScores.clamp("affection", 50, 99))
    @Test fun negativeIsAtMostEight() = assertEquals(42, CompanionRelationshipScores.clamp("security", 50, 0))
    @Test fun trustPositiveIsAtMostThree() = assertEquals(53, CompanionRelationshipScores.clamp("trust", 50, 100))
    @Test fun trustNegativeIsAtMostThree() = assertEquals(47, CompanionRelationshipScores.clamp("trust", 50, 0))
    @Test fun smallerChangesArePreserved() {
        assertEquals(52, CompanionRelationshipScores.clamp("dependency", 50, 52))
        assertEquals(48, CompanionRelationshipScores.clamp("dependency", 50, 48))
    }
    @Test fun eightyIsIncludedInPositiveDamping() = assertEquals(82, CompanionRelationshipScores.clamp("familiarity", 80, 100))
    @Test fun seventyNineDoesNotDamp() = assertEquals(84, CompanionRelationshipScores.clamp("familiarity", 79, 100))
    @Test fun dampedIntegerUsesFloorAfterClamping() {
        assertEquals(81, CompanionRelationshipScores.clamp("affection", 80, 83))
        assertEquals(80, CompanionRelationshipScores.clamp("affection", 80, 81))
        assertEquals(81, CompanionRelationshipScores.clamp("trust", 80, 100))
    }
    @Test fun highScoreDoesNotDampNegativeChanges() {
        assertEquals(82, CompanionRelationshipScores.clamp("affection", 90, 0))
        assertEquals(87, CompanionRelationshipScores.clamp("trust", 90, 0))
    }
    @Test fun scoresNeverExceedZeroAndOneHundred() {
        assertEquals(100, CompanionRelationshipScores.clamp("affection", 99, 1000))
        assertEquals(0, CompanionRelationshipScores.clamp("affection", 1, -1000))
        assertEquals(100, CompanionRelationshipScores.clamp("affection", 100, Int.MAX_VALUE))
        assertEquals(0, CompanionRelationshipScores.clamp("trust", 0, Int.MIN_VALUE))
    }
    @Test fun initialScoresComeFromModelWithoutStepLimit() {
        val result = parse(all(92), null)
        assertTrue(result.relationships.values.all { it?.score == 92 })
        assertEquals(100, CompanionRelationshipScores.clamp("trust", null, 500))
        assertEquals(0, CompanionRelationshipScores.clamp("trust", null, -500))
    }
    @Test fun omittedDimensionsKeepScoreAndSentenceVerbatim() {
        val old = previous()
        val result = parse("""{"trust":{"score":80,"description":"我又愿意多信他一点，仍记得旧顾虑。"}}""", old)
        assertEquals(53, result.relationships["trust"]?.score)
        for (key in CompanionRelationshipPolicy.dimensions.keys - "trust") assertSame(old.relationships[key], result.relationships[key])
    }
    @Test fun explicitNullAlsoKeepsDimensionVerbatim() = assertEquals(previous().relationships,
        parse("""{"jealousy":null}""").relationships)
    @Test fun emptyBatchOutputHasNoTimeDecay() {
        val old = previous(90)
        val result = CompanionRelationshipScores.parse(json(), old, null, emptyList(), Long.MAX_VALUE)
        assertEquals(old.relationships, result.relationships)
        assertEquals(old.feelings, result.feelings)
    }
    @Test fun missingInitialDimensionCannotAdvanceAnIncompleteResult() {
        assertThrows(IllegalArgumentException::class.java) { parse("{}", null) }
    }
    @Test fun scoreMustBeNumberAndIntegral() {
        for (score in listOf("50.1", "\"50\"", "null", "true", "1e50")) {
            assertThrows(IllegalArgumentException::class.java) { parse("""{"trust":{"score":$score,"description":"我还在犹豫。"}}""") }
        }
    }
    @Test fun malformedOutputAndUnknownDimensionFailWithoutChangingPrevious() {
        val old = previous()
        assertThrows(IllegalArgumentException::class.java) { parse("""{"unknown":{"score":50,"description":"我还在犹豫。"}}""", old) }
        assertThrows(IllegalArgumentException::class.java) { parse("""{"trust":{"score":50}}""", old) }
        assertEquals(50, old.relationships["trust"]?.score)
    }
    @Test fun outputNeedsNoReasonsQuotesOrEvents() {
        val result = parse("""{"affection":{"score":54,"description":"我觉得和他待在一起越来越自然了。"}}""")
        assertEquals(54, result.relationships["affection"]?.score)
        assertTrue(result.relationships.getValue("affection")!!.sources.isEmpty())
        assertEquals("", result.relationships["affection"]?.reason)
        assertEquals(CompanionRelationshipScores.FORMAT, CompanionRelationshipResponse.json(gson.toJson(json()))["format"].asString)
    }
    @Test fun newFeelingsUseRealLastTurnAndNeedNoModelReferences() {
        val result = CompanionRelationshipScores.parse(json(feelings = """{"mood":"有一点期待","intensity":"轻微地在意着","energy":null}"""), previous(), null, listOf(row), 2)
        assertEquals(row.createdAt, result.feelings?.observedAt)
        assertEquals(listOf(RelationshipSource(row.id, CompanionRelationshipPolicy.hash(row))), result.feelings?.sources)
        assertEquals("", result.feelings?.reason)
        assertEquals(result, CompanionRelationshipNarrative.read(gson.toJson(result)))
    }
    @Test fun noNewFeelingsKeepOriginalObservationAndTime() {
        val feeling = RelationshipFeelings("期待", null, null, "旧的理由保留", listOf(RelationshipSource("u", "hash")), "2020-01-01")
        val old = previous().copy(feelings = feeling)
        assertSame(feeling, parse(previous = old).feelings)
    }
    @Test fun correctionKeepsUnrelatedDimensionsAndHistoricalFeelings() {
        val old = previous()
        assertThrows(IllegalArgumentException::class.java) {
            CompanionRelationshipScores.parse(json("""{"jealousy":{"score":70,"description":"我有些在意。"}}"""), old, null, emptyList(), 2, setOf("trust"))
        }
        assertEquals(old.relationships, CompanionRelationshipScores.parse(json(), old, null, emptyList(), 2, emptySet()).relationships)
    }
    @Test fun chatReferenceContainsScoresAndSentencesWithoutReasons() {
        val old = previous().copy(relationships = previous().relationships.mapValues { (_, f) -> f!!.copy(reason = "不要发送的长理由") })
        val prompt = CompanionRelationshipPrompt.build(old, null, emptyMap(), emptyList())
        assertTrue(prompt.contains("亲近 50：我愿意把心事"))
        assertFalse(prompt.contains("不要发送的长理由"))
        assertFalse(prompt.contains("判断依据"))
    }
}
