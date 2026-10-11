package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.components.GlassCard

@Composable
internal fun ChatEffectsSettings() {
    val prefs = ServiceContainer.prefs
    var thinking by remember { mutableStateOf(prefs.dynamicThinking) }
    var messages by remember { mutableStateOf(prefs.dynamicMessages) }
    var choosing by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.style_chat_effects), style = MaterialTheme.typography.titleMedium)
        AppearanceSettingRow(title = stringResource(R.string.style_reasoning_style),
            subtitle = if (thinking) "生成时展开，结束后收起" else "保留原来的思考展示",
            onClick = { choosing = true }) {
            Text(stringResource(if (thinking) R.string.style_dynamic_thinking else R.string.style_original_thinking),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppearanceDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).testTag("style_dynamic_messages")
            .toggleable(value = messages, role = Role.Switch, onValueChange = { messages = it; prefs.dynamicMessages = it })
            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.style_dynamic_messages), style = MaterialTheme.typography.bodyLarge)
                Text("新气泡轻轻弹出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = messages, onCheckedChange = null)
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("思考样式") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to R.string.style_original_thinking, true to R.string.style_dynamic_thinking).forEach { (dynamic, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .testTag(if (dynamic) "style_dynamic_thinking" else "style_original_thinking")
                    .selectable(selected = thinking == dynamic, role = Role.RadioButton, onClick = {
                        thinking = dynamic; prefs.dynamicThinking = dynamic; choosing = false
                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = thinking == dynamic, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(stringResource(label))
                        Text(stringResource(if (dynamic) R.string.style_dynamic_thinking_hint else R.string.style_original_thinking_hint),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(stringResource(R.string.style_effects_scope_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("关闭") } })
}

@Composable
internal fun AppearanceSettingRow(title: String, subtitle: String? = null, onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

@Composable
internal fun AppearanceDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
