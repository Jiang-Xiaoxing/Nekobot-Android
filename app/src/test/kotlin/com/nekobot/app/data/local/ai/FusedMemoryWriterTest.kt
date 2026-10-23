package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** 执行同一个生产写入器，替换数据库传输与事务；真实SQL迁移由迁移测试验证。 */
class FusedMemoryWriterTest {
    private val scope = CompanionScopeEntity("scope", "test", "session", "fictional", 0, updatedAt = "now")
    private val user = LocalMessageEntity("user", "session", "user", "我下周三去面试，有点紧张哈哈", timestamp = "2026-10-23T10:00:00Z", createdAt = "2026-10-23T10:00:00Z")
    private val assistant = user.copy(id = "assistant", role = "assistant", content = "我会陪你准备")
    private fun memory(id: String = "old", manual: Boolean = false, locked: Boolean = false) = CompanionMemoryEntity(
        id, scope.id, id, 1, "fact", "", "我记得他在杭州上班", "active", manual = manual, locked = locked,
        category = "user_profile", createdAt = "2026-10-20", updatedAt = "2026-10-20")

    private inner class Store(initial: List<CompanionMemoryEntity> = emptyList()) {
        val memories = initial.associateBy { it.id }.toMutableMap()
        val sources = mutableMapOf<Pair<String, String>, CompanionSourceEntity>()
        val ops = mutableMapOf<String, CompanionAppliedOpEntity>()
        val reviews = mutableListOf<CompanionLockedReviewEntity>()
        val messages = listOf(user, assistant).associateBy { it.id }.toMutableMap()
        val logs = mutableListOf<String>()
        var revision = 0
        var failCommit = false
        @Suppress("UNCHECKED_CAST")
        val dao = Proxy.newProxyInstance(CompanionMemoryDao::class.java.classLoader, arrayOf(CompanionMemoryDao::class.java)) { _, method, args ->
            val a = args!!
            when (method.name) {
                "memory" -> memories[a[0]]
                "versions" -> memories.values.filter { it.scopeId == a[0] && it.factKey == a[1] }
                "sources" -> sources.values.filter { it.memoryId == a[0] }
                "appliedOp" -> ops[a[1]]
                "saveMemory" -> { val row = a[0] as CompanionMemoryEntity; memories[row.id] = row; Unit }
                "saveSources" -> { (a[0] as List<CompanionSourceEntity>).forEach { sources[it.memoryId to it.messageId] = it }; Unit }
                "voidMemory" -> { val row = memories.getValue(a[0] as String); memories[row.id] = row.copy(current = false,
                    voided = true, voidedReason = a[1] as String, voidedAt = a[2] as String, supersededById = a[3] as String?); Unit }
                "insertAppliedOp" -> { val row = a[0] as CompanionAppliedOpEntity; check(ops.putIfAbsent(row.opKey, row) == null); Unit }
                "insertLockedReview" -> { reviews += a[0] as CompanionLockedReviewEntity; Unit }
                "bumpRevision" -> { revision++; Unit }
                else -> error("未实现DAO调用 ${method.name}")
            }
        } as CompanionMemoryDao
        val writer = FusedMemoryWriter(dao, { block ->
            val m = memories.toMap(); val s = sources.toMap(); val o = ops.toMap(); val r = reviews.toList(); val v = revision
            try { val result = block(); if (failCommit) error("模拟提交失败"); result }
            catch (e: Exception) { memories.clear(); memories.putAll(m); sources.clear(); sources.putAll(s); ops.clear(); ops.putAll(o);
                reviews.clear(); reviews.addAll(r); revision = v; throw e }
        }, { messages[it] }, { true }, { level, text -> logs += "$level $text" })
        fun refs() = FusedMemoryReferences(listOf(user, assistant), memories.values.toList())
        suspend fun write(line: String, refs: FusedMemoryReferences = refs(), tool: Boolean = false) =
            writer.write(scope, FusedMemoryValidation.parseLine(line), refs, tool)
    }
    private fun add(content: String = "我记得他要去面试", evidence: String = "S1", extra: String = "") =
        """{"op":"add","category":"user_profile","content":"$content","evidence":["$evidence"],"date_type":"day"$extra}"""
    private fun update(target: String = "M1", content: String = "我记得他现在在上海上班", extra: String = "") =
        """{"op":"update","target":"$target","category":"user_profile","content":"$content","evidence":["S1"],"date_type":"day"$extra}"""

