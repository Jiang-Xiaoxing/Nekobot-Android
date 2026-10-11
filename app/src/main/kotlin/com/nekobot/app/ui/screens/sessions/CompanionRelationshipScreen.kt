package com.nekobot.app.ui.screens.sessions

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.nekobot.app.ui.screens.settings.AppearanceSettingRow
import com.nekobot.app.ui.screens.settings.AppearanceDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.CompanionConversionModel
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.CompanionRelationshipEventEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    var detail by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var oldRecordsOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var evidenceExpanded by rememberSaveable { mutableStateOf(false) }
    var background by remember { mutableStateOf(ServiceContainer.prefs.relationshipBackground) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { background = ServiceContainer.prefs.relationshipBackground }
    var characterName by remember(sessionId) { mutableStateOf<String?>(null) }
    var assessments by remember { mutableStateOf<List<com.nekobot.app.data.local.db.CompanionRelationshipAssessmentEntity>?>(null) }
    var showCount by remember { mutableIntStateOf(12) }
    val androidView = LocalView.current
    val systemBars = remember(androidView) {
        (androidView.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, androidView) }
    }
    DisposableEffect(systemBars) {
        val wasLight = systemBars?.isAppearanceLightStatusBars
        onDispose { if (wasLight != null) systemBars?.isAppearanceLightStatusBars = wasLight }
    }
    SideEffect { systemBars?.isAppearanceLightStatusBars = false }
    BackHandler(onBack = onBack)
    suspend fun reload() {
        view = service.view(sessionId)
        characterName = repository.getCharacter(requireNotNull(view).state.characterId)?.name
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
    RelationshipSkinTheme(background) {
    RelationshipJournalBackdrop(background) {
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text("关系状态", color = Color.White) }, navigationIcon = {
        Surface(shape = RoundedCornerShape(16.dp), color = journalPaperColor(), shadowElevation = 2.dp,
            modifier = Modifier.padding(start = 10.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            characterName?.let { name ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = CircleShape, color = journalPaperColor(), shadowElevation = 3.dp) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Text(name.trim().take(1), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            val v = view
            if (v == null) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else {
                val state = v.state
                RelationshipCurrentState(v) { title, text, sources -> detail = Triple(title, text, sources) }
                RelationshipSummary(v, working, onUpdate = { action { service.start(sessionId) } }) { title, text, sources ->
                    detail = Triple(title, text, sources)
                }
                if (state.enabled && state.awaitingHistory) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.historyReviewRequired) "历史范围待确认：${v.pending} 轮" else "有 ${v.pending} 轮尚未评估", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.historyReviewRequired) "旧版可能把迁入时的初始关系当成了评估进度。无法确认旧历史是否评估或曾被跳过，请选择是否从头补评；现有聊天不变。补评会分批调用所选模型并产生费用。" else "是否补评由你决定。较长历史会分批调用评估模型，每批完成后保存进度。")
                        Button(onClick = { action { service.start(sessionId) } }, enabled = !working && !v.busy) { Text("补评这些对话") }
                        TextButton(onClick = { action { service.skipPending(sessionId) } }, enabled = !working && !v.busy) { Text("不补评，从现在继续") }
                    } }
                }
                if (v.busy && !state.awaitingHistory) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (v.busy && state.awaitingHistory) RelationshipPaper {
                    Text("正在分批补评，已完成的进度会保留。")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (!v.busy) state.notice?.let { RelationshipPaper { Text(it, color = MaterialTheme.colorScheme.error) } }
                state.correctionResult?.let { RelationshipPaper { Text(it, style = MaterialTheme.typography.bodySmall) } }
                v.sourceWarning?.let { warning ->
                    RelationshipPaper {
                        Text(warning, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { action { service.skipPending(sessionId) } }, enabled = !v.busy && !working) { Text("保留已有积累，从现在继续") }
                    }
                }
                if (state.updatedAt > 0) Surface(shape = RoundedCornerShape(8.dp), color = journalPaperColor()) {
                    Text("最近评估：${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.updatedAt))}",
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                }
                JournalAction("评估设置", "模型与更新频率", Icons.Filled.Tune, onClick = { settingsExpanded = !settingsExpanded })
                if (settingsExpanded) RelationshipSection("评估设置") {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("启用关系状态", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(state.enabled, enabled = !working && (state.enabled || state.modelId != null), onCheckedChange = { enabled ->
                                    action { service.configure(sessionId, enabled, state.automatic, state.modelId, state.interval) }
                                })
                            }
                            AppearanceDivider()
                            AppearanceSettingRow("评估模型", onClick = { if (!working) modelPicker = true }) {
                                val model = models.firstOrNull { it.id == state.modelId }
                                Text(model?.name ?: if (state.modelId == null) "请选择" else "不可用", modifier = Modifier.widthIn(max = 140.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                            }
                            AppearanceDivider()
                            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("按轮次自动更新", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(state.automatic, enabled = state.enabled && !working, onCheckedChange = { automatic ->
                                    action { service.configure(sessionId, state.enabled, automatic, state.modelId, state.interval) }
                                })
                            }
                            AppearanceDivider()
                            AppearanceSettingRow("更新间隔", onClick = { if (!working) { intervalText = state.interval.toString(); intervalEditor = true } }) {
                                Text("每 ${state.interval} 轮", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                            }
                        }
                    }
                    Text("单独调用所选模型，会产生对应费用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    JournalAction("变化依据", icon = Icons.Filled.History, onClick = { evidenceExpanded = !evidenceExpanded }, modifier = Modifier.weight(1f).fillMaxHeight())
                    JournalAction("旧版记录", icon = Icons.Filled.Archive, onClick = { oldRecordsOpen = true }, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                if (evidenceExpanded) {
                RelationshipPaper { JournalHeading("变化依据") }
                if (v.events.isEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Text("暂无新的评估记录", Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            }
            error?.let { RelationshipPaper {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text("重新读取") }
            } }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
    detail?.let { item -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(item.first) }, text = {
        Text(item.second, modifier = Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }, dismissButton = {
        if (item.third != null) TextButton(onClick = {
            detail = null
            action { quotes = service.sourceQuotes(sessionId, item.third!!) }
        }) { Text("查看原话") }
    }) }
    if (oldRecordsOpen) AlertDialog(onDismissRequest = { oldRecordsOpen = false }, title = { Text("旧版记录") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val old = view?.legacy
            if (old == null) Text("没有可读取的旧版记录。") else {
                Text("${old.origin}，不会因新版文字评估而删除。旧数值不是当前模型的新判断。", style = MaterialTheme.typography.bodySmall)
                old.observedAt?.let { Text("原话时间 ${relationshipTime(it)}") }
                Text("心情：${old.mood}\n精力：${old.energy}\n原情绪强度：${old.intensity?.let { "${(it * 100).roundToInt()}%" } ?: "未记录"}")
                if (old.reason.isNotBlank()) Text(old.reason)
                CompanionRelationshipPolicy.dimensions.forEach { (key, label) ->
                    Text("$label：${old.descriptions[key] ?: "暂无文字描述"}\n原数值：${old.totals[key] ?: "未记录"}")
                }
                Text("旧记录未提供原话依据的部分，不补造来源。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { oldRecordsOpen = false }) { Text("关闭") } }, dismissButton = {
        TextButton(onClick = { oldRecordsOpen = false; action { assessments = service.assessmentHistory(sessionId) } }) { Text("历次文字评估") }
    })
    assessments?.let { items -> AlertDialog(onDismissRequest = { assessments = null }, title = { Text("历次文字评估") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (items.isEmpty()) Text("还没有新版文字评估记录。")
            items.forEach { saved ->
                val snapshot = CompanionRelationshipNarrative.read(saved.narrativeJson)
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(saved.createdAt)), style = MaterialTheme.typography.titleSmall)
                if (snapshot == null) Text("这份记录暂不可读取。") else {
                    snapshot.feelings?.let { feeling -> Text("当时感受（${relationshipTime(feeling.observedAt)}）：\n心情：${feeling.mood ?: "未知"}\n情绪强度：${feeling.intensity ?: "未知"}\n精力：${feeling.energy ?: "未知"}\n${feeling.reason}") }
                    snapshot.relationships.forEach { (key, field) -> field?.let { Text("${CompanionRelationshipPolicy.dimensions[key]}：${it.description}\n${it.reason}") } }
                }
            }
            if (items.size >= 50) Text("这里显示最近50份文字评估；更早记录仍保留在导出资料中。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { assessments = null }) { Text("关闭") } }) }
    if (modelPicker) AlertDialog(onDismissRequest = { modelPicker = false }, title = { Text("选择状态评估模型") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("关闭关系状态后不再更新，也不提供给聊天；已有记录保留。评估失败保留旧状态，不自动切换聊天模型。", style = MaterialTheme.typography.bodySmall)
            if (models.isEmpty()) Text("请先在 API 设置添加聊天或辅助模型。")
            models.forEach { model -> TextButton(onClick = {
                modelPicker = false
                view?.state?.let { state -> action { service.configure(sessionId, state.enabled, state.automatic, model.id, state.interval) } }
            }, modifier = Modifier.fillMaxWidth()) { Text("${model.name}\n${model.provider} · ${model.model}") } }
        } }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text("关闭") } })
    if (intervalEditor) AlertDialog(onDismissRequest = { intervalEditor = false }, title = { Text("自动更新间隔") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("一轮为你发言后收到一次回复。一次评估会同时判断心情、情绪强度、精力和六项关系，聊天不会等待评估完成。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(intervalText, { intervalText = it }, label = { Text("1—100 轮") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
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
}
