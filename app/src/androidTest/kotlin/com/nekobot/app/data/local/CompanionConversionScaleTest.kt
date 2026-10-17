package com.nekobot.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 外部真实资料专项。资料、输出及凭据不打入 APK，不在测试日志打印原话。 */
class CompanionConversionScaleTest {
    @Test fun convertExternalHistoryThroughConfiguredModel() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("conversion_scale") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.getExternalFilesDir(null), "conversion-scale")
        val config = JsonParser.parseString(File(root, "fixture.json").readText()).asJsonObject
        val profile = args.getString("scale_profile") ?: "conversion-scale-real"
        require(profile.startsWith("conversion-scale-"))
        val previousProfile = ServiceContainer.prefs.activeDbName
        val previousMode = ServiceContainer.prefs.appMode
        val gson = Gson()
        fun record(phase: String, values: Map<String, Any?> = emptyMap()) {
            val line = gson.toJson(values + mapOf("phase" to phase, "at" to System.currentTimeMillis()))
            File(root, "progress.jsonl").appendText(line + "\n")
            println("CONVERSION_SCALE $line")
        }
        try {
            ServiceContainer.switchAppMode(AppMode.LOCAL); ServiceContainer.switchLocalDb(profile)
            var db = NekobotDatabase.get(context, profile)
            val sourceId = config["session_id"].asString
            if (db.sessionDao().getById(sourceId) == null) {
                record("importing")
                File(root, "source.zip").inputStream().use {
                    PortableDataArchiveManager(context).import(it, "", setOf(PortableDataCategory.CONVERSATIONS,
                        PortableDataCategory.CHARACTERS, PortableDataCategory.MEMORIES, PortableDataCategory.MEDIA))
                }
                record("imported")
            }
            // 导入会重建 ServiceContainer，重新获取当前数据库与仓库。
            db = NekobotDatabase.get(context, profile)
            val repo = ServiceContainer.localRepository
            val original = db.messageDao().listBySession(sourceId)
            assertEquals(config["messages"].asInt, original.size)
            val before = CompanionMemoryPolicy.digest(original.joinToString("\n") { CompanionMemoryPolicy.fingerprint(it) })
            val endpoint = args.getString("live_conversion_endpoint").orEmpty()
            require(endpoint.startsWith("http://127.0.0.1:"))
            repo.upsertAiModel(LocalAiModelEntity("scale-conversion-model", "全量转换测试", "openai_chat",
                apiKey = "private-local-relay", baseUrl = endpoint, model = "mimo-v2.5", maxTokens = 8000,
                maxContextLength = 65536, supportsTools = false, supportsStream = false, createdAt = "test"))
            repo.selectCompanionConversionModel("scale-conversion-model")
            val preview = repo.previewCompanionTransfer(sourceId)
            record("preview", mapOf("job" to preview.jobId, "messages" to preview.messages, "oldMemories" to preview.memories))
            val start = System.currentTimeMillis()
            val targetId = repo.convertCompanionTransfer(preview.jobId, "独立转换测试副本", "scale-conversion-model") {
                record(it.phase, mapOf("done" to it.processed, "total" to it.total))
            }
            val target = db.sessionDao().getById(targetId)!!
            val store = CompanionMemoryStore(db); val scope = store.ensureScope(target)
            val memories = store.validatedMemories(scope)
            val current = memories.filter { it.current }
            val dao = db.companionMemoryDao()
            File(root, "private-converted-memories.ndjson").bufferedWriter().use { out ->
                for (m in memories) out.appendLine(gson.toJson(mapOf("memory" to m, "sources" to dao.sources(m.id))))
            }
            File(root, "private-converted-messages.ndjson").bufferedWriter().use { out ->
                for (m in db.messageDao().listBySession(targetId)) out.appendLine(gson.toJson(m))
            }
            assertEquals(original.size, db.messageDao().listBySession(targetId).size)
            assertEquals(before, CompanionMemoryPolicy.digest(db.messageDao().listBySession(sourceId)
                .joinToString("\n") { CompanionMemoryPolicy.fingerprint(it) }))
            assertTrue(current.isNotEmpty())
            assertTrue(current.filterNot { it.manual }.all { m -> dao.sources(m.id).isNotEmpty() })
            val result = mapOf("messages" to original.size, "memories" to current.size, "allVersions" to memories.size,
                "categories" to current.groupingBy { it.category }.eachCount(), "elapsedMs" to System.currentTimeMillis()-start,
                "target" to targetId, "sourcePreserved" to true, "rules" to CompanionConversionRules.version)
            File(root, "result.json").writeText(gson.toJson(result))
            record("complete", result)
        } catch (failure: Exception) {
            // 异常详情可能含原句，只写私有测试目录，终端只报告异常类型。
            File(root, "private-error.txt").writeText(failure.stackTraceToString())
            record("failed", mapOf("type" to failure.javaClass.simpleName))
            throw AssertionError("全量转换未通过，请检查私有测试记录")
        } finally {
            ServiceContainer.switchLocalDb(previousProfile); ServiceContainer.switchAppMode(previousMode)
        }
    }
}
