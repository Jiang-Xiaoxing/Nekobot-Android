package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.PromptSection
import com.nekobot.app.data.local.ai.promptSectionPercentages
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.ui.components.CharacterThinkingSettingsControls
import java.util.Locale

/** 思考强度仍按会话保存；角色思考风格复用现有全局偏好。 */
@Composable
internal fun AdvancedReasoningCard(
    effort: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit,
    showCharacterThinkingSettings: Boolean,
    customFields: @Composable () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    AdvancedSettingsCard(Modifier.testTag("advanced-reasoning")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text("思考强度", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(onClick = { menu = true }, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(effort.displayName)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ReasoningEffort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName) }, onClick = { onSelect(option); menu = false })
                    }
                }
            }
        }
        Text("只影响当前会话，选择后立即保存", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp))
        Text("模型需支持对应选项", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp))
        if (effort == ReasoningEffort.CUSTOM) customFields()
        if (showCharacterThinkingSettings) CharacterThinkingSettingsControls(effort)
    }
}

/** 默认折叠且折叠时不组合正文，复制、详情和启用操作在原内容中保留。 */
@Composable
internal fun AdvancedDebugCard(
    sessionId: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
internal fun AdvancedSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun promptSectionLabel(section: PromptSection) = when (section) {
    PromptSection.CHARACTER -> "角色设定"
    PromptSection.CORE_MEMORY -> "核心记忆"
    PromptSection.RELATIONSHIP -> "关系状态"
    PromptSection.PLAYER -> "玩家背景"
    PromptSection.OTHER -> "其他提示词"
}

private fun promptSectionColor(section: PromptSection) = when (section) {
    PromptSection.CHARACTER -> Color(0xFF559AF0)
    PromptSection.CORE_MEMORY -> Color(0xFFA681EE)
    PromptSection.RELATIONSHIP -> Color(0xFF35C8C3)
    PromptSection.PLAYER -> Color(0xFFF37DAB)
    PromptSection.OTHER -> Color(0xFFF0BD50)
}

@Composable
internal fun AdvancedPromptUsageCard(counts: Map<PromptSection, Int>?) {
    val percentages = remember(counts) { promptSectionPercentages(counts.orEmpty()) }
    val total = counts.orEmpty().values.sumOf { it.coerceAtLeast(0).toLong() }
    fun number(value: Long) = String.format(Locale.getDefault(), "%,d", value)
    AdvancedSettingsCard(Modifier.testTag("advanced-prompt-usage")) {
        Text("提示词各段占比", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("按上次请求的提示词与辅助资料估算 Token", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(14.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            PromptSection.entries.forEach { section ->
                val tokens = counts?.get(section)?.coerceAtLeast(0) ?: 0
                if (tokens > 0) Box(Modifier.weight(tokens.toFloat()).fillMaxHeight().background(promptSectionColor(section)))
            }
        }
        PromptSection.entries.forEach { section ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).background(promptSectionColor(section), CircleShape))
                Text(promptSectionLabel(section), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(if (counts == null) "—" else "${percentages.getValue(section)}% · ${number((counts[section] ?: 0).coerceAtLeast(0).toLong())}",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("合计", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (counts == null) "未记录" else "${number(total)} Token", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Text(if (counts == null) "暂无分段记录，完成一次聊天请求后再查看"
            else "占比随请求变化；不含聊天原话和工具定义", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
