package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class ModelCapabilityReportStore(root: File, profile: String) {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val directory = File(root, hash(profile))
    private val gson = Gson()
    fun read(modelId: String): ModelCapabilityReport? = runCatching {
        val file = File(directory, hash(modelId) + ".json")
        if (!file.isFile || file.length() > 64 * 1024) return@runCatching null
        gson.fromJson(file.readText(Charsets.UTF_8), ModelCapabilityReport::class.java)
            .takeIf { it.modelId == modelId && it.results.size == ModelCapability.entries.size }
    }.getOrNull()
    fun write(report: ModelCapabilityReport) {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, hash(report.modelId) + ".json")
        val temporary = File(directory, target.name + "." + java.util.UUID.randomUUID() + ".tmp")
        temporary.writeText(gson.toJson(report), Charsets.UTF_8)
        runCatching { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
