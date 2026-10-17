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
        val bad = proposals().apply { getAsJsonObject("relationships").getAsJsonObject("trust").addProperty("score", "不是分数") }
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

    @Test fun oldFirstEvaluationWithOmittedJealousyKeepsOtherModelScoresAndOldText() {
        val old = legacyNarrative()
        val original = gson.toJson(old)
        val missing = mutableListOf<String>()
        val result = CompanionRelationshipScores.parse(proposals(CompanionRelationshipPolicy.dimensions.keys - "jealousy"),
            old, null, emptyList(), 999, initialScores = mapOf("jealousy" to 17), onMissingScore = { missing += it })
        assertEquals(17, result.relationships["jealousy"]?.score)
        assertEquals(old.relationships["jealousy"]?.description, result.relationships["jealousy"]?.description)
        for (key in CompanionRelationshipPolicy.dimensions.keys - "jealousy") assertEquals(86, result.relationships[key]?.score)
        assertEquals(listOf("jealousy"), missing)
        assertEquals(original, gson.toJson(old))
        assertEquals(result, CompanionRelationshipNarrative.read(gson.toJson(result)))
    }

    @Test fun oldFirstEvaluationWithNullDimensionUsesCharacterCardInsteadOfFailing() {
        val json = proposals().apply { getAsJsonObject("relationships").add("trust", com.google.gson.JsonNull.INSTANCE) }
        val old = legacyNarrative()
        val result = CompanionRelationshipScores.parse(json, old, null, emptyList(), 999,
            initialScores = mapOf("trust" to 23))
        assertEquals(23, result.relationships["trust"]?.score)
        assertEquals(old.relationships["trust"]?.description, result.relationships["trust"]?.description)
        assertEquals(86, result.relationships["security"]?.score)
    }

    @Test fun missingOrNullScoreWithSentenceOnlyDoesNotFailAnInitialEvaluation() {
        for (nullScore in listOf(false, true)) {
            val json = proposals()
            val obj = json.getAsJsonObject("relationships").getAsJsonObject("dependency")
            if (nullScore) obj.add("score", com.google.gson.JsonNull.INSTANCE) else obj.remove("score")
            val result = CompanionRelationshipScores.parse(json, legacyNarrative(), null, emptyList(), 999,
                initialScores = mapOf("dependency" to 12))
            assertEquals(12, result.relationships["dependency"]?.score)
            assertEquals(obj["description"].asString, result.relationships["dependency"]?.description)
            assertEquals(86, result.relationships["trust"]?.score)
        }
    }

    @Test fun scoredDimensionWithMissingScoreKeepsEntirePriorRecord() {
        val field = RelationshipDescription("我仍然记得那些兑现的承诺。", "", emptyList(), score = 83)
        val old = legacyNarrative().copy(relationships = legacyNarrative().relationships + ("trust" to field))
        for (nullScore in listOf(false, true)) {
            val json = proposals()
            val obj = json.getAsJsonObject("relationships").getAsJsonObject("trust")
            if (nullScore) obj.add("score", com.google.gson.JsonNull.INSTANCE) else obj.remove("score")
            val result = CompanionRelationshipScores.parse(json, old, null, emptyList(), 999)
            assertSame(field, result.relationships["trust"])
            assertEquals(86, result.relationships["affection"]?.score)
        }
    }

    @Test fun missingScoreAndMissingSentenceRetainOldWordsDuringInitialization() {
        val json = proposals()
        json.getAsJsonObject("relationships").add("trust", com.google.gson.JsonObject())
        val old = legacyNarrative()
        val result = CompanionRelationshipScores.parse(json, old, null, emptyList(), 999)
        assertEquals(50, result.relationships["trust"]?.score)
        assertEquals(old.relationships["trust"]?.description, result.relationships["trust"]?.description)
    }

    @Test fun frozenNumericHistoryIsNeverUsedForMissingScoresAndRemainsUnchanged() {
        val legacy = RelationshipLegacySnapshot("旧版记录", mapOf("jealousy" to "此前保存的嫉妒描述"), emptyMap(),
            CompanionRelationshipPolicy.dimensions.mapValues { 50000L }, "尚未评估", "尚未评估", null, "", emptyList(), null, 1)
        val frozen = gson.toJson(legacy)
        val result = CompanionRelationshipScores.parse(proposals(emptySet()), null, legacy, emptyList(), 999,
            initialScores = mapOf("trust" to 24, "jealousy" to 9))
        assertEquals(24, result.relationships["trust"]?.score)
        assertEquals(9, result.relationships["jealousy"]?.score)
        assertEquals("此前保存的嫉妒描述", result.relationships["jealousy"]?.description)
        assertEquals(50, result.relationships["affection"]?.score)
        assertEquals(frozen, gson.toJson(legacy))
    }

    @Test fun fallbackCharacterCardScoresAreBoundedAndInitializationHasNoStepLimit() {
        val result = CompanionRelationshipScores.parse(proposals(emptySet()), legacyNarrative(), null, emptyList(), 999,
            initialScores = mapOf("trust" to 94, "jealousy" to -1, "affection" to 200))
        assertEquals(94, result.relationships["trust"]?.score)
        assertEquals(0, result.relationships["jealousy"]?.score)
        assertEquals(100, result.relationships["affection"]?.score)
    }

    @Test fun correctionDoesNotInitializeUnrelatedOldDimensions() {
        val old = legacyNarrative()
        val result = CompanionRelationshipScores.parse(proposals(setOf("trust")), old, null, emptyList(), 999, setOf("trust"))
        assertEquals(86, result.relationships["trust"]?.score)
        for (key in CompanionRelationshipPolicy.dimensions.keys - "trust") assertSame(old.relationships[key], result.relationships[key])
    }

    @Test fun initializationListRequiresEveryUnscoredDimensionButSkipsSavedScores() {
        val keys = CompanionRelationshipPolicy.dimensions.keys
        assertEquals(keys, CompanionRelationshipScores.initializationKeys(null))
        assertEquals(keys, CompanionRelationshipScores.initializationKeys(legacyNarrative()))
        val old = legacyNarrative().copy(relationships = legacyNarrative().relationships +
            ("trust" to RelationshipDescription("我愿意相信他。", "", emptyList(), score = 83)))
        assertEquals(keys - "trust", CompanionRelationshipScores.initializationKeys(old))
        val mixed = CompanionRelationshipScores.parse(proposals(keys - "trust"), old, null, emptyList(), 999)
        assertTrue(CompanionRelationshipScores.initializationKeys(mixed).isEmpty())
    }

    @Test fun omittedWithdrawnDimensionDoesNotResurrectItsFrozenOldWords() {
        val legacy = RelationshipLegacySnapshot("旧版记录", mapOf("trust" to "已撤回的旧判断"), emptyMap(),
            CompanionRelationshipPolicy.dimensions.mapValues { 0L }, "尚未评估", "尚未评估", null, "", emptyList(), null, 1)
        val old = legacyNarrative().copy(relationships = legacyNarrative().relationships + ("trust" to null), withdrawnKeys = setOf("trust"))
        val result = CompanionRelationshipScores.parse(proposals(emptySet()), old, legacy, emptyList(), 999)
        assertNotEquals("已撤回的旧判断", result.relationships["trust"]?.description)
        assertNotNull(CompanionRelationshipNarrative.read(gson.toJson(result)))
    }
}
