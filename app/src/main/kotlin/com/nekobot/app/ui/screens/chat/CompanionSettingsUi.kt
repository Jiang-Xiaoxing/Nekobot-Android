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

@Composable
internal fun CompanionSettingsScreen(
    characterName: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit, onDetails: () -> Unit, onSearch: () -> Unit,
    onFavorites: () -> Unit, onUsage: () -> Unit, onArchive: (() -> Unit)? = null,
    portraitUrl: String? = null
) {
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize().testTag("companion-settings-screen"), color = companionSettingsBackground(),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            CompanionReadingHeader("聊天设置", onDismiss)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                CompanionSettingsIdentity(characterName, portraitUrl, onDetails)
                Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    CompanionPreferenceGroup("聊天内容") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CompanionSettingsAction("查找记录", Icons.Outlined.Search, onSearch, Modifier.weight(1f))
                            CompanionSettingsAction("收藏消息", Icons.Outlined.StarBorder, onFavorites, Modifier.weight(1f))
                            if (onArchive != null) CompanionSettingsAction("查看归档", Icons.Outlined.FolderOpen, onArchive, Modifier.weight(1f))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompanionPreferenceGroup("显示与用量") {
                            CompanionPreferenceRow("显示思考", Icons.Outlined.Description, "只影响当前角色",
                                checked = checked, onCheckedChange = onCheckedChange)
                            CompanionPreferenceDivider()
                            CompanionPreferenceRow("聊天用量", Icons.Outlined.BarChart, "查看上次请求带入的内容", onClick = onUsage)
                        }
                        Text("开启后，可在消息中展开查看思考。", Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionSettingsIdentity(characterName: String?, portraitUrl: String?, onDetails: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val base = companionSettingsBackground()
    val name = characterName?.takeIf(String::isNotBlank) ?: "单会话角色"
    Column(Modifier.fillMaxWidth().clipToBounds().drawBehind {
        drawRect(Brush.verticalGradient(listOf(lerp(base, primary, 0.13f), base)))
        drawOval(primary.copy(alpha = 0.055f), topLeft = Offset(size.width * 0.58f, -size.height * 0.72f),
            size = Size(size.width * 0.94f, size.height * 1.8f))
    }.padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(
                Brush.linearGradient(listOf(lerp(primary, Color.White, 0.17f), primary))), contentAlignment = Alignment.Center) {
                val portrait = resolveAvatarUrl(portraitUrl)
                if (!portrait.isNullOrBlank()) AsyncImage(portrait, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(name.take(1), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("单会话角色", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(onClick = onDetails, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Contacts, null, tint = primary, modifier = Modifier.size(26.dp))
                Text("角色与记忆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompanionSettingsAction(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
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
