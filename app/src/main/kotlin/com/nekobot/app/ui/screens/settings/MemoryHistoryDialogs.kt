package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.MemoryVersion
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.accentSuccess

@Composable
internal fun MemoryHistoryDialogs(
    visible: Boolean, preview: MemoryVersion?, versions: List<MemoryVersion>,
    onDismiss: () -> Unit, onPreview: (MemoryVersion) -> Unit, onClosePreview: () -> Unit,
    onRestore: (String) -> Unit, onLocate: (String, String?) -> Unit
) {
    if (!visible) return
    val close = if (preview == null) onDismiss else onClosePreview
    // 列表和预览复用同一个玻璃窗口，内容区域不再套实色 Surface。
    NekoDialog(
        onDismiss = close,
        title = stringResource(if (preview == null) R.string.memory_history_title else R.string.memory_history_preview),
        confirmText = stringResource(if (preview == null) R.string.common_close else R.string.memory_history_restore),
        onConfirm = { if (preview == null) onDismiss() else onRestore(preview.id) },
        cancelText = if (preview == null) null else stringResource(R.string.common_back),
        onCancel = if (preview == null) null else onClosePreview
    ) {
        if (preview == null) MemoryHistoryList(versions, onPreview)
        else MemoryVersionDiffContent(preview, onLocate)
    }
}

@Composable
private fun MemoryHistoryList(versions: List<MemoryVersion>, onPreview: (MemoryVersion) -> Unit) {
    if (versions.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.History, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.memory_history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.6f),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(versions, key = { it.id }) { version ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .clickable { onPreview(version) }.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(formatGlobalMemorySavedAt(version.createdAt).orEmpty(),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(memoryVersionSource(version.source)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(version.content.take(80).ifBlank { stringResource(R.string.memory_history_empty_content) },
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MemoryVersionDiffContent(preview: MemoryVersion, onLocate: (String, String?) -> Unit) {
    val diff = remember(preview.previousContent, preview.content) {
        buildMemoryHistoryDiff(preview.previousContent, preview.content)
    }
    var showUnchanged by rememberSaveable(preview.id) { mutableStateOf(false) }
    val rows = remember(diff, showUnchanged) { diff.rows(showUnchanged) }
    val success = accentSuccess()
    val numberWidth = (maxOf(2, diff.lines.size.toString().length) * 7 + 4).dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatGlobalMemorySavedAt(preview.createdAt).orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(memoryVersionSource(preview.source)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            preview.sourceSessionId?.let { sessionId ->
                TextButton(onClick = { onLocate(sessionId, preview.sourceMessageId) }) {
                    Text(stringResource(R.string.memory_history_source))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.memory_history_diff_summary, diff.added, diff.removed),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showUnchanged = !showUnchanged }) {
                Text(stringResource(if (showUnchanged) R.string.memory_history_focus_changes else R.string.memory_history_show_all))
            }
        }
        if (!diff.hasChanges) {
            Text(stringResource(if (diff.lineEndingsOnly) R.string.memory_history_line_endings_only else R.string.memory_history_no_changes),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("− ${stringResource(R.string.memory_history_before)}", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
            Text("+ ${stringResource(R.string.memory_history_after)}", color = success,
                style = MaterialTheme.typography.labelSmall)
        }
        if (rows.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.5f)) {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows) { row ->
                            when (row) {
                                is MemoryDiffRow.Omitted -> Text(
                                    stringResource(R.string.memory_history_unchanged_lines, row.count),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                is MemoryDiffRow.Line -> {
                                    val line = row.value
                                    val accent = when (line.kind) {
                                        MemoryDiffKind.REMOVED -> MaterialTheme.colorScheme.error
                                        MemoryDiffKind.ADDED -> success
                                        MemoryDiffKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    val changed = line.kind != MemoryDiffKind.CONTEXT
                                    val content = remember(line, accent) {
                                        buildAnnotatedString {
                                            append(line.content.ifEmpty { " " })
                                            if (changed && line.highlightStart < line.highlightEnd) {
                                                addStyle(SpanStyle(background = accent.copy(alpha = 0.22f),
                                                    fontWeight = FontWeight.Medium), line.highlightStart, line.highlightEnd)
                                            }
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth()
                                        .background(if (changed) accent.copy(alpha = 0.09f) else Color.Transparent)
                                        .padding(horizontal = 4.dp, vertical = 3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val numberStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                                        Text(line.oldNumber?.toString().orEmpty(), Modifier.width(numberWidth),
                                            style = numberStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        Text(line.newNumber?.toString().orEmpty(), Modifier.width(numberWidth),
                                            style = numberStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        Text(line.kind.marker, Modifier.width(12.dp), style = numberStyle,
                                            color = accent, fontWeight = FontWeight.Bold)
                                        Text(content, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
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
