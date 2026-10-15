package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.ContextUsageBreakdown
import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.model.SessionCacheStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CompanionUsageContent(
    usedTokens: Long, maxTokens: Int?, breakdown: ContextUsageBreakdown, cache: SessionCacheStats,
    snapshot: Boolean, sentAt: Long?, reportedInput: Int?, hasSummary: Boolean, onSummary: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            CompanionPreferenceGroup(if (snapshot) "上次完成的请求" else "当前上下文估算") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (snapshot && sentAt == null) "暂无请求记录" else "约 ${String.format(Locale.getDefault(), "%,d", usedTokens)} Token",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { maxTokens?.takeIf { it > 0 }?.let { (usedTokens.toFloat() / it).coerceIn(0f, 1f) } ?: 0f },
                        modifier = Modifier.fillMaxWidth().height(10.dp), color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
                    Text(maxTokens?.takeIf { it > 0 }?.let { "模型上限 ${String.format(Locale.getDefault(), "%,d", it)} Token" } ?: "模型上限未提供",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("服务商输入用量", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(reportedInput?.let { "$it Token" } ?: "—", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item {
            CompanionPreferenceGroup {
                CompanionPreferenceRow("本会话缓存命中", Icons.Outlined.Layers,
                    subtitle = if (cache.hitRate == null) "服务商未上报缓存数据" else "${cache.cachedInputTokens} / ${cache.inputTokens} 输入 Token",
                    trailing = { Text(formatCacheHitRate(cache.hitRate), fontWeight = FontWeight.SemiBold) })
            }
        }
        item { Text("内容构成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        if (breakdown.parts.isEmpty()) item { CompanionReadingHint(stringResource(R.string.chat_context_analysis_empty)) }
        else items(breakdown.parts, key = { it.part.name }) { part ->
            val clickable = part.part == ContextUsagePart.SUMMARY && hasSummary
            val fraction = if (breakdown.totalTokens > 0) (part.tokens.toFloat() / breakdown.totalTokens).coerceIn(0f, 1f) else 0f
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().then(if (clickable) Modifier.clickable(onClick = onSummary) else Modifier)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(contextPartLabel(part.part), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("约 ${part.tokens} Token", style = MaterialTheme.typography.bodyMedium)
                        if (clickable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    Text("${part.itemCount} 项 · ${(fraction * 100).toInt()}%" + if (clickable) " · 点开查看摘要原文" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (snapshot) "按上次实际发送内容分类估算，可能与服务商用量不同；不代表下一次请求。" else stringResource(R.string.chat_context_analysis_basis),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sentAt?.let { "请求时间 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))}" }
                    ?: if (snapshot) "完成一次聊天后，这里会显示请求记录。" else "还没有新版请求记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
