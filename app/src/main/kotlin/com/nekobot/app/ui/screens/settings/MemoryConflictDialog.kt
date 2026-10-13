package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.GlobalAgentMemoryStore
import com.nekobot.app.ui.components.BorderlessOutlinedTextField
import com.nekobot.app.ui.components.NekoDialog

@Composable
internal fun MemoryConflictDialog(draft: String, latest: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var merged by remember(draft, latest) { mutableStateOf(draft) }
    NekoDialog(onDismiss = onDismiss, title = stringResource(R.string.memory_conflict_review),
        confirmText = stringResource(R.string.memory_merge_save), onConfirm = { onConfirm(merged) },
        onCancel = onDismiss, contentScrollable = true) {
        BorderlessOutlinedTextField(value = latest, onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.memory_conflict_latest)) },
            modifier = Modifier.fillMaxWidth().height(160.dp))
        BorderlessOutlinedTextField(value = merged,
            onValueChange = { if (it.length <= GlobalAgentMemoryStore.MAX_CONTENT_CHARS) merged = it },
            label = { Text(stringResource(R.string.memory_conflict_draft)) },
            modifier = Modifier.fillMaxWidth().height(220.dp))
    }
}
