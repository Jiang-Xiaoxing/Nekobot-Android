package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.SessionChatModels
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionChatModelViewModel : BaseViewModel() {
    private val _models = MutableStateFlow(SessionChatModels())
    val models = _models.asStateFlow()

    fun load(sessionId: String) {
        clearError()
        launchResult(
            block = { unified.getSessionChatModels(sessionId) },
            onSuccess = { _models.value = it }
        )
    }

    fun select(sessionId: String, modelId: String?, onSuccess: () -> Unit) {
        clearError()
        launchResult(
            block = { unified.setSessionChatModel(sessionId, modelId) },
            onSuccess = { _models.value = it; onSuccess() }
        )
    }
}

@Composable
internal fun SessionChatModelPickerEntry(sessionId: String, enabled: Boolean) {
    val vm: SessionChatModelViewModel = viewModel(
        key = "chat_model_${ServiceContainer.prefs.activeDbName}_$sessionId"
    )
    val queue by vm.models.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var open by remember(vm, sessionId) { mutableStateOf(false) }
    LaunchedEffect(vm, sessionId) { vm.load(sessionId) }

    ChatSettingEntry(
        icon = Icons.Outlined.Memory,
        title = stringResource(R.string.chat_p0_model),
        value = if (queue.customized) {
            queue.models.firstOrNull()?.name.orEmpty()
        } else stringResource(R.string.chat_model_follow_default),
        onClick = { open = true; vm.load(sessionId) },
        enabled = enabled
    )

    if (open) {
        NekoDialog(
            onDismiss = { open = false },
            title = stringResource(R.string.chat_p0_model),
            message = stringResource(R.string.chat_model_session_hint),
            confirmText = stringResource(R.string.common_close),
            onConfirm = { open = false },
            cancelText = null,
            contentScrollable = true
        ) {
            Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp))
                }
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.load(sessionId) }, enabled = !loading) {
                        Text(stringResource(R.string.common_retry))
                    }
                } else {
                    ChatSettingChoice(
                        title = stringResource(R.string.chat_model_follow_default),
                        selected = !queue.customized,
                        enabled = enabled && !loading,
                        onClick = { vm.select(sessionId, null) { open = false } }
                    )
                    queue.models.forEachIndexed { index, model ->
                        ChatSettingChoice(
                            title = stringResource(R.string.chat_model_priority_name, index, model.name),
                            subtitle = model.model,
                            selected = queue.customized && index == 0,
                            enabled = enabled && !loading,
                            onClick = { vm.select(sessionId, model.id) { open = false } }
                        )
                    }
                    if (!loading && queue.models.isEmpty()) {
                        Text(
                            stringResource(R.string.chat_no_ai_model),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
