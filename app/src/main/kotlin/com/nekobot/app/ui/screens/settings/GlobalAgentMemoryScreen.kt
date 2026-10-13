package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.GlobalAgentMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.nekobot.app.data.local.ai.MemoryVersion
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GlobalAgentMemoryUiState(
    val draft: String = "",
    val savedContent: String = "",
    val updatedAt: String? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val versions: List<MemoryVersion> = emptyList(),
    val conflict: Boolean = false,
    val conflictLatest: String? = null,
    val ownerRevision: Long = -1
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
}

class GlobalAgentMemoryViewModel : com.nekobot.app.ui.BaseViewModel() {
    private val _uiState = MutableStateFlow(GlobalAgentMemoryUiState())
    val uiState: StateFlow<GlobalAgentMemoryUiState> = _uiState.asStateFlow()

    init {
        reload()
        viewModelScope.launch { ServiceContainer.memoryChanged.collect { reload(preserveDraft = true) } }
        viewModelScope.launch {
            ServiceContainer.dataSourceRevision.drop(1).collect {
                _uiState.value = GlobalAgentMemoryUiState()
                reload()
            }
        }
    }

    fun updateDraft(content: String) {
        if (content.length <= GlobalAgentMemoryStore.MAX_CONTENT_CHARS)
            _uiState.update { it.copy(draft = content, message = null, messageIsError = false) }
    }

