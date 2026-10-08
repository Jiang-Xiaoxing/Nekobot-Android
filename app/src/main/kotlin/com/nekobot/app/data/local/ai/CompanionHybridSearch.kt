package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.*
import com.nekobot.app.data.local.knowledge.LocalKnowledgeSearch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class CompanionSearchItem(val type: String, val id: String, val text: String) {
    val cacheId get() = "$type:$id"
    val fingerprint get() = CompanionMemoryPolicy.digest(text)
}

internal data class CompanionEmbeddingBackend(
    val modelKey: String, val embed: suspend (List<String>) -> List<FloatArray>
)

/** Both collections share ONE query vector. No chat model/reranker or paid fallback is invoked. */
internal class CompanionHybridSearch(
    private val db: NekobotDatabase,
    private val backend: suspend (CompanionScopeEntity) -> CompanionEmbeddingBackend? = { configuredBackend(db, it) }
) {
    private val dao = db.companionMemoryDao()
    private val gson = Gson()

    /** Document work is scheduled only in the background for the fused mode. */
    suspend fun index(scope: CompanionScopeEntity, items: List<CompanionSearchItem>) {
        try {
            withTimeoutOrNull(30_000L) {
                mutexes.computeIfAbsent("${System.identityHashCode(db)}:${scope.id}") { Mutex() }.withLock {
                    val model = backend(scope) ?: return@withLock
                    if (!current(scope)) return@withLock
                    val saved = dao.vectors(scope.id, model.modelKey).associateBy { "${it.itemType}:${it.itemId}" }
                    val missing = items.filter { saved[it.cacheId]?.let { row ->
                        row.fingerprint == it.fingerprint && decode(row.vectorJson) != null
                    } != true }.take(32)
                    for (batch in missing.chunked(16)) {
                        val vectors = model.embed(batch.map { it.text })
                        require(vectors.size == batch.size && vectors.all(::validVector) && vectors.map { it.size }.distinct().size == 1)
                        db.withTransaction {
                            if (current(scope)) dao.saveVectors(batch.zip(vectors).map { (item, vector) ->
                                CompanionVectorEntity(scope.id, item.type, item.id, model.modelKey, item.fingerprint,
                                    gson.toJson(vector), Instant.now().toString())
                            })
                        }
                    }
                    if (current(scope)) dao.pruneVectors(scope.id, model.modelKey, items.map { it.cacheId })
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Rebuildable index: the next background pass retries missing rows. */ }
    }

    /** No document embedding or wait on the indexing mutex on the foreground path. */
    suspend fun cachedScores(scope: CompanionScopeEntity, query: String, items: List<CompanionSearchItem>): Map<String, Float> {
        if (query.isBlank() || items.isEmpty()) return emptyMap()
        return try {
            withTimeoutOrNull(1_500L) {
                val model = backend(scope) ?: return@withTimeoutOrNull emptyMap()
                val cached = dao.vectors(scope.id, model.modelKey).associateBy { "${it.itemType}:${it.itemId}" }
                if (cached.isEmpty() || !current(scope)) return@withTimeoutOrNull emptyMap()
                val vectors = model.embed(listOf(query.take(2_000)))
                require(vectors.size == 1 && validVector(vectors.single()))
                val q = vectors.single()
                if (!current(scope)) return@withTimeoutOrNull emptyMap()
                items.mapNotNull { item ->
                    val vector = cached[item.cacheId]?.takeIf { it.fingerprint == item.fingerprint }
                        ?.let { decode(it.vectorJson) }?.takeIf { it.size == q.size } ?: return@mapNotNull null
                    item.cacheId to LocalKnowledgeSearch.cosine(q, vector).coerceIn(0f, 1f)
                }.toMap()
            } ?: emptyMap()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { emptyMap() }
    }

    suspend fun scores(scope: CompanionScopeEntity, query: String, items: List<CompanionSearchItem>, completeInventory: Boolean = false): Map<String, Float> {
        if (items.isEmpty() || query.isBlank()) return emptyMap()
        return mutexes.computeIfAbsent("${System.identityHashCode(db)}:${scope.id}") { Mutex() }.withLock {
            try {
                withTimeoutOrNull(5_000L) {
                    val model = backend(scope) ?: return@withTimeoutOrNull emptyMap()
                    if (!current(scope)) return@withTimeoutOrNull emptyMap()
                    val cached = dao.vectors(scope.id, model.modelKey).associateBy { "${it.itemType}:${it.itemId}" }.toMutableMap()
                    // A large imported backlog is indexed incrementally, never all sent on every question.
                    val missing = items.filter {
                        cached[it.cacheId]?.let { row -> row.fingerprint != it.fingerprint || decode(row.vectorJson) == null } != false
                    }.take(32)
                    for (batch in missing.chunked(16)) {
                        val vectors = model.embed(batch.map { it.text.take(4_000) })
                        require(vectors.size == batch.size && vectors.all(::validVector))
                        require(vectors.map { it.size }.distinct().size == 1)
                        val rows = batch.zip(vectors).map { (item, vector) ->
                            CompanionVectorEntity(scope.id, item.type, item.id, model.modelKey, item.fingerprint,
                                gson.toJson(vector), Instant.now().toString())
                        }
                        db.withTransaction { if (current(scope)) dao.saveVectors(rows) }
                        if (!current(scope)) return@withTimeoutOrNull emptyMap()
                        rows.forEach { cached["${it.itemType}:${it.itemId}"] = it }
                    }
                    // Only the question is sent here; the saved memory vectors stay on the device.
                    val queryVectors = model.embed(listOf(query.take(2_000)))
                    require(queryVectors.size == 1 && validVector(queryVectors.single()))
                    val vector = queryVectors.single()
                    val result = items.mapNotNull { item ->
                        val saved = cached[item.cacheId]?.takeIf { it.fingerprint == item.fingerprint } ?: return@mapNotNull null
                        val old = decode(saved.vectorJson) ?: return@mapNotNull null
                        if (old.size != vector.size) return@mapNotNull null
                        item.cacheId to LocalKnowledgeSearch.cosine(vector, old).coerceIn(0f, 1f)
                    }.toMap()
                    if (!current(scope)) return@withTimeoutOrNull emptyMap()
                    db.withTransaction {
                        if (current(scope)) {
                            // A maintenance search may contain only facts: it must not erase archive indexes.
                            if (completeInventory) dao.pruneVectors(scope.id, model.modelKey, items.map { it.cacheId })
                            val incompatible = cached.filterValues { decode(it.vectorJson)?.size != vector.size }.keys.toList()
                            if (incompatible.isNotEmpty()) dao.eraseVectorItems(scope.id, model.modelKey, incompatible)
                        }
                    }
                    result
                } ?: emptyMap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No silent model switching/retries; lexical recall remains available.
                emptyMap()
            }
        }
    }

    private suspend fun current(scope: CompanionScopeEntity) = db.isOpen && db.dbName == scope.profile &&
        dao.scope(scope.id)?.revision == scope.revision && db.sessionDao().getById(scope.sessionId)?.let {
            it.usesCompanionMemory() && it.characterId == scope.characterId
        } == true

    companion object {
        private val mutexes = ConcurrentHashMap<String, Mutex>()
        internal fun validVector(v: FloatArray) = v.isNotEmpty() && v.size <= MAX_EMBEDDING_DIMENSIONS &&
            v.all { it.isFinite() } && v.any { it != 0f }
        internal fun decode(json: String): FloatArray? = runCatching {
            JsonParser.parseString(json).asJsonArray.map { it.asFloat }.toFloatArray().takeIf(::validVector)
        }.getOrNull()
        internal suspend fun configuredBackend(db: NekobotDatabase, scope: CompanionScopeEntity): CompanionEmbeddingBackend? {
            val model = db.aiModelDao().getActiveByPurpose("embedding")?.takeIf { it.enabled } ?: return null
            // Provider, model and explicit dimensions each identify a separate index.
            val identity = embeddingCacheIdentity(model)
            val client = LocalAiClient()
            return CompanionEmbeddingBackend(identity) { inputs ->
                val started = System.nanoTime()
                val result = client.createEmbeddings(model, inputs)
                val duration = (System.nanoTime() - started) / 1_000_000.0
                val estimated = result.inputTokens <= 0
                val tokens = result.inputTokens.takeIf { it > 0 } ?: inputs.sumOf(::estimateLocalTextTokens)
                getGlobalTokenStatsManager().recordUsage(tokens, 0, model = model.name,
                    actualModel = model.model, sessionId = scope.sessionId, userId = "local-user",
                    purpose = TokenStatsManager.PURPOSE_EMBEDDING, durationMs = duration,
                    provider = model.provider, inputPricePerMillion = model.inputPrice, outputPricePerMillion = 0.0)
                runCatching {
                    com.nekobot.app.ServiceContainer.localRepository.recordCompanionEmbeddingUsage(db, scope.sessionId,
                        model, tokens, duration, estimated)
                }
                result.vectors
            }
        }
    }
}
