package com.nekobot.app.ui.screens.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.*
import com.nekobot.app.data.local.db.CompanionTransferJobEntity
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.resolveAvatarUrl
import kotlinx.coroutines.*

internal data class CompanionCreateState(
    val characters: List<CharacterPreset> = emptyList(), val selectedId: String? = null,
    val eligibility: CompanionEligibility? = null, val busy: Boolean = false,
    val preview: CompanionTransferPreview? = null, val progress: CompanionTransferProgress? = null,
    val error: String? = null, val details: String? = null, val completed: String? = null,
    val records: List<CompanionTransferJobEntity> = emptyList()
)

internal class CompanionCreateViewModel : ViewModel() {
    var state by mutableStateOf(CompanionCreateState()); private set
    private val repo = ServiceContainer.localRepository
    private var operation: Job? = null
    init { reload() }
    private fun active() { check(repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) { ServiceContainer.getString(R.string.companion_profile_changed) } }
    private fun launch(block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, details = null)
        operation = viewModelScope.launch {
            try { active(); block(); active() }
            catch (_: CancellationException) { state = state.copy(progress = null) }
            catch (failure: Exception) {
                val readable = when (failure) {
                    is CompanionCreationException -> ServiceContainer.getString(failure.messageRes)
                    is IllegalArgumentException, is IllegalStateException -> failure.message ?: ServiceContainer.getString(R.string.companion_operation_failed)
                    else -> ServiceContainer.getString(R.string.companion_operation_failed)
                }
                state = state.copy(error = readable, details = failure.toString(), progress = null)
            } finally { state = state.copy(busy = false) }
        }
    }
    fun reload() = launch {
        val characters = repo.listCharacters()
        state = state.copy(characters = characters, records = repo.companionTransferRecords())
    }
    fun select(id: String) = launch {
        state = state.copy(selectedId = id, eligibility = null, preview = null, completed = null)
        state = state.copy(eligibility = repo.companionCreationEligibility(id))
    }
    fun preview(sourceId: String) = launch {
        state = state.copy(preview = repo.previewCompanionTransfer(sourceId))
    }
    fun restore(jobId: String) = launch {
        val preview = repo.companionTransferPreview(jobId)
        state = state.copy(preview = preview, completed = preview.completedSessionId)
    }
    fun create(name: String, greeting: String, open: (String) -> Unit) = launch {
        val result = ServiceContainer.unified.createSession(CreateSessionRequest(
            name = name.takeIf(String::isNotBlank), characterId = state.selectedId,
            sessionMode = "agent", companionMode = true, firstMessage = greeting.takeIf(String::isNotBlank),
            relationshipStateSource = "initial"
        ))
        when (result) {
            is Resource.Success -> open(requireNotNull(result.data.id))
            is Resource.Error -> { state = state.copy(error = result.message); state = state.copy(eligibility = repo.companionCreationEligibility(state.selectedId)) }
            else -> Unit
        }
    }
    fun transfer(name: String) = launch {
        val preview = requireNotNull(state.preview)
        val target = repo.executeCompanionTransfer(preview.jobId, name) { progress ->
            // IO 回调切回 ViewModel 主协程，避免跨线程写入 Compose 状态。
            viewModelScope.launch { state = state.copy(progress = progress) }
        }
        state = state.copy(completed = target, progress = null, records = repo.companionTransferRecords())
    }
    fun cancel() { operation?.cancel() }
    fun backToSelection() { state = state.copy(preview = null, completed = null, progress = null, error = null, details = null) }
    fun deleteBackup(jobId: String) = launch { repo.deleteCompanionTransferBackup(jobId); state = state.copy(records = repo.companionTransferRecords()) }
    suspend fun receipt(jobId: String): String { active(); return repo.companionTransferReceipt(jobId) }
}

