package com.nekobot.app.ui.screens.aiconfig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CapabilityObservation
import com.nekobot.app.data.local.ai.ModelCapability
import com.nekobot.app.data.local.ai.ModelCapabilityReport
import com.nekobot.app.data.local.ai.capabilityFingerprint
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.screens.settings.formatGlobalMemorySavedAt

@Composable
internal fun ModelCapabilityDialog(model: LocalAiModelEntity, report: ModelCapabilityReport?, running: Boolean,
    onRun: () -> Unit, onStop: () -> Unit, onApply: () -> Unit, onDismiss: () -> Unit) {
    val current = report?.fingerprint == capabilityFingerprint(model)
    val canApply = current && !running && report?.results.orEmpty().any {
        it.observation == CapabilityObservation.OBSERVED && !model.capabilityEnabled(it.capability)
    }
    NekoDialog(onDismiss = onDismiss, title = stringResource(R.string.model_probe_title) + " · " + model.name,
        confirmText = stringResource(if (running) R.string.common_cancel else R.string.model_probe_start),
        onConfirm = if (running) onStop else onRun, cancelText = stringResource(R.string.common_close), onCancel = onDismiss,
        extraActionText = stringResource(R.string.model_probe_apply), extraActionEnabled = canApply, onExtraAction = onApply,
        contentScrollable = true) {
        Text(stringResource(R.string.model_probe_hint), style = MaterialTheme.typography.bodySmall)
        report?.testedAt?.takeIf(String::isNotBlank)?.let {
            Text(stringResource(R.string.model_probe_saved, formatGlobalMemorySavedAt(it).orEmpty()), style = MaterialTheme.typography.labelSmall)
        }
        if (report != null && !current) Text(stringResource(R.string.model_probe_stale), color = MaterialTheme.colorScheme.error)
        ModelCapability.entries.forEach { capability ->
            val result = report?.results?.firstOrNull { it.capability == capability }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(capability.label()), modifier = Modifier.weight(1f))
                    Text(stringResource(when (result?.observation) {
                        CapabilityObservation.OBSERVED -> R.string.model_probe_pass
                        CapabilityObservation.NOT_OBSERVED -> R.string.model_probe_unknown
                        CapabilityObservation.ERROR -> R.string.model_probe_fail
                        null -> if (running) R.string.model_probe_running else R.string.model_probe_pending
                    }), style = MaterialTheme.typography.labelLarge)
                }
                result?.let {
                    Text("${it.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    it.error?.takeIf(String::isNotBlank)?.let { message -> Text(message, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (current && it.observation == CapabilityObservation.OBSERVED && !model.capabilityEnabled(capability))
                        Text(stringResource(R.string.model_probe_mismatch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun ModelCapability.label() = when (this) {
    ModelCapability.STREAM -> R.string.model_probe_stream
    ModelCapability.TOOLS -> R.string.model_probe_tools
    ModelCapability.VISION -> R.string.model_probe_vision
    ModelCapability.REASONING -> R.string.model_probe_reasoning
}
private fun LocalAiModelEntity.capabilityEnabled(capability: ModelCapability) = when (capability) {
    ModelCapability.STREAM -> supportsStream
    ModelCapability.TOOLS -> supportsTools
    ModelCapability.VISION -> supportsVision
    ModelCapability.REASONING -> supportsReasoning
}
