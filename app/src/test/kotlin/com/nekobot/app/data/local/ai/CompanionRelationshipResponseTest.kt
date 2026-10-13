package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionRelationshipResponseTest {
    private val row = LocalMessageEntity("u", "s", "user", "我已兑现承诺", timestamp = "0", createdAt = "2026-10-13T00:00:00Z")
    private val rows = mapOf("u" to row)
    private fun event(key: String = "promise", sources: String = "[\"u\"]", kind: String = "reliability") =
        """{"key":"$key","summary":"兑现约定","reason":"可靠行为","kind":"$kind","impacts":{"trust":"信任增加"},"sources":$sources}"""
    private fun root(events: String) = JsonParser.parseString("""{"format":"relationship-text-v1","events":[$events]}""").asJsonObject
    private fun parse(events: String) = CompanionRelationshipResponse.events(root(events), rows, emptyList(), "s", 2, 3)

    @Test fun explanationAndCodeFencesAroundJsonAreAccepted() {
        val content = "这是解释 {非 JSON}\n```json\n${root(event())}\n```\n评估完成"
        assertEquals(root(event()), CompanionRelationshipResponse.json(content))
    }
    @Test fun bracesAndEscapedQuotesInsideJsonStringsDoNotBreakExtraction() {
        val obj = root(event()); obj.addProperty("explanation", "含有 } 和 {，以及\"引号\"和\\反斜线")
        assertEquals(obj, CompanionRelationshipResponse.json("前言\n$obj\n后记"))
    }
    @Test fun missingOrMalformedJsonStillFails() {
        for (content in listOf("没有 JSON", "{\"format\":\"relationship-text-v1\"", "{\"format\":\"old\"}"))
            assertThrows(IllegalArgumentException::class.java) { CompanionRelationshipResponse.json(content) }
    }
    @Test fun badSourceOnlyDiscardsThatEvent() {
        val result = parse(event("bad", "[\"foreign\"]") + "," + event())
        assertEquals(listOf("promise"), result.accepted.map { it.eventKey })
        assertEquals(setOf("bad"), result.discardedKeys)
    }
    @Test fun malformedItemsAndUnknownKindsDoNotDiscardValidNeighbors() {
        val result = parse("null,42,{}," + event("bad", kind = "unknown") + "," + event())
        assertEquals(listOf("promise"), result.accepted.map { it.eventKey })
    }
    @Test fun rejectedItemDoesNotReserveItsKeyOrSources() {
        val result = parse(event("promise", kind = "bad") + "," + event())
        assertEquals(1, result.accepted.size)
        assertTrue(result.discardedKeys.isEmpty())
    }
    @Test fun duplicateKeyAndSourceSetOnlyDiscardTheDuplicate() {
        val result = parse(event() + "," + event() + "," + event("duplicate-source"))
        assertEquals(1, result.accepted.size)
        assertEquals(setOf("duplicate-source"), result.discardedKeys)
    }
    @Test fun discardedEventDependencyKeepsOldDimensionWhileValidDimensionUpdates() {
        val json = root(event("bad", "[\"foreign\"]"))
        json.add("relationships", JsonParser.parseString("""{
            "affection":{"action":"update","description":"相处更亲近","reason":"原话表达亲近","sources":["u"],"event_keys":[]},
            "trust":{"action":"update","description":"新信任","reason":"坏事件","sources":["u"],"event_keys":["bad"]},
            "familiarity":{"action":"keep"},"dependency":{"action":"keep"},"security":{"action":"keep"},"jealousy":{"action":"keep"}}
        """))
        json.add("feelings", com.google.gson.JsonNull.INSTANCE)
        val prior = RelationshipDescription("旧信任", "旧依据", listOf(RelationshipSource("u", CompanionRelationshipPolicy.hash(row))))
        val previous = RelationshipNarrative(relationships = CompanionRelationshipPolicy.dimensions.mapValues { if (it.key == "trust") prior else null }, feelings = null, assessedAt = 1)
        val events = CompanionRelationshipResponse.events(json, rows, emptyList(), "s", 2, 3)
        val result = CompanionRelationshipNarrative.parse(json, previous, rows, rows, emptyList(), events.accepted,
            setOf("u"), row.createdAt, 3, discardedEventKeys = events.discardedKeys)
        assertEquals(prior, result.relationships["trust"])
        assertEquals("相处更亲近", result.relationships["affection"]?.description)
    }
    @Test fun correctionCannotAcceptAnUnrelatedEvent() {
        val result = CompanionRelationshipResponse.events(root(event()), rows, emptyList(), "s", 2, 3, "target")
        assertTrue(result.accepted.isEmpty())
    }
}
