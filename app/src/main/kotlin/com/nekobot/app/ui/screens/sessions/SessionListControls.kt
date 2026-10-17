package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

@Composable
internal fun SessionHeaderAction(icon: ImageVector, description: String, prominent: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(24.dp),
            tint = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun SessionModeFilters(overview: SessionOverview, selected: SessionFilter, onToggle: (SessionFilter) -> Unit) {
    val modes = listOf(SessionModeLabel.CHARACTER, SessionModeLabel.AGENT, SessionModeLabel.COMPANION)
    val filters = listOf(SessionFilter.CHARACTER_SESSIONS, SessionFilter.AGENT_SESSIONS, SessionFilter.AGENT_CHARACTERS)
    val counts = listOf(overview.characterSessions, overview.agentSessions, overview.agentCharacterSessions)
    val largeText = LocalDensity.current.fontScale > 1.2f
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEachIndexed { index, mode ->
            val active = selected == filters[index]
            val accent = mode.color()
            Surface(onClick = { onToggle(filters[index]) }, modifier = Modifier.weight(1f).fillMaxHeight().semantics { this.selected = active },
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, contentColor = accent,
                border = BorderStroke(1.dp, accent.copy(alpha = if (active) 0.58f else 0.24f)),
                shadowElevation = 0.dp, tonalElevation = 0.dp) {
                val contentModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .padding(horizontal = 7.dp, vertical = 8.dp)
                if (largeText) Column(contentModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(mode.icon(), null, Modifier.size(18.dp), tint = accent)
                        Spacer(Modifier.weight(1f))
                        Text(counts[index].toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = accent)
                    }
                    Text(mode.title(), style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 2)
                } else Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(mode.icon(), null, Modifier.size(18.dp), tint = accent)
                    Text(mode.title(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = accent,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(counts[index].toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = accent)
                }
            }
        }
    }
}

@Composable
internal fun SessionRecentControls(selected: SessionFilter, onToggle: (SessionFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50)) { Box(Modifier.size(6.dp)) }
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.sessions_recent), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        listOf(SessionFilter.FAVORITE to Icons.Outlined.FavoriteBorder, SessionFilter.ARCHIVED to Icons.Outlined.Archive).forEach { (filter, icon) ->
            val active = selected == filter
            TextButton(onClick = { onToggle(filter) }, contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.semantics { this.selected = active },
                colors = ButtonDefaults.textButtonColors(contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(if (filter == SessionFilter.FAVORITE) R.string.sessions_favorite else R.string.sessions_archive),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
