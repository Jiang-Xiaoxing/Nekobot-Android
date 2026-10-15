package com.nekobot.app.ui.screens.memory

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.local.ai.LocalProtocols
import com.nekobot.app.data.model.ApiKey
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.screens.aiconfig.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 复用作者模型编辑器，只固定用途；保存本身不调用向量、不改变全局激活模型。 */
@Composable internal fun CompanionEmbeddingModelEditor(repo: LocalRepository, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf(emptyList<ApiKey>()) }
    var available by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    val protocols = remember { LocalProtocols.names().map { ProtocolOption(it, it) } }
    val initial = remember { AiModelEditorState(purpose = "embedding", model = "", maxTokens = "", maxContextLength = "",
        protocol = "openai_compatible", supportsTools = false, supportsReasoning = false, supportsStream = false) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { "资料库已切换，请重新打开" }
                block()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, "操作未完成，请检查模型配置后重试。", Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) { keys = runCatching { repo.listApiKeys() }.getOrDefault(emptyList()) }
    AiModelEditorDialog(initial = initial, isEditing = false, protocols = protocols, purposes = listOf("embedding"),
        availableModels = available, savedApiKeys = keys, showProxyConfig = true,
        onResolveApiKey = { id, callback -> action { repo.getApiKey(id)?.key?.let(callback) } },
        onFetchModels = { url, key, protocol, append, proxy -> action { available = repo.fetchAvailableModels(url, key, protocol, append, proxy) } },
        onDismiss = { if (!busy) onDismiss() }, onConfirm = { state -> action {
            val model = state.copy(purpose = "embedding").toLocalEntity(null).copy(active = false)
            repo.upsertAiModel(model)
            onAdded(model.id)
        } })
    LoadingOverlay(visible = busy)
}
