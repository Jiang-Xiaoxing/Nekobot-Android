package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.data.local.ai.CompanionImportPolicy
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.ai.estimateLocalTextTokens
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.CompanionSourceEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 记忆入口内的宽面板：正文独立滚动，底部动作始终可见，避开系统栏与键盘。 */
@Composable
private fun MemoryPanel(
    title: String, subtitle: String, busy: Boolean = false, onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Filled.Close, "关闭") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    content(Modifier.weight(1f, fill = false))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionMemoryEditor(
    memory: CompanionMemoryEntity?, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (String, String, List<String>, Boolean, Boolean) -> Unit
) {
    var content by rememberSaveable(memory?.id) { mutableStateOf(memory?.content.orEmpty()) }
    var tags by rememberSaveable(memory?.id) { mutableStateOf(memory?.let { MemoryTags.fromJson(it.tagsJson).joinToString("，") }.orEmpty()) }
    var category by rememberSaveable(memory?.id) { mutableStateOf(memory?.category ?: "user_profile") }
    var pinned by rememberSaveable(memory?.id) { mutableStateOf(memory?.let { CompanionCoreMemory.contains(it) } ?: false) }
    var locked by rememberSaveable(memory?.id) { mutableStateOf(memory?.locked ?: false) }
    MemoryPanel(if (memory == null) "添加一条记忆" else "编辑这条记忆", "写下你希望角色记住的事", busy, onDismiss,
        actions = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            Button(onClick = { onSave(content, category, tags.split(',', '，').map(String::trim).filter(String::isNotEmpty), pinned, locked) },
                enabled = !busy && content.isNotBlank() && content.length <= 1500) {
                Text(if (busy) "正在保存…" else "保存记忆")
            }
        }) { body ->
        Column(body.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            error?.let { CompanionMemoryNotice("这条记忆还没有保存", it) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("记忆正文") },
                    placeholder = { Text("例如：我现在更喜欢喝无糖茉莉花茶。") }, minLines = 4, maxLines = 9,
                    shape = RoundedCornerShape(16.dp), isError = content.length > 1500,
                    supportingText = { Text("${content.length} / 1500 字符") })
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("放在哪一类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                MemoryCategorySelector(category, { category = it }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text(when (category) {
                    "user_profile" -> "你的喜好、习惯和个人资料。"
                    "character_profile" -> "角色在相处中表现出的特点。"
                    "relationship" -> "你们的关系，以及关系发生的变化。"
                    "episode" -> "实际发生过的事，包括独自经历和共同经历。"
                    "promise" -> "答应要做的事，以及完成或取消的情况。"
                    else -> "还没有结果、需要后续关注的事情。"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("标签（可选）") }, placeholder = { Text("饮料，喜好") },
                supportingText = { Text("用逗号分开，方便以后查找。") }, shape = RoundedCornerShape(16.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)) {
                Column {
                    MemoryOption("设为核心记忆", "每次聊天都会带上。手动移入或移出核心后，模型不会改回；移出不删除正文。",
                        pinned, !busy) { pinned = it }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    MemoryOption("只允许我修改", "AI 不会自动改写；你仍然可以编辑或删除。", locked, !busy) { locked = it }
                }
            }
        }
    }
}

@Composable
private fun MemoryOption(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch, onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun CompanionMemoryChanges(rows: List<CompanionMemoryEntity>, onDismiss: () -> Unit) {
    MemoryPanel("修改记录", "查看这条记忆以前写过什么。打开此页不会调用模型。", onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss) { Text("知道了") } }) { body ->
        LazyColumn(body.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (rows.isEmpty()) item { Text("还没有可查看的修改记录。") }
            items(rows, key = { it.id }) { row ->
                Surface(shape = RoundedCornerShape(18.dp), color = if (row.current)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when {
                            row.current -> "当前内容"
                            row.status == "conflict" -> "待核对的内容"
                            row.status == "locked_conflict" -> "等待你修改的内容"
                            else -> "以前的内容"
                        }, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge, color = memoryAccent())
                        SelectionContainer { Text(row.content.ifBlank { "这条内容已删除" }, style = MaterialTheme.typography.bodyLarge) }
                        Text("${memorySavedTime(row.updatedAt)} · ${if (row.manual) "手动保存" else "自动整理"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun memorySavedTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value.ifBlank { "保存时间未记录" })

@Composable
internal fun CompanionMemorySources(
    rows: List<CompanionSourceEntity>, memory: CompanionMemoryEntity?, ownerName: String,
    onDismiss: () -> Unit, onLocate: (String) -> Unit
) {
    MemoryPanel("这条记忆的原话", "引用供你核对，点开可以查看前后聊天。", onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss) { Text("关闭") } }) { body ->
        LazyColumn(body.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            memory?.let { item("memory") {
                Text("记忆内容", style = MaterialTheme.typography.labelLarge, color = memoryAccent())
                Text(it.content, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (rows.isEmpty()) item("empty") {
                CompanionMemoryIntro("还没有对应的原话", when {
                    memory?.manual == true -> "这条记忆由你手动填写，没有附带聊天来源。"
                    memory?.provenance == CompanionImportPolicy.IMPORTED_UNVERIFIED -> "这条记忆来自旧资料，原话来源还没有核验。"
                    else -> "没有找到可核对的聊天来源，请先检查记忆是否准确。"
                })
            }
            items(rows, key = { it.messageId }) { source ->
                val isUser = source.role in setOf("user", "human")
                Surface(shape = RoundedCornerShape(18.dp), color = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (isUser) "你" else ownerName.ifBlank { "角色" },
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = memoryAccent())
                        SelectionContainer { Text(source.quote, style = MaterialTheme.typography.bodyLarge) }
                        source.spokenDate?.takeIf(String::isNotBlank)?.let { Text("发言日期：$it",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { onLocate(source.messageId) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("去聊天中查看")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompanionMemoryDelete(memory: CompanionMemoryEntity, busy: Boolean, error: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    MemoryPanel("删除这条记忆？", "聊天原话仍然保留。", busy, onDismiss,
        actions = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("保留记忆") }
            Button(onClick = onDelete, enabled = !busy, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "正在删除…" else "删除记忆")
            }
        }) { body ->
        Column(body.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { CompanionMemoryNotice("还没有删除成功", it) }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Text(memory.content, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text("这条记忆及其修改记录会被清除。已整理进度不变；以后新的明确表达仍可能形成新的记忆。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
