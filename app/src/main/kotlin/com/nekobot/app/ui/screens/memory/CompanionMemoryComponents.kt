package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.CompanionMemoryOverview
import com.nekobot.app.data.local.ai.CompanionImportPolicy
import com.nekobot.app.data.local.ai.CompanionCoreMemory
import com.nekobot.app.data.local.ai.FusedCompanionPolicy
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.db.CompanionMemoryEntity

// 界面与记忆整理共用分类名称，避免仅在界面覆盖后与模型规则脱节。
internal val memoryCategoryNames = FusedCompanionPolicy.categories

@Composable
internal fun memoryAccent(): Color = MaterialTheme.colorScheme.primary

@Composable
fun CompanionMemoryTheme(content: @Composable () -> Unit) = content()

@Composable
internal fun memoryCategoryColor(category: String): Color = MaterialTheme.colorScheme.primary

@Composable
internal fun CompanionMemoryIntro(title: String, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Psychology, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CompanionMemoryNotice(title: String, message: String, onRetry: (() -> Unit)? = null) {
    var details by remember(message) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { details = !details }) { Text(if (details) "收起原因" else "查看原因") }
                Spacer(Modifier.weight(1f))
                onRetry?.let { TextButton(onClick = it) { Text("重新读取") } }
            }
            if (details) SelectionContainer { Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
internal fun CompanionMemoryOverviewCard(
    ownerName: String,
    overview: CompanionMemoryOverview?,
    memoryCount: Int,
    pinnedTokens: Int,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    var details by rememberSaveable(ownerName) { mutableStateOf(false) }
    val accent = memoryAccent()
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.12f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ownerName.ifBlank { "角色的独立记忆" }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("已保存 $memoryCount 条记忆", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, "刷新状态", tint = accent)
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
            overview?.error?.let {
                CompanionMemoryNotice("有一部分记忆尚未整理完成", it)
            }
            Row(Modifier.fillMaxWidth().clickable { details = !details }.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(if (overview == null) { if (loading) "正在读取整理情况" else "整理情况暂不可用" } else
                    "已整理 ${overview.processedMessages} · 未整理 ${overview.pendingMessages}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (details) "收起详情" else "整理详情", style = MaterialTheme.typography.labelMedium, color = accent)
                Icon(if (details) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (details) "收起整理详情" else "展开整理详情", Modifier.size(20.dp), tint = accent)
            }
            if (details) {
                Text("原话始终保留。未整理数量包含近期原话；较早的聊天达到整理量后，APP 调用模型归纳，再核对来源并保存。\n最近检索：${overview?.lastRetrieval ?: "尚未聊天"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("核心记忆约 $pinnedTokens Token，每次完整提供，不扣主动回查额度；仍占模型上下文。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun CompanionMemoryFilters(
    query: String, onQueryChange: (String) -> Unit,
    category: String, onCategoryChange: (String) -> Unit,
    counts: Map<String, Int>, total: Int, visibleCount: Int = total
) {
    val accent = memoryAccent()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = query, onValueChange = onQueryChange, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            placeholder = { Text("搜索记忆、主题或标签") },
            leadingIcon = { Icon(Icons.Filled.Search, "搜索记忆") },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, "清空搜索")
            } },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MemoryCategorySelector(category, onCategoryChange, counts = counts, total = total,
                includeAll = true, modifier = Modifier.weight(1f))
            Text("$visibleCount 条", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionMemoryCard(
    memory: CompanionMemoryEntity, enabled: Boolean,
    onSource: () -> Unit, onEdit: () -> Unit, onVersions: () -> Unit, onDelete: () -> Unit,
    onCoreChange: (() -> Unit)? = null
) {
    val accent = memoryCategoryColor(memory.category)
    var menu by remember(memory.id) { mutableStateOf(false) }
    val tags = remember(memory.tagsJson) { MemoryTags.fromJson(memory.tagsJson) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, accent.copy(alpha = 0.10f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(memoryCategoryIcon(memory.category), null, Modifier.size(19.dp), tint = accent)
                Spacer(Modifier.width(7.dp))
                Text(memoryCategoryNames[memory.category] ?: memory.category, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = accent)
                MemoryStatusLabel(memory.status)
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Filled.MoreHoriz, "更多记忆操作") }
                    DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
                        if (onCoreChange != null && memory.current && memory.status in setOf("active", "completed")) {
                            DropdownMenuItem(text = { Text(if (CompanionCoreMemory.contains(memory)) "移出核心记忆" else "设为核心记忆") },
                                leadingIcon = { CoreMemoryIcon() },
                                onClick = { menu = false; onCoreChange() })
                        }
                        DropdownMenuItem(text = { Text("编辑记忆") }, leadingIcon = { EditMemoryIcon() },
                            onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("修改记录") }, leadingIcon = { MemoryHistoryIcon() },
                            onClick = { menu = false; onVersions() })
                        DropdownMenuItem(text = { Text("删除记忆", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            SelectionContainer {
                Text(memory.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            if (!memory.eventDate.isNullOrBlank()) {
                Text(memory.eventDate.orEmpty(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (memory.category == "episode") {
                Text("发生日期未确定", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { Text("#$it", style = MaterialTheme.typography.labelMedium, color = accent) }
            }
            if (CompanionCoreMemory.contains(memory) || memory.locked || memory.manual) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (CompanionCoreMemory.contains(memory)) MemoryBadge("核心记忆") { CoreMemoryIcon(Modifier.size(16.dp)) }
                if (memory.locked) MemoryBadge("只允许我修改") {
                    Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = memoryAccent())
                }
                if (memory.manual) MemoryBadge("手动记忆") { EditMemoryIcon(Modifier.size(16.dp)) }
            }
            if (memory.provenance == CompanionImportPolicy.IMPORTED_UNVERIFIED && !memory.manual) {
                Text("迁入的旧记忆 · 原话来源未验证", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium)
            }
            memory.expiresAt?.let { Text("有效期至 $it", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSource, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = accent)) {
                    Icon(Icons.Filled.FormatQuote, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("查看原话")
                }
            }
        }
    }
}

@Composable
private fun MemoryBadge(label: String, icon: (@Composable () -> Unit)? = null) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { it(); Spacer(Modifier.width(4.dp)) }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
}

@Composable
private fun MemoryStatusLabel(status: String) {
    val color = if (status in setOf("conflict", "locked_conflict")) MaterialTheme.colorScheme.error else memoryAccent()
    val label = when (status) {
        "active" -> "有效"
        "completed" -> "已完成"
        "cancelled", "canceled" -> "已取消"
        "conflict" -> "待核对"
        "locked_conflict" -> "需你修改"
        "retracted" -> "已撤回"
        "deleted" -> "已删除"
        "expired" -> "已过期"
        "superseded" -> "历史版本"
        else -> status
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

internal fun memoryCategoryIcon(category: String): ImageVector = when (category) {
    "user_profile" -> Icons.Filled.PersonOutline
    "character_profile" -> Icons.Filled.Face
    "relationship" -> Icons.Filled.FavoriteBorder
    "episode" -> Icons.AutoMirrored.Filled.MenuBook
    "promise" -> Icons.Filled.EventAvailable
    else -> Icons.AutoMirrored.Filled.HelpOutline
}

@Composable
internal fun CompanionMemoryEmpty(filtered: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Psychology, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (filtered) "没有找到这段记忆" else "记忆会慢慢积累", style = MaterialTheme.typography.titleMedium)
        Text(if (filtered) "换个关键词，或查看其他分类。" else "较早的聊天达到整理量后会自动保存，也可以点右上角添加。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
