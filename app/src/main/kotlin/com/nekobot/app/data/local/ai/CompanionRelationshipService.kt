package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.net.SocketTimeoutException

internal data class CompanionRelationshipView(
    val state: CompanionRelationshipEntity,
    val descriptions: Map<String, String>,
    val events: List<CompanionRelationshipEventEntity>,
    val pending: Int,
    val busy: Boolean,
    val sourceWarning: String? = null,
    val shortDescriptions: Map<String, String> = emptyMap(),
    val feelingsHaveSources: Boolean = false,
    val narrative: RelationshipNarrative? = null,
    val legacy: RelationshipLegacySnapshot? = null,
    val validRelationshipKeys: Set<String> = emptySet()
)

/** 独立后台作业：聊天不等待；配置版本与来源核验共同决定结果是否还能写入。 */
internal class CompanionRelationshipService(
    private val db: NekobotDatabase,
    private val client: LocalAiClient,
    private val onUsage: (String, LocalAiModelEntity, LocalAiResult) -> Unit
) {
    private val dao = db.companionRelationshipDao()
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val running = active.asStateFlow()
    fun observe(id: String) = dao.observe(id)
    private val tags = ConcurrentHashMap<String, String>()
    private val interruptedSessions = ConcurrentHashMap.newKeySet<String>()
    private val startupRecovery = scope.async(start = CoroutineStart.LAZY) {
        val recoverable = lock.withLock {
            dao.statesWithNotice().mapNotNull { state ->
                if (CompanionRelationshipRecovery.interrupted(state.notice)) {
                    dao.save(CompanionRelationshipRecovery.clearInterruptedNotice(state))
                    interruptedSessions.add(state.sessionId)
                    state.sessionId
                } else state.sessionId.takeIf { CompanionRelationshipRecovery.retryable(state.notice) }
            }
        }
        recoverable.forEach { afterChat(it, afterEvaluation = true) }
    }
    init { startupRecovery.start() }
    private suspend fun session(id: String) = requireNotNull(db.sessionDao().getById(id)?.takeIf { it.companionMode && it.characterId != null }) { "当前会话不是单会话角色" }
    private suspend fun rows(id: String): List<LocalMessageEntity> {
        val s = session(id)
        return CompanionMemoryStore(db).permittedHistory(s, db.messageDao().listBySession(id)).filter { !it.deleted }
    }
    private suspend fun ensure(id: String): CompanionRelationshipEntity {
        val s = session(id)
        dao.state(id)?.let {
            require(it.characterId == s.characterId) { "角色归属已变化，不能读取旧关系" }
            return reviewHistoryScope(freezeLegacy(it, existing = true))
        }
        val old = db.relationshipDao().get(s.characterId!!, sessionRelationshipTargetId(id))
        val initialOnly = old?.let { runCatching { JsonParser.parseString(it.dataJson).asJsonObject["companion_initial_only"]?.asBoolean == true }.getOrDefault(false) } == true
        val baseline = old?.let { CompanionRelationshipPolicy.values(it.dataJson) } ?: initialValues(s.characterId, id)
        val oldState = db.characterStateDao().get(s.characterId, id)?.let { runCatching { JsonParser.parseString(it.dataJson).asJsonObject }.getOrNull() }
        // 已知初始值没有评估进度；真实旧积累继续保留，来源不明的初始值交玩家确认。
        val legacy = old != null && !initialOnly
        val cursor = if (legacy) CompanionRelationshipPolicy.turns(rows(id)).lastOrNull()?.endId else null
        val created = CompanionRelationshipEntity(id, s.characterId, cursorId = cursor, baselineJson = gson.toJson(baseline),
            legacy = legacy, mood = oldState?.get("mood")?.asString ?: "尚未评估", energy = "尚未评估",
            historyScopeVersion = if (legacy) 0 else 1)
        dao.save(created)
        return reviewHistoryScope(freezeLegacy(created, existing = false))
    }
    private suspend fun freezeLegacy(state: CompanionRelationshipEntity, existing: Boolean): CompanionRelationshipEntity {
        if (state.legacySnapshotJson != null) return state
        val byId = rows(state.sessionId).associateBy { it.id }
        val events = dao.currentEvents(state.sessionId).filter { valid(refs(it.sourcesJson), byId) }
        val totals = CompanionRelationshipPolicy.totals(CompanionRelationshipPolicy.values(state.baselineJson),
            events.map { CompanionRelationshipPolicy.values(it.deltasJson) })
        val feelingSources = refs(state.feelingSources)
        val snapshot = RelationshipLegacySnapshot(if (existing || state.legacy) "旧版记录" else "初始设定",
            if (existing) CompanionRelationshipPolicy.dimensions.mapValues { (key, _) -> CompanionRelationshipPolicy.describe(key, totals.getValue(key)) } else emptyMap(),
            if (existing) CompanionRelationshipPolicy.dimensions.mapValues { (key, _) -> CompanionRelationshipPolicy.shortDescription(key, totals.getValue(key)) } else emptyMap(),
            totals, state.mood, state.energy, state.moodIntensity, state.feelingReason, feelingSources,
            byId.values.lastOrNull { row -> feelingSources.any { it.id == row.id } }?.createdAt, state.updatedAt)
        return state.copy(legacySnapshotJson = gson.toJson(snapshot)).also { dao.save(it) }
    }
    private suspend fun initialValues(characterId: String, sessionId: String): Map<String, Int> = CompanionRelationshipPolicy.values(
        relationshipStateFromInitial(characterId, sessionRelationshipTargetId(sessionId),
            LocalProfileRepository(db.characterDao()).getById(characterId)?.initialState.orEmpty(), "").toJson())

    private suspend fun reviewHistoryScope(state: CompanionRelationshipEntity): CompanionRelationshipEntity {
        if (state.historyScopeVersion >= 1) return state
        val candidate = CompanionRelationshipPolicy.needsHistoryReview(state,
            hasEvents = dao.currentEvents(state.sessionId).isNotEmpty(),
            hasSnapshots = db.stateSnapshotDao().listBySession(state.sessionId).isNotEmpty(),
            initialValues = initialValues(state.characterId, state.sessionId))
        // 不移动旧游标，不重置基线，不静默重评；一次性标记也保留后续明确跳过的决定。
        return state.copy(historyScopeVersion = 1, historyReviewRequired = candidate,
            awaitingHistory = state.awaitingHistory || candidate, version = state.version + 1).also { dao.save(it) }
    }
    private fun refs(json: String): List<RelationshipSource> = gson.fromJson(json, object : TypeToken<List<RelationshipSource>>() {}.type)
    private fun valid(refs: List<RelationshipSource>, rows: Map<String, LocalMessageEntity>) = CompanionRelationshipNarrative.validSources(refs, rows)
    private suspend fun referencedRows(id: String, ids: List<String>): List<LocalMessageEntity> {
        val selected = ids.distinct().chunked(800).flatMap { dao.sources(id, it) }
        return CompanionMemoryStore(db).permittedHistory(session(id), selected)
    }
    private suspend fun viewUnlocked(id: String): CompanionRelationshipView {
        val state = ensure(id)
        val all = rows(id)
        val byId = all.associateBy { it.id }
        val events = dao.currentEvents(id)
        val validEvents = events.filter { valid(refs(it.sourcesJson), byId) }
        val narrative = CompanionRelationshipNarrative.read(state.narrativeJson)
        val legacy = CompanionRelationshipNarrative.legacy(state.legacySnapshotJson)
        val validKeys = narrative?.relationships.orEmpty().filterValues { field -> field != null && CompanionRelationshipNarrative.valid(field, byId, events) }.keys
        val count = runCatching { CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(all), if (state.historyReviewRequired) null else state.cursorId).size }
        val feelingRefs = if (narrative != null) narrative.feelings?.sources.orEmpty() else refs(state.feelingSources)
        val feelingChanged = feelingRefs.isNotEmpty() && !valid(feelingRefs, byId)
        val staleNarrative = narrative?.relationships.orEmpty().any { (key, field) -> field != null && key !in validKeys }
        val warning = if (validEvents.size != events.size || feelingChanged || staleNarrative || state.narrativeJson != null && narrative == null)
            "部分评估依据已变化或不可读取，对应描述暂不提供给聊天，请核对变化记录。" else count.exceptionOrNull()?.message
        val descriptions = CompanionRelationshipPolicy.dimensions.mapValues { (key, _) ->
            if (narrative != null) narrative.relationships[key]?.description ?: legacy?.descriptions?.get(key)?.takeUnless { key in narrative.withdrawnKeys } ?: "尚未评估"
            else legacy?.descriptions?.get(key) ?: "尚未评估"
        }
        return CompanionRelationshipView(state, descriptions,
            events, count.getOrDefault(0), jobs[id]?.isActive == true, warning,
            if (narrative != null) descriptions else legacy?.shortDescriptions.orEmpty(),
            valid(feelingRefs, byId), narrative, legacy, validKeys)
    }
    suspend fun view(id: String) = withContext(Dispatchers.IO) { lock.withLock { viewUnlocked(id) } }

    suspend fun configure(id: String, enabled: Boolean, automatic: Boolean, modelId: String?, interval: Int) = withContext(Dispatchers.IO) {
        startupRecovery.await()
        require(interval in 1..100) { "更新间隔请填写 1—100 轮" }
        if (enabled) require(db.aiModelDao().getById(modelId.orEmpty())?.let { it.enabled && it.purpose in setOf("chat", "compression") } == true) { "请先选择可用的状态评估模型" }
        lock.withLock {
            val old = ensure(id)
            interruptedSessions.remove(id)
            val count = runCatching { CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(rows(id)), old.cursorId).size }.getOrDefault(1)
            tags[id]?.let(client::cancelRequests); jobs.remove(id)?.cancel(); active.update { it - id }
            dao.save(old.copy(version = old.version + 1, enabled = enabled, automatic = automatic, interval = interval, modelId = modelId,
                awaitingHistory = old.awaitingHistory || (enabled && (!old.enabled || (automatic && !old.automatic)) && count > 0), notice = null))
        }
    }

    /** 不补评明确推进到此刻，后续新聊天才累计；不改旧事件、不清空聊天。 */
    suspend fun skipPending(id: String) = withContext(Dispatchers.IO) {
        startupRecovery.await()
        lock.withLock {
            val old = ensure(id)
            interruptedSessions.remove(id)
            tags[id]?.let(client::cancelRequests); jobs.remove(id)?.cancel(); active.update { it - id }
            dao.save(old.copy(cursorId = CompanionRelationshipPolicy.turns(rows(id)).lastOrNull()?.endId,
                version = old.version + 1, awaitingHistory = false, historyReviewRequired = false, notice = null))
        }
    }

    fun afterChat(id: String, afterEvaluation: Boolean = false) { scope.launch {
        runCatching {
            startupRecovery.await()
            val ready = lock.withLock {
                val state = ensure(id)
                val pending = CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(rows(id)), state.cursorId).size
                if (pending == 0) interruptedSessions.remove(id)
                jobs[id]?.isActive != true && CompanionRelationshipRecovery.automaticReady(state,
                    pending,
                    interrupted = id in interruptedSessions, afterEvaluation = afterEvaluation)
            }
            if (ready) start(id, false)
        }
    } }

    /** 手动补评以点击时的消息为上限；不会把后续源源不断的新聊天一并追到后台。 */
    suspend fun start(id: String, manual: Boolean = true, correctionKey: String? = null, claim: String? = null) = withContext(Dispatchers.IO) {
        startupRecovery.await()
        lock.withLock {
            val state = ensure(id)
            require(state.enabled) { "请先开启关系状态" }
            require(jobs[id]?.isActive != true) { "正在评估，请等待完成" }
            if (!manual) require(CompanionRelationshipRecovery.automaticReady(state,
                CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(rows(id)), state.cursorId).size,
                interrupted = id in interruptedSessions))
            val recovering = !manual && (id in interruptedSessions || CompanionRelationshipRecovery.retryable(state.notice))
            interruptedSessions.remove(id)
            if (state.historyReviewRequired) {
                require(manual && correctionKey == null) { "请先确认是否补评旧历史" }
                // 仅“补评这些对话”的明确操作才会回到历史开头；原游标在确认之前保持原样。
                dao.save(state.copy(cursorId = null, legacy = false, historyReviewRequired = false,
                    awaitingHistory = true, version = state.version + 1, notice = null))
            }
            val boundary = CompanionRelationshipPolicy.turns(rows(id)).lastOrNull()?.endId
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    evaluate(id, boundary, correctionKey, claim, recovering)
                } catch (_: SocketTimeoutException) {
                    // 超时已保存原因，本批保持待评；本次作业不再追加请求。
                }
                finally {
                    if (jobs.remove(id, currentCoroutineContext()[Job])) {
                        active.update { it - id }
                        if (db.isOpen) afterChat(id, afterEvaluation = true)
                    }
                }
            }
            jobs[id] = job
            active.update { it + id }
            job.start()
        }
    }

    private suspend fun evaluate(id: String, boundary: String?, correctionKey: String?, claim: String?, recovering: Boolean) {
        var expected: CompanionRelationshipEntity? = null
        var requestTag: String? = null
        try {
            val input = lock.withLock {
                val state = ensure(id)
                expected = state
                require(state.enabled)
                val model = requireNotNull(db.aiModelDao().getById(state.modelId.orEmpty())?.takeIf {
                    it.enabled && it.purpose in setOf("chat", "compression")
                }) { "所选评估模型已不可用，请重新选择" }
                val all = rows(id)
                val events = dao.currentEvents(id)
                val turns = CompanionRelationshipPolicy.turns(all)
                val available = CompanionRelationshipPolicy.throughBoundary(turns, boundary)
                val pending = if (correctionKey == null) CompanionRelationshipPolicy.pending(available, state.cursorId) else emptyList()
                if (correctionKey == null && pending.isEmpty()) {
                    dao.save(state.copy(awaitingHistory = false, notice = null)); return
                }
                val batch = pending
                val target = correctionKey?.let { key -> requireNotNull(events.firstOrNull { it.eventKey == key }) { "需要纠正的记录已不存在" } }
                val evidence = if (target != null) {
                    val sourceIds = refs(target.sourcesJson).map { it.id }.toSet()
                    (all.filter { it.id in sourceIds } + available.takeLast(10).flatMap { it.rows }).distinctBy { it.id }
                } else batch.flatMap { it.rows }
                // 合并旧备份可恢复较早的状态版本；历史记录保留，后续评估编号不能与其碰撞。
                val inFlight = state.copy(version = maxOf(state.version, dao.latestRevision(id) ?: 0) + 1,
                    notice = if (recovering) CompanionRelationshipRecovery.RETRY_IN_FLIGHT else CompanionRelationshipRecovery.IN_FLIGHT)
                dao.save(inFlight); expected = inFlight
                EvaluationInput(inFlight, model, all, events, batch, evidence, target)
            }
            val byId = input.all.associateBy { it.id }
            val usable = input.events.filter { valid(refs(it.sourcesJson), byId) }
            val previous = CompanionRelationshipNarrative.read(input.state.narrativeJson)
            val legacy = CompanionRelationshipNarrative.legacy(input.state.legacySnapshotJson)
            val words = input.evidence.joinToString("\n") { it.content }.windowed(2, 2).distinct().filter { it.isNotBlank() }.take(1000)
            val relevant = usable.sortedByDescending { event -> words.count { it in event.summary } }.take(25)
            val selectedEvents = (listOfNotNull(input.target) + relevant + usable.take(15)).distinctBy { it.eventKey }
            val character = db.characterDao().getById(input.state.characterId)
            val previousDescriptions = previous?.relationships?.mapValues { (_, field) ->
                field?.takeIf { CompanionRelationshipNarrative.valid(it, byId, input.events) }?.let {
                    mapOf("description" to it.description, "reason" to it.reason, "event_keys" to it.eventRevisions.keys)
                }
            }
            val payload = gson.toJson(mapOf(
                "角色" to character?.name,
                "角色背景参考" to listOfNotNull(character?.basicInfo, character?.description, character?.personality).joinToString("\n").take(4000),
                "已有文字关系" to previousDescriptions,
                "旧版文字参考_没有原话保证_不能编造细节" to legacy?.descriptions?.filterKeys { previous?.relationships?.get(it) == null && it !in previous?.withdrawnKeys.orEmpty() },
                "上次感受_只是历史观察" to previous?.feelings?.takeIf { valid(it.sources, byId) }?.let {
                    mapOf("mood" to it.mood, "intensity" to it.intensity, "energy" to it.energy, "observed_at" to it.observedAt, "reason" to it.reason)
                },
                "既有事件" to selectedEvents.map { mapOf("key" to it.eventKey, "summary" to it.summary,
                    "reason" to it.reason, "文字影响" to it.impactsJson, "受影响项目" to CompanionRelationshipNarrative.affected(it),
                    "依据有效" to valid(refs(it.sourcesJson), byId)) },
                "原话" to input.evidence.map { mapOf("id" to it.id, "role" to it.role, "content" to it.content, "time" to it.createdAt) },
                "材料末轮id" to input.batch.lastOrNull()?.rows?.map { it.id },
                "纠错目标" to correctionKey, "玩家提出的待核对说法" to claim?.take(2000)))
            val messages = listOf(mapOf("role" to "system", "content" to RULES), mapOf("role" to "user", "content" to payload))
            val outputBudget = CompanionRelationshipRequest.outputBudget(input.model)
            require(estimateLocalTextTokens(RULES + payload) + outputBudget < (input.model.maxContextLength ?: 32000)) { "评估材料超过模型上下文预算，请选择上下文更大的模型" }
            requestTag = "relationship-${UUID.randomUUID()}"
            tags[id] = requestTag!!
            val requestStarted = System.nanoTime()
            LocalLogger.i("Relationship", "开始整批评估：轮数=${input.batch.size}，输出上限=$outputBudget")
            // 关系评估独立等待 300 秒，不修改模型的全局设置。
            val result = runRelationshipEvaluation {
                client.chatOnce(input.model, messages, extra = CompanionRelationshipRequest.extra(input.model), requestTag = requestTag)
            }
            val finish = result.finishReason.takeIf { it in setOf("stop", "length", "max_tokens", "max_output_tokens", "content_filter", "tool_calls") } ?: "other"
            LocalLogger.i("Relationship", "评估请求完成：耗时毫秒=${(System.nanoTime() - requestStarted) / 1_000_000}，结束原因=$finish，思考字符数=${result.thinkingContent.length}")
            val usage = resolveLocalTokenUsage(result.usage, messages, result.content)
            onUsage(id, input.model, result.copy(usage = result.usage + mapOf("prompt" to usage.inputTokens,
                "completion" to usage.outputTokens, "estimated" to if (usage.estimated) 1 else 0)))
            CompanionRelationshipRequest.requireComplete(result)
            require(result.error == null && result.content.isNotBlank()) { "评估模型没有返回有效结果" }
            val json = CompanionRelationshipResponse.json(result.content)
            val evidenceMap = input.evidence.associateBy { it.id }
            val now = System.currentTimeMillis()
            val parsedEvents = CompanionRelationshipResponse.events(json, evidenceMap, input.events,
                id, input.state.version + 1, now, correctionKey)
            val effects = parsedEvents.accepted
            val correctionReason = if (correctionKey != null) CompanionRelationshipNarrative.text(json, "correction_reason", 500) else null
            if (correctionKey != null) {
                require(json["verdict"]?.asString in setOf("corrected", "unchanged")) { "纠错结论无效" }
                if (json["verdict"].asString == "unchanged") require(effects.isEmpty()) { "保留原判断时不能替换事件" }
                else require(effects.size == 1) { "纠错需要明确给出目标事件的修正影响" }
            }
            val inheritedKeys = previous?.relationships?.values.orEmpty().filterNotNull().flatMap { it.eventRevisions.keys }.toSet()
            val skippedKeys = mutableSetOf<String>()
            val narrative = CompanionRelationshipNarrative.parse(json, previous, evidenceMap, byId, input.events,
                effects, input.batch.lastOrNull()?.rows?.map { it.id }?.toSet().orEmpty(),
                input.batch.lastOrNull()?.rows?.lastOrNull()?.createdAt.orEmpty(), now, input.target, parsedEvents.discardedKeys,
                selectedEvents.map { it.eventKey }.toSet() + inheritedKeys, skippedKeys::add)
            if (skippedKeys.isNotEmpty()) LocalLogger.w("Relationship", "评估有 ${skippedKeys.size} 项关系的事件引用无效，保留这些项目的旧描述；其他合法结果继续保存。项目=${skippedKeys.joinToString()}")
            val allowedKeys = (selectedEvents + effects).map { it.eventKey }.toSet()
            require(narrative.relationships.values.filterNotNull().all { field ->
                field.eventRevisions.keys.all { it in allowedKeys || it in previous?.relationships?.values.orEmpty().filterNotNull().flatMap { old -> old.eventRevisions.keys } }
            }) { "关系描述引用了未提供的事件" }
            val narrativeJson = gson.toJson(narrative)
            lock.withLock {
                db.withTransaction {
                    val current = dao.state(id)
                    require(current?.enabled == true && current.version == input.state.version && session(id).characterId == input.state.characterId) { "评估期间设置已变化，旧结果不再写入" }
                    require(db.aiModelDao().getById(input.model.id) == input.model) { "评估模型配置已改变，旧结果不再写入" }
                    val latest = rows(id).associateBy { it.id }
                    require(input.evidence.all { old -> latest[old.id]?.let { CompanionRelationshipPolicy.hash(it) == CompanionRelationshipPolicy.hash(old) } == true }) { "评估期间原话已变化，旧结果不再写入" }
                    require(dao.currentEvents(id) == input.events) { "关系记录已更新，旧结果不再写入" }
                    // 生成期间先前关系的依据也可能被改动；不得只核对新消息。
                    require(CompanionRelationshipNarrative.allSources(narrative).all { ref ->
                        latest[ref.id]?.let { CompanionRelationshipPolicy.hash(it) == ref.hash } == true ||
                            byId[ref.id]?.let { CompanionRelationshipPolicy.hash(it) == ref.hash } != true
                    }) { "评估期间已有关系的依据发生变化，旧状态保留" }
                    dao.append(effects)
                    dao.appendAssessment(CompanionRelationshipAssessmentEntity(UUID.randomUUID().toString(), id,
                        current.version + 1, narrativeJson, now, correctionKey))
                    dao.save(current.copy(version = current.version + 1, awaitingHistory = false,
                        cursorId = if (correctionKey == null) input.batch.last().endId else current.cursorId,
                        narrativeJson = narrativeJson, updatedAt = now, notice = null,
                        correctionResult = if (correctionKey == null) current.correctionResult else if (effects.isEmpty())
                            "本次核对保留原判断：$correctionReason" else "已修正这件事的判断，其他关系保留：$correctionReason"))
                }
            }
            LocalLogger.i("Relationship", "评估保存完成：事件=${effects.size}，丢弃事件=${parsedEvents.discardedKeys.size}，保留旧关系=${skippedKeys.size}")
        } catch (e: CancellationException) {
            saveError(id, expected, if (e is TimeoutCancellationException) {
                CompanionRelationshipRecovery.TIMEOUT
            } else if (recovering) CompanionRelationshipRecovery.RETRY_IN_FLIGHT else CompanionRelationshipRecovery.IN_FLIGHT)
            throw e
        } catch (e: Exception) {
            if (e is SocketTimeoutException) {
                saveError(id, expected, CompanionRelationshipRecovery.TIMEOUT)
                throw e
            }
            val safe = if (e is IllegalArgumentException || e is IllegalStateException) e.message?.takeIf { it.length <= 150 && !it.contains("http", true) } else null
            saveError(id, expected, safe ?: "状态评估失败，旧状态保留。请检查评估模型后手动重试。")
        } finally {
            requestTag?.let { client.cancelRequests(it); tags.remove(id, it) }
        }
    }
    private suspend fun saveError(id: String, expected: CompanionRelationshipEntity?, text: String) = withContext(NonCancellable + Dispatchers.IO) {
        if (db.isOpen && expected != null) lock.withLock { dao.state(id)?.takeIf { it.version == expected.version }?.let { dao.save(it.copy(notice = text)) } }
    }
    suspend fun sourceQuotes(id: String, json: String): List<LocalMessageEntity> = withContext(Dispatchers.IO) {
        referencedRows(id, refs(json).map { it.id })
    }
    suspend fun eventHistory(id: String, key: String) = withContext(Dispatchers.IO) { session(id); dao.history(id, key) }
    suspend fun assessmentHistory(id: String) = withContext(Dispatchers.IO) { session(id); dao.assessments(id) }
    /** 同一份已保存文字供页面和聊天使用；没有新评估时才显式引用旧版文字。 */
    suspend fun context(id: String): Pair<String, String> = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = dao.state(id)?.takeIf { it.enabled } ?: return@withLock ("" to "relationship-text-v1:off")
            require(saved.characterId == session(id).characterId)
            val state = freezeLegacy(saved, existing = true)
            val narrative = CompanionRelationshipNarrative.read(state.narrativeJson)
            if (state.narrativeJson != null && narrative == null) return@withLock ("" to "relationship-text-v1:invalid")
            val legacy = CompanionRelationshipNarrative.legacy(state.legacySnapshotJson)
            val events = dao.currentEvents(id)
            val sourceRefs = CompanionRelationshipNarrative.allSources(narrative) + legacy?.sources.orEmpty()
            val sources = referencedRows(id, sourceRefs.map { it.id }).associateBy { it.id }
            val text = CompanionRelationshipPrompt.build(narrative, legacy, sources, events)
            text to ("relationship-text-v1:" + CompanionMemoryPolicy.digest(text))
        }
    }

    private data class EvaluationInput(val state: CompanionRelationshipEntity, val model: LocalAiModelEntity,
        val all: List<LocalMessageEntity>, val events: List<CompanionRelationshipEventEntity>, val batch: List<RelationshipTurn>,
        val evidence: List<LocalMessageEntity>, val target: CompanionRelationshipEventEntity?)

    companion object {
        val RULES: String get() = CompanionRelationshipNarrative.RULES
    }
}
