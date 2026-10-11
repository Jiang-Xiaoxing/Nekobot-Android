package com.nekobot.app.data.local.ai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.CompanionRelationshipEventEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionRelationshipNarrativeTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private val old = LocalMessageEntity("old", "s", "user", "那次我没有兑现承诺", timestamp = "old", createdAt = "2026-10-10T01:00:00Z")
    private val recent = old.copy(id = "new", content = "今天我已按约定完成", createdAt = "2026-10-11T02:00:00Z")
    private val rows = listOf(old, recent).associateBy { it.id }
    private fun refs(row: LocalMessageEntity) = listOf(RelationshipSource(row.id, CompanionRelationshipPolicy.hash(row)))
    private fun event(key: String = "promise", revision: Long = 1, row: LocalMessageEntity = old, impacts: String = "{\"trust\":\"失约留下顾虑\"}") =
        CompanionRelationshipEventEntity("$key-$revision", "s", key, revision, "失约", "约定未完成", "reliability", "{}", gson.toJson(refs(row)), 1, revision > 1, impacts)
    private fun previous() = RelationshipNarrative(relationships = CompanionRelationshipPolicy.dimensions.mapValues { (key, _) ->
        if (key == "trust") RelationshipDescription("愿意听解释，但仍担心再次失约", "之前约定未完成", refs(old), mapOf("promise" to 1)) else null
    }, feelings = RelationshipFeelings("有些失落", "谈起失约仍有些低落", null, "旧消息表达失望", refs(old), old.createdAt), assessedAt = 1)
    private fun json(): JsonObject = JsonParser.parseString("""{
        "format":"relationship-text-v1", "feelings":null,
        "relationships":{"affection":{"action":"keep"},"trust":{"action":"keep"},"familiarity":{"action":"keep"},"dependency":{"action":"keep"},"security":{"action":"keep"},"jealousy":{"action":"keep"}}, "events":[]
    }""").asJsonObject
    private fun update(root: JsonObject, key: String = "trust", text: String = "看到你这次做到，愿意再相信一点，但之前的顾虑还在", eventKeys: String = "[]") {
        root["relationships"].asJsonObject.add(key, JsonParser.parseString("""{"action":"update","description":"$text","reason":"新的兑现行为增加了可靠性，尚不能抵消此前失约","sources":["new"],"event_keys":$eventKeys}"""))
    }
    private fun parse(root: JsonObject, prior: RelationshipNarrative? = previous(), effects: List<CompanionRelationshipEventEntity> = emptyList(), target: CompanionRelationshipEventEntity? = null) =
        CompanionRelationshipNarrative.parse(root, prior, rows, rows, listOf(event()), effects, setOf("new"), recent.createdAt, 99, target)
    private fun legacy() = RelationshipLegacySnapshot("旧版记录", mapOf("trust" to "旧的固定信任描述"), mapOf("trust" to "愿意倾听"),
        CompanionRelationshipPolicy.dimensions.mapValues { 50L }, "旧心情", "旧精力", 0.35f, "旧原因", refs(old), old.createdAt, 1)

    @Test fun evaluatorWordsAreSavedVerbatimAndKeepPreservesLongTermConcern() {
        val root = json(); update(root)
        val result = parse(root)
        assertEquals("看到你这次做到，愿意再相信一点，但之前的顾虑还在", result.relationships["trust"]?.description)
        assertEquals(setOf("old", "new"), result.relationships.getValue("trust")!!.sources.map { it.id }.toSet())
        assertEquals(result.relationships, parse(json(), result).relationships)
    }

    @Test fun noNewFeelingKeepsOriginalObservationTimeWithoutRenewingIt() {
        val result = parse(json())
        assertEquals(previous().feelings, result.feelings)
        assertEquals(old.createdAt, result.feelings?.observedAt)
        assertEquals(99L, result.assessedAt)
        val prompt = CompanionRelationshipPrompt.build(result, null, rows, listOf(event()))
        assertTrue(prompt.contains("原话时间：${old.createdAt}"))
        assertTrue(prompt.contains("历史，不能直接当作此刻心情"))
        assertTrue(prompt.contains("临时安慰不自动抹去长期失信"))
    }

    @Test fun feelingsMustCiteLastTurnAndUseConversationTime() {
        val root = json()
        root.add("feelings", JsonParser.parseString("""{"mood":"释然了一些","intensity":"能轻松说话，仍偶尔犹豫","energy":null,"reason":"末轮表达释然","sources":["new"]}"""))
        val result = parse(root)
        assertEquals(recent.createdAt, result.feelings?.observedAt)
        assertNull(result.feelings?.energy)
        root["feelings"].asJsonObject.add("sources", JsonParser.parseString("[\"old\"]"))
        assertThrows(IllegalArgumentException::class.java) { parse(root) }
    }

    @Test fun percentagesAndUnknownMessageIdsAreRejected() {
        val root = json(); update(root, text = "35%")
        assertThrows(IllegalArgumentException::class.java) { parse(root) }
        update(root)
        root["relationships"].asJsonObject["trust"].asJsonObject.add("sources", JsonParser.parseString("[\"someone-elses-message\"]"))
        assertThrows(IllegalArgumentException::class.java) { parse(root) }
    }

    @Test fun editingEvidenceInvalidatesBothDisplayEligibilityAndChatReference() {
        val prior = previous()
        val editedRows = rows + (old.id to old.copy(content = "我提前和你商量了取消"))
        assertFalse(CompanionRelationshipNarrative.valid(prior.relationships.getValue("trust")!!, editedRows, listOf(event())))
        val prompt = CompanionRelationshipPrompt.build(prior, legacy(), editedRows, listOf(event()))
        assertFalse(prompt.contains("愿意听解释"))
        assertFalse(prompt.contains("旧的固定信任描述"))
        assertFalse(prompt.contains("有些失落"))
    }

    @Test fun eventRevisionChangeAlsoInvalidatesDerivedText() {
        val field = previous().relationships.getValue("trust")!!
        assertFalse(CompanionRelationshipNarrative.valid(field, rows, listOf(event(revision = 2))))
    }

    @Test fun correctionCannotChangeUnrelatedDimensionOrKeepAffectedJudgment() {
        val corrected = event(revision = 2, row = recent, impacts = "{}")
        assertThrows(IllegalArgumentException::class.java) { parse(json(), effects = listOf(corrected), target = event()) }
        val root = json(); update(root); update(root, key = "jealousy")
        assertThrows(IllegalArgumentException::class.java) { parse(root, effects = listOf(corrected), target = event()) }
    }

    @Test fun correctingOneEventRetainsOtherDimensionAndOldFeelingTime() {
        val familiar = RelationshipDescription("记得你的日常习惯", "一起相处的细节", refs(recent))
        val prior = previous().copy(relationships = previous().relationships + ("familiarity" to familiar))
        val corrected = event(revision = 2, row = recent, impacts = "{\"trust\":\"确认是提前商量取消\"}")
        val root = json(); update(root, text = "了解那次已商量取消，这件事不再引起顾虑", eventKeys = "[\"promise\"]")
        val result = parse(root, prior, listOf(corrected), event())
        assertEquals(familiar, result.relationships["familiarity"])
        assertEquals(prior.feelings, result.feelings)
        assertEquals(mapOf("promise" to 2L), result.relationships["trust"]?.eventRevisions)
        assertEquals(listOf("new"), result.relationships["trust"]?.sources?.map { it.id })
    }

    @Test fun withdrawnJudgmentDoesNotReappearFromLegacyFallback() {
        val root = json()
        root["relationships"].asJsonObject.add("trust", JsonParser.parseString("{\"action\":\"unknown\"}"))
        val result = parse(root, effects = listOf(event(revision = 2, row = recent, impacts = "{}")), target = event())
        assertTrue("trust" in result.withdrawnKeys)
        assertNull(result.relationships["trust"])
        assertFalse(CompanionRelationshipPrompt.build(result, legacy(), rows, emptyList()).contains("旧的固定信任描述"))
        assertEquals(result, CompanionRelationshipNarrative.read(gson.toJson(result)))
    }

    @Test fun ordinaryUnknownCannotEraseKnownRelationship() {
        val root = json()
        root["relationships"].asJsonObject.add("trust", JsonParser.parseString("{\"action\":\"unknown\"}"))
        assertThrows(IllegalArgumentException::class.java) { parse(root) }
    }

    @Test fun oldRecordsAreLabeledAndRawScoresAreNotInjected() {
        val prompt = CompanionRelationshipPrompt.build(null, legacy(), rows, emptyList())
        assertTrue(prompt.contains("旧版记录，未经新版核对"))
        assertTrue(prompt.contains("旧的固定信任描述"))
        assertFalse(prompt.contains("35%")); assertFalse(prompt.contains("50"))
    }

    @Test fun unknownFieldsRoundTripAndMalformedSnapshotIsRejected() {
        val result = parse(json(), prior = null)
        assertEquals(result, CompanionRelationshipNarrative.read(gson.toJson(result)))
        assertEquals(6, result.relationships.size)
        assertNull(CompanionRelationshipNarrative.read("{\"format\":\"relationship-text-v1\"}"))
    }
}
