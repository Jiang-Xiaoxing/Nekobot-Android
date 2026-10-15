package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

/** Only provided inside a single-session companion chat; shared Agent/role UI keeps its own style. */
internal val LocalCompanionChatName = staticCompositionLocalOf<String?> { null }
internal val LocalCompanionShowReasoning = staticCompositionLocalOf { false }
/** 打开本会话「角色记忆」页；非单会话角色为 null。 */
internal val LocalOpenCompanionMemory = staticCompositionLocalOf<(() -> Unit)?> { null }
enum class CompanionChatUtility { TERMINAL, BROWSER, RESTORE_ARCHIVE, VIEW_ARCHIVE }

internal data class CompanionChatPalette(
    val background: Color, val surface: Color, val outgoing: Color,
    val ink: Color, val secondary: Color, val accent: Color,
    val outgoingInk: Color = Color(0xFF172411)
)

@Composable
internal fun companionChatPalette(): CompanionChatPalette = MaterialTheme.colorScheme.let { colors ->
    CompanionChatPalette(colors.background, colors.surface, colors.primary,
        colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.onPrimary)
}

/** A display preference only; NONE applies to new requests, never to saved messages. */
@Composable
internal fun CompanionReasoningSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = companionChatPalette()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Psychology, null, Modifier.size(22.dp), tint = palette.ink)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.companion_chat_show_reasoning),
                style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            Text(stringResource(R.string.companion_chat_show_reasoning_hint),
                style = MaterialTheme.typography.bodySmall, color = palette.secondary)
        }
        Switch(checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.accent))
    }
}

/** Reuses saved-run recovery, without exposing the Agent execution panel. */
@Composable
internal fun CompanionReplyRecovery(onResume: () -> Unit, onDismiss: () -> Unit) {
    val palette = companionChatPalette()
    Column(Modifier.fillMaxWidth().background(palette.background).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.companion_chat_reply_unfinished),
            color = palette.secondary, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onResume) { Text(stringResource(R.string.companion_chat_resume_reply), color = palette.accent) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.companion_chat_dismiss_reply), color = palette.secondary) }
        }
    }
}

/** Compact optional disclosure; hidden/empty reasoning never leaves a blank card. */
@Composable
internal fun CompanionReasoningSection(messageId: String?, reasoning: String?, enabled: Boolean) {
    if (!enabled || reasoning.isNullOrBlank()) return
    var expanded by remember(messageId) { mutableStateOf(false) }
    val palette = companionChatPalette()
    val dark = palette.background.luminance() < 0.5f
    val container = MaterialTheme.colorScheme.surfaceVariant
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("companion_reasoning"),
        shape = RoundedCornerShape(8.dp), color = container) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = content)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.companion_chat_reasoning), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = content)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    Modifier.size(20.dp), tint = content)
            }
            if (expanded) {
                SelectionContainer {
                    Text(reasoning, Modifier.fillMaxWidth().heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = content)
                }
            }
        }
    }
}



/** The composer reuses the existing send, draft, attachment and microphone handlers. */




@OptIn(ExperimentalMaterial3Api::class)




@Composable
internal fun CompanionClearMessagesDialog(enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var confirmation by remember { mutableStateOf("") }
    val keyword = stringResource(R.string.companion_chat_clear_keyword)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.companion_chat_clear)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_clear_current_confirm))
                OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, singleLine = true,
                    label = { Text(stringResource(R.string.companion_chat_clear_type, keyword)) })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled && confirmation.trim() == keyword) {
            Text(stringResource(R.string.companion_chat_clear), color = if (enabled && confirmation.trim() == keyword)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
