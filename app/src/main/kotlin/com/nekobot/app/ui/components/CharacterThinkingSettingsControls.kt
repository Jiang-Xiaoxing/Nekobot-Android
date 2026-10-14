package com.nekobot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.DEFAULT_CHARACTER_THINKING_PROMPT
import com.nekobot.app.data.model.ReasoningEffort

/** 所有角色／群聊入口共用同一份全局偏好；重新进入时读取最新值。 */
@Composable
internal fun CharacterThinkingSettingsControls(effort: ReasoningEffort, modifier: Modifier = Modifier) {
    val prefs = ServiceContainer.prefs
    var characterThinkingSettings by remember { mutableStateOf(prefs.getCharacterThinkingSettings()) }
    var promptDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val thinkingControlsEnabled = effort != ReasoningEffort.NONE
    LaunchedEffect(thinkingControlsEnabled) {
        if (!thinkingControlsEnabled) promptDraft = null
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .testTag("character-thinking-switch")
                .toggleable(value = characterThinkingSettings.enabled, enabled = thinkingControlsEnabled,
                    role = Role.Switch, onValueChange = { enabled ->
                        prefs.characterThinkingEnabled = enabled
                        characterThinkingSettings = prefs.getCharacterThinkingSettings()
                    }),
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
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
                    TextButton(onClick = { promptDraft = "" }) { Text("恢复默认") }
                    TextButton(onClick = {
                        prefs.characterThinkingPrompt = promptDraft.orEmpty()
                        characterThinkingSettings = prefs.getCharacterThinkingSettings()
                        promptDraft = null
                    }) { Text("保存") }
                }
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
