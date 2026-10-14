package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.nekobot.app.data.local.ai.CharacterThinkingSettings
import com.nekobot.app.data.local.ai.DEFAULT_CHARACTER_THINKING_PROMPT
import com.nekobot.app.data.local.ai.PromptSection
import com.nekobot.app.data.local.ai.promptSectionPercentages
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.ui.components.GlassAlertDialog
import java.util.Locale

/** 思考强度仍按会话保存；角色思考风格复用现有全局偏好。 */
@Composable
internal fun AdvancedReasoningCard(
    effort: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit,
    characterThinkingSettings: CharacterThinkingSettings,
    onCharacterThinkingEnabledChange: (Boolean) -> Unit,
    onCharacterThinkingPromptSave: (String) -> Unit,
    customFields: @Composable () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var promptDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val thinkingControlsEnabled = effort != ReasoningEffort.NONE
    LaunchedEffect(thinkingControlsEnabled) {
        if (!thinkingControlsEnabled) promptDraft = null
    }
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
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .testTag("character-thinking-switch")
                .toggleable(value = characterThinkingSettings.enabled, enabled = thinkingControlsEnabled,
                    role = Role.Switch, onValueChange = onCharacterThinkingEnabledChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CharacterThinkingRowLabel(Icons.Outlined.PersonOutline, "角色视角思考",
                "思考用中文、以角色“我”的口吻。", thinkingControlsEnabled, Modifier.weight(1f))
            Switch(checked = characterThinkingSettings.enabled, onCheckedChange = null, enabled = thinkingControlsEnabled)
        }
        HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .testTag("character-thinking-prompt-entry")
                .clickable(enabled = thinkingControlsEnabled, role = Role.Button) {
                    promptDraft = characterThinkingSettings.customPrompt
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CharacterThinkingRowLabel(Icons.Outlined.Edit, "自定义思考提示词",
                "留空则使用默认。", thinkingControlsEnabled, Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(24.dp),
                tint = if (thinkingControlsEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
    }
    if (thinkingControlsEnabled && promptDraft != null) {
        GlassAlertDialog(
            onDismissRequest = { promptDraft = null },
            title = { Text("自定义思考提示词") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("留空则使用默认。")
                    OutlinedTextField(
                        value = promptDraft.orEmpty(),
                        onValueChange = { promptDraft = it },
                        modifier = Modifier.fillMaxWidth().testTag("character-thinking-prompt-editor"),
                        label = { Text("思考提示词") },
                        placeholder = { Text(DEFAULT_CHARACTER_THINKING_PROMPT) },
                        minLines = 8,
                        maxLines = 12,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { promptDraft = "" }) { Text("恢复默认") }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCharacterThinkingPromptSave(promptDraft.orEmpty())
                    promptDraft = null
                }) { Text("保存") }
            }
        )
    }
}

@Composable
private fun CharacterThinkingRowLabel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else disabledColor)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor)
        }
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
