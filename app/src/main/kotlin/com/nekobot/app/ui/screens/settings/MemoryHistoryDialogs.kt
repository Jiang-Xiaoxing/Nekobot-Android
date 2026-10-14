package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.MemoryVersion
import com.nekobot.app.ui.components.NekoDialog

@Composable
internal fun MemoryHistoryDialogs(
    visible: Boolean, preview: MemoryVersion?, versions: List<MemoryVersion>,
    onDismiss: () -> Unit, onPreview: (MemoryVersion) -> Unit, onClosePreview: () -> Unit,
    onRestore: (String) -> Unit, onLocate: (String, String?) -> Unit
) {
    if (visible && preview == null) NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.memory_history_title),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        cancelText = null,
        contentScrollable = true
    ) {
        if (versions.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.memory_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            versions.forEach { version ->
                Surface(
                    onClick = { onPreview(version) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                formatGlobalMemorySavedAt(version.createdAt).orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(memoryVersionSource(version.source)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                version.content.take(80).ifBlank { stringResource(R.string.memory_history_empty_content) },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
    if (visible && preview != null) NekoDialog(
        onDismiss = onClosePreview,
        title = stringResource(R.string.memory_history_preview),
        confirmText = stringResource(R.string.memory_history_restore),
        onConfirm = { onRestore(preview.id) },
        onCancel = onClosePreview,
        contentScrollable = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    formatGlobalMemorySavedAt(preview.createdAt).orEmpty(),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(memoryVersionSource(preview.source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            preview.sourceSessionId?.let { sessionId ->
                TextButton(onClick = { onDismiss(); onClosePreview(); onLocate(sessionId, preview.sourceMessageId) }) {
                    Text(stringResource(R.string.memory_history_source))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            MemoryVersionContent(stringResource(R.string.memory_history_before), preview.previousContent)
            MemoryVersionContent(stringResource(R.string.memory_history_after), preview.content)
        }
    }
}

@Composable
private fun MemoryVersionContent(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            SelectionContainer {
                Text(
                    content.ifBlank { stringResource(R.string.memory_history_empty_content) },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun memoryVersionSource(source: String) = when (source) {
    "agent" -> R.string.memory_history_agent
    "tool" -> R.string.memory_history_tool
    "restore" -> R.string.memory_history_restored
    else -> R.string.memory_history_manual
}
