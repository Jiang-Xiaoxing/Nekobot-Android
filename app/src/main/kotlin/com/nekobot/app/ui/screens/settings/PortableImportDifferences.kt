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

/** Individual edits remain a draft; explicit bulk actions also refresh the review without writing data. */
@Composable
internal fun PortableImportDifferences(plan: PortableImportPreview, choices: PortableImportChoices, busy: Boolean,
    onDismiss: () -> Unit, onSave: (PortableImportChoices) -> Unit, onMore: (PortableImportChoices) -> Unit,
    onBulkRecords: (PortableImportChoices, Boolean) -> Unit, reviewRowKeys: Set<String>? = null) {
    var draft by remember(plan) { mutableStateOf(choices.normalized()) }
    var confirmStory by remember { mutableStateOf(false) }
    var confirmRecords by remember(plan) { mutableStateOf(false) }
    val focused = reviewRowKeys != null
    val reviewRows = plan.rows.differences.filter { reviewRowKeys == null || it.key in reviewRowKeys }
    val count = reviewRowKeys?.size ?: plan.rows.counts.different
    val hasChoices = !focused || reviewRows.size == count && reviewRows.all { row ->
        row.fields.all { it.column in draft.fields[row.key].orEmpty() } || row.memoryId in draft.keepMemorySources
    }
    NekoDialog(onDismiss = onDismiss, title = stringResource(if (focused) R.string.import_review_compare else R.string.safe_import_changes),
        message = if (focused) null else stringResource(R.string.import_difference_notice),
        confirmText = stringResource(R.string.import_difference_save), confirmEnabled = !busy && hasChoices,
        onConfirm = { onSave(draft.normalized()) }, onCancel = onDismiss, contentScrollable = true) {
        if (focused) Text(stringResource(R.string.import_review_memory_choice_hint), style = MaterialTheme.typography.bodyMedium)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.safe_import_checking), style = MaterialTheme.typography.bodyMedium)
        }
        if (count > 0) {
            Text(stringResource(if (focused) R.string.import_review_related_scope else R.string.import_bulk_record_scope, count),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirmRecords = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("safe-difference-bulk-record-incoming")) {
                Text(stringResource(if (focused) R.string.import_review_update_related else R.string.import_bulk_record_incoming))
            }
            TextButton(onClick = { onBulkRecords(draft.normalized(), false) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("safe-difference-bulk-record-keep")) {
                Text(stringResource(if (focused) R.string.import_review_keep_related else R.string.import_bulk_record_keep))
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        reviewRows.forEachIndexed { index, row ->
            DifferenceHeading(importDetailLabel(PortableCategoryDetail("table:${row.table}", 1)), row.label, row.owner)
            if (focused || row.table in setOf("companion_memories", "local_characters")) {
                val visible = row.fields.filter { it.column in setOf("content", "subject", "name", "description", "basic_info", "rules", "personality", "category", "alwaysProvide") }
                visible.forEach { field ->
                    Text(when (field.column) {
                        "subject" -> stringResource(R.string.import_review_memory_person)
                        "basic_info" -> stringResource(R.string.import_review_basic_info)
                        "rules" -> stringResource(R.string.import_review_rules)
                        "personality" -> stringResource(R.string.import_review_personality)
                        else -> importFieldLabel(field.column)
                    },
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    DifferenceValues(importDifferenceValue(field.column, field.current), importDifferenceValue(field.column, field.incoming))
                }
                val other = row.fields - visible.toSet()
                if (other.isNotEmpty()) {
                    Text(stringResource(if (row.memoryId != null) R.string.import_review_memory_metadata else R.string.import_review_record_metadata),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var expanded by remember(row.key) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.import_review_hide_details else R.string.import_review_details)) }
                    if (expanded) other.forEach { field ->
                        Text(importFieldLabel(field.column), style = MaterialTheme.typography.titleSmall)
                        DifferenceValues(importDifferenceValue(field.column, field.current), importDifferenceValue(field.column, field.incoming))
                    }
                }
                val decision = when {
                    row.fields.all { it.column in draft.fields[row.key].orEmpty() } -> true
                    row.memoryId in draft.keepMemorySources -> false
                    else -> if (focused) null else false
                }
                DifferenceChoice(decision, !busy,
                    if (focused) "safe-difference-memory-$index" else "safe-difference-row-$index-${row.fields.first().column}",
                    if (row.memoryId != null) R.string.import_review_keep_memory else R.string.import_review_keep_record,
                    if (row.memoryId != null) R.string.import_review_update_memory else R.string.import_review_update_record) { replace ->
                    draft = draft.withRecordChoice(row, replace, plan.rows.sourceFieldChoices[row.memoryId].orEmpty())
                }
            } else row.fields.forEach { field ->
                Text(importFieldLabel(field.column), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                DifferenceValues(importDifferenceValue(field.column, field.current), importDifferenceValue(field.column, field.incoming))
                DifferenceChoice(field.column in draft.fields[row.key].orEmpty(), !busy, "safe-difference-row-$index-${field.column}") { replace ->
                    val fields = draft.fields[row.key].orEmpty()
                    draft = draft.copy(fields = draft.fields + (row.key to if (replace) fields + field.column else fields - field.column)).normalized()
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        plan.fileDifferences.filter { !focused }.forEachIndexed { index, file ->
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
        plan.settingDifferences.filter { !focused }.forEach { field ->
            DifferenceHeading(stringResource(R.string.portability_category_app_settings), importFieldLabel(field.column), PortableImportOwner())
            DifferenceValues(field.current, field.incoming)
            DifferenceChoice(field.column in draft.settings, !busy, "safe-difference-setting-${field.column}") { replace ->
                draft = draft.copy(settings = if (replace) draft.settings + field.column else draft.settings - field.column)
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }
        if (plan.storyDifferent && !focused) {
            DifferenceHeading(stringResource(R.string.safe_import_story_confirm_title), stringResource(R.string.safe_import_story), PortableImportOwner())
            Text(stringResource(R.string.safe_import_story_confirm_message), style = MaterialTheme.typography.bodyMedium)
            DifferenceChoice("plot_story" in draft.files, !busy, "safe-difference-story") { replace ->
                if (replace) confirmStory = true else draft = draft.copy(files = draft.files - "plot_story")
            }
        }
        val more = plan.rows.differences.size < plan.rows.counts.different || plan.fileDifferences.size < plan.fileCounts.different
        if (more && !focused) TextButton(onClick = { onMore(draft.normalized()) }, enabled = !busy) { Text(stringResource(R.string.safe_import_more)) }
    }
    if (confirmStory) NekoDialog(onDismiss = { confirmStory = false }, title = stringResource(R.string.safe_import_story_confirm_title),
        message = stringResource(R.string.safe_import_story_confirm_message),
        onConfirm = { confirmStory = false; draft = draft.copy(files = draft.files + "plot_story") }, onCancel = { confirmStory = false })
    if (confirmRecords) NekoDialog(onDismiss = { confirmRecords = false }, title = stringResource(if (focused) R.string.import_review_update_related else R.string.import_bulk_record_incoming),
        message = stringResource(if (focused) R.string.import_review_update_confirm else R.string.import_bulk_record_confirm, count),
        confirmEnabled = !busy,
        onConfirm = { confirmRecords = false; onBulkRecords(draft.normalized(), true) }, onCancel = { confirmRecords = false })
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

@Composable private fun DifferenceChoice(replace: Boolean?, enabled: Boolean, tag: String,
    keepLabel: Int = R.string.import_difference_keep, updateLabel: Int = R.string.import_difference_replace, onSelect: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(top = 4.dp)) {
        listOf(false, true).forEach { useIncoming ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = replace == useIncoming, enabled = enabled,
                role = Role.RadioButton, onClick = { onSelect(useIncoming) }).testTag(tag + if (useIncoming) "-replace" else "-keep").padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = replace == useIncoming, enabled = enabled, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (useIncoming) updateLabel else keepLabel),
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
