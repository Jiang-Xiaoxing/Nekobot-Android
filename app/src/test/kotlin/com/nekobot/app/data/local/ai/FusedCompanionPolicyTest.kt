package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class FusedCompanionPolicyTest {
    @org.junit.Test fun fusedToolLoopNeverHardTrimsConversation() {
        val messages = mutableListOf(mutableMapOf<String, Any>("role" to "user", "content" to "原话".repeat(5000)))
        val before = messages.map { it.toMap() }
        try { manageInLoopContextBudget(messages, 512, preserveConversation=true); org.junit.Assert.fail() }
        catch (_: IllegalArgumentException) { org.junit.Assert.assertEquals(before, messages) }
    }
    @org.junit.Test fun companionShellDoesNotBindHostProcessFilesystem() {
        val args = buildLocalProotPrefix(java.io.File("/bin/proot"),java.io.File("/rootfs"),java.io.File("/workspace"),hostProcAccess=false)
        org.junit.Assert.assertFalse(args.contains("/proc"))
        org.junit.Assert.assertTrue(args.contains("${java.io.File("/workspace").absolutePath}:/workspace"))
    }
    private fun row(id: Int, role: String, text: String) = LocalMessageEntity(
        id = "$id", sessionId = "s", role = role, content = text, timestamp = "2026-10-08T12:00:00Z", createdAt = "2026-10-08T12:00:00Z")
    @Test fun recent_keepsWholeTurnsAndNeverCutsLongMessage() {
        val old = listOf(row(1,"user","旧问题"), row(2,"assistant","旧回复"))
        val recent = listOf(row(3,"user","当前问题"), row(4,"assistant","当前回复"))
        assertEquals(recent, FusedCompanionPolicy.recent(old + recent, FusedCompanionPolicy.tokens(recent)))
        assertTrue(FusedCompanionPolicy.recent(listOf(row(1,"user","长".repeat(1000)), row(2,"assistant","答")), 100).isEmpty())
    }
    @Test fun budgetsScaleTogetherAfterFixedReservations() {
        assertEquals(8000, FusedCompanionPolicy.budgets(14000).recent)
        assertEquals(4000, FusedCompanionPolicy.budgets(7000).recent)
        assertEquals(500, FusedCompanionPolicy.budgets(7000).pinned)
        assertEquals(1000, FusedCompanionPolicy.budgets(7000).trigger)
    }
    @Test fun timeChangesOnlyWithPeriodDateOrTimezone() {
        val a = ZonedDateTime.parse("2026-10-08T20:00:01+08:00[Asia/Shanghai]")
        assertEquals(FusedCompanionPolicy.timeKey(a), FusedCompanionPolicy.timeKey(a.plusMinutes(10)))
        assertNotEquals(FusedCompanionPolicy.timeKey(a), FusedCompanionPolicy.timeKey(a.plusDays(1)))
        assertFalse(FusedCompanionPolicy.timeKey(a).contains("20:00"))
    }
    @Test fun allMemoryToolAliasesAreScopedAndOtherDefinitionsAreUntouched() {
        val original = (FusedCompanionTools.readNames + FusedCompanionTools.writeNames + "write_file")
            .map { mapOf<String,Any>("type" to "function", "function" to mapOf("name" to it, "description" to "original")) }
        val result = FusedCompanionTools.definitions(original)
        assertEquals(original.last(), result.last())
        assertTrue(result.dropLast(1).all { (it["function"] as Map<*, *>)["description"].toString().contains("当前") })
    }
}
