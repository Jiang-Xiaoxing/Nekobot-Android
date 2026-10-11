package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.nekobot.app.data.local.ai.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun RelationshipSection(title: String, badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (badge != null) RelationshipBadge(badge)
            }
            content()
        }
    }
}

@Composable
private fun RelationshipBadge(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun relationshipTime(value: String?): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }
        .getOrElse { _ -> it.take(19).replace('T', ' ') }
} ?: "未知"

@Composable
internal fun RelationshipCurrentState(view: CompanionRelationshipView, onDetail: (String, String, String?) -> Unit) {
    val state = view.state
    val fresh = view.narrative
    val feeling = fresh?.feelings
    val legacy = view.legacy
    val hasNew = fresh != null
    val knownOld = legacy?.mood?.takeUnless { it == "尚未评估" || it.isBlank() }
    val values = if (hasNew) listOf("心情" to feeling?.mood, "情绪强度" to feeling?.intensity, "精力" to feeling?.energy)
        else listOf("心情" to knownOld, "情绪强度" to null, "精力" to legacy?.energy?.takeUnless { it == "尚未评估" || it.isBlank() })
    RelationshipSection("上次感受", if (hasNew) "文字评估" else legacy?.origin) {
        val observedAt = if (hasNew) feeling?.observedAt else legacy?.observedAt
        if (observedAt != null) Text("原话时间 ${relationshipTime(observedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                values.forEachIndexed { index, (label, value) ->
                    Surface(onClick = {
                        val explanation = when (label) {
                            "情绪强度" -> "由评估模型描述当时的情绪有多明显，与好感、精力分别判断。"
                            "精力" -> "角色当时的精神与活力状态。"
                            else -> "角色在原话所对应时刻的心情，不直接代表此刻。"
                        }
                        val reason = if (hasNew) feeling?.reason else legacy?.reason
                        val oldNotice = if (!hasNew) "\n\n${legacy?.origin ?: "尚未评估"}，尚未生成新版文字。" else ""
                        val invalid = if (hasNew && feeling != null && !view.feelingsHaveSources) "\n\n原话依据已变化，暂不用于聊天。" else ""
                        val refs = if (hasNew) feeling?.sources else legacy?.sources
                        onDetail(label, "${value ?: "尚未生成"}\n\n$explanation" + reason?.takeIf { it.isNotBlank() }?.let { "\n\n依据：$it" }.orEmpty() + oldNotice + invalid,
                            refs?.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it) })
                    }, color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (hasNew && feeling != null && !view.feelingsHaveSources) "依据待核对" else value ?: if (hasNew) "尚无依据" else "尚未生成",
                                Modifier.weight(1.6f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (index < values.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
        Text(if (!hasNew && legacy?.intensity != null) "原来的数值保留在旧版记录中。" else "这是评估当时的感受，不代表此刻仍然如此。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val status = when {
            view.busy -> "正在更新，暂显示上次结果"
            !state.enabled -> "已关闭状态引用与更新"
            state.historyReviewRequired -> "等待确认历史范围"
            state.awaitingHistory -> "等待确认是否补评"
            state.notice != null -> "上次更新未完成"
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun RelationshipSummary(view: CompanionRelationshipView, working: Boolean, onUpdate: () -> Unit,
    showUpdate: Boolean = true, onDetail: (String, String, String?) -> Unit) {
    val narrative = view.narrative
    val badge = if (narrative == null) view.legacy?.origin else if (narrative.relationships.values.any { it == null }) "部分更新" else "文字评估"
    RelationshipSection("长期相处", badge) {
        CompanionRelationshipPolicy.dimensions.entries.toList().chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (key, label) ->
                    val field = narrative?.relationships?.get(key)
                    val isValid = key in view.validRelationshipKeys
                    val oldText = view.legacy?.shortDescriptions?.get(key)?.takeUnless { key in narrative?.withdrawnKeys.orEmpty() }
                    val value = if (field != null && !isValid) "依据待核对" else field?.description ?: oldText ?: "尚未评估"
                    Surface(onClick = {
                        val text = if (field != null) "${field.description}\n\n判断依据：${field.reason}" + if (!isValid) "\n\n原话或事件依据已变化，暂不用于聊天。" else ""
                            else (view.legacy?.descriptions?.get(key)?.takeIf { oldText != null } ?: "还没有可用的文字评估。") +
                                if (oldText != null) "\n\n${view.legacy?.origin}，尚未重新评估；旧数值不能充当原话依据。" else ""
                        onDetail(label, text, field?.sources?.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it) })
                    }, modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.heightIn(min = 88.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                                if (narrative != null && field == null && oldText != null) Text(view.legacy?.origin.orEmpty(), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text(if (narrative == null) "这些是已有记录，尚未生成新版文字。" else "点开可看具体理由和原话依据。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showUpdate) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (view.state.historyReviewRequired) "历史待确认：${view.pending} 轮" else "待评估对话：${view.pending} 轮", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onUpdate, enabled = view.state.enabled && !view.busy && !working && view.pending > 0 && !view.state.awaitingHistory) { Text("立即更新") }
            }
        }
    }
}
