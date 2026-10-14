package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.userFacingTitle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nekobot.app.data.model.Session
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.screens.chat.*

/** Presentation only. Values and persistence stay in SessionDetailViewModel. */
@Composable
internal fun CompanionRoleSettingsContent(
    session: Session, name: String, tags: String, pinned: Boolean, favorite: Boolean,
    onNameChange: (String) -> Unit, onTagsChange: (String) -> Unit,
    onPinnedChange: (Boolean) -> Unit, onFavoriteChange: (Boolean) -> Unit,
    onMemories: () -> Unit, onRelationship: () -> Unit, onModels: (() -> Unit)?, onAdvanced: () -> Unit,
    delayEnabled: Boolean, onDelayChange: (Boolean) -> Unit, delayEligible: Boolean,
    ttsEnabled: Boolean, onTtsChange: (Boolean) -> Unit,
    proactiveEnabled: Boolean, onProactiveChange: (Boolean) -> Unit,
    notificationEnabled: Boolean, onNotificationChange: (Boolean) -> Unit,
    delayConfiguration: @Composable () -> Unit,
    ttsConfiguration: @Composable () -> Unit,
    proactiveConfiguration: @Composable () -> Unit,
    notificationDescription: String
) {
    CompanionPreferenceGroup {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val character = session.userFacingTitle()
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    val portrait = resolveAvatarUrl(session.portraitUrl)
                    if (!portrait.isNullOrBlank()) AsyncImage(portrait, character, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text(character.take(1), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(character, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Text("单会话角色", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text("固定在同一个会话聊天，记忆只用于这个会话", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.archived == true || session.readOnly == true) Text(
                    listOfNotNull("已归档".takeIf { session.archived == true }, "只读".takeIf { session.readOnly == true }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    CompanionPreferenceGroup("角色资料") {
        CompanionPreferenceRow("单会话角色记忆", Icons.AutoMirrored.Outlined.MenuBook, onClick = onMemories)
        CompanionPreferenceDivider()
        CompanionPreferenceRow("关系状态", Icons.Outlined.FavoriteBorder, onClick = onRelationship)
        if (onModels != null) {
            CompanionPreferenceDivider()
            CompanionPreferenceRow("聊天模型", Icons.Outlined.Forum, onClick = onModels)
        }
        CompanionPreferenceDivider()
        CompanionPreferenceRow("高级设置", Icons.Outlined.Tune, "查看调试信息", onClick = onAdvanced)
    }
    CompanionPreferenceGroup("会话信息") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, onNameChange, label = { Text("会话名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            OutlinedTextField(tags, onTagsChange, label = { Text("标签") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(pinned, { onPinnedChange(!pinned) }, label = { Text("置顶") },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    colors = companionChoiceColors(), modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                FilterChip(favorite, { onFavoriteChange(!favorite) }, label = { Text("收藏") },
                    leadingIcon = { Icon(Icons.Outlined.StarBorder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                    colors = companionChoiceColors(), modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
            val metadata = listOfNotNull(session.messageCount?.let { "$it 条消息" }, session.updatedAt?.let { "更新于 ${it.replace('T', ' ').take(16)}" })
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    CompanionPreferenceGroup("回复与提醒") {
        if (delayEligible) {
            CompanionPreferenceRow("延迟回复", Icons.Outlined.Timer, "继续输入时暂停计时", checked = delayEnabled, onCheckedChange = onDelayChange)
            if (delayEnabled) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { delayConfiguration() }
            CompanionPreferenceDivider()
        }
        CompanionPreferenceRow("语音朗读", Icons.AutoMirrored.Outlined.VolumeUp, checked = ttsEnabled, onCheckedChange = onTtsChange)
        if (ttsEnabled) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { ttsConfiguration() }
        CompanionPreferenceDivider()
        CompanionPreferenceRow("主动聊天", Icons.Outlined.ChatBubbleOutline, checked = proactiveEnabled, onCheckedChange = onProactiveChange)
        if (proactiveEnabled) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { proactiveConfiguration() }
        CompanionPreferenceDivider()
        CompanionPreferenceRow("通知提醒", Icons.Outlined.NotificationsNone, checked = notificationEnabled, onCheckedChange = onNotificationChange)
        if (notificationEnabled) Text(notificationDescription, Modifier.padding(start = 72.dp, end = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SessionSharingContainer(
    companion: Boolean, title: String, expanded: Boolean, onExpand: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!companion) GlassCard(Modifier.fillMaxWidth(), content = content)
    else CompanionPreferenceGroup(title) {
        CompanionPreferenceRow(title, Icons.Outlined.Share, if (expanded) "收起分享设置" else "展开查看分享设置",
            trailing = { Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null) }, onClick = onExpand)
        if (expanded) Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun CompanionRoleMenu(onExport: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit, expanded: Boolean) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text("导出会话") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { onDismiss(); onExport() })
        DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { onDismiss(); onDelete() })
    }
}
