package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.FusedCompanionPolicy
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.CompanionSourceEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FusedCompanionMemoryScreen(sessionId: String, onBack: () -> Unit, onLocate: (String) -> Unit) {
    val repo = remember { ServiceContainer.localRepository }
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<CompanionMemoryEntity>()) }
    var status by remember { mutableStateOf("正在读取整理状态…") }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var versions by remember { mutableStateOf<List<CompanionMemoryEntity>?>(null) }
    var sources by remember { mutableStateOf<List<CompanionSourceEntity>?>(null) }
    var deletion by remember { mutableStateOf<CompanionMemoryEntity?>(null) }
    var ready by remember { mutableStateOf(false) }
    val categories = FusedCompanionPolicy.categories
    LaunchedEffect(sessionId, dataSourceRevision, appMode) {
        records = emptyList()
        versions = null
        sources = null
        editor = false
        deletion = null
        selected = null
    }
    LaunchedEffect(sessionId, refresh, dataSourceRevision, appMode) {
        ready = false
        try {
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开记忆页" }
            check(repo.getSession(sessionId)?.companionMode == true) { "此入口仅管理当前 Agent 角色的独立记忆" }
            val loadedRecords = repo.listCompanionMemories(sessionId)
            val loadedStatus = repo.companionMemoryStatus(sessionId)
            check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开记忆页" }
            records = loadedRecords
            status = loadedStatus
            error = null
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { records = emptyList(); status = ""; error = failure.message }
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
                records = emptyList(); versions = null; sources = null; editor = false; deletion = null; ready = false
            }
            error = failure.message
        }
    } }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.agent_character_memory_title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { IconButton(enabled = ready && error == null && repo === ServiceContainer.localRepository,
        onClick = { selected = null; editor = true }) { Icon(Icons.Filled.Add, "新增记忆") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("fused-memory-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(status, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { refresh++ }) { Text("刷新状态") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(query, { query = it }, label = { Text("搜索正文、主题或标签") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(category.isBlank(), { category = "" }, label = { Text("全部") })
                    categories.forEach { (key, label) -> FilterChip(category == key, { category = key }, label = { Text(label) }) }
                }
                val pinned = records.filter { it.current && it.alwaysProvide }
                Text("每次聊天都提供：约 ${pinned.sumOf { estimateLocalTextTokens(it.content) + 30 }} Token；通常预算约 1000，小上下文会缩减。超额时会明确提示调整。",
                    style = MaterialTheme.typography.labelSmall)
            }
            val shown = records.filter { (it.current || it.status in setOf("conflict", "locked_conflict")) &&
                (category.isEmpty() || it.category == category) &&
                (query.isBlank() || "${it.content} ${it.tagsJson} ${it.factKey}".contains(query, true)) }
            if (shown.isEmpty()) item { Text("还没有符合条件的记忆。较早的聊天达到整理量后会自动出现，也可以手动添加。") }
            items(shown, key = { it.id }) { memory ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(categories[memory.category] ?: memory.category, style = MaterialTheme.typography.titleSmall)
                    Text(memory.content)
                    Text("v${memory.version} · ${when (memory.status) { "active" -> "有效"; "completed" -> "已完成"; "conflict" -> "待核对"; "locked_conflict" -> "需玩家修改"; else -> memory.status }} · ${memory.eventDate.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                    Text(MemoryTags.fromJson(memory.tagsJson).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    if (memory.alwaysProvide) Text("每次聊天都提供", style = MaterialTheme.typography.labelSmall)
                    if (memory.locked) Text("只允许我修改：AI 不会自动改写这条记忆", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { selected = memory; editor = true }) { Text("编辑") }
                        TextButton(onClick = { action { versions = repo.fusedMemoryVersions(sessionId, memory.factKey) } }) { Text("版本") }
                        TextButton(onClick = { action { sources = repo.fusedMemorySources(sessionId, memory.id) } }) { Text("查看原话") }
                        TextButton(onClick = { deletion = memory }) { Text("删除") }
                    }
                } }
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
                Row(Modifier.horizontalScroll(rememberScrollState())) { categories.forEach { (key, name) ->
                    FilterChip(type == key, { type = key }, label = { Text(name) })
                } }
                OutlinedTextField(content, { content = it }, label = { Text("记忆正文（最多 1500 字符）") })
                OutlinedTextField(tags, { tags = it }, label = { Text("标签，用逗号分隔") })
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
            if (rows.isEmpty()) Text("这条记忆由玩家手动记录，没有聊天原话来源。")
            rows.forEach { source -> Text("${source.spokenDate.orEmpty()} · ${source.role}\n${source.quote}")
                TextButton(onClick = { sources = null; onLocate(source.messageId) }) { Text("定位到聊天原文") } }
        }
    }, confirmButton = { TextButton(onClick = { sources = null }) { Text("关闭") } }) }
    deletion?.let { memory -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除这条记忆？") },
        text = { Text("保留聊天原话和已整理进度。以后新的明确表达仍可形成新记忆。") },
        confirmButton = { TextButton(onClick = { action { repo.deleteFusedMemory(sessionId, memory.factKey); deletion = null } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("取消") } }) }
}
