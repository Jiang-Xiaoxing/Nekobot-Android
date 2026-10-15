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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nekobot.app.data.local.ai.CompanionRecallSettings
import com.nekobot.app.data.local.ai.CompanionConsolidationSettings
import com.nekobot.app.data.local.CompanionEmbeddingSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionRecallSettingsScreen(
    sessionId: String, ownerName: String, ownerAvatar: String?, initial: CompanionRecallSettings,
    busy: Boolean, error: String?, onBack: () -> Unit, onSave: (Int, Int, Int, Boolean, String?, CompanionConsolidationSettings) -> Unit,
    embedding: CompanionEmbeddingSettings, onAddModel: () -> Unit, addedModelId: String? = null,
    initialConsolidation: CompanionConsolidationSettings = CompanionConsolidationSettings()
) {
    var rounds by rememberSaveable(sessionId) { mutableIntStateOf(initial.rounds) }
    var tokens by rememberSaveable(sessionId) { mutableIntStateOf(initial.tokens) }
    var perCall by rememberSaveable(sessionId) { mutableIntStateOf(initial.effectivePerCall) }
    var summaryVector by rememberSaveable(sessionId) { mutableStateOf(initialConsolidation.vectorEnabled) }
    var recentLimit by rememberSaveable(sessionId) { mutableIntStateOf(initialConsolidation.recent) }
    var triggerLimit by rememberSaveable(sessionId) { mutableIntStateOf(initialConsolidation.trigger) }
    var existingLimit by rememberSaveable(sessionId) { mutableIntStateOf(initialConsolidation.existing) }
    var vectorEnabled by rememberSaveable(sessionId) { mutableStateOf(embedding.enabled) }
    var embeddingId by rememberSaveable(sessionId) { mutableStateOf(embedding.selectedId) }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var editingLimit by rememberSaveable { mutableStateOf<String?>(null) }
    var draftLimit by rememberSaveable { mutableStateOf("") }
    var quotaInfo by rememberSaveable { mutableStateOf(false) }
    var chooseFirst by rememberSaveable { mutableStateOf(false) }
    val chosen = embedding.options.firstOrNull { it.id == embeddingId }
    val summaryVectorReady = vectorEnabled && chosen != null
    val summaryVectorState = summaryVectorSwitchState(summaryVector, summaryVectorReady)
    LaunchedEffect(addedModelId) { if (addedModelId != null) { embeddingId = addedModelId; chooseFirst = false } }
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("记忆设置") }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }, bottomBar = {
        Surface {
            Button(onClick = { onSave(rounds, tokens, perCall.coerceAtMost(tokens), vectorEnabled, embeddingId,
                summaryVectorState.settings(recentLimit, triggerLimit, existingLimit)) },
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
                    chosen != null -> embeddingModelSubtitle(chosen.name, chosen.model)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("回查额度 · ${ownerName.ifBlank { "当前角色" }}", style = MaterialTheme.typography.titleSmall)
                Box {
                    IconButton(onClick = { quotaInfo = true }, modifier = Modifier.size(32.dp).testTag("recall-quota-info")) {
                        Icon(Icons.Outlined.Info, "回查额度说明", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (quotaInfo) Popup(onDismissRequest = { quotaInfo = false }, offset = IntOffset(0, 96),
                        properties = PopupProperties(focusable = true)) {
                        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 280.dp).testTag("recall-quota-popover")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                recallQuotaExplanation(rounds, tokens, perCall).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    LimitRow("最多回查", "$rounds 轮", !busy) { draftLimit = rounds.toString(); editingLimit = "rounds" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow("资料上限", "$tokens Token", !busy) { draftLimit = tokens.toString(); editingLimit = "tokens" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow("单次上限", "${perCall.coerceAtMost(tokens)} Token", !busy) { draftLimit = perCall.coerceAtMost(tokens).toString(); editingLimit = "perCall" }
                }
            }
            Text("额度仅对当前角色生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // 显示和保存用户偏好；向量是否就绪只影响可操作性，运行时仍可回退关键词。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("记忆整理", style = MaterialTheme.typography.titleSmall)
                InfoPopoverButton("记忆整理说明", "consolidation-info", listOf(
                    "整理：把较早的聊天归纳成记忆", "近期原话只给模型参考，不会被整理", "整理在后台进行，不影响聊天"))
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("语义对照", style = MaterialTheme.typography.bodyMedium)
                        InfoPopoverButton("向量用于总结说明", "summary-vector-info", listOf(
                            "默认已经用关键词找；打开后再按意思找，换种说法也能认出是同一件事。"))
                        Spacer(Modifier.weight(1f))
                        Switch(summaryVectorState.checked, onCheckedChange = { summaryVector = it },
                            enabled = !busy && summaryVectorState.enabled, modifier = Modifier.testTag("summary-vector-switch"))
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow("保留近期原话", "$recentLimit Token", !busy) { draftLimit = recentLimit.toString(); editingLimit = "recent" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow("攒够多少开始整理", "$triggerLimit Token", !busy) { draftLimit = triggerLimit.toString(); editingLimit = "trigger" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow("参考旧记忆上限", "$existingLimit Token", !busy) { draftLimit = existingLimit.toString(); editingLimit = "existing" }
                }
            }
            Text("整理在后台进行，不影响聊天。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val range = when (kind) {
            "rounds" -> 1..100
            "perCall" -> CompanionRecallSettings.MIN_PER_CALL..tokens
            "recent" -> 1000..64000
            "trigger", "existing" -> 256..64000
            else -> 256..64000
        }
        val number = draftLimit.toIntOrNull()
        AlertDialog(onDismissRequest = { editingLimit = null }, title = { Text(when (kind) { "rounds" -> "最多回查"; "perCall" -> "单次上限"; "recent" -> "保留近期原话"; "trigger" -> "攒够多少开始整理"; "existing" -> "参考旧记忆上限"; else -> "资料上限" }) },
            text = { OutlinedTextField(draftLimit, { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) draftLimit = it },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = number == null || number !in range, supportingText = { Text("请输入 ${range.first} 至 ${range.last} 的整数") }) },
            confirmButton = { TextButton(onClick = {
                when (kind) {
                    "rounds" -> rounds = requireNotNull(number)
                    "perCall" -> perCall = requireNotNull(number)
                    "recent" -> recentLimit = requireNotNull(number)
                    "trigger" -> triggerLimit = requireNotNull(number)
                    "existing" -> existingLimit = requireNotNull(number)
                    else -> { tokens = requireNotNull(number); perCall = perCall.coerceAtMost(tokens) }
                }
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

/** 与“回查额度”说明相同样式的 i 图标气泡。 */
@Composable private fun InfoPopoverButton(description: String, tag: String, lines: List<String>) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp).testTag(tag)) {
            Icon(Icons.Outlined.Info, description, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Popup(onDismissRequest = { open = false }, offset = IntOffset(0, 96),
            properties = PopupProperties(focusable = true)) {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 280.dp).testTag("$tag-popover")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
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

/** 向量暂不可用不覆盖已保存的语义对照偏好。 */
internal data class SummaryVectorSwitchState(val checked: Boolean, val enabled: Boolean) {
    fun settings(recent: Int, trigger: Int, existing: Int) =
        CompanionConsolidationSettings(checked, recent, trigger, existing)
}

internal fun summaryVectorSwitchState(requested: Boolean, vectorReady: Boolean) =
    SummaryVectorSwitchState(checked = requested, enabled = vectorReady)

/** 回查额度说明，数字跟随当前设置。 */
internal fun recallQuotaExplanation(
    rounds: Int, tokens: Int,
    perCallSetting: Int = com.nekobot.app.data.local.ai.CompanionRecallBudget.PER_CALL_TOKENS
): List<String> {
    val perCall = minOf(tokens, perCallSetting)
    val items = if (perCall == 1500) "七八条" else "约 ${(perCall / 200).coerceAtLeast(1)} 条"
    val essay = if (tokens in 2500..6000) "，约一篇短文" else ""
    return listOf(
        "回查次数 $rounds：角色每句最多翻几次记忆",
        "总份额 $tokens ≈ ${tokens * 3 / 4}～$tokens 个汉字$essay",
        "单次 $perCall ≈ ${items}普通记忆",
        "只算查回来的资料，不算角色回复"
    )
}

/** 显示名与模型 ID 相同时只显示一行，避免重复。 */
internal fun embeddingModelSubtitle(name: String, model: String): String {
    val n = name.trim(); val m = model.trim()
    return when {
        n.isEmpty() -> m
        m.isEmpty() || n.equals(m, ignoreCase = true) -> n
        else -> n + "\n" + m
    }
}
