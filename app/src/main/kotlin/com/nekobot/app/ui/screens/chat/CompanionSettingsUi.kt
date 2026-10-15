package com.nekobot.app.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.components.CharacterThinkingSettingsControls
import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort

/** Scoped to companion settings: deliberately independent of message and relationship skin colours. */
@Composable
internal fun companionSettingsBackground(): Color =
    lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, 0.035f)

@Composable
internal fun CompanionPreferenceGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            color = if (highlighted) lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, 0.045f)
                else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
            Column(content = content)
        }
    }
}

@Composable
internal fun CompanionPreferenceDivider() {
    HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
internal fun CompanionPreferenceIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun CompanionPreferenceRow(
    title: String, icon: ImageVector, subtitle: String? = null,
    modifier: Modifier = Modifier,
    checked: Boolean? = null, onCheckedChange: ((Boolean) -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val interaction = when {
        checked != null && onCheckedChange != null -> Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    Row(modifier.fillMaxWidth().then(interaction).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CompanionPreferenceIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            checked != null -> Switch(checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = MaterialTheme.colorScheme.surface))
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionSettingsScreen(
    characterName: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit, onDetails: () -> Unit, onSearch: () -> Unit,
    onFavorites: () -> Unit, onUsage: () -> Unit, onArchive: (() -> Unit)? = null,
    portraitUrl: String? = null,
    showInnerMonologue: Boolean = true,
    onShowInnerMonologueChange: ((Boolean) -> Unit)? = null,
    reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    customReasoning: CustomReasoningParam = CustomReasoningParam(),
    onReasoningEffortChange: (ReasoningEffort) -> Unit = {},
    onCustomReasoningChange: (CustomReasoningParam) -> Unit = {},
    onSaved: () -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(characterName ?: "单会话角色", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { onDismiss(); onDetails() }) { Text("角色与记忆") }
            CompanionPreferenceRow("显示思考", Icons.Outlined.Description, checked = checked,
                onCheckedChange = { onCheckedChange(it); onSaved() })
            CompanionReasoningEffortSetting(reasoningEffort, onReasoningEffortChange, onSaved)
            if (reasoningEffort == ReasoningEffort.CUSTOM) {
                CustomReasoningFields(customReasoning, true, { onCustomReasoningChange(it); onSaved() })
            }
            CharacterThinkingSettingsControls(reasoningEffort, compact = true, onSaved = onSaved)
            if (onShowInnerMonologueChange != null) CompanionPreferenceRow("显示内心独白", Icons.Outlined.ChatBubbleOutline,
                "只影响当前会话", checked = showInnerMonologue,
                onCheckedChange = { onShowInnerMonologueChange(it); onSaved() })
            Text("同时开启显示思考和角色视角思考后，可展开内心想法 Beta（测试版）。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onDismiss(); onSearch() }) { Text("查找记录") }
            TextButton(onClick = { onDismiss(); onFavorites() }) { Text("收藏消息") }
            TextButton(onClick = { onDismiss(); onUsage() }) { Text("聊天用量") }
            if (onArchive != null) TextButton(onClick = { onDismiss(); onArchive() }) { Text("查看归档") }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun CompanionReasoningEffortSetting(
    effort: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit,
    onSaved: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    CompanionPreferenceRow("思考强度", Icons.Outlined.Psychology, "只影响当前会话",
        trailing = {
            Box {
                OutlinedButton(onClick = { menuOpen = true }, shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(effort.displayName)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ReasoningEffort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.displayName) }, onClick = {
                            onSelect(option)
                            menuOpen = false
                            onSaved()
                        })
                    }
                }
            }
        })
}





@Composable
internal fun companionChoiceColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary
)

/** Native full-height reading surface; the back target remains available while the keyboard is open. */
@Composable
internal fun CompanionReadingDialog(
    title: String, onBack: () -> Unit, onDismiss: () -> Unit = onBack,
    subtitle: String? = null, content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = companionSettingsBackground(), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                CompanionReadingHeader(title, onBack, subtitle = subtitle)
                content()
            }
        }
    }
}

@Composable
internal fun CompanionReadingHeader(
    title: String, onBack: () -> Unit, subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}
