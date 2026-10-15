package com.nekobot.app.ui.screens.characters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.CharacterQueueModel
import com.nekobot.app.data.local.ai.CharacterQueueSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 保存的是角色对已有配置的引用，不复制服务地址或密钥。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterModelQueueScreen(characterId: String, onBack: () -> Unit) {
    val repository = remember(characterId) { ServiceContainer.localRepository }
    val scope = rememberCoroutineScope()
    var settings by remember(characterId) { mutableStateOf<CharacterQueueSettings?>(null) }
    var error by remember(characterId) { mutableStateOf<String?>(null) }
    var loadVersion by remember { mutableIntStateOf(0) }
    var initialized by rememberSaveable(characterId) { mutableStateOf(false) }
    var custom by rememberSaveable(characterId) { mutableStateOf(false) }
    var order by rememberSaveable(characterId) { mutableStateOf(listOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(characterId, loadVersion) {
        error = null
        try {
            val loaded = repository.characterQueueSettings(characterId)
            settings = loaded
            if (!initialized) {
                custom = loaded.selected != null
                order = loaded.selected.orEmpty()
                initialized = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "模型配置读取失败" }
    }
    val selected = if (custom) order else null
    val dirty = settings?.let { selected != it.selected } == true
    fun leave() { if (dirty) confirmDiscard = true else onBack() }
    BackHandler { if (!saving) leave() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("聊天模型") }, navigationIcon = {
            IconButton(onClick = { leave() }, enabled = !saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        }, actions = {
            TextButton(enabled = settings != null && !saving && (!custom || order.isNotEmpty()), onClick = {
                saving = true
                error = null
                scope.launch {
                    try {
                        check(repository === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) {
                            "资料库已切换，请重新打开角色设置"
                        }
                        repository.saveCharacterQueue(characterId, selected)
                        onBack()
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
                    finally { saving = false }
                }
            }) { Text(if (saving) "保存中…" else "保存") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (settings == null) TextButton(onClick = { loadVersion++ }) { Text("重新读取") }
            }
            val state = settings
            if (state == null && error == null) CircularProgressIndicator()
            if (state != null) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text("先用排在前面的模型，失败或不可用再试下一个。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedCard {
                    QueueChoice("沿用默认队列", "使用 API 设置中的默认队列和选择规则。", !custom, !saving) { custom = false }
                    HorizontalDivider()
                    QueueChoice("单独设置", "只按下方顺序尝试，全部不可用时提示错误。", custom, !saving) {
                        if (!custom && order.isEmpty()) order = state.defaults
                        custom = true
                    }
                }
                val visibleIds = if (custom) order else state.defaults
                Text(if (custom) "这个角色的队列" else "默认队列", style = MaterialTheme.typography.titleMedium)
                if (visibleIds.isEmpty()) {
                    Text(if (custom) "还没有选择模型，点击下方添加。" else "暂无可用聊天模型，请先到 API 设置添加。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                visibleIds.forEachIndexed { index, id ->
                    key(id) {
                        val model = state.models.firstOrNull { it.id == id }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("${index + 1} · ${if (index == 0) "主用" else "备用"}",
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                                QueueModelDescription(model)
                                if (custom) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        IconButton(enabled = !saving && index > 0, onClick = {
                                            order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                        }) { Icon(Icons.Filled.ArrowUpward, "上移第 ${index + 1} 个模型") }
                                        IconButton(enabled = !saving && index < order.lastIndex, onClick = {
                                            order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                        }) { Icon(Icons.Filled.ArrowDownward, "下移第 ${index + 1} 个模型") }
                                        IconButton(enabled = !saving, onClick = { order = order - id }) {
                                            Icon(Icons.Filled.Close, "从队列移除 ${model?.name ?: "已删除的模型"}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (custom) OutlinedButton(onClick = { adding = true }, enabled = !saving && order.size < 100,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("添加已有模型")
                }
                Text("同一角色的会话共用这份选择。其他角色的顺序不变。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("服务地址、密钥和模型参数仍在 API 设置维护；情绪评估和向量模型使用各自的设置。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (adding) {
        val candidates = settings?.models.orEmpty().filter { it.enabled && it.chat && it.id !in order }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加聊天模型") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) Text("没有可添加的模型。请先在 API 设置添加或启用聊天模型。")
                candidates.forEach { model ->
                    Column(Modifier.fillMaxWidth().clickable {
                        order = order + model.id; adding = false
                    }.padding(vertical = 16.dp)) { QueueModelDescription(model) }
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = { adding = false }) { Text("关闭") } })
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃未保存的调整？") }, text = { Text("已保存的模型顺序不会改变。") },
        confirmButton = { TextButton(onClick = onBack) { Text("放弃调整") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
}

@Composable
private fun QueueChoice(title: String, detail: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = choose).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = choose, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueModelDescription(model: CharacterQueueModel?) {
    Text(model?.name ?: "已删除的模型", style = MaterialTheme.typography.titleSmall)
    if (model != null) Text("${model.provider} · ${model.model}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val issue = when {
        model == null -> "不可用，将跳过；可从队列移除"
        !model.enabled -> "已停用，将跳过"
        !model.chat -> "已改为其他用途，将跳过"
        else -> null
    }
    issue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
