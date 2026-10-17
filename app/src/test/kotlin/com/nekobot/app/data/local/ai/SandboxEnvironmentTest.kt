package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SandboxEnvironmentTest {
    @Test
    fun `界面保存和运行读取使用同一个档案键且会话彼此隔离`() {
        assertEquals(SandboxEnvironment.storageKey("s", "profile"), SandboxEnvironment.storageKey("s", "profile.db"))
        assertNotEquals(SandboxEnvironment.storageKey("s", "profile"), SandboxEnvironment.storageKey("s", "other"))
        assertNotEquals(SandboxEnvironment.storageKey("s", "profile"), SandboxEnvironment.storageKey("t", "profile"))
        assertEquals(SandboxEnvironment.storageKey(null, "profile"), SandboxEnvironment.storageKey(null, "other"))
    }

    @Test
    fun `会话覆盖全局且空值不会被忽略`() {
        val global = mapOf("TOKEN" to "global", "URL" to "https://example.test")
        val a = SandboxEnvironment.merge(global, mapOf("TOKEN" to "session-a", "EMPTY" to ""))
        val b = SandboxEnvironment.merge(global, emptyMap())
        assertEquals("session-a", a["TOKEN"])
        assertEquals("", a["EMPTY"])
        assertEquals("global", b["TOKEN"])
        assertFalse(b.containsKey("EMPTY"))
        assertEquals("global", global["TOKEN"])
    }

    @Test
    fun `阻止非法名称和沙箱内部变量且允许常见代理变量`() {
        listOf("API_KEY", "https_proxy", "_TOKEN", "PATH").forEach { assertTrue(SandboxEnvironment.isValidName(it)) }
        listOf("", "2TOKEN", "A=B", "A B", "X;echo", "变量", "PROOT_TMP_DIR", "LD_LIBRARY_PATH", "NEKOBOT_SESSION_ID", "__nekobot_exit", "ENV")
            .forEach { assertFalse(it, SandboxEnvironment.isValidName(it)) }
        assertFalse(SandboxEnvironment.isValidValue("abc\u0000def"))
        assertTrue(SandboxEnvironment.isValidValue("a\nb"))
    }

    @Test
    fun `更新持久 shell 时转义值并撤销删除项`() {
        val updates = SandboxEnvironment.shellUpdates(
            previous = mapOf("TOKEN" to "old", "GONE" to "old", "PATH" to "/custom", "UNCHANGED" to "same"),
            current = mapOf("TOKEN" to "a'b\n\$(echo danger)", "UNCHANGED" to "same", "EMPTY" to ""),
            defaults = mapOf("PATH" to "/usr/bin:/bin"),
        )
        assertTrue(updates.contains("export TOKEN='a'\\''b\n\$(echo danger)'\n"))
        assertTrue(updates.contains("unset GONE\n"))
        assertTrue(updates.contains("export PATH=/usr/bin:/bin\n"))
        assertTrue(updates.contains("export EMPTY=''\n"))
        assertFalse(updates.contains("UNCHANGED"))
        assertTrue(updates.indexOf("set +x") < updates.indexOf("export TOKEN="))
        assertEquals("", SandboxEnvironment.shellUpdates(mapOf("KEY" to "same"), mapOf("KEY" to "same")))
    }

    @Test
    fun `请求拒绝重复或保留变量名`() {
        assertTrue(EnvironmentVariableRequestCodec.parse(mapOf("variables" to listOf(mapOf("name" to "API_KEY")))).isSuccess)
        assertTrue(EnvironmentVariableRequestCodec.parse(mapOf("variables" to listOf(mapOf("name" to "PROOT_LOADER")))).isFailure)
        assertTrue(EnvironmentVariableRequestCodec.parse(mapOf("variables" to listOf(mapOf("name" to "KEY"), mapOf("name" to "KEY")))).isFailure)
    }

    @Test
    fun `用户改名后保存最终变量且结果不含值`() = runBlocking {
        val manager = LocalAskUserQuestionManager()
        var request: AskUserQuestionRequest? = null
        var saved: List<AskUserQuestionAnswer> = emptyList()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            manager.requestAnswer(
                sessionId = "session-a",
                questions = emptyList(),
                onRequest = { request = it },
                environmentVariables = listOf(EnvironmentVariableField("SUGGESTED")),
                saveEnvironment = { saved = it },
            )
        }
        val id = checkNotNull(request).requestId
        val answer = AskUserQuestionAnswer("USER_TOKEN", "", emptyList(), "secret-123", "global")
        assertFalse(manager.resolve(id, "session-b", listOf(answer)))
        assertFalse(manager.resolve(id, "session-a", listOf(answer.copy(id = "A=B"))))
        assertTrue(manager.resolve(id, "session-a", listOf(answer)))
        val returned = result.await()
        assertEquals(listOf(answer), saved)
        assertEquals(listOf("USER_TOKEN"), returned["variable_names"])
        assertEquals("global", returned["scope"])
        assertEquals(listOf(mapOf("requested_name" to "SUGGESTED", "name" to "USER_TOKEN")), returned["variables"])
        assertFalse(Gson().toJson(returned).contains("secret-123"))
        assertFalse(manager.resolve(id, "session-a", listOf(answer)))
    }

    @Test
    fun `跳过停止与超时不会保存变量`() = runBlocking {
        for (mode in listOf("skip", "stop", "timeout")) {
            val manager = LocalAskUserQuestionManager(timeoutMs = 20)
            var request: AskUserQuestionRequest? = null
            var saved = false
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                manager.requestAnswer("s", emptyList(), { request = it },
                    environmentVariables = listOf(EnvironmentVariableField("TOKEN")),
                    saveEnvironment = { saved = true })
            }
            if (mode == "skip") assertTrue(manager.cancel(checkNotNull(request).requestId, "s"))
            if (mode == "stop") manager.cancelSession("s")
            assertEquals(true, result.await()["cancelled"])
            assertFalse(saved)
        }
    }
}
