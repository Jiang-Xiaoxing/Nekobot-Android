package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

enum class SessionModeLabel { CHARACTER, AGENT, COMPANION, GROUP }

internal fun SessionModeLabel.icon(): ImageVector = when (this) {
    SessionModeLabel.CHARACTER -> Icons.Filled.Person
    SessionModeLabel.AGENT -> Icons.Filled.WorkOutline
    SessionModeLabel.COMPANION -> Icons.Filled.Forum
    SessionModeLabel.GROUP -> Icons.Filled.Group
}

@Composable
internal fun SessionModeLabel.title(): String = stringResource(when (this) {
    SessionModeLabel.CHARACTER -> R.string.sessions_filter_character_sessions
    SessionModeLabel.AGENT -> R.string.sessions_filter_agent_sessions
    SessionModeLabel.COMPANION -> R.string.sessions_filter_agent_characters
    SessionModeLabel.GROUP -> R.string.session_mode_group_label
})

@Composable
internal fun SessionModeLabel.color(): Color {
    return MaterialTheme.colorScheme.primary
}

@Composable
internal fun SessionModeBadge(mode: SessionModeLabel, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(mode.icon(), null, Modifier.size(17.dp), tint = mode.color())
        Text(mode.title(), color = mode.color(), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SessionModeMenuText(mode: SessionModeLabel, hint: Int) {
    Column(Modifier.widthIn(max = 230.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(mode.title(), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