    fun reload(preserveDraft: Boolean = false) {
        val target = ServiceContainer.unified
        val revision = ServiceContainer.dataSourceRevision.value
        viewModelScope.launch {
            if (revision != ServiceContainer.dataSourceRevision.value) return@launch
            _uiState.update { it.copy(loading = true, ownerRevision = revision) }
            try {
                val (snapshot, versions) = withContext(Dispatchers.IO) {
                    target.readGlobalAgentMemory() to target.globalAgentMemoryVersions()
                }
                if (revision != ServiceContainer.dataSourceRevision.value) return@launch
                _uiState.update { state ->
                    val keep = preserveDraft && state.hasUnsavedChanges
                    val conflict = keep && state.savedContent != snapshot.content
                    state.copy(draft = if (keep) state.draft else snapshot.content,
                        savedContent = if (keep) state.savedContent else snapshot.content,
                        updatedAt = snapshot.updatedAt, loading = false, versions = versions,
                        conflict = conflict,
                        message = if (conflict) string(R.string.memory_conflict_message) else null,
                        messageIsError = conflict)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (revision == ServiceContainer.dataSourceRevision.value)
                    _uiState.update { it.copy(loading = false, message = string(R.string.global_memory_read_failed), messageIsError = true) }
            }
        }
    }

    fun save() {
        val state = _uiState.value
        persist { it.saveGlobalAgentMemory(state.draft, state.savedContent) }
    }

    fun restore(id: String) {
        val expected = _uiState.value.savedContent
        persist { it.restoreGlobalAgentMemoryVersion(id, expected) }
    }

    fun undo() {
        val expected = _uiState.value.savedContent
        persist { it.undoGlobalAgentMemory(expected) }
    }

    fun mergeAndSave() {
        val state = _uiState.value
        if (state.ownerRevision != ServiceContainer.dataSourceRevision.value) return
        val target = ServiceContainer.unified
        val revision = ServiceContainer.dataSourceRevision.value
        viewModelScope.launch {
            if (revision != ServiceContainer.dataSourceRevision.value || _uiState.value.loading || _uiState.value.saving) return@launch
            _uiState.update { it.copy(loading = true) }
            try {
                val latest = withContext(Dispatchers.IO) { target.readGlobalAgentMemory().content }
                if (revision != ServiceContainer.dataSourceRevision.value) return@launch
                val merged = com.nekobot.app.data.local.ai.mergeMemoryEdits(state.savedContent, state.draft, latest)
                if (merged == null || merged.length > GlobalAgentMemoryStore.MAX_CONTENT_CHARS) {
                    _uiState.update { it.copy(loading = false, conflict = true, conflictLatest = latest, message = string(R.string.memory_merge_conflict), messageIsError = true) }
                } else {
                    _uiState.update { it.copy(loading = false, draft = merged, savedContent = latest, conflict = false) }
                    save()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == ServiceContainer.dataSourceRevision.value) _uiState.update {
                    it.copy(loading = false, message = string(R.string.global_memory_read_failed), messageIsError = true)
                }
            }
        }
    }

    fun dismissConflict() { _uiState.update { it.copy(conflictLatest = null) } }

    fun confirmConflictMerge(content: String, expected: String) {
        if (_uiState.value.ownerRevision != ServiceContainer.dataSourceRevision.value || content.length > GlobalAgentMemoryStore.MAX_CONTENT_CHARS) return
        _uiState.update { it.copy(draft = content, savedContent = expected, conflictLatest = null, conflict = false) }
        save()
    }

    private fun persist(action: (com.nekobot.app.data.repository.UnifiedRepository) -> GlobalAgentMemoryStore.Snapshot) {
        if (_uiState.value.loading || _uiState.value.saving || _uiState.value.ownerRevision != ServiceContainer.dataSourceRevision.value) return
        val target = ServiceContainer.unified
        val revision = ServiceContainer.dataSourceRevision.value
        viewModelScope.launch {
            if (revision != ServiceContainer.dataSourceRevision.value) return@launch
            _uiState.update { it.copy(saving = true, message = null) }
            try {
                val (snapshot, versions) = withContext(Dispatchers.IO) {
                    action(target) to target.globalAgentMemoryVersions()
                }
                if (revision != ServiceContainer.dataSourceRevision.value) return@launch
                _uiState.update { it.copy(draft = snapshot.content, savedContent = snapshot.content,
                    updatedAt = snapshot.updatedAt, saving = false, versions = versions, conflict = false,
                    message = string(R.string.global_memory_saved), messageIsError = false) }
                ServiceContainer.notifyMemoryChanged()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (revision == ServiceContainer.dataSourceRevision.value) _uiState.update {
                    val conflict = error is com.nekobot.app.data.local.ai.MemoryConflictException
                    it.copy(saving = false, conflict = conflict,
                        message = string(if (conflict) R.string.memory_conflict_message else R.string.global_memory_save_failed),
                        messageIsError = true)
                }
            }
        }
    }
}

/**
 * 存储层记录的是 UTC 时间戳（Instant#toString），直接展示会让用户看到差若干小时的时间。
 * 这里按手机系统时区转换后再显示；无法解析的值原样返回。
 */
internal fun formatGlobalMemorySavedAt(raw: String?, zoneId: ZoneId = ZoneId.systemDefault()): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    if (instant != null) return instant.atZone(zoneId).format(formatter)
    // 兼容不带时区的旧数据：按手机当前时区解释，无法凭空推断原始时区。
    return runCatching {
        LocalDateTime.parse(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(zoneId)
            .format(formatter)
    }.getOrNull() ?: value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalAgentMemoryScreen(
    onBack: () -> Unit,
    onLocate: (String, String?) -> Unit = { _, _ -> },
    viewModel: GlobalAgentMemoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MemoryVersion?>(null) }
    val revision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    LaunchedEffect(revision) { showHistory = false; preview = null }
    MemoryHistoryDialogs(showHistory, preview, uiState.versions, { showHistory = false },
        { preview = it }, { preview = null }, { id -> viewModel.restore(id); preview = null; showHistory = false }, onLocate)
    uiState.conflictLatest?.let { latest ->
        MemoryConflictDialog(uiState.draft, latest, viewModel::dismissConflict,
            onConfirm = { content -> viewModel.confirmConflictMerge(content, latest) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_global_agent_memory), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 键盘弹出时不再压缩输入框：输入框保持固定高度，页面整体可滚动，
            // 最近保存、提示与按钮顺次滚到键盘下方。
            val fieldHeight = (maxHeight - 190.dp).coerceAtLeast(200.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.global_memory_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = uiState.draft,
                    onValueChange = viewModel::updateDraft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(fieldHeight),
                    enabled = !uiState.loading && !uiState.saving,
                    label = { Text(stringResource(R.string.global_memory_label)) },
                    placeholder = { Text(stringResource(R.string.global_memory_placeholder)) },
                    supportingText = {
                        Text("${uiState.draft.length} / ${GlobalAgentMemoryStore.MAX_CONTENT_CHARS}")
                    }
                )
                formatGlobalMemorySavedAt(uiState.updatedAt)?.let { savedAt ->
                    Text(
                        stringResource(R.string.global_memory_last_saved, savedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.messageIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showHistory = true }, enabled = !uiState.loading && !uiState.saving && !uiState.hasUnsavedChanges) {
                        Text(stringResource(R.string.memory_history_title))
                    }
                    TextButton(onClick = viewModel::undo, enabled = !uiState.loading && !uiState.saving && !uiState.hasUnsavedChanges && uiState.versions.firstOrNull()?.content == uiState.savedContent) {
                        Text(stringResource(R.string.memory_undo))
                    }
                    if (uiState.conflict) TextButton(onClick = viewModel::mergeAndSave, enabled = !uiState.loading && !uiState.saving) {
                        Text(stringResource(R.string.memory_merge_save))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = viewModel::reload,
                        enabled = !uiState.loading && !uiState.saving
                    ) {
                        Text(stringResource(R.string.global_memory_discard))
                    }
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier.weight(1f),
                        enabled = uiState.hasUnsavedChanges && !uiState.loading && !uiState.saving
                    ) {
                        Text(if (uiState.saving) stringResource(R.string.global_memory_saving) else stringResource(R.string.common_save))
                    }
                }
            }
        }
    }
}
