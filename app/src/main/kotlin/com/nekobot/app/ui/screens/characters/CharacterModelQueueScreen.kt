package com.nekobot.app.ui.screens.characters

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.CharacterQueueModel
import com.nekobot.app.data.local.ai.CharacterQueueSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 保存的是角色对已有配置的引用，不复制服务地址或密钥。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterModelQueueScreen(characterId: String, onBack: () -> Unit) {
    val textResources = LocalResources.current
    val repository = remember(characterId) { ServiceContainer.localRepository }
    val scope = rememberCoroutineScope()
    var settings by remember(characterId) { mutableStateOf<CharacterQueueSettings?>(null) }
    var error by remember(characterId) { mutableStateOf<String?>(null) }
    var loadVersion by remember { mutableIntStateOf(0) }
    var initialized by rememberSaveable(characterId) { mutableStateOf(false) }
    var custom by rememberSaveable(characterId) { mutableStateOf(false) }
    var order by rememberSaveable(characterId) { mutableStateOf(listOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(characterId, loadVersion) {
        error = null
        try {
            val loaded = repository.characterQueueSettings(characterId)
            settings = loaded
            if (!initialized) {
                custom = loaded.selected != null
                order = loaded.selected.orEmpty()
                initialized = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: textResources.getString(R.string.single_role_could_not_load_model_settings) }
    }
    val selected = if (custom) order else null
    val dirty = settings?.let { selected != it.selected } == true
    fun leave() { if (dirty) confirmDiscard = true else onBack() }
    BackHandler { if (!saving) leave() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(textResources.getString(R.string.single_role_chat_models)) }, navigationIcon = {
            IconButton(onClick = { leave() }, enabled = !saving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, textResources.getString(R.string.common_back))
            }
        }, actions = {
            TextButton(enabled = settings != null && !saving && (!custom || order.isNotEmpty()), onClick = {
                saving = true
                error = null
                scope.launch {
                    try {
                        check(repository === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) {
                            textResources.getString(R.string.single_role_the_data_profile_changed_reopen_character_settings)
                        }
                        repository.saveCharacterQueue(characterId, selected)
                        onBack()
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: textResources.getString(R.string.single_role_could_not_save_please_try_again) }
                    finally { saving = false }
                }
            }) { Text(if (saving) textResources.getString(R.string.failover_saving) else textResources.getString(R.string.common_save)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (settings == null) TextButton(onClick = { loadVersion++ }) { Text(textResources.getString(R.string.single_role_reload)) }
            }
            val state = settings
            if (state == null && error == null) CircularProgressIndicator()
            if (state != null) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(textResources.getString(R.string.single_role_try_models_in_order_moving_to_the_next_if_one_fails_or_is_unavail),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedCard {
                    QueueChoice(textResources.getString(R.string.single_role_use_default_queue), textResources.getString(R.string.single_role_use_the_default_queue_and_selection_rules_from_api_settings), !custom, !saving) { custom = false }
                    HorizontalDivider()
                    QueueChoice(textResources.getString(R.string.single_role_set_separately), textResources.getString(R.string.single_role_try_only_the_order_below_report_an_error_if_no_model_is_available), custom, !saving) {
                        if (!custom && order.isEmpty()) order = state.defaults
                        custom = true
                    }
                }
                val visibleIds = if (custom) order else state.defaults
                Text(if (custom) textResources.getString(R.string.single_role_this_character_s_queue) else textResources.getString(R.string.playground_auto_model), style = MaterialTheme.typography.titleMedium)
                if (visibleIds.isEmpty()) {
                    Text(if (custom) textResources.getString(R.string.single_role_no_models_selected_add_one_below) else textResources.getString(R.string.single_role_no_chat_models_available_add_one_in_api_settings_first),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                visibleIds.forEachIndexed { index, id ->
                    key(id) {
                        val model = state.models.firstOrNull { it.id == id }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("${index + 1} · ${if (index == 0) textResources.getString(R.string.single_role_primary) else textResources.getString(R.string.single_role_backup)}",
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                                QueueModelDescription(model)
                                if (custom) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        IconButton(enabled = !saving && index > 0, onClick = {
                                            order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                                        }) { Icon(Icons.Filled.ArrowUpward, textResources.getString(R.string.single_role_move_model_1_s_up, index + 1)) }
                                        IconButton(enabled = !saving && index < order.lastIndex, onClick = {
                                            order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                                        }) { Icon(Icons.Filled.ArrowDownward, textResources.getString(R.string.single_role_move_model_1_s_down, index + 1)) }
                                        IconButton(enabled = !saving, onClick = { order = order - id }) {
                                            Icon(Icons.Filled.Close, textResources.getString(R.string.single_role_remove_1_s_from_queue, model?.name ?: textResources.getString(R.string.single_role_deleted_model)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (custom) OutlinedButton(onClick = { adding = true }, enabled = !saving && order.size < 100,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(textResources.getString(R.string.single_role_add_existing_model))
                }
                Text(textResources.getString(R.string.single_role_all_sessions_for_this_character_share_this_selection_other_charac),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(textResources.getString(R.string.single_role_manage_endpoints_keys_and_model_parameters_in_api_settings_relati),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (adding) {
        val candidates = settings?.models.orEmpty().filter { it.enabled && it.chat && it.id !in order }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text(textResources.getString(R.string.single_role_add_chat_model)) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) Text(textResources.getString(R.string.single_role_no_models_to_add_add_or_enable_a_chat_model_in_api_settings_first))
                candidates.forEach { model ->
                    Column(Modifier.fillMaxWidth().clickable {
                        order = order + model.id; adding = false
                    }.padding(vertical = 16.dp)) { QueueModelDescription(model) }
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = { adding = false }) { Text(textResources.getString(R.string.common_close)) } })
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(textResources.getString(R.string.single_role_discard_unsaved_changes)) }, text = { Text(textResources.getString(R.string.single_role_the_saved_model_order_will_stay_the_same)) },
        confirmButton = { TextButton(onClick = onBack) { Text(textResources.getString(R.string.single_role_discard_changes)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(textResources.getString(R.string.chat_sandbox_files_keep_editing)) } })
}

@Composable
private fun QueueChoice(title: String, detail: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = choose).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = choose, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QueueModelDescription(model: CharacterQueueModel?) {
    val textResources = LocalResources.current
    Text(model?.name ?: textResources.getString(R.string.single_role_deleted_model), style = MaterialTheme.typography.titleSmall)
    if (model != null) Text("${model.provider} · ${model.model}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val issue = when {
        model == null -> textResources.getString(R.string.single_role_unavailable_will_be_skipped_you_can_remove_it_from_the_queue)
        !model.enabled -> textResources.getString(R.string.single_role_disabled_will_be_skipped)
        !model.chat -> textResources.getString(R.string.single_role_purpose_changed_will_be_skipped)
        else -> null
    }
    issue?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
