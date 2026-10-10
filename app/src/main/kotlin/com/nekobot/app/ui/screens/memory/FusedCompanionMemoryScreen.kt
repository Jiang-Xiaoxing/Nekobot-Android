package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable(sessionId) { mutableStateOf("") }
    var category by rememberSaveable(sessionId) { mutableStateOf("") }
    var editor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var versions by remember { mutableStateOf<List<CompanionMemoryEntity>?>(null) }
    var sources by remember { mutableStateOf<List<CompanionSourceEntity>?>(null) }
    var sourceMemory by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var deletion by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var ready by remember { mutableStateOf(false) }
    val categories = memoryCategoryNames
    LaunchedEffect(sessionId, dataSourceRevision, appMode) {
        records = emptyList()
        overview = null
        ownerName = ""
        versions = null
        sources = null
        sourceMemory = null
        editor = false
        deletion = null
        selected = null
        actionError = null
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
    val shown = visibleRecords.filter { (category.isEmpty() || it.category == category) &&
        (query.isBlank() || "${it.content} ${it.tagsJson} ${it.factKey}".contains(query, true)) }
    val editable = ready && !busy && error == null && repo === ServiceContainer.localRepository
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.agent_character_memory_title),
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { IconButton(enabled = editable,
        onClick = { selected = null; actionError = null; editor = true }) { Icon(Icons.Filled.Add, "新增记忆") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("fused-memory-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("overview") {
                CompanionMemoryOverviewCard(ownerName, overview, visibleRecords.size,
                    records.filter { it.current && it.alwaysProvide }.sumOf { estimateLocalTextTokens(it.content) + 30 },
                    loading = !ready && error == null, onRefresh = { refresh++ })
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
                    visibleRecords.groupingBy { it.category }.eachCount(), visibleRecords.size, shown.size)
            }
            if (shown.isEmpty() && ready && error == null) item("empty") {
                CompanionMemoryEmpty(query.isNotBlank() || category.isNotBlank())
            }
            items(shown, key = { it.id }) { memory ->
                CompanionMemoryCard(memory, enabled = editable,
                    onSource = { action { sources = repo.fusedMemorySources(sessionId, memory.id); sourceMemory = memory } },
                    onEdit = { selected = memory; actionError = null; editor = true },
                    onVersions = { action { versions = repo.fusedMemoryVersions(sessionId, memory.factKey) } },
                    onDelete = { actionError = null; deletion = memory })
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
