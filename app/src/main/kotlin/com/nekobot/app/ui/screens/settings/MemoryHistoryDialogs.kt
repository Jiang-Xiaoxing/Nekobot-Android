package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    if (visible && preview == null) NekoDialog(onDismiss = onDismiss,
        title = stringResource(R.string.memory_history_title), confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss, cancelText = null, contentScrollable = true) {
        if (versions.isEmpty()) Text(stringResource(R.string.memory_history_empty))
        versions.forEach { version ->
            TextButton(onClick = { onPreview(version) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(formatGlobalMemorySavedAt(version.createdAt).orEmpty())
                    Text(stringResource(memoryVersionSource(version.source)), style = MaterialTheme.typography.labelSmall)
                    Text(version.content.take(80), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (visible && preview != null) NekoDialog(onDismiss = onClosePreview,
        title = stringResource(R.string.memory_history_preview), confirmText = stringResource(R.string.memory_history_restore),
        onConfirm = { onRestore(preview.id) }, onCancel = onClosePreview, contentScrollable = true) {
        Text(formatGlobalMemorySavedAt(preview.createdAt).orEmpty())
        Text(stringResource(memoryVersionSource(preview.source)), style = MaterialTheme.typography.labelSmall)
        preview.sourceSessionId?.let { sessionId ->
            TextButton(onClick = { onDismiss(); onClosePreview(); onLocate(sessionId, preview.sourceMessageId) }) {
                Text(stringResource(R.string.memory_history_source))
            }
        }
        Text(stringResource(R.string.memory_history_before), style = MaterialTheme.typography.labelLarge)
        Text(preview.previousContent.ifBlank { stringResource(R.string.memory_history_empty_content) },
            modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.memory_history_after), style = MaterialTheme.typography.labelLarge)
        Text(preview.content.ifBlank { stringResource(R.string.memory_history_empty_content) }, style = MaterialTheme.typography.bodySmall)
    }
}

private fun memoryVersionSource(source: String) = when (source) {
    "agent" -> R.string.memory_history_agent
    "tool" -> R.string.memory_history_tool
    "restore" -> R.string.memory_history_restored
    else -> R.string.memory_history_manual
}
