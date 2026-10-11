package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.local.ai.CompanionRelationshipPolicy
import com.nekobot.app.data.local.ai.CompanionRelationshipView
import kotlin.math.roundToInt

@Composable
internal fun RelationshipSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun statusColors(): List<Color> = if (isSystemInDarkTheme()) listOf(
    MaterialTheme.colorScheme.primary, Color(0xFF89BCFF), Color(0xFFC4A6FF), Color(0xFFFFBD80),
    MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant
) else listOf(MaterialTheme.colorScheme.primary, Color(0xFF2874CB), Color(0xFF7954BE), Color(0xFFAA641E),
    MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun RelationshipCurrentState(view: CompanionRelationshipView, onDetail: (String, String, Boolean) -> Unit) {
    val state = view.state
    val strength = state.moodIntensity?.takeIf { view.feelingsHaveSources }?.let { "${(it * 100).roundToInt()}%" } ?: "尚未评估"
    val colors = statusColors()
    val values = listOf("心情" to state.mood, "情绪强度" to strength, "精力" to state.energy)
    RelationshipSection("当前状态") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEachIndexed { index, (label, value) ->
                StatusTile(label, value, colors[index], Modifier.weight(1f), centered = true) {
                    val explanation = when (label) {
                        "情绪强度" -> "当前心情的强烈程度。0% 较弱，100% 很强；不是好感、精力或模型的把握程度。没有评估依据时不填数值。"
                        "精力" -> "角色当前的精神与活力状态，与情绪强烈程度分别判断。"
                        else -> "角色最近一次评估时的心情。"
                    }
                    onDetail(label, "$value\n\n$explanation" + state.feelingReason.takeIf { it.isNotBlank() }?.let { "\n\n评估依据：$it" }.orEmpty(), true)
                }
            }
        }
        val status = when {
            view.busy -> "正在更新，暂显示上次结果"
            !state.enabled -> "已关闭"
            state.historyReviewRequired -> "等待确认历史范围"
            state.awaitingHistory -> "等待确认是否补评"
            state.notice != null -> "上次更新未完成"
            state.updatedAt == 0L -> "等待更新"
            view.sourceWarning != null -> "原话依据待核对"
            else -> "已更新"
        }
        Text("评估状态：$status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RelationshipSummary(view: CompanionRelationshipView, working: Boolean, onUpdate: () -> Unit,
    onDetail: (String, String, Boolean) -> Unit) {
    val colors = statusColors()
    RelationshipSection("长期相处") {
        CompanionRelationshipPolicy.dimensions.entries.toList().chunked(3).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { columnIndex, (key, label) ->
                    StatusTile(label, view.shortDescriptions[key].orEmpty(), colors[rowIndex * 3 + columnIndex], Modifier.weight(1f)) {
                        onDetail(label, view.descriptions[key].orEmpty() + if (view.state.legacy) "\n\n包含升级前的关系积累，旧值没有可核对的原话依据。" else "", false)
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (view.state.historyReviewRequired) "历史待确认：${view.pending} 轮" else "尚未评估：${view.pending} 轮",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onUpdate, enabled = view.state.enabled && !view.busy && !working && view.pending > 0 && !view.state.awaitingHistory) {
                    Text("立即更新")
                }
            }
        }
    }
}

@Composable
private fun StatusTile(label: String, value: String, color: Color, modifier: Modifier,
    centered: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.heightIn(min = 82.dp).padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, color = color, style = if (centered) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!centered) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
