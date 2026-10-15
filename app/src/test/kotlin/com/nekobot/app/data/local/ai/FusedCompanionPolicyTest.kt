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
    @Test fun onlyChatSharesScaleAfterFixedReservations() {
        assertEquals(8000, FusedCompanionPolicy.budgets(14000).recent)
        assertEquals(5090, FusedCompanionPolicy.budgets(7000).recent)
        assertEquals(636, FusedCompanionPolicy.budgets(7000).pinned)
        assertEquals(2000, FusedCompanionPolicy.budgets(7000).trigger)
    }
    @Test fun requestClockHasMinutePrecisionAndExplicitTimezone() {
        val a = ZonedDateTime.parse("2026-10-08T20:00:01+08:00[Asia/Shanghai]")
        assertEquals("2026-10-08 20:00（Asia/Shanghai，UTC+08:00）", FusedCompanionPolicy.timeKey(a))
        assertEquals(FusedCompanionPolicy.timeKey(a), FusedCompanionPolicy.timeKey(a.plusSeconds(30)))
        assertNotEquals(FusedCompanionPolicy.timeKey(a), FusedCompanionPolicy.timeKey(a.plusMinutes(1)))
        assertTrue(FusedCompanionPolicy.timeKey(a.withHour(23).withMinute(59).plusMinutes(1)).startsWith("2026-10-09 00:00"))
        assertEquals("2026-10-08 12:00（UTC，UTC+00:00）", FusedCompanionPolicy.timeKey(a.withZoneSameInstant(java.time.ZoneId.of("UTC"))))
        val fold = ZonedDateTime.parse("2026-11-01T01:30:00-04:00[America/New_York]")
        assertNotEquals(FusedCompanionPolicy.timeKey(fold), FusedCompanionPolicy.timeKey(fold.plusHours(1)))
    }
    @Test fun companionEnvironmentKeepsStablePrefixWhileOrdinaryAgentKeepsItsClock() {
        val now = ZonedDateTime.parse("2026-10-08T20:00:01+08:00[Asia/Shanghai]")
        fun prompt(time: ZonedDateTime, clock: Boolean, lang: String): String = PromptStack().apply {
            addAgentEnvPrompt(time, "Test device", lang, includeClock = clock)
        }.getItems().single().content
        for (lang in listOf("zh", "zh-TW", "en", "ja", "ko")) {
            assertEquals(prompt(now, false, lang), prompt(now.plusDays(1), false, lang))
            assertFalse(prompt(now, false, lang).contains("2026-10-08"))
            assertTrue(prompt(now, true, lang).contains("2026-10-08 20:00:01"))
            assertTrue(prompt(now, false, lang).contains("get_date_time"))
            assertTrue(prompt(now, false, lang).contains("Test device"))
        }
    }
    @Test fun allowedMemoryToolsAreScopedAndUnrelatedPermittedToolsAreUntouched() {
        val original = (FusedCompanionTools.readNames + FusedCompanionTools.writeNames + "write_file" + "get_date_time")
            .map { mapOf<String,Any>("type" to "function", "function" to mapOf("name" to it, "description" to "original")) }
        val result = FusedCompanionTools.definitions(original)
        assertEquals(original.last(), result.last())
        assertEquals(setOf("agent_memory_read", "agent_memory_update", "get_date_time"),
            result.map { (it["function"] as Map<*, *>)["name"] }.toSet())
        assertTrue(result.dropLast(1).all { (it["function"] as Map<*, *>)["description"].toString().contains("当前") })
    }
}
