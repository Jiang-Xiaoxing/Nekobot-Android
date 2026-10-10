package com.nekobot.app.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.CompanionMemoryOverview
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.CompanionSourceEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun FusedCompanionMemoryScreen(sessionId: String, onBack: () -> Unit, onLocate: (String) -> Unit) {
    CompanionMemoryTheme { CompanionMemoryDetail(sessionId, onBack, onLocate) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CompanionMemoryDetail(sessionId: String, onBack: () -> Unit, onLocate: (String) -> Unit) {
    val repo = remember { ServiceContainer.localRepository }
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<CompanionMemoryEntity>()) }
    var overview by remember { mutableStateOf<CompanionMemoryOverview?>(null) }
    var ownerName by remember { mutableStateOf("") }
    var ownerAvatar by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable(sessionId) { mutableStateOf("") }
    var category by rememberSaveable(sessionId) { mutableStateOf("") }
    var coreOnly by rememberSaveable(sessionId) { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var versions by remember { mutableStateOf<List<CompanionMemoryEntity>?>(null) }
    var sources by remember { mutableStateOf<List<CompanionSourceEntity>?>(null) }
    var sourceMemory by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var deletion by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var ready by remember { mutableStateOf(false) }
    var recallSettingsOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    var recallSettings by remember { mutableStateOf(repo.companionRecallSettings(sessionId)) }
    val categories = memoryCategoryNames
    LaunchedEffect(sessionId, dataSourceRevision, appMode) {
        records = emptyList()
        overview = null
        ownerName = ""
        ownerAvatar = null
        versions = null
        sources = null
        sourceMemory = null
        editor = false
        deletion = null
        selected = null
        actionError = null
        recallSettingsOpen = false
    }
    LaunchedEffect(sessionId, refresh, dataSourceRevision, appMode) {
        ready = false
        error = null
        try {
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开记忆页" }
            val session = repo.getSession(sessionId)
            check(session?.companionMode == true) { "此入口仅管理当前单会话角色的独立记忆" }
            val loadedRecords = repo.listCompanionMemories(sessionId)
            val loadedOverview = repo.companionMemoryOverview(sessionId)
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开记忆页" }
            records = loadedRecords
            overview = loadedOverview
            ownerName = session.characterName?.takeIf { it.isNotBlank() } ?: session.name.orEmpty()
            ownerAvatar = session.portrait?.takeIf(String::isNotBlank) ?: session.characterAvatar
            error = null
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            records = emptyList(); overview = null; ownerName = ""
            error = failure.message ?: "记忆暂时无法读取，请重试"
        }
    }
    LaunchedEffect(sessionId) {
        repo.experienceSourceReader().changes().collect { refresh++ }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
        try {
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开" }
            block()
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开" }
            refresh++
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (repo !== ServiceContainer.localRepository || !ServiceContainer.prefs.isLocalMode) {
                records = emptyList(); overview = null; ownerName = ""
                versions = null; sources = null; editor = false; deletion = null; ready = false
            }
            actionError = failure.message ?: "这次操作未完成，请重试"
        }
        finally { busy = false }
    } }
    val visibleRecords = records.filter { it.current || it.status in setOf("conflict", "locked_conflict") }
    val coreRecords = CompanionCoreMemory.rows(records)
    val coreTokens = CompanionCoreMemory.tokens(coreRecords)
    val filteredRecords = if (coreOnly) coreRecords else visibleRecords
    val shown = filteredRecords.filter { (category.isEmpty() || it.category == category) &&
        (query.isBlank() || "${it.content} ${it.tagsJson} ${it.factKey}".contains(query, true)) }
    val editable = ready && !busy && error == null && repo === ServiceContainer.localRepository
    if (recallSettingsOpen) {
        CompanionRecallSettingsScreen(sessionId, ownerName, ownerAvatar, recallSettings, busy, actionError,
            onBack = { recallSettingsOpen = false; actionError = null }, onSave = { rounds, tokens -> action {
                repo.saveCompanionRecallSettings(sessionId, rounds, tokens)
                recallSettings = repo.companionRecallSettings(sessionId)
                recallSettingsOpen = false
            } })
        return
    }
    BackHandler(enabled = coreOnly && !editor && versions == null && sources == null && deletion == null) { coreOnly = false }
    Scaffold(topBar = { TopAppBar(title = { Text(if (coreOnly) "每次都会带上的记忆" else stringResource(R.string.agent_character_memory_title),
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }, navigationIcon = {
        IconButton(onClick = { if (coreOnly) coreOnly = false else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = {
        IconButton(enabled = editable, onClick = { recallSettings = repo.companionRecallSettings(sessionId); actionError = null; recallSettingsOpen = true }) {
            Icon(Icons.Filled.Tune, "回查设置")
        }
        IconButton(enabled = editable,
        onClick = { selected = null; actionError = null; editor = true }) { Icon(Icons.Filled.Add, "新增记忆") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("fused-memory-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!coreOnly) item("overview") {
                CompanionMemoryOverviewCard(ownerName, overview, visibleRecords.size,
                    coreTokens,
                    loading = !ready && error == null, onRefresh = { refresh++ })
            }
            if (ready) item("core-entry") {
                Surface(shape = RoundedCornerShape(18.dp), color = memoryCategoryColor("relationship").copy(alpha = 0.09f)) {
                    Column(Modifier.fillMaxWidth().then(if (!coreOnly) Modifier.clickable {
                        coreOnly = true; category = ""; query = ""
                    } else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.PushPin, null, tint = memoryCategoryColor("relationship"))
                            Text(if (coreOnly) "${coreRecords.size} 条核心记忆" else "每次都会带上的记忆", Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (!coreOnly) { Text("${coreRecords.size} 条"); Icon(Icons.Filled.ChevronRight, "查看核心记忆") }
                        }
                        Text(if (coreOnly) "这里的内容，每次聊天都会提供给模型。点记忆右上角可移出核心，正文仍然保留。模型在整理旧聊天时挑选和更新，你的手动选择优先。"
                            else "重要的称呼、关系与相处习惯，随时查看和调整。", style = MaterialTheme.typography.bodySmall)
                        if (coreOnly) Text("约 $coreTokens Token · 不扣主动回查额度", style = MaterialTheme.typography.labelMedium)
                        if (coreTokens > CompanionCoreMemory.maintenanceTokens) Text(
                            "现有核心超过精简目标，仍会完整提供。建议缩短正文或移出部分条目；新增核心前需要腾出空间。",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { message -> item("error") {
                CompanionMemoryNotice("记忆暂时无法读取", message) { refresh++ }
            } }
            if (!editor && deletion == null) actionError?.let { message -> item("action-error") {
                CompanionMemoryNotice("这次操作没有完成", message) { actionError = null; refresh++ }
            } }
            if (busy && !editor && deletion == null) item("action-loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item("filters") {
                CompanionMemoryFilters(query, { query = it }, category, { category = it },
                    filteredRecords.groupingBy { it.category }.eachCount(), filteredRecords.size, shown.size)
            }
            if (shown.isEmpty() && ready && error == null) item("empty") {
                if (coreOnly && coreRecords.isEmpty()) Text("还没有核心记忆。返回全部记忆，点条目右上角的“设为核心”；也可以在后续聊天整理中由模型挑选。",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else CompanionMemoryEmpty(query.isNotBlank() || category.isNotBlank())
            }
            items(shown, key = { it.id }) { memory ->
                CompanionMemoryCard(memory, enabled = editable,
                    onSource = { action { sources = repo.fusedMemorySources(sessionId, memory.id); sourceMemory = memory } },
                    onEdit = { selected = memory; actionError = null; editor = true },
                    onVersions = { action { versions = repo.fusedMemoryVersions(sessionId, memory.factKey) } },
                    onDelete = { actionError = null; deletion = memory },
                    onCoreChange = { action { repo.setFusedMemoryCore(sessionId, memory.factKey, !CompanionCoreMemory.contains(memory)) } })
            }
        }
    }
    if (editor) {
        CompanionMemoryEditor(selected, busy, actionError, onDismiss = { editor = false; actionError = null }) {
                content, type, tags, pinned, onlyMe -> action {
            repo.saveFusedMemory(sessionId, selected?.factKey, content, type, tags, pinned, onlyMe)
            editor = false
        } }
    }
    versions?.let { rows -> CompanionMemoryChanges(rows) { versions = null } }
    sources?.let { rows -> CompanionMemorySources(rows, sourceMemory, ownerName,
        onDismiss = { sources = null }, onLocate = { messageId -> sources = null; onLocate(messageId) }) }
    deletion?.let { memory -> CompanionMemoryDelete(memory, busy, actionError,
        onDismiss = { deletion = null; actionError = null },
        onDelete = { action { repo.deleteFusedMemory(sessionId, memory.factKey); deletion = null } }) }
}
