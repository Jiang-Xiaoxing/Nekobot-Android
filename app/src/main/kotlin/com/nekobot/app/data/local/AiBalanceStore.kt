package com.nekobot.app.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.ai.AiBalanceRecord
import java.io.File

/**
 * AI 模型余额查询记录的持久化存储。
 *
 * 使用按数据库 Profile 隔离的 JSON 文件，而不是 Room 表：
 * 余额记录属于轻量查询日志，不需要迁移，也避免与其它正在开发中的
 * 数据库迁移版本互相占用版本号。写入采用临时文件 + 重命名，避免半截文件。
 *
 * 记录按创建时间倒序保存，最多保留 [MAX_RECORDS] 条。
 */
internal class AiBalanceStore private constructor(private val file: File?) {

    private val lock = Any()
    private val memory = mutableListOf<AiBalanceRecord>()

    fun list(): List<AiBalanceRecord> = synchronized(lock) {
        if (file == null) return memory.toList()
        read().sortedByDescending { it.createdAt }
    }

    fun append(record: AiBalanceRecord): List<AiBalanceRecord> = synchronized(lock) {
        val records = ArrayList(list())
        records.add(0, record)
        persist(records.take(MAX_RECORDS))
        records.take(MAX_RECORDS)
    }

    fun delete(id: String): List<AiBalanceRecord> = synchronized(lock) {
        val remaining = list().filterNot { it.id == id }
        persist(remaining)
        remaining
    }

    fun clear() = synchronized(lock) {
        persist(emptyList())
        Unit
    }

    private fun read(): List<AiBalanceRecord> {
        val target = file ?: return memory.toList()
        if (!target.isFile) return emptyList()
        return runCatching {
            val json = target.readText()
            if (json.isBlank()) emptyList()
            else GSON.fromJson<List<AiBalanceRecord>>(json, LIST_TYPE)
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    private fun persist(records: List<AiBalanceRecord>) {
        val target = file
        if (target == null) {
            memory.clear()
            memory.addAll(records)
            return
        }
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(GSON.toJson(records))
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    companion object {
        private const val MAX_RECORDS = 100
        private val GSON = Gson()
        private val LIST_TYPE = object : TypeToken<List<AiBalanceRecord>>() {}.type

        fun create(context: Context?, dbName: String): AiBalanceStore {
            if (context == null) return AiBalanceStore(null)
            val profile = dbName.removeSuffix(".db").ifBlank { "default" }
            return AiBalanceStore(File(context.filesDir, "ai_balance_$profile.json"))
        }
    }
}
