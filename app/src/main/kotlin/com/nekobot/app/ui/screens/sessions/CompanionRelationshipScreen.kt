package com.nekobot.app.ui.screens.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.CompanionConversionModel
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.CompanionRelationshipEventEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionRelationshipScreen(sessionId: String, onBack: () -> Unit) {
    val repository = remember { ServiceContainer.localRepository }
    val service = remember { repository.companionRelationship }
    val worker = rememberCoroutineScope()
    val stored by remember(sessionId) { service.observe(sessionId) }.collectAsStateWithLifecycle(null)
    val running by service.running.collectAsStateWithLifecycle()
    var view by remember(sessionId) { mutableStateOf<CompanionRelationshipView?>(null) }
    var models by remember { mutableStateOf<List<CompanionConversionModel>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var modelPicker by remember { mutableStateOf(false) }
    var intervalEditor by remember { mutableStateOf(false) }
    var intervalText by remember { mutableStateOf("5") }
    var correction by remember { mutableStateOf<CompanionRelationshipEventEntity?>(null) }
    var claim by remember { mutableStateOf("") }
    var quotes by remember { mutableStateOf<List<LocalMessageEntity>?>(null) }
    var history by remember { mutableStateOf<List<CompanionRelationshipEventEntity>?>(null) }
    var showCount by remember { mutableIntStateOf(12) }
    BackHandler(onBack = onBack)
    suspend fun reload() {
        view = service.view(sessionId)
        models = repository.companionConversionModels()
    }
    fun action(block: suspend () -> Unit) {
        if (working) return
        worker.launch {
            working = true; error = null
            try { block(); reload() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "操作未完成，请重试" }
            finally { working = false }
        }
    }
    LaunchedEffect(sessionId, stored, running, refresh) {
        try { reload() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "读取状态失败" }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("情绪与关系") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("心情可以变化，关系来自长期相处。评估模型提出变化，软件核对原话并保存积累。", style = MaterialTheme.typography.bodyMedium)
            val v = view
            if (v == null) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else {
                val state = v.state
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用情绪与关系", style = MaterialTheme.typography.titleMedium)
                                Text("关闭后不再更新，也不提供给聊天；已有记录保留。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(state.enabled, enabled = !working && (state.enabled || state.modelId != null), onCheckedChange = { enabled ->
                                action { service.configure(sessionId, enabled, state.automatic, state.modelId, state.interval) }
                            })
                        }
                        val model = models.firstOrNull { it.id == state.modelId }
                        Text("状态评估模型", style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { modelPicker = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                            Text(model?.let { "${it.name}\n${it.provider} · ${it.model}" } ?: if (state.modelId == null) "选择一个模型" else "所选模型不可用，请重新选择")
                        }
                        Text("单独调用此模型，会产生对应费用。失败时保留旧状态，不自动改用聊天模型。", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("按轮次自动更新", Modifier.weight(1f))
                            Switch(state.automatic, enabled = state.enabled && !working, onCheckedChange = { automatic ->
                                action { service.configure(sessionId, state.enabled, automatic, state.modelId, state.interval) }
                            })
                        }
                        OutlinedButton(onClick = { intervalText = state.interval.toString(); intervalEditor = true }, enabled = !working) { Text("每 ${state.interval} 轮更新") }
                        Text("一轮为你发言后收到一次回复。六个方面由同一次评估判断，聊天不会等待评估完成。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.enabled && state.awaitingHistory) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("有 ${v.pending} 轮尚未评估", style = MaterialTheme.typography.titleMedium)
                        Text("是否补评由你决定。较长历史会分批调用评估模型，每批完成后保存进度。")
                        Button(onClick = { action { service.start(sessionId) } }, enabled = !working && !v.busy) { Text("补评这些对话") }
                        TextButton(onClick = { action { service.skipPending(sessionId) } }, enabled = !working && !v.busy) { Text("不补评，从现在继续") }
                    } }
                } else {
                    Text(if (v.busy) "正在后台评估，聊天仍使用上次状态…" else "尚未评估：${v.pending} 轮", style = MaterialTheme.typography.bodyMedium)
                    if (v.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { action { service.start(sessionId) } }, enabled = state.enabled && !v.busy && !working && v.pending > 0) { Text("立即更新") }
                }
                if (v.busy && state.awaitingHistory) { Text("正在分批补评，已完成的进度会保留。"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!v.busy) state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.correctionResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                v.sourceWarning?.let { warning ->
                    Text(warning, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { action { service.skipPending(sessionId) } }, enabled = !v.busy && !working) { Text("保留已有积累，从现在继续") }
                }
                if (state.updatedAt > 0) Text("最近评估：${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.updatedAt))}", style = MaterialTheme.typography.labelMedium)
                if (state.legacy) Text("包含升级前的关系积累，旧数值没有可核对的原话依据，不会伪造来源。", style = MaterialTheme.typography.bodySmall)
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("先前的心情与精力", style = MaterialTheme.typography.titleMedium)
                    Text("${state.mood} · ${state.energy}")
                    if (state.feelingReason.isNotBlank()) Text(state.feelingReason)
                    if (state.feelingSources != "[]") TextButton(onClick = { action { quotes = service.sourceQuotes(sessionId, state.feelingSources) } }) { Text("查看原话") }
                    HorizontalDivider()
                    Text("长期相处", style = MaterialTheme.typography.titleMedium)
                    v.descriptions.forEach { (key, description) -> Text("${CompanionRelationshipPolicy.dimensions[key]}：$description") }
                } }
                Text("变化依据", style = MaterialTheme.typography.titleMedium)
                if (v.events.isEmpty()) Text("还没有新的评估记录。不会为了填满六个方面而制造变化。")
                v.events.take(showCount).forEach { event ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(event.summary, style = MaterialTheme.typography.titleSmall)
                        Text(event.reason)
                        Text(if (event.corrected) "这件事的判断已修正" else "有原话依据的事件", style = MaterialTheme.typography.labelSmall)
                        Row {
                            TextButton(onClick = { action { quotes = service.sourceQuotes(sessionId, event.sourcesJson) } }) { Text("原话") }
                            TextButton(onClick = { correction = event; claim = "" }, enabled = state.enabled && !working && !v.busy) { Text("指出错误") }
                            TextButton(onClick = { action { history = service.eventHistory(sessionId, event.eventKey) } }) { Text("修改记录") }
                        }
                    } }
                }
                if (v.events.size > showCount) TextButton(onClick = { showCount += 12 }) { Text("查看更多依据") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("重新读取") } }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (modelPicker) AlertDialog(onDismissRequest = { modelPicker = false }, title = { Text("选择状态评估模型") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (models.isEmpty()) Text("请先在 API 设置添加聊天或辅助模型。")
            models.forEach { model -> TextButton(onClick = {
                modelPicker = false
                view?.state?.let { state -> action { service.configure(sessionId, state.enabled, state.automatic, model.id, state.interval) } }
            }, modifier = Modifier.fillMaxWidth()) { Text("${model.name}\n${model.provider} · ${model.model}") } }
        } }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text("关闭") } })
    if (intervalEditor) AlertDialog(onDismissRequest = { intervalEditor = false }, title = { Text("自动更新间隔") }, text = {
        OutlinedTextField(intervalText, { intervalText = it }, label = { Text("1—100 轮") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }, confirmButton = { TextButton(enabled = intervalText.toIntOrNull() in 1..100, onClick = {
        intervalEditor = false
        view?.state?.let { state -> action { service.configure(sessionId, state.enabled, state.automatic, state.modelId, intervalText.toInt()) } }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { intervalEditor = false }) { Text("取消") } })
    correction?.let { event -> AlertDialog(onDismissRequest = { correction = null }, title = { Text("核对这件事") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(event.summary)
            Text("指出哪段事实或判断有误。评估模型会核对原话，只修正这件事的影响；不会按要求直接改变感情。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(claim, { claim = it.take(2000) }, label = { Text("例如：那次是提前商量取消，并非失约") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = claim.isNotBlank(), onClick = {
        val reason = claim; correction = null
        action { service.start(sessionId, correctionKey = event.eventKey, claim = reason) }
    }) { Text("请求核对") } }, dismissButton = { TextButton(onClick = { correction = null }) { Text("取消") } }) }
    quotes?.let { rows -> AlertDialog(onDismissRequest = { quotes = null }, title = { Text("原话依据") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (rows.isEmpty()) Text("原话已不可读取，不能用记忆正文代替来源。")
            rows.forEach { Text("${if (it.role == "user") "你" else "角色"} · ${it.createdAt.take(19)}\n${it.content}") }
        }
    }, confirmButton = { TextButton(onClick = { quotes = null }) { Text("关闭") } }) }
    history?.let { items -> AlertDialog(onDismissRequest = { history = null }, title = { Text("这件事的修改记录") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.forEach { Text("${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.createdAt))}\n${it.summary}\n${it.reason}") }
        }
    }, confirmButton = { TextButton(onClick = { history = null }) { Text("关闭") } }) }
}
