package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.local.ai.ContextUsageBreakdown
import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.model.SessionCacheStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CompanionUsageContent(usedTokens: Long, maxTokens: Int?, breakdown: ContextUsageBreakdown, cache: SessionCacheStats,
    snapshot: Boolean, sentAt: Long?, reportedInput: Int?, hasSummary: Boolean, onSummary: () -> Unit, modifier: Modifier = Modifier) {
    val arcs = remember(breakdown) { companionUsageArcs(breakdown.parts) }
    var selectedName by rememberSaveable { mutableStateOf(ContextUsagePart.TOOL_DEFINITIONS.name) }
    val selected = arcs.firstOrNull { it.value.part.name == selectedName } ?: arcs.firstOrNull()
    val onSelect: (ContextUsagePart) -> Unit = { selectedName = it.name }
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val noSnapshot = snapshot && sentAt == null
    LazyColumn(modifier.fillMaxSize().testTag("usage-content"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = scheme.surface) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (snapshot) "上次完成的请求" else "当前上下文估算", style = MaterialTheme.typography.bodyMedium, color = muted)
                    CompanionUsageRing(arcs, selected?.value?.part, onSelect, usedTokens, noSnapshot)
                    selected?.let { arc ->
                        val colour = companionUsagePartColor(arc.value.part, scheme.primary)
                        Column(Modifier.fillMaxWidth().background(lerp(scheme.surface, colour, 0.08f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("usage-selected"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UsageColourDot(colour)
                                Text(companionUsageLabel(arc.value.part), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(companionUsagePercent(arc.fraction), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text("${companionUsageNumber(arc.value.tokens.toLong())} Token · ${arc.value.itemCount} 项", Modifier.padding(start = 18.dp),
                                style = MaterialTheme.typography.bodySmall, color = muted)
                            if (arc.value.part == ContextUsagePart.SUMMARY && hasSummary) TextButton(onClick = onSummary) { Text("查看摘要原文") }
                        }
                    }
                    UsageRequestFacts(maxTokens, reportedInput)
                    Text("图中占比按本地估算", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth().testTag("usage-cache"), shape = RoundedCornerShape(16.dp), color = lerp(scheme.surface, scheme.primary, 0.065f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(36.dp).background(scheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Layers, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Text("本会话累计缓存命中", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(companionUsagePercent(cache.hitRate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("usage-cache-rate"))
                    }
                    Text(if (cache.hitRate == null) "服务商未上报缓存数据"
                        else "${companionUsageNumber(cache.cachedInputTokens)} / ${companionUsageNumber(cache.inputTokens)} 输入 Token",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        item { Text("内容明细", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
        if (arcs.isEmpty()) item {
            Text(if (noSnapshot) "完成一次聊天后，这里会显示请求记录。" else "暂无可统计的内容。",
                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
        } else items(arcs, key = { it.value.part.name }) { arc ->
            val isSelected = selected?.value?.part == arc.value.part
            val label = companionUsageLabel(arc.value.part)
            val colour = companionUsagePartColor(arc.value.part, scheme.primary)
            Surface(shape = RoundedCornerShape(10.dp), color = if (isSelected) lerp(scheme.surface, colour, 0.09f) else Color.Transparent) {
                Row(Modifier.fillMaxWidth().testTag("usage-row-${arc.value.part.name}").semantics {
                    this.selected = isSelected
                    contentDescription = "$label，${arc.value.tokens} Token，${companionUsagePercent(arc.fraction)}，${arc.value.itemCount} 项"
                }.clickable(role = Role.Button, onClickLabel = "选择$label") { onSelect(arc.value.part) }
                    .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UsageColourDot(colour)
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(companionUsageNumber(arc.value.tokens.toLong()), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                        Text(companionUsagePercent(arc.fraction), style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
            }
        }
        item {
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (snapshot) "按上次实际发送内容分类估算，可能与接口上报不同；不代表下一次请求。"
                    else "按当前可见资料分类估算，可能与接口上报不同。", style = MaterialTheme.typography.bodySmall, color = muted)
                Text(sentAt?.let { "请求时间 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))}" }
                    ?: if (snapshot) "尚无已完成请求" else "还没有新版请求记录。", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable private fun UsageRequestFacts(maxTokens: Int?, reportedInput: Int?) {
    val capacity = maxTokens?.takeIf { it > 0 }?.let { "${companionUsageNumber(it.toLong())} Token" } ?: "未提供"
    val input = reportedInput?.let { "${companionUsageNumber(it.toLong())} Token" } ?: "未上报"
    if (LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UsageFact("模型上限", capacity); UsageFact("接口上报输入", input)
    } else Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { UsageFact("模型上限", capacity) }
        Box(Modifier.weight(1f)) { UsageFact("接口上报输入", input) }
    }
}
@Composable private fun UsageFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun UsageColourDot(colour: Color) { Box(Modifier.size(10.dp).background(colour, CircleShape)) }
@Composable internal fun companionUsageLabel(part: ContextUsagePart): String = when (part) {
    ContextUsagePart.SYSTEM_PROMPT -> "角色设定"
    ContextUsagePart.ASSISTANT_MESSAGES -> "AI 回复"
    ContextUsagePart.AUXILIARY_CONTEXT -> "辅助资料"
    else -> contextPartLabel(part)
}
