package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
    /** 是否已成功读取过一次会话模型队列：工具行胶囊据此避免先闪「跟随默认」再跳成模型 id。 */
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()

    fun load(sessionId: String) {
        clearError()
        launchResult(
            block = { unified.getSessionChatModels(sessionId) },
            onSuccess = {
                _models.value = it
                _loaded.value = true
            }
        )
    }

    fun select(sessionId: String, modelId: String?, onSuccess: () -> Unit) {
        clearError()
        launchResult(
            block = { unified.setSessionChatModel(sessionId, modelId) },
            onSuccess = {
                _models.value = it
                _loaded.value = true
                onSuccess()
            }
        )
    }
}

/** 会话聊天模型 ViewModel 的共享 key：+ 面板入口与输入工具行胶囊共用同一份状态。 */
private fun sessionChatModelVmKey(sessionId: String) =
    "chat_model_${ServiceContainer.prefs.activeDbName}_$sessionId"

@Composable
internal fun SessionChatModelPickerEntry(sessionId: String, enabled: Boolean) {
    val vm: SessionChatModelViewModel = viewModel(key = sessionChatModelVmKey(sessionId))
    val queue by vm.models.collectAsStateWithLifecycle()
    var open by remember(vm, sessionId) { mutableStateOf(false) }
    LaunchedEffect(vm, sessionId) { vm.load(sessionId) }

    if (queue.characterQueueConfigured) return

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
        SessionChatModelDialog(
            sessionId = sessionId,
            enabled = enabled,
            vm = vm,
            onDismiss = { open = false }
        )
    }
}

/**
 * 输入工具行里的当前模型胶囊：显示当前生效的模型 id，点击由调用方打开 P0 模型弹窗。
 * 仅在本地模式、且角色未单独配置主备队列时显示。
 *
 * 弹窗状态交给外层（[SessionChatModelDialogHost]）持有：输入法收起时工具行会折叠，
 * 弹窗若挂在这里会随胶囊一起被销毁，表现为「点开立刻消失」。
 */
@Composable
internal fun SessionChatModelChip(
    sessionId: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (!ServiceContainer.prefs.isLocalMode) return
    val vm: SessionChatModelViewModel = viewModel(key = sessionChatModelVmKey(sessionId))
    val queue by vm.models.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    LaunchedEffect(vm, sessionId) { vm.load(sessionId) }

    // 首次读取完成前不渲染，避免先显示兜底文案再跳变
    if (queue.characterQueueConfigured || !loaded) return

    val modelLabel = queue.models.firstOrNull()
        ?.let { it.model.ifBlank { it.name } }
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_model_follow_default)

    Row(
        modifier = modifier
            .widthIn(max = 200.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(enabled = enabled) {
                vm.load(sessionId)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = modelLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** P0 模型弹窗宿主：状态由输入区外层持有，输入法收起、工具行折叠时弹窗不会消失。 */
@Composable
internal fun SessionChatModelDialogHost(
    sessionId: String,
    enabled: Boolean,
    onDismiss: () -> Unit
) {
    val vm: SessionChatModelViewModel = viewModel(key = sessionChatModelVmKey(sessionId))
    LaunchedEffect(vm, sessionId) { vm.load(sessionId) }
    SessionChatModelDialog(
        sessionId = sessionId,
        enabled = enabled,
        vm = vm,
        onDismiss = onDismiss
    )
}

/** P0 模型弹窗：会话级主模型选择（+ 面板入口与输入工具行胶囊共用）。 */
@Composable
private fun SessionChatModelDialog(
    sessionId: String,
    enabled: Boolean,
    vm: SessionChatModelViewModel,
    onDismiss: () -> Unit
) {
    val queue by vm.models.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.chat_p0_model),
        message = stringResource(R.string.chat_model_session_hint),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
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
                    onClick = { vm.select(sessionId, null) { onDismiss() } }
                )
                queue.models.forEachIndexed { index, model ->
                    ChatSettingChoice(
                        title = stringResource(R.string.chat_model_priority_name, index, model.name),
                        subtitle = model.model,
                        selected = queue.customized && index == 0,
                        enabled = enabled && !loading,
                        onClick = { vm.select(sessionId, model.id) { onDismiss() } }
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
