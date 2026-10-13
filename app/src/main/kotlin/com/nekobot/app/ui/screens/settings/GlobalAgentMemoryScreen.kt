package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    GlobalAgentMemoryContent(
        uiState = uiState,
        onBack = onBack,
        onDraftChange = viewModel::updateDraft,
        onHistory = { showHistory = true },
        onUndo = viewModel::undo,
        onDiscard = { viewModel.reload() },
        onSave = viewModel::save,
        onMerge = viewModel::mergeAndSave
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalAgentMemoryContent(
    uiState: GlobalAgentMemoryUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onHistory: () -> Unit,
    onUndo: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    onMerge: () -> Unit
) {
    val busy = uiState.loading || uiState.saving
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.more_global_agent_memory),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            GlobalMemorySaveBar(uiState, onDiscard, onSave, onMerge)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            // 编辑区有独立的文本滚动，窄屏与键盘场景仍可滚动查看整页。
            val fieldHeight = (maxHeight - 220.dp).coerceIn(240.dp, 560.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.global_memory_editor_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                GlobalMemoryStatus(uiState)
                            }
                            Column(
                                modifier = Modifier.weight(1.3f),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(R.string.global_memory_character_count, uiState.draft.length, GlobalAgentMemoryStore.MAX_CONTENT_CHARS),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                formatGlobalMemorySavedAt(uiState.updatedAt)?.let { savedAt ->
                                    val savedAtDescription = stringResource(R.string.global_memory_last_saved, savedAt)
                                    Text(
                                        savedAt,
                                        modifier = Modifier.semantics { contentDescription = savedAtDescription },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        val editorLabel = stringResource(R.string.global_memory_label)
                        Box(Modifier.fillMaxWidth().padding(20.dp)) {
                            BasicTextField(
                                value = uiState.draft,
                                onValueChange = onDraftChange,
                                enabled = !busy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(fieldHeight)
                                    .semantics { contentDescription = editorLabel },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (uiState.draft.isEmpty() && !uiState.loading) {
                                            Text(
                                                stringResource(R.string.global_memory_placeholder),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            if (uiState.loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = onHistory,
                                enabled = !busy && !uiState.hasUnsavedChanges,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.memory_history_title))
                            }
                            TextButton(
                                onClick = onUndo,
                                enabled = !busy && !uiState.hasUnsavedChanges && uiState.versions.firstOrNull()?.content == uiState.savedContent,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.memory_undo))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalMemoryStatus(uiState: GlobalAgentMemoryUiState) {
    val status = when {
        uiState.loading -> R.string.common_loading
        uiState.saving -> R.string.global_memory_saving
        uiState.conflict -> R.string.global_memory_status_conflict
        uiState.hasUnsavedChanges -> R.string.global_memory_status_unsaved
        uiState.messageIsError -> R.string.global_memory_status_error
        uiState.savedContent.isBlank() -> R.string.global_memory_status_empty
        else -> R.string.global_memory_status_saved
    }
    val tint = if (uiState.conflict || uiState.messageIsError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (uiState.loading || uiState.saving) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = tint)
        } else {
            Icon(
                when {
                    uiState.conflict || uiState.messageIsError -> Icons.Outlined.ErrorOutline
                    uiState.hasUnsavedChanges || uiState.savedContent.isBlank() -> Icons.Outlined.EditNote
                    else -> Icons.Outlined.CheckCircle
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun GlobalMemorySaveBar(
    uiState: GlobalAgentMemoryUiState,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    onMerge: () -> Unit
) {
    val busy = uiState.loading || uiState.saving
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Column(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                uiState.message?.let { message ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            if (uiState.messageIsError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (uiState.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (uiState.conflict) {
                    TextButton(onClick = onMerge, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.memory_merge_save))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = onDiscard,
                        enabled = !busy && (uiState.hasUnsavedChanges || uiState.messageIsError),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(if (uiState.messageIsError && !uiState.hasUnsavedChanges) R.string.common_retry else R.string.global_memory_discard))
                    }
                    Button(
                        onClick = onSave,
                        enabled = uiState.hasUnsavedChanges && !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (uiState.saving) R.string.global_memory_saving else R.string.common_save))
                    }
                }
            }
        }
    }
}