@Composable
internal fun CompanionCreateScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onNewCharacter: () -> Unit) {
    val vm: CompanionCreateViewModel = viewModel()
    val revision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val initialRevision = remember { revision }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.reload() }
    val scope = rememberCoroutineScope()
    var receipt by remember { mutableStateOf<String?>(null) }
    var backupToDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision, appMode) { if (revision != initialRevision || !ServiceContainer.prefs.isLocalMode) { vm.cancel(); onBack() } }
    CompanionCreateContent(vm.state, onBack = { vm.cancel(); onBack() }, onSelect = vm::select,
        onPreview = vm::preview, onCreate = { name, greeting -> vm.create(name, greeting, onOpen) },
        onTransfer = vm::transfer, onOpen = onOpen, onCancel = vm::cancel, onNewCharacter = onNewCharacter,
        onBackToSelection = vm::backToSelection, onRestore = vm::restore,
        onReceipt = { id -> scope.launch { receipt = runCatching { vm.receipt(id) }.getOrElse { it.message.orEmpty() } } },
        onDeleteBackup = { backupToDelete = it })
    receipt?.let { body -> AlertDialog(onDismissRequest = { receipt = null },
        title = { Text(stringResource(R.string.companion_transfer_record)) },
        text = { LazyColumn { item { androidx.compose.foundation.text.selection.SelectionContainer { Text(body) } } } },
        confirmButton = { TextButton(onClick = { receipt = null }) { Text(stringResource(R.string.common_close)) } }) }
    backupToDelete?.let { id -> AlertDialog(onDismissRequest = { backupToDelete = null },
        title = { Text(stringResource(R.string.companion_delete_backup)) }, text = { Text(stringResource(R.string.companion_delete_backup_hint)) },
        confirmButton = { TextButton(onClick = { backupToDelete = null; vm.deleteBackup(id) }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { backupToDelete = null }) { Text(stringResource(R.string.common_cancel)) } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionCreateContent(
    state: CompanionCreateState, onBack: () -> Unit, onSelect: (String) -> Unit,
    onPreview: (String) -> Unit, onCreate: (String, String) -> Unit, onTransfer: (String) -> Unit,
    onOpen: (String) -> Unit, onCancel: () -> Unit, onNewCharacter: () -> Unit,
    onBackToSelection: () -> Unit, onRestore: (String) -> Unit, onReceipt: (String) -> Unit,
    onDeleteBackup: (String) -> Unit
) {
    var picker by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(state.selectedId, state.preview?.jobId) { mutableStateOf(state.preview?.name.orEmpty()) }
    var greeting by rememberSaveable(state.selectedId) { mutableStateOf("") }
    val selected = state.characters.firstOrNull { it.id == state.selectedId }
    val existing = (state.eligibility as? CompanionEligibility.Existing)?.sessionId
    val completed = state.completed ?: state.preview?.completedSessionId
    val candidates = (state.eligibility as? CompanionEligibility.Used)?.candidates.orEmpty()
    val back = { if (state.busy) onCancel() else if (state.preview != null) onBackToSelection() else onBack() }
    BackHandler(onBack = back)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(stringResource(if (state.preview != null) R.string.companion_transfer_title else R.string.companion_create_title), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } }) },
        bottomBar = { Surface(tonalElevation = 2.dp) { Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("companion_primary"),
                enabled = !state.busy && (completed != null || existing != null || state.preview?.let { it.missingAttachments.isEmpty() && name.isNotBlank() } == true ||
                    state.preview == null && (state.eligibility == CompanionEligibility.Available || candidates.isNotEmpty())),
                onClick = { when {
                    completed != null -> onOpen(completed)
                    state.preview != null -> onTransfer(name)
                    existing != null -> onOpen(existing)
                    state.eligibility == CompanionEligibility.Available -> onCreate(name, greeting)
                    else -> sources = true
                } }) {
                Text(stringResource(when {
                    completed != null -> R.string.companion_open_new
                    state.busy -> R.string.companion_processing
                    state.preview != null -> R.string.companion_backup_transfer
                    existing != null -> R.string.companion_open_existing
                    candidates.isNotEmpty() -> R.string.companion_select_session
                    else -> R.string.companion_create_title
                }))
            }
        } } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("companion_content"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.preview == null) {
                item { Text(stringResource(R.string.companion_create_intro), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
                item {
                    Card(onClick = { if (!state.busy) picker = true }, modifier = Modifier.fillMaxWidth().testTag("companion_character"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (selected?.avatarUrl != null) AsyncImage(resolveAvatarUrl(selected.avatarUrl), contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                            else Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Filled.Person, null, Modifier.padding(16.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                            Column(Modifier.weight(1f)) {
                                Text(selected?.displayName ?: stringResource(R.string.companion_choose_character), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(when(state.eligibility) {
                                    CompanionEligibility.Available -> R.string.companion_available
                                    is CompanionEligibility.Existing -> R.string.companion_has_main
                                    is CompanionEligibility.Used -> R.string.companion_has_sessions
                                    CompanionEligibility.ReservedCharacter -> R.string.companion_reserved
                                    else -> R.string.companion_select_hint
                                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(if (selected == null) R.string.common_select else R.string.companion_change), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (state.eligibility is CompanionEligibility.Used) {
                    item { Text(stringResource(if (candidates.isEmpty()) R.string.companion_group_unsupported else R.string.companion_copy_hint), style = MaterialTheme.typography.bodyMedium) }
                    if (sources) items(candidates, key = { it.id }) { session ->
                        OutlinedCard(onClick = { if (!state.busy) onPreview(session.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) { Text(session.name, style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.companion_session_messages, session.messageCount), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (state.eligibility == CompanionEligibility.ReservedCharacter) item { Text(stringResource(R.string.companion_create_reserved), style = MaterialTheme.typography.bodyMedium) }
                if (state.eligibility == CompanionEligibility.Available) {
                    item { TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (advanced) R.string.companion_less_settings else R.string.companion_more_settings)) } }
                    if (advanced) {
                        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.companion_optional_name)) }, singleLine = true) }
                        item { OutlinedTextField(greeting, { greeting = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.companion_optional_greeting)) }, minLines = 3) }
                        item { Text(stringResource(R.string.companion_initial_state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                item { TextButton(onClick = onNewCharacter, enabled = !state.busy) { Text(stringResource(R.string.companion_new_character)) } }
            } else {
                val preview = state.preview
                item { Text(if (completed != null) stringResource(R.string.companion_complete_hint) else stringResource(R.string.companion_preview_hint), style = MaterialTheme.typography.bodyLarge) }
                item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("companion_copy_name"), enabled = !state.busy && completed == null, label = { Text(stringResource(R.string.companion_copy_name)) }) }
                item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(preview.sourceName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.companion_preview_counts, preview.messages, preview.variants, preview.memories, preview.attachments), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.companion_preview_space, (preview.requiredBytes / 1048576.0).let { String.format(java.util.Locale.getDefault(), "%.1f", it) }), style = MaterialTheme.typography.bodyMedium)
                    if(preview.skippedMemories > 0) Text(stringResource(R.string.companion_skipped_count, preview.skippedMemories))
                    if(preview.remoteAttachments > 0) Text(stringResource(R.string.companion_remote_count, preview.remoteAttachments))
                } } }
                items(preview.reviewNotes) { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(preview.missingAttachments) { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if(completed != null) item { TextButton(onClick = { onReceipt(preview.jobId) }) { Text(stringResource(R.string.companion_transfer_record)) } }
            }
            if (state.busy) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress?.let { it.phase + if(it.total > 0) " ${it.processed} / ${it.total}" else "" } ?: stringResource(R.string.companion_processing))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            } }
            state.error?.let { message -> item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (state.details != null) { TextButton(onClick = { details = !details }) { Text(stringResource(R.string.companion_technical_details)) }; if (details) Text(state.details, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } }
            if (state.preview == null && state.records.isNotEmpty()) {
                item { HorizontalDivider(); Text(stringResource(R.string.companion_transfer_records), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                items(state.records, key = { it.id }) { job ->
                    Column {
                        TextButton(onClick = { onRestore(job.id) }, enabled = !state.busy) { Text(job.targetName + " · " + stringResource(if (job.phase in setOf("complete", "backup_deleted")) R.string.companion_completed else R.string.companion_resume)) }
                        Row { TextButton(onClick = { onReceipt(job.id) }) { Text(stringResource(R.string.companion_transfer_record)) }
                            if(job.phase == "complete") TextButton(onClick = { onDeleteBackup(job.id) }, enabled = !state.busy) { Text(stringResource(R.string.companion_delete_backup)) } }
                    }
                }
            }
        }
    }
    if(picker) ModalBottomSheet(onDismissRequest = { picker = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.companion_choose_character), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), singleLine = true, label = { Text(stringResource(R.string.companion_search_character)) })
            LazyColumn { items(state.characters.filter { it.displayName.contains(search, true) }, key = { it.id.orEmpty() }) { character ->
                ListItem(headlineContent = { Text(character.displayName) }, supportingContent = { character.description?.take(90)?.let { Text(it, maxLines = 2) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { character.id?.let(onSelect); picker = false })
            } }
        }
    }
}
