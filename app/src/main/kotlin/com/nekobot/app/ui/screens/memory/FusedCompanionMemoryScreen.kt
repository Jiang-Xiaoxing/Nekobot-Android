package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.CompanionSourceEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FusedCompanionMemoryScreen(sessionId: String, onBack: () -> Unit, onLocate: (String) -> Unit) {
    val repo = remember { ServiceContainer.localRepository }
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<CompanionMemoryEntity>()) }
    var overview by remember { mutableStateOf<CompanionMemoryOverview?>(null) }
    var ownerName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
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
    }
    LaunchedEffect(sessionId, refresh, dataSourceRevision, appMode) {
        ready = false
        error = null
        try {
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开记忆页" }
            val session = repo.getSession(sessionId)
            check(session?.companionMode == true) { "此入口仅管理当前 Agent 角色的独立记忆" }
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
    fun action(block: suspend () -> Unit) { scope.launch {
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
            error = failure.message ?: "这次操作未完成，请重试"
        }
    } }
    val visibleRecords = records.filter { it.current || it.status in setOf("conflict", "locked_conflict") }
    val shown = visibleRecords.filter { (category.isEmpty() || it.category == category) &&
        (query.isBlank() || "${it.content} ${it.tagsJson} ${it.factKey}".contains(query, true)) }
    val editable = ready && error == null && repo === ServiceContainer.localRepository
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.agent_character_memory_title),
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { IconButton(enabled = editable,
        onClick = { selected = null; editor = true }) { Icon(Icons.Filled.Add, "新增记忆") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("fused-memory-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("overview") {
                CompanionMemoryOverviewCard(ownerName, overview, visibleRecords.size,
                    records.filter { it.current && it.alwaysProvide }.sumOf { estimateLocalTextTokens(it.content) + 30 },
                    loading = !ready && error == null, onRefresh = { refresh++ })
            }
            error?.let { message -> item("error") {
                com.nekobot.app.ui.components.ErrorBanner(message = message, onRetry = { refresh++ })
            } }
            item("filters") {
                CompanionMemoryFilters(query, { query = it }, category, { category = it },
                    visibleRecords.groupingBy { it.category }.eachCount(), visibleRecords.size)
            }
            item("count") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (category.isBlank()) "全部记忆" else categories[category].orEmpty(), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${shown.size} 条", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (shown.isEmpty() && ready && error == null) item("empty") {
                CompanionMemoryEmpty(query.isNotBlank() || category.isNotBlank())
            }
            items(shown, key = { it.id }) { memory ->
                CompanionMemoryCard(memory, enabled = editable,
                    onSource = { action { sources = repo.fusedMemorySources(sessionId, memory.id); sourceMemory = memory } },
                    onEdit = { selected = memory; editor = true },
                    onVersions = { action { versions = repo.fusedMemoryVersions(sessionId, memory.factKey) } },
                    onDelete = { deletion = memory })
            }
        }
    }
    if (editor) {
        var content by remember(selected?.id) { mutableStateOf(selected?.content.orEmpty()) }
        var tags by remember(selected?.id) { mutableStateOf(selected?.let { MemoryTags.fromJson(it.tagsJson).joinToString("，") }.orEmpty()) }
        var type by remember(selected?.id) { mutableStateOf(selected?.category ?: "user_profile") }
        var pinned by remember(selected?.id) { mutableStateOf(selected?.alwaysProvide ?: false) }
        var onlyMe by remember(selected?.id) { mutableStateOf(selected?.locked ?: false) }
        AlertDialog(onDismissRequest = { editor = false }, title = { Text(stringResource(R.string.agent_character_memory_edit)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { categories.forEach { (key, name) ->
                    FilterChip(type == key, { type = key }, label = { Text(name) })
                } }
                OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("记忆正文") }, supportingText = { Text("${content.length} / 1500 字符") },
                    isError = content.length > 1500, minLines = 3)
                OutlinedTextField(tags, { tags = it }, modifier = Modifier.fillMaxWidth(), label = { Text("标签，用逗号分隔") })
                Row { Checkbox(pinned, { pinned = it }); Text("每次聊天都提供\n约 ${estimateLocalTextTokens(content) + 30} Token", Modifier.padding(top = 8.dp)) }
                Row { Checkbox(onlyMe, { onlyMe = it }); Text("只允许我修改\nAI 不会自动改写；你仍可编辑或删除", Modifier.padding(top = 8.dp)) }
                Text("两个选项分别控制提供频率和修改权限，互不影响。", style = MaterialTheme.typography.labelSmall)
            }
        }, confirmButton = { TextButton(enabled = content.isNotBlank() && content.length <= 1500, onClick = { action {
            repo.saveFusedMemory(sessionId, selected?.factKey, content, type, tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }, pinned, onlyMe)
            editor = false
        } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editor = false }) { Text("取消") } })
    }
    versions?.let { rows -> AlertDialog(onDismissRequest = { versions = null }, title = { Text("记忆版本") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { rows.forEach { Text("v${it.version} · ${if (it.current) "当前" else "历史"}\n${it.content.ifBlank { "已删除" }}\n") } }
    }, confirmButton = { TextButton(onClick = { versions = null }) { Text("关闭") } }) }
    sources?.let { rows -> AlertDialog(onDismissRequest = { sources = null }, title = { Text("来源原话") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) Text(when {
                sourceMemory?.manual == true -> "这条记忆由玩家手动记录，没有聊天原话来源。"
                sourceMemory?.provenance == com.nekobot.app.data.local.ai.CompanionImportPolicy.IMPORTED_UNVERIFIED -> "这条记忆从旧资料迁入，尚无已核验的聊天原话来源。"
                else -> "没有可核验的聊天原话来源，请核对这条记忆。"
            })
            rows.forEach { source -> Text("${source.spokenDate.orEmpty()} · ${source.role}\n${source.quote}")
                TextButton(onClick = { sources = null; onLocate(source.messageId) }) { Text("定位到聊天原文") } }
        }
    }, confirmButton = { TextButton(onClick = { sources = null }) { Text("关闭") } }) }
    deletion?.let { memory -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除这条记忆？") },
        text = { Text("保留聊天原话和已整理进度。以后新的明确表达仍可形成新记忆。") },
        confirmButton = { TextButton(onClick = { action { repo.deleteFusedMemory(sessionId, memory.factKey); deletion = null } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } }) }
}
