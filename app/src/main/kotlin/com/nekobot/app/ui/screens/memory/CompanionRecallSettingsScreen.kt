package com.nekobot.app.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.local.ai.CompanionRecallSettings
import com.nekobot.app.data.local.CompanionEmbeddingSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionRecallSettingsScreen(
    sessionId: String, ownerName: String, ownerAvatar: String?, initial: CompanionRecallSettings,
    busy: Boolean, error: String?, onBack: () -> Unit, onSave: (Int, Int, Boolean, String?) -> Unit,
    embedding: CompanionEmbeddingSettings, onAddModel: () -> Unit, addedModelId: String? = null
) {
    var rounds by rememberSaveable(sessionId) { mutableIntStateOf(initial.rounds) }
    var tokens by rememberSaveable(sessionId) { mutableIntStateOf(initial.tokens) }
    var vectorEnabled by rememberSaveable(sessionId) { mutableStateOf(embedding.enabled) }
    var embeddingId by rememberSaveable(sessionId) { mutableStateOf(embedding.selectedId) }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var editingLimit by rememberSaveable { mutableStateOf<String?>(null) }
    var draftLimit by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    var chooseFirst by rememberSaveable { mutableStateOf(false) }
    val chosen = embedding.options.firstOrNull { it.id == embeddingId }
    LaunchedEffect(addedModelId) { if (addedModelId != null) { embeddingId = addedModelId; chooseFirst = false } }
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("记忆设置") }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }, bottomBar = {
        Surface {
            Button(onClick = { onSave(rounds, tokens, vectorEnabled, embeddingId) },
                enabled = !busy && (!vectorEnabled || chosen != null), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp).heightIn(min = 52.dp)) {
                Text(if (busy) "正在保存…" else "保存设置")
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("检索设置 · 所有单会话角色通用", style = MaterialTheme.typography.titleSmall)
            RetrievalCard {
                RetrievalHeading(Icons.AutoMirrored.Outlined.MenuBook, "关键词检索", "按文字和词语在本地查找") {
                    Text("始终可用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            RetrievalCard {
                RetrievalHeading(Icons.Outlined.Search, "向量检索", "按相近意思找记忆，换种说法也能查") {
                    Switch(vectorEnabled, onCheckedChange = { requested ->
                        if (requested && chosen == null) { chooseFirst = true; choosingModel = true }
                        else { vectorEnabled = requested; chooseFirst = false }
                    }, enabled = !busy, modifier = Modifier.testTag("companion-vector-switch"))
                }
                Text(if (vectorEnabled) "当前使用关键词＋向量" else "当前仅使用关键词",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (chooseFirst) Text("请先选择模型，再主动开启向量检索。", style = MaterialTheme.typography.bodySmall)
            }
            RetrievalCard {
                RetrievalHeading(Icons.Outlined.Hub, "向量模型", when {
                    chosen != null -> chosen.name + "\n" + chosen.model
                    !embeddingId.isNullOrBlank() -> "原来选择的模型已不可用"
                    else -> "尚未选择"
                }) {}
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choosingModel = true }, enabled = !busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), shape = RoundedCornerShape(12.dp)) { Text("选择已有模型") }
                    OutlinedButton(onClick = onAddModel, enabled = !busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), shape = RoundedCornerShape(12.dp)) { Text("添加向量模型") }
                }
                Text("向量调用按所选服务商计费。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vectorEnabled || !embeddingId.isNullOrBlank() && chosen == null) Text("模型不可用或索引未就绪时，暂用关键词。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("共用模型，记忆仍按角色分开。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text("${ownerName.ifBlank { "当前角色" }}的回查额度", style = MaterialTheme.typography.titleSmall)
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    LimitRow("最多回查", "$rounds 轮", !busy) { draftLimit = rounds.toString(); editingLimit = "rounds" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow("资料上限", "$tokens Token", !busy) { draftLimit = tokens.toString(); editingLimit = "tokens" }
                }
            }
            Text("额度仅对当前角色生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }) { Text(if (details) "收起说明" else "额度怎么算？") }
            if (details) Text("一轮指模型发起一批查询、软件返回结果，再由模型继续判断。同一条回复内共用额度，暂停后继续也不会清零。\n\n画像、经历、约定、旧记录和原话共用额度；自动提供的记忆不扣这里的额度。达到上限后根据已有资料回答。\n\n资料按 Token 估算，并受模型剩余上下文限制；它不是整次请求的费用上限。调高数值可能增加等待和模型用量。额度从下一条新消息开始使用。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { CompanionMemoryNotice("设置尚未保存", it) }
        }
    }
    if (choosingModel) ModalBottomSheet(onDismissRequest = { choosingModel = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选择已有向量模型", style = MaterialTheme.typography.titleLarge)
            Text("选择不会自动开启检索；点“保存设置”后生效。", style = MaterialTheme.typography.bodySmall)
            embedding.options.forEach { option ->
                OutlinedButton(onClick = { embeddingId = option.id; choosingModel = false; chooseFirst = false },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(option.name, style = MaterialTheme.typography.titleSmall)
                        Text("${option.provider.ifBlank { "自定义服务商" }} · ${option.model}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (option.id == embeddingId) Text("已选")
                }
            }
            if (embedding.options.isEmpty()) Text("还没有可用的向量模型。", Modifier.padding(vertical = 16.dp))
            TextButton(onClick = { choosingModel = false; onAddModel() }) { Text("添加向量模型") }
        }
    }
    editingLimit?.let { kind ->
        val range = if (kind == "rounds") 1..100 else 256..64000
        val number = draftLimit.toIntOrNull()
        AlertDialog(onDismissRequest = { editingLimit = null }, title = { Text(if (kind == "rounds") "最多回查" else "资料上限") },
            text = { OutlinedTextField(draftLimit, { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) draftLimit = it },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = number == null || number !in range, supportingText = { Text("请输入 ${range.first} 至 ${range.last} 的整数") }) },
            confirmButton = { TextButton(onClick = {
                if (kind == "rounds") rounds = requireNotNull(number) else tokens = requireNotNull(number)
                editingLimit = null
            }, enabled = number != null && number in range) { Text("确定") } },
            dismissButton = { TextButton(onClick = { editingLimit = null }) { Text("取消") } })
    }
}

@Composable private fun RetrievalCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun RetrievalHeading(icon: ImageVector, title: String, description: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable private fun LimitRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