    @Test fun toolCannotReuseBackgroundNReferenceThroughIdempotency() = runBlocking {
        val db = Store(); val refs = db.refs()
        assertTrue(db.write(add(), refs).success)
        assertTrue(db.write(update("N1"), refs).success)
        assertEquals("unknown_ref", db.write(update("N1"), refs, tool = true).reason)
    }
    @Test fun conversionExpiryAppliesToNewMemoryButNeverExtendsExistingExpiry() = runBlocking {
        val db = Store()
        val expires = "2026-12-01T00:00:00Z"
        val created = db.writer.write(scope, FusedMemoryValidation.parseLine(add()), db.refs(), newMemoryExpiresAt = expires)
        assertEquals(expires, db.memories[created.memoryId]!!.expiresAt)
        val updated = db.writer.write(scope, FusedMemoryValidation.parseLine(update()), db.refs(), newMemoryExpiresAt = "2027-01-01T00:00:00Z")
        assertEquals(expires, db.memories[updated.memoryId]!!.expiresAt)
    }

    @Test fun retractingUncertainMemoryClearsPendingAndStillOffersLockedReview() = runBlocking {
        val original = memory(locked = true)
        val pending = memory("uncertain").copy(pendingConfirm = true, tagsJson = "[\"待确认\"]", uncertainTargetId = original.id)
        val db = Store(listOf(original, pending))
        assertTrue(db.write("""{"op":"retract","target":"M2","evidence":["S1"]}""").success)
        val confirmed = db.memories.getValue(pending.id)
        assertTrue(confirmed.voided); assertFalse(confirmed.pendingConfirm)
        assertFalse(MemoryTags.fromJson(confirmed.tagsJson).contains("待确认"))
        val review = db.reviews.single()
        assertFalse(CompanionMemoryInteractionPolicy.obsolete(review, original, confirmed))
        assertTrue(CompanionMemoryInteractionPolicy.obsolete(review, original, confirmed.copy(voidedReason = "user")))
        assertEquals(original, db.memories[original.id])
    }

