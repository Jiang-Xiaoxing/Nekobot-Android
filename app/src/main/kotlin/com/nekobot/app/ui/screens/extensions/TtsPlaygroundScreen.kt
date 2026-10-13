package com.nekobot.app.ui.screens.extensions

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalAudioResult
import com.nekobot.app.data.model.TtsPreviewRequest
import com.nekobot.app.data.model.TtsVoice
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.screens.chat.AudioRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TtsPlaygroundViewModel : MediaPlaygroundViewModel("tts") {
    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    val voices = _voices.asStateFlow()
    private val _audio = MutableStateFlow<LocalAudioResult?>(null)
    val audio = _audio.asStateFlow()
    private val _uploadedVoiceId = MutableStateFlow<String?>(null)
    val uploadedVoiceId = _uploadedVoiceId.asStateFlow()
    val uploadUsesDefaultModel get() = !isLocalMode
    val canUpload get() = (if (isLocalMode) currentModel else models.value.firstOrNull())?.canUploadVoice == true

    override suspend fun loadModelDetails() {
        when (val result = unified.listTtsVoices(requestedModelId)) {
            is Resource.Success -> _voices.value = result.data.orEmpty()
            is Resource.Error -> { _voices.value = emptyList(); showError(result.message) }
            is Resource.Loading -> Unit
        }
    }

    override fun clearResult() {
        _audio.value = null
        _voices.value = emptyList()
        _uploadedVoiceId.value = null
    }

    fun preview(text: String, voice: String, speed: Float, pitch: Float, volume: Float) {
        if (text.isBlank()) { showToast(string(R.string.tts_prompt_input_text)); return }
        if (voice.isBlank()) { showToast(string(R.string.tts_prompt_select_voice)); return }
        val modelId = requestedModelId
        val modelName = currentModel?.name.orEmpty()
        runOperation {
            if (isLocalMode) {
                when (val result = unified.synthesizeAudio(text.trim(), voice.trim(), speed, pitch, volume, modelId)) {
                    is Resource.Success -> _audio.value = result.data
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } else {
                when (val result = unified.ttsPreview(TtsPreviewRequest(text.trim(), modelId, voice.trim(), speed, pitch, volume))) {
                    is Resource.Success -> {
                        val url = result.data.audioUrl
                        if (url.isNullOrBlank() || result.data.success == false) {
                            showError(result.data.message ?: string(R.string.tts_no_audio_url))
                        } else when (val cached = unified.cacheTtsPreview(url)) {
                            is Resource.Success -> _audio.value = cached.data.copy(usedModelId = modelId.orEmpty(), usedModelName = modelName)
                            is Resource.Error -> showError(cached.message)
                            is Resource.Loading -> Unit
                        }
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            }
        }
    }

    fun upload(context: Context, uri: Uri, name: String, sampleText: String, onSuccess: () -> Unit) {
        if (!canUpload || name.isBlank() || sampleText.isBlank()) return
        val modelId = requestedModelId
        runOperation {
            val bytes = readPlaygroundFile(context, uri, 10 * 1024 * 1024)
            val mimeType = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) ?: "audio/wav" }
            when (val result = unified.uploadPlaygroundVoice(bytes, mimeType, name.trim(), sampleText.trim(), modelId)) {
                is Resource.Success -> {
                    if (result.data.id.isBlank()) { showError(string(R.string.playground_voice_upload_failed)); return@runOperation }
                    // 服务器上传使用默认 TTS 模型，切回默认目录后选中新音色。
                    if (!isLocalMode) useDefaultModel()
                    _voices.value = (_voices.value + result.data).distinctBy { it.id }
                    _uploadedVoiceId.value = result.data.id
                    showToast(string(R.string.playground_voice_uploaded))
                    onSuccess()
                }
                is Resource.Error -> showError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }
}

@Composable
fun TtsPlaygroundScreen(onBack: () -> Unit, onConfigureModels: () -> Unit) {
    val vm: TtsPlaygroundViewModel = viewModel()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val audio by vm.audio.collectAsStateWithLifecycle()
    val uploadedVoiceId by vm.uploadedVoiceId.collectAsStateWithLifecycle()
    val busy by vm.loading.collectAsStateWithLifecycle()
    val configLoading by vm.configLoading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val revision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultText = stringResource(R.string.tts_default_test_text)
    var text by rememberSaveable { mutableStateOf(defaultText) }
    var voice by rememberSaveable { mutableStateOf("") }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var pitch by rememberSaveable { mutableFloatStateOf(1f) }
    var volume by rememberSaveable { mutableFloatStateOf(1f) }
    var parametersExpanded by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var uploadDialog by rememberSaveable { mutableStateOf(false) }
    var initializedModel by rememberSaveable { mutableStateOf("") }
    var playedAudio by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val selectedModel = models.firstOrNull { it.id == modelId } ?: models.firstOrNull()
    val modelKey = "$appMode:$revision:${selectedModel?.id}:$modelId"
    key(appMode, revision) {
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.updateSource("$appMode:$revision") }
    }
    LaunchedEffect(modelKey, voices) {
        if (selectedModel != null && voices.isNotEmpty() && initializedModel != modelKey) {
            voice = uploadedVoiceId?.takeIf { uploaded -> voices.any { it.id == uploaded } }
                ?: selectedModel.voice.takeIf { configured -> voices.any { it.id == configured } } ?: voices.first().id
            speed = selectedModel.speed.coerceIn(0.25f, 4f)
            pitch = selectedModel.pitch.coerceIn(0.5f, 2f)
            volume = selectedModel.volume.coerceIn(0f, 2f)
            initializedModel = modelKey
        }
    }
    LaunchedEffect(uploadedVoiceId) { uploadedVoiceId?.let { voice = it } }
    LaunchedEffect(audio?.cacheUri) {
        if (audio != null && audio?.cacheUri != playedAudio) listState.animateScrollToItem(if (error == null) 2 else 3)
    }
    LaunchedEffect(toast) { toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearToast() } }
    val visibleVoices = remember(voices, search) { voices.filter { search.isBlank() || it.displayName.contains(search, true) || it.id.contains(search, true) } }

    PlaygroundScaffold(stringResource(R.string.tts_title), stringResource(R.string.tts_generate_preview),
        busy, text.isNotBlank() && voice.isNotBlank() && models.isNotEmpty() && !configLoading,
        onBack, { vm.refresh() }, { vm.preview(text, voice, speed, pitch, volume) }, vm::cancel, onConfigureModels) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            error?.let { message -> item { ErrorBanner(message, onRetry = { vm.clearError(); vm.refresh() }) } }
            item {
                PlaygroundSection(stringResource(R.string.playground_input), trailing = {
                    TextButton(onClick = { text = defaultText }, enabled = !busy) { Text(stringResource(R.string.playground_sample)) }
                }) {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.tts_text_content)) }, minLines = 4, maxLines = 8,
                        supportingText = { Text(stringResource(R.string.playground_character_count, text.length)) })
                    PlaygroundModelPicker(models, modelId, !busy && !configLoading, vm::selectModel)
                    PlaygroundDropdown(stringResource(R.string.tts_voice), voice, voices.map { it.id to it.displayName },
                        { voice = it }, !busy && !configLoading)
                    if (configLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            item {
                PlaygroundSection(stringResource(R.string.playground_parameters), trailing = {
                    IconButton(onClick = { parametersExpanded = !parametersExpanded }) {
                        Icon(if (parametersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            stringResource(if (parametersExpanded) R.string.playground_collapse else R.string.playground_expand))
                    }
                }) {
                    if (parametersExpanded) {
                        SliderField(stringResource(R.string.tts_speed), speed, 0.25f..4f, !busy) { speed = it }
                        SliderField(stringResource(R.string.tts_pitch), pitch, 0.5f..2f, !busy) { pitch = it }
                        SliderField(stringResource(R.string.tts_volume), volume, 0f..2f, !busy) { volume = it }
                        OutlinedTextField(voice, { voice = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            enabled = !busy, label = { Text(stringResource(R.string.playground_voice_id)) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.playground_provider_parameters), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { speed = 1f; pitch = 1f; volume = 1f }, enabled = !busy) { Text(stringResource(R.string.playground_reset)) }
                        }
                    } else {
                        Text(stringResource(R.string.playground_voice_parameters, speed, pitch, volume),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            audio?.let { result -> item(key = "audio") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tts_preview_result), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (result.usedModelName.isNotBlank()) Text(result.usedModelName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AudioRenderer(result.cacheUri, modifier = Modifier.fillMaxWidth(),
                        autoPlay = playedAudio != result.cacheUri, onAutoPlay = { playedAudio = result.cacheUri })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PlaygroundMediaActions(result.cacheUri, result.mimeType) }
                }
            } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tts_voice_list), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.tts_voice_count, voices.size), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vm.canUpload) IconButton(onClick = { uploadDialog = true }, enabled = !busy) {
                        Icon(Icons.Default.Upload, stringResource(R.string.playground_upload_voice))
                    }
                }
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.playground_search_voices)) }, leadingIcon = { Icon(Icons.Default.Search, null) })
            }
            if (visibleVoices.isEmpty() && !configLoading) item {
                Text(stringResource(if (voices.isEmpty()) R.string.tts_no_voices else R.string.playground_no_matches),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            items(visibleVoices, key = { it.id }) { item ->
                VoiceRow(item, voice == item.id, !busy && !configLoading,
                    { voice = item.id }, { voice = item.id; vm.preview(defaultText, item.id, speed, pitch, volume) })
            }
        }
    }
    if (uploadDialog) VoiceUploadDialog(vm, onDismiss = { uploadDialog = false })
}

