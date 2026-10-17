package com.nekobot.app.data.local.ai

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionRelationshipQuoteReferenceTest {
    private val old = LocalMessageEntity("old", "s", "user", "以前有些担心", timestamp = "0", createdAt = "2026-10-10T00:00:00Z")
    private val latest = old.copy(id = "new", content = "今天兑现了约定", createdAt = "2026-10-16T00:00:00Z")
    private val all = listOf(old, latest).associateBy { it.id }
    private val evidence = mapOf(latest.id to latest)
    private fun refs(row: LocalMessageEntity) = listOf(RelationshipSource(row.id, CompanionRelationshipPolicy.hash(row)))
    private val prior = RelationshipDescription("愿意相信，但仍有顾虑", "先前经历", refs(old))
    private val previous = RelationshipNarrative(
        relationships = CompanionRelationshipPolicy.dimensions.mapValues { if (it.key == "trust") prior else null },
        feelings = RelationshipFeelings("担心", null, null, "先前感受", refs(old), old.createdAt), assessedAt = 1)
    private fun array(json: String) = JsonParser.parseString(json)
    private fun root(): JsonObject = JsonObject().apply {
        addProperty("format", CompanionRelationshipNarrative.FORMAT)
        add("relationships", JsonObject().also { fields ->
            CompanionRelationshipPolicy.dimensions.keys.forEach { key -> fields.add(key, array("""{"action":"keep"}""")) }
        })
        add("events", array("[]"))
        add("feelings", JsonNull.INSTANCE)
    }
    private fun update(json: JsonObject, key: String, sources: String) {
        json["relationships"].asJsonObject.add(key, array("""{
            "action":"update","description":"相处时更安心","reason":"这次兑现了承诺","sources":$sources,"event_keys":[]
        }"""))
    }
    private fun feeling(json: JsonObject, sources: String) {
        json.add("feelings", array("""{"mood":"释然","intensity":null,"energy":null,"reason":"末轮表达轻松","sources":$sources}"""))
    }
    private fun parse(json: JsonObject, priorState: RelationshipNarrative? = previous,
        issues: MutableList<Pair<String, String>> = mutableListOf()): RelationshipNarrative {
        val events = CompanionRelationshipResponse.events(json, evidence, emptyList(), "s", 2, 3,
            onQuoteIssue = { key, detail -> issues.add(key to detail) })
        return CompanionRelationshipNarrative.parse(json, priorState, evidence, all, emptyList(), events.accepted,
            setOf("new"), latest.createdAt, 3, discardedEventKeys = events.discardedKeys,
            onQuoteIssue = { key, detail -> issues.add(key to detail) })
    }

    @Test fun mixedReferencesKeepOnlyCanonicalVerifiedSourcesAndReportCounts() {
        val issues = mutableListOf<String>()
        val result = CompanionRelationshipNarrative.sources(array("""[" new ","new","foreign",null,{},true,["new"]]"""), evidence, issues::add)
        assertEquals(refs(latest), result)
        assertEquals(1, issues.size)
        assertTrue(issues.single().contains("格式无效=4，不在可用材料内=1，重复=1"))
    }

    @Test fun numericIdsMatchExactlyWithoutGuessingOrChangingDatabaseIds() {
        val row = latest.copy(id = "42")
        val rows = mapOf("42" to row)
        assertEquals(refs(row), CompanionRelationshipNarrative.sources(array("[42,\" 42 \",42.0]"), rows))
        assertTrue(CompanionRelationshipNarrative.sources(array("[42]"), evidence).isEmpty())
    }

    @Test fun whitespaceAliasesAreUniqueAndOriginalStoredIdAndHashSurvive() {
        val padded = latest.copy(id = " padded ")
        assertEquals(refs(padded), CompanionRelationshipNarrative.sources(array("[\"padded\"]"), mapOf(padded.id to padded)))
        val collision = padded.copy(id = "padded  ")
        assertTrue(CompanionRelationshipNarrative.sources(array("[\"padded\"]"), listOf(padded, collision).associateBy { it.id }).isEmpty())
    }

    @Test fun onlyFirstTwelveValidUniqueReferencesSurviveAndDeletedRowsAreExcluded() {
        val rows = (1..14).map { latest.copy(id = "m$it") }.associateBy { it.id }
        val input = array((listOf("\"foreign\"") + rows.keys.map { "\"$it\"" }).joinToString(",", "[", "]"))
        val issues = mutableListOf<String>()
        val result = CompanionRelationshipNarrative.sources(input, rows, issues::add)
        assertEquals(rows.keys.take(12), result.map { it.id })
        assertTrue(issues.single().contains("超过12条=2"))
        assertTrue(CompanionRelationshipNarrative.sources(array("[\"new\"]"), mapOf("new" to latest.copy(deleted = true))).isEmpty())
    }

    @Test fun badReferencesAcrossRelationshipsFeelingsAndEventsDoNotAbortBatch() {
        val json = root()
        update(json, "trust", """["old","other-session","原话id"]""")
        update(json, "affection", """["missing"," new ","new",{}]""")
        feeling(json, """["new","foreign"," new "]""")
        json.add("events", array("""[
            {"key":"bad","summary":"无依据事件","reason":"没有可用引用","kind":"other","impacts":{"trust":"不能采用"},"sources":["foreign"]},
            {"key":"promise","summary":"兑现承诺","reason":"实际行动","kind":"reliability","impacts":{"trust":"更愿意相信"},"sources":["foreign"," new ","new"]}
        ]"""))
        val issues = mutableListOf<Pair<String, String>>()
        val events = CompanionRelationshipResponse.events(json, evidence, emptyList(), "s", 2, 3)
        assertEquals(listOf("promise"), events.accepted.map { it.eventKey })
        assertEquals(setOf("bad"), events.discardedKeys)
        val result = parse(json, issues = issues)
        assertEquals(prior, result.relationships["trust"])
        assertEquals(refs(latest), result.relationships["affection"]?.sources)
        assertEquals("释然", result.feelings?.mood)
        assertEquals(latest.createdAt, result.feelings?.observedAt)
        assertEquals(refs(latest), result.feelings?.sources)
        assertTrue(issues.any { it.first == "trust" && it.second.contains("保留该项旧描述") })
        assertTrue(issues.any { it.first == "event#1" && it.second.contains("丢弃该事件") })
        assertEquals(3L, result.assessedAt)
    }

    @Test fun missingMalformedAndEmptySourcesPreserveOnlyUnsupportedItems() {
        for (sources in listOf("null", "[]", "{}", "\"new\"", "[null,true,{}]")) {
            val json = root()
            update(json, "trust", sources)
            update(json, "affection", "[\"new\"]")
            feeling(json, sources)
            val result = parse(json)
            assertEquals(prior, result.relationships["trust"])
            assertEquals(previous.feelings, result.feelings)
            assertNotNull(result.relationships["affection"])
            val withoutPrior = parse(json, priorState = null)
            assertNull(withoutPrior.relationships["trust"])
            assertNull(withoutPrior.feelings)
        }
        val json = root(); update(json, "trust", "[]")
        json["relationships"].asJsonObject["trust"].asJsonObject.remove("sources")
        assertEquals(prior, parse(json).relationships["trust"])
    }

    @Test fun filteringAwayLastTurnEvidencePreservesOldFeelingTimeAndValidRelationships() {
        val json = root(); update(json, "trust", "[\"new\"]"); feeling(json, "[\"old\",\"foreign\"]")
        val result = CompanionRelationshipNarrative.parse(json, previous, all, all, emptyList(), emptyList(),
            setOf("new"), latest.createdAt, 3)
        assertNotEquals(prior, result.relationships["trust"])
        assertEquals(previous.feelings, result.feelings)
        assertEquals(old.createdAt, result.feelings?.observedAt)
    }

    @Test fun correctionWithNoValidQuoteCannotSaveChangedEventAndOldDependentText() {
        val json = root(); update(json, "trust", "[\"foreign\"]")
        val eventJson = array("""{"events":[{"key":"target","summary":"兑现承诺","reason":"行动","kind":"reliability","impacts":{"trust":"可靠"},"sources":["new"]}]}""").asJsonObject
        val target = CompanionRelationshipResponse.events(eventJson, evidence, emptyList(), "s", 1, 1).accepted.single()
        assertThrows(IllegalArgumentException::class.java) {
            CompanionRelationshipNarrative.parse(json, previous, evidence, all, listOf(target), listOf(target.copy(revision = 2)),
                setOf("new"), latest.createdAt, 3, correctionTarget = target)
        }
    }

    @Test fun thinkingDraftsAreIgnoredAndNestedFinalJsonAndFencesStillWork() {
        val draft = root().apply { addProperty("draft", true) }
        val answer = root()
        for (tag in listOf("think", "thinking", "THINK")) {
            assertEquals(answer, CompanionRelationshipResponse.json("<$tag>$draft</$tag>\n```json\n{\"result\":$answer}\n```"))
            assertThrows(IllegalArgumentException::class.java) { CompanionRelationshipResponse.json("<$tag>$draft") }
        }
        answer.addProperty("note", "原话里写着 <think>，这是JSON文字")
        assertEquals(answer, CompanionRelationshipResponse.json(answer.toString()))
    }

    @Test fun promptDefinesActualBatchScopeAndDoesNotProvideFakeQuoteIds() {
        val rules = CompanionRelationshipNarrative.RULES
        assertTrue(rules.contains("只能逐字复制本次“原话”列表每条的id值"))
        assertTrue(rules.contains("相同id可以被不同关系或事件分别引用"))
        assertFalse(rules.contains("\"sources\":[\"原话id\"]"))
    }
}