    @Test fun invalidMiddleLineDoesNotRollBackOtherEntries() = runBlocking {
        val db = Store(); val refs = db.refs()
        val results = listOf(add("一"), add("二"), update("M99"), add("四"), add("五")).map { db.write(it, refs) }
        assertEquals(4, results.count { it.success }); assertEquals(4, db.memories.size)
        assertEquals("unknown_ref", results[2].reason); assertTrue(db.logs.any { "ref=M99" in it })
    }
    @Test fun noQuoteOrSemanticRegexIsNeeded() = runBlocking {
        val db = Store(); db.messages[user.id] = user.copy(content = "如果有空也许我会去面试，哈哈,好紧张")
        val result = db.write(add(), FusedMemoryReferences(listOf(db.messages.getValue(user.id))))
        assertTrue(result.success); assertEquals("", db.sources.values.single().quote)
        assertEquals(db.messages[user.id]!!.content, db.sources.values.single().messageSnapshot)
    }
    @Test fun invalidReferencesNeverBecomeAdds() = runBlocking {
        for (ref in listOf("M99", "杭州-1", "M3a", "memory_12", "old")) {
            val db = Store(listOf(memory())); assertEquals("unknown_ref", db.write(update(ref)).reason); assertEquals(1, db.memories.size)
        }
        assertEquals("reference_not_evidence", Store().write(add(evidence = "R1")).reason)
        assertEquals("unknown_ref", Store().write(add(evidence = "S7")).reason)
    }
    @Test fun anyAssistantEvidenceRejectsUserProfileButRelationshipAllowsIt() = runBlocking {
        val db = Store()
        assertEquals("user_profile_from_assistant", db.write(add(evidence = "S2")).reason)
        assertEquals("user_profile_from_assistant", db.write(add().replace("[\"S1\"]", "[\"S1\",\"S2\"]")).reason)
        assertTrue(db.write(add(evidence = "S2").replace("user_profile", "relationship")).success)
    }
    @Test fun sourceEditAndDeleteDuringCallRejectOnlyThatLine() = runBlocking {
        val db = Store(); val refs = db.refs(); db.messages[user.id] = user.copy(content = "新内容")
        assertEquals("source_changed", db.write(add(), refs).reason)
        db.messages.remove(user.id); assertEquals("source_changed", db.write(add(), refs).reason)
        assertTrue(db.memories.isEmpty())
    }
    @Test fun repeatedOutputIsIdempotentAndMappingIsPerCall() = runBlocking {
        val db = Store(); val first = db.write(add()); val second = db.write(add())
        assertEquals(first.memoryId, second.memoryId); assertTrue(second.skipped); assertEquals(1, db.memories.size)
        val a = FusedMemoryReferences(listOf(user)); val b = FusedMemoryReferences(listOf(assistant))
        assertNotEquals(a.sources["S1"]?.id, b.sources["S1"]?.id)
    }
    @Test fun failedAddReservesInvalidNAndSuccessfulAddCanBeUpdated() = runBlocking {
        val db = Store(); val refs = db.refs()
        db.write(add(evidence = "S9"), refs)
        assertEquals("unknown_ref", db.write(update("N1"), refs).reason)
        val added = db.write(add(), refs); assertEquals(added.memoryId, refs.memoryId("N2"))
        val updated = db.write(update("N2"), refs)
        assertEquals(2, db.memories[updated.memoryId]!!.version)
        assertEquals("update", db.memories[added.memoryId]!!.voidedReason)
    }
    @Test fun sameContentOnlyAddsSources() = runBlocking {
        val original = memory(); val db = Store(listOf(original))
        assertTrue(db.write(update(content = original.content)).success)
        assertEquals(1, db.memories.size); assertEquals(1, db.sources.size)
    }
    @Test fun updateCancelCompleteAndRetractKeepOldRows() = runBlocking {
        for (op in listOf("update", "cancel", "complete", "retract")) {
            val original = memory(); val db = Store(listOf(original))
            val result = db.write(update().replace("\"update\"", "\"$op\""))
            assertTrue(result.success); assertTrue(db.memories[original.id]!!.voided)
            assertEquals(op, db.memories[original.id]!!.voidedReason)
            assertEquals(if (op == "retract") 1 else 2, db.memories.size)
            assertFalse(CompanionReferences.current(db.memories.values.toList()).any { it.id == original.id })
        }
    }
    @Test fun protectedChangesCreateUncertaintyAndNeverModifyOriginal() = runBlocking {
        for (original in listOf(memory(locked = true), memory(manual = true))) {
            val db = Store(listOf(original)); val result = db.write(update())
            assertEquals(original, db.memories[original.id]); assertTrue(result.uncertain)
            val added = db.memories[result.memoryId]!!
            assertEquals(original.id, added.uncertainTargetId); assertTrue(added.pendingConfirm)
        }
        val db = Store(listOf(memory(manual = true)))
        val result = db.write("""{"op":"retract","target":"M1","evidence":["S1"]}""")
        assertTrue(db.memories[result.memoryId]!!.content.contains("好像有变化了"))
    }
    @Test fun confirmationVoidsUnlockedOriginalOrQueuesLockedReview() = runBlocking {
        for (locked in listOf(false, true)) {
            val original = memory(manual = true, locked = locked)
            val uncertain = memory("uncertain").copy(pendingConfirm = true, uncertainTargetId = original.id, tagsJson = "[\"待确认\"]")
            val db = Store(listOf(original, uncertain)); val result = db.write(update("M2"))
            assertFalse(db.memories[result.memoryId]!!.pendingConfirm)
            assertFalse("待确认" in db.memories[result.memoryId]!!.tagsJson)
            assertEquals(!locked, db.memories[original.id]!!.voided)
            assertEquals(if (locked) 1 else 0, db.reviews.size)
            if (!locked) assertEquals("uncertain_confirmed", db.memories[original.id]!!.voidedReason)
            else assertEquals(result.memoryId, db.reviews.single().confirmedMemoryId)
        }
    }
    @Test fun transactionFailureDoesNotPublishNOrEraseEarlierCommit() = runBlocking {
        val db = Store(); val refs = db.refs(); val first = db.write(add("先存的"), refs)
        db.failCommit = true
        try { db.write(add("失败的"), refs); fail() } catch (_: IllegalStateException) { }
        assertEquals(setOf(first.memoryId), db.memories.keys); assertNull(refs.memoryId("N2")); assertEquals(1, db.ops.size)
    }
    @Test fun toolAllowsOnlyCurrentUserAndUsesSameDateRules() = runBlocking {
        val db = Store()
        assertEquals("bad_format", db.write(add(evidence = "S2"), tool = true).reason)
        val result = db.write(add(extra = ""","time_text":"下周三","model_date":"2026-10-21""""), tool = true)
        assertEquals("2026-10-28", db.memories[result.memoryId]!!.eventDate)
        assertEquals("program", db.memories[result.memoryId]!!.dateSource)
    }
    @Test fun formatValidationRejectsRemovedOpsAndMalformedFields() {
        for (line in listOf("not json", "{'op':'add'}", "[]", add().replace("add", "merge"), add().replace("add", "clarify"),
            add().replace("\"day\"", "\"month\""), add().replace("[\"S1\"]", "[]"), add().replace("user_profile", "unknown"),
            add(extra = ",\"target\":\"M1\""), "{\"done\":false}")) {
            assertTrue(line, FusedMemoryValidation.parseLine(line) is FusedMemoryLine.Rejected)
        }
        assertEquals(FusedMemoryLine.Done, FusedMemoryValidation.parseLine("{\"done\":true}"))
    }
    @Test fun dateFallbackHabitAndEvidenceDayUseTheSharedPolicy() {
        val base = (FusedMemoryValidation.parseLine(add()) as FusedMemoryLine.Operation).value
        assertEquals("2026-10-23", FusedMemoryDate.resolve(base, listOf(user)).day.toString())
        assertEquals("spoken_day", FusedMemoryDate.resolve(base, listOf(user)).source)
        assertEquals("model", FusedMemoryDate.resolve(base.copy(timeText = "月底", modelDate = "2026-10-31"), listOf(user)).source)
        assertNull(FusedMemoryDate.resolve(base.copy(timeText = "过几天"), listOf(user)).day)
        assertNull(FusedMemoryDate.resolve(base.copy(dateType = "habit", modelDate = "2026-10-23"), listOf(user)).day)
        val earlier = user.copy(id = "earlier", content = "前天很冷", timestamp = "2026-10-20T10:00:00Z", createdAt = "2026-10-20T10:00:00Z")
        assertEquals("2026-10-18", FusedMemoryDate.resolve(base.copy(timeText = "前天"), listOf(user, earlier)).day.toString())
        assertTrue(FusedMemoryDate.resolve(base.copy(timeText = "月底"), listOf(user)).missingTimeEvidence)
    }
    @Test fun allProtectedQuestionLanguagesAndSixtyCoreRowsRemainAvailable() {
        assertEquals(5, listOf("zh", "zh-TW", "en", "ja", "ko").map { FusedMemoryWriter.protectedQuestion("旧事实", it) }.distinct().size)
        val rows = (1..60).map { memory("m$it").copy(alwaysProvide = true) }
        assertEquals(60, CompanionCoreMemory.rows(rows).size)
        assertEquals(59, CompanionCoreMemory.rows(rows.drop(1) + rows.first().copy(pendingConfirm = true)).size)
    }
}