@Composable
private fun VoiceRow(voice: TtsVoice, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, onPreview: () -> Unit) {
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = onSelect, enabled = enabled)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(voice.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (!voice.description.isNullOrBlank()) Text(voice.description, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (voice.custom) Text(stringResource(R.string.tts_custom), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onPreview, enabled = enabled) { Icon(Icons.Default.PlayArrow, stringResource(R.string.playground_listen)) }
        }
    }
}

@Composable
private fun SliderField(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(String.format(java.util.Locale.getDefault(), "%.2f", value), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, onChange, valueRange = range, enabled = enabled)
    }
}

@Composable
private fun VoiceUploadDialog(vm: TtsPlaygroundViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val busy by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var sampleText by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        uri = selected?.toString()
        selected?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }
    NekoDialog(onDismiss = { if (!busy) onDismiss() }, title = stringResource(R.string.playground_upload_voice),
        confirmText = stringResource(if (busy) R.string.playground_working else R.string.playground_upload),
        confirmEnabled = !busy && uri != null && name.isNotBlank() && sampleText.isNotBlank(),
        onConfirm = { uri?.let { vm.upload(context.applicationContext, Uri.parse(it), name, sampleText, onDismiss) } },
        onCancel = { vm.cancel(); onDismiss() }, contentScrollable = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (vm.uploadUsesDefaultModel) R.string.playground_upload_default_model else R.string.playground_upload_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (uri == null) R.string.playground_select_audio else R.string.playground_audio_selected))
            }
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.playground_voice_name)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(sampleText, { sampleText = it }, label = { Text(stringResource(R.string.playground_sample_text)) },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !busy)
        }
    }
}
