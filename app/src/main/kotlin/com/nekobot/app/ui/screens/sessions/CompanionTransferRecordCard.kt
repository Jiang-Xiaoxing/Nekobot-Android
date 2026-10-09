package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.CompanionTransferRecordInfo
import com.nekobot.app.data.local.db.CompanionTransferJobEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionTransferRecordCard(job: CompanionTransferJobEntity, info: CompanionTransferRecordInfo?, enabled: Boolean,
    onRestore: () -> Unit, onReceipt: () -> Unit, onDeleteBackup: () -> Unit, onDelete: () -> Unit) {
    val deleting = job.phase.startsWith("deleting")
    val completed = job.phase in setOf("complete", "backup_deleted")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompanionTransferRecordDetails(job, info)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!deleting) {
                    TextButton(onClick = onRestore, enabled = enabled) {
                        Text(stringResource(if (completed) R.string.companion_open_new else R.string.companion_resume))
                    }
                    TextButton(onClick = onReceipt, enabled = enabled) { Text(stringResource(R.string.companion_transfer_record)) }
                    if (job.phase == "complete") TextButton(onClick = onDeleteBackup, enabled = enabled) {
                        Text(stringResource(R.string.companion_delete_backup))
                    }
                }
                TextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("companion_delete_record_${job.id}"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.companion_delete_conversion))
                }
            }
        }
    }
}

@Composable
private fun CompanionTransferRecordDetails(job: CompanionTransferJobEntity, info: CompanionTransferRecordInfo?) {
    val time = remember(job.createdAt) { runCatching { Instant.parse(job.createdAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrDefault(job.createdAt) }
    Text(job.targetName, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.companion_record_created, time), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.companion_record_identifier, job.id.take(8)), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(when (job.phase) {
        "complete" -> R.string.companion_completed
        "backup_deleted" -> R.string.companion_record_backup_deleted
        "preview" -> R.string.companion_record_not_started
        "failed" -> R.string.companion_record_failed
        "cancelled" -> R.string.companion_record_paused
        "deleting", "deleting_complete" -> R.string.companion_record_deleting
        else -> R.string.companion_record_incomplete
    }), style = MaterialTheme.typography.bodyMedium)
    if (info?.total != null) {
        Text(stringResource(R.string.companion_record_total, info.total), style = MaterialTheme.typography.bodySmall)
    }
    if (job.phase !in setOf("complete", "backup_deleted") && !job.phase.startsWith("deleting")) {
        Text(if (info?.processed != null) stringResource(R.string.companion_record_processed, info.processed)
            else stringResource(R.string.companion_record_progress_unknown), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CompanionTransferDeleteDialog(job: CompanionTransferJobEntity, info: CompanionTransferRecordInfo?,
    onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val completed = job.phase in setOf("complete", "backup_deleted", "deleting_complete")
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.companion_delete_conversion)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompanionTransferRecordDetails(job, info)
            HorizontalDivider()
            Text(stringResource(if (completed) R.string.companion_delete_completed_record_hint else R.string.companion_delete_pending_record_hint))
        } },
        confirmButton = { TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.common_delete))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
