package com.nekobot.app.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.screens.memory.memoryCategoryNames

/** One comparison surface; edits stay in a disposable draft until Save. */
@Composable
internal fun PortableImportDifferences(plan: PortableImportPreview, choices: PortableImportChoices, busy: Boolean,
    onDismiss: () -> Unit, onSave: (PortableImportChoices) -> Unit, onMore: (PortableImportChoices) -> Unit) {
    var draft by remember(plan) { mutableStateOf(choices.normalized()) }
    var confirmStory by remember { mutableStateOf(false) }
    NekoDialog(onDismiss = onDismiss, title = stringResource(R.string.safe_import_changes),
        message = stringResource(R.string.import_difference_notice),
        confirmText = stringResource(R.string.import_difference_save), confirmEnabled = !busy,
        onConfirm = { onSave(draft.normalized()) }, onCancel = onDismiss, contentScrollable = true) {
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.safe_import_checking), style = MaterialTheme.typography.bodyMedium)
        }
        plan.rows.differences.forEachIndexed { index, row ->
            DifferenceHeading(importDetailLabel(PortableCategoryDetail("table:${row.table}", 1)), row.label, row.owner)
            row.fields.forEach { field ->
                Text(importFieldLabel(field.column), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                DifferenceValues(importDifferenceValue(field.column, field.current), importDifferenceValue(field.column, field.incoming))
                DifferenceChoice(field.column in draft.fields[row.key].orEmpty(), !busy, "safe-difference-row-$index-${field.column}") { replace ->
                    val fields = draft.fields[row.key].orEmpty()
                    draft = draft.copy(fields = draft.fields + (row.key to if (replace) fields + field.column else fields - field.column)).normalized()
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        plan.fileDifferences.forEachIndexed { index, file ->
            DifferenceHeading(stringResource(R.string.import_difference_file), file.fileName, file.owner)
            Text(portableDetailLabel(PortableCategoryDetail("root:${file.root}", 1, true)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val extension = file.fileName.substringAfterLast('.', "").uppercase().ifBlank { stringResource(R.string.import_difference_unknown_type) }
            Text(stringResource(R.string.import_difference_type, extension), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DifferenceValues(fileSize(file.currentBytes) + checksum(file.currentHash), fileSize(file.incomingBytes) + checksum(file.incomingHash))
            DifferenceChoice(file.key in draft.files, !busy, "safe-difference-file-$index") { replace ->
                draft = draft.copy(files = if (replace) draft.files + file.key else draft.files - file.key)
            }
            var pathVisible by remember(file.key) { mutableStateOf(false) }
            TextButton(onClick = { pathVisible = !pathVisible }) { Text(stringResource(if (pathVisible) R.string.import_difference_hide_path else R.string.import_difference_show_path)) }
            if (pathVisible) SelectionContainer { Text(file.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        plan.settingDifferences.forEach { field ->
            DifferenceHeading(stringResource(R.string.portability_category_app_settings), importFieldLabel(field.column), PortableImportOwner())
            DifferenceValues(field.current, field.incoming)
            DifferenceChoice(field.column in draft.settings, !busy, "safe-difference-setting-${field.column}") { replace ->
                draft = draft.copy(settings = if (replace) draft.settings + field.column else draft.settings - field.column)
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        if (plan.storyDifferent) {
            DifferenceHeading(stringResource(R.string.safe_import_story_confirm_title), stringResource(R.string.safe_import_story), PortableImportOwner())
            Text(stringResource(R.string.safe_import_story_confirm_message), style = MaterialTheme.typography.bodyMedium)
            DifferenceChoice("plot_story" in draft.files, !busy, "safe-difference-story") { replace ->
                if (replace) confirmStory = true else draft = draft.copy(files = draft.files - "plot_story")
            }
        }
        val more = plan.rows.differences.size < plan.rows.counts.different || plan.fileDifferences.size < plan.fileCounts.different
        if (more) TextButton(onClick = { onMore(draft.normalized()) }, enabled = !busy) { Text(stringResource(R.string.safe_import_more)) }
    }
    if (confirmStory) NekoDialog(onDismiss = { confirmStory = false }, title = stringResource(R.string.safe_import_story_confirm_title),
        message = stringResource(R.string.safe_import_story_confirm_message),
        onConfirm = { confirmStory = false; draft = draft.copy(files = draft.files + "plot_story") }, onCancel = { confirmStory = false })
}

@Composable private fun DifferenceHeading(kind: String, title: String, owner: PortableImportOwner) {
    Text(kind, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    val parts = buildList {
        if (owner.character.isNotBlank() && owner.character.trim() != title.trim())
            add(stringResource(R.string.import_difference_character, owner.character))
        if (owner.session.isNotBlank() && owner.session.trim() != title.trim())
            add(stringResource(R.string.import_difference_session, owner.session))
        if (owner.shared) add(stringResource(R.string.import_difference_shared))
    }
    if (parts.isNotEmpty()) Text(parts.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable private fun DifferenceValues(current: String, incoming: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DifferenceValue(stringResource(R.string.import_difference_current), current)
        DifferenceValue(stringResource(R.string.import_difference_incoming), incoming)
    }
}

@Composable private fun DifferenceValue(label: String, value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable private fun DifferenceChoice(replace: Boolean, enabled: Boolean, tag: String, onSelect: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(top = 4.dp)) {
        listOf(false, true).forEach { useIncoming ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = replace == useIncoming, enabled = enabled,
                role = Role.RadioButton, onClick = { onSelect(useIncoming) }).testTag(tag + if (useIncoming) "-replace" else "-keep").padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = replace == useIncoming, enabled = enabled, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (useIncoming) R.string.import_difference_replace else R.string.import_difference_keep),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun fileSize(bytes: Long): String = if (bytes < 0) stringResource(R.string.import_difference_unknown_size)
    else Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable private fun checksum(value: String): String = if (value.isBlank()) "" else "\n" + stringResource(R.string.import_difference_checksum, value.take(12))

@Composable private fun importDifferenceValue(column: String, value: String): String = when {
    column == "category" -> memoryCategoryNames[value] ?: value
    column in setOf("alwaysProvide", "current", "valid", "active", "enabled") && value in setOf("0", "1") -> stringResource(if (value == "1") R.string.common_yes else R.string.common_no)
    else -> value
}
