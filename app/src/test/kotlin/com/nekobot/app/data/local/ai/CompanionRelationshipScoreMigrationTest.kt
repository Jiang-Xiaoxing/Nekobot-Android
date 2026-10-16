package com.nekobot.app.data.local.ai

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** JSON增量升级不改Room表；模拟旧备份与新旧混合状态，旧数值不冒充新分数。 */
class CompanionRelationshipScoreMigrationTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private fun legacyNarrative(): RelationshipNarrative {
        val text = "我还会留一点，不把旧顾虑忘掉。"
        val fields = CompanionRelationshipPolicy.dimensions.mapValues {
            RelationshipDescription(text, "旧理由必须留档", listOf(RelationshipSource("old", "hash")), mapOf("event" to 7L))
        }
        return RelationshipNarrative(relationships = fields, feelings = null, assessedAt = 123)
    }
    private fun proposals(keys: Set<String> = CompanionRelationshipPolicy.dimensions.keys) = JsonParser.parseString(
        keys.joinToString(",", "{\"format\":\"relationship-score-v2\",\"relationships\":{", "}}") {
            """"$it":{"score":86,"description":"我仍然愿意按自己的节奏信任他。"}"""
        }).asJsonObject

    @Test fun oldJsonWithoutScoreRemainsReadableWithoutInventedDefaults() {
        val json = JsonParser.parseString(gson.toJson(legacyNarrative())).asJsonObject
        json["relationships"].asJsonObject.entrySet().forEach { it.value.asJsonObject.remove("score") }
        val read = CompanionRelationshipNarrative.read(gson.toJson(json))!!
        assertTrue(read.relationships.values.all { it?.score == null })
        assertEquals(legacyNarrative(), read)
    }
    @Test fun upgradeInitializesMissingScoresFromModelAndPreservesOriginalJson() {
        val original = gson.toJson(legacyNarrative())
        val previous = CompanionRelationshipNarrative.read(original)!!
        val upgraded = CompanionRelationshipScores.parse(proposals(), previous, null, emptyList(), 999)
        assertTrue(upgraded.relationships.values.all { it?.score == 86 })
        assertEquals(original, gson.toJson(previous))
        assertEquals(123L, previous.assessedAt)
        assertEquals(mapOf("event" to 7L), previous.relationships["trust"]?.eventRevisions)
        assertEquals(upgraded, CompanionRelationshipNarrative.read(gson.toJson(upgraded)))
    }
    @Test fun previousInputCarriesOldSentenceVerbatimWithNullScore() {
        val old = legacyNarrative()
        val input = gson.toJsonTree(CompanionRelationshipScores.previous(old, null)).asJsonObject
        assertTrue(input["trust"].asJsonObject["score"].isJsonNull)
        assertEquals(old.relationships["trust"]?.description, input["trust"].asJsonObject["description"].asString)
        assertFalse(input.toString().contains("旧理由"))
    }
    @Test fun partialMigrationOnlyInitializesUnscoredDimensionsAndKeepsScoredSentence() {
        val old = legacyNarrative().copy(relationships = legacyNarrative().relationships +
            ("trust" to RelationshipDescription("我还是会把心事留一点。", "", emptyList(), score = 90)))
        val next = CompanionRelationshipScores.parse(proposals(CompanionRelationshipPolicy.dimensions.keys - "trust"), old, null, emptyList(), 999)
        assertSame(old.relationships["trust"], next.relationships["trust"])
        assertEquals(86, next.relationships["affection"]?.score)
    }
    @Test fun oldNumericTotalsStayHistoricalAndNeverBecomeCurrentScores() {
        val legacy = RelationshipLegacySnapshot("旧版记录", mapOf("trust" to "旧描述原样"), emptyMap(),
            CompanionRelationshipPolicy.dimensions.mapValues { 50000L }, "旧心情", "旧精力", .3f, "旧理由",
            listOf(RelationshipSource("old", "hash")), "2020-01-01", 1)
        val frozen = gson.toJson(legacy)
        val input = gson.toJsonTree(CompanionRelationshipScores.previous(null, legacy)).asJsonObject
        assertTrue(input["trust"].asJsonObject["score"].isJsonNull)
        assertEquals("旧描述原样", input["trust"].asJsonObject["description"].asString)
        val result = CompanionRelationshipScores.parse(proposals(), null, legacy, emptyList(), 999)
        assertEquals(86, result.relationships["trust"]?.score)
        assertEquals("旧心情", result.feelings?.mood)
        assertEquals("2020-01-01", result.feelings?.observedAt)
        assertEquals(frozen, gson.toJson(legacy))
    }
    @Test fun invalidUpgradeLeavesPriorValuesAndDescriptionReadable() {
        val original = gson.toJson(legacyNarrative())
        val old = CompanionRelationshipNarrative.read(original)!!
        val bad = proposals().apply { getAsJsonObject("relationships").remove("trust") }
        assertThrows(IllegalArgumentException::class.java) { CompanionRelationshipScores.parse(bad, old, null, emptyList(), 999) }
        assertEquals(original, gson.toJson(old))
        assertNotNull(CompanionRelationshipNarrative.read(original))
    }
    @Test fun newFormatCorruptionIsRejectedWithoutLosingLegacyReader() {
        val result = CompanionRelationshipScores.parse(proposals(), null, null, emptyList(), 999)
        val json = gson.toJsonTree(result).asJsonObject
        json["relationships"].asJsonObject["trust"].asJsonObject.addProperty("score", 101)
        assertNull(CompanionRelationshipNarrative.read(gson.toJson(json)))
        assertNotNull(CompanionRelationshipNarrative.read(gson.toJson(legacyNarrative())))
    }
}
