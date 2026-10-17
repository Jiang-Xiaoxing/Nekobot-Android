package com.nekobot.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

internal data class SafeImportUiState(
    val prepared: PreparedPortableImport? = null,
    val selected: Map<PortableDataCategory, Set<String>> = emptyMap(),
    val choices: PortableImportChoices = PortableImportChoices(),
    val plan: PortableImportPreview? = null,
    val planValid: Boolean = false,
    val busy: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val result: PortableImportResult? = null,
    val route: String? = null,
    val passwordNeeded: Boolean = false
)

class SafePortableImportViewModel : ViewModel() {
    internal val state = MutableStateFlow(SafeImportUiState())
    private var work: Job? = null
    private var cleared = false
    private var limit = 50
    fun inspect(context: Context, uri: Uri, password: String) {
        if (state.value.busy) return
        state.value.prepared?.close()
        state.value = SafeImportUiState(busy = true)
        work = viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { PortableDataArchiveManager(context).prepareImport(it, password) }
                        ?: error(context.getString(R.string.portability_open_input_failed))
                }
                val selected = prepared.preview.categories.associate { summary -> summary.category to
                    if (summary.category == PortableDataCategory.CREDENTIALS) emptySet() else summary.details
                        .filter { it.key != "plot_story" }.mapTo(linkedSetOf()) { it.key } }
                state.value = state.value.copy(prepared = prepared, selected = selected)
            } catch (error: CancellationException) { state.value = state.value.copy(error = context.getString(R.string.safe_import_cancelled)) }
            catch (error: Exception) {
                val route = withContext(Dispatchers.IO) { recognizeDedicatedImport(context, uri) }
                state.value = state.value.copy(route = route,
                    passwordNeeded = route == null && error.message?.contains("加密") == true,
                    error = if (route != null) context.getString(R.string.safe_import_dedicated) else error.message ?: context.getString(R.string.safe_import_failed))
            } finally { state.value = state.value.copy(busy = false); if (cleared) state.value.prepared?.close() }
        }
    }
    internal fun select(category: PortableDataCategory, keys: Set<String>, checked: Boolean) {
        if (state.value.busy) return
        val old = state.value.selected[category].orEmpty()
        state.value = state.value.copy(selected = state.value.selected + (category to if (checked) old + keys else old - keys), planValid = false, result = null)
    }
    internal fun choices(value: PortableImportChoices) { if (!state.value.busy) state.value = state.value.copy(choices = value, planValid = false, result = null) }
    fun preview(context: Context, more: Boolean = false) {
        val prepared = state.value.prepared ?: return
        if (state.value.busy) return
        if (more) limit += 50
        state.value = state.value.copy(busy = true, error = null, result = null, planValid = false, progress = context.getString(R.string.safe_import_checking))
        work = viewModelScope.launch {
            try {
                val snapshot = state.value
                val plan = PortableDataArchiveManager(context).previewImport(prepared, snapshot.selected, snapshot.choices, limit)
                state.value = state.value.copy(plan = plan, planValid = true)
            } catch (_: CancellationException) { state.value = state.value.copy(error = context.getString(R.string.safe_import_cancelled)) }
            catch (error: Exception) { state.value = state.value.copy(error = error.message ?: context.getString(R.string.safe_import_failed), planValid = false) }
            finally { state.value = state.value.copy(busy = false); if (cleared) prepared.close() }
        }
    }
    fun commit(context: Context) {
        val snapshot = state.value
        val prepared = snapshot.prepared ?: return
        val plan = snapshot.plan ?: return
        if (snapshot.busy || !snapshot.planValid || plan.issues.isNotEmpty()) return
        state.value = snapshot.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                val result = PortableDataArchiveManager(context).commitImport(prepared, plan) { message -> state.value = state.value.copy(progress = message) }
                state.value = state.value.copy(result = result, planValid = false)
            } catch (_: CancellationException) {
                state.value = state.value.copy(result = prepared.result, planValid = false,
                    error = if (prepared.result == null) context.getString(R.string.safe_import_cancelled) else null)
            } catch (error: Exception) { state.value = state.value.copy(result = prepared.result,
                error = if (prepared.result != null) context.getString(R.string.safe_import_reload_failed) else error.message ?: context.getString(R.string.safe_import_failed), planValid = false) }
            finally { state.value = state.value.copy(busy = false); if (cleared) prepared.close() }
        }
    }
    fun cancel() { work?.cancel() }
    override fun onCleared() { cleared = true; val running = work?.isCompleted == false; work?.cancel(); if (!running) state.value.prepared?.close() }
}

private data class ImportGroup(val category: PortableDataCategory, val title: String, val details: List<PortableCategoryDetail>)

@Composable
internal fun SafePortableImportPanel(onNavigate: (String) -> Unit = {}, externalBusy: Boolean = false,
    onBusyChanged: (Boolean) -> Unit = {}, onCompleted: () -> Unit = {},
    vm: SafePortableImportViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.busy) { onBusyChanged(state.busy) }
    LaunchedEffect(state.result) { if (state.result != null) onCompleted() }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<ImportGroup?>(null) }
    var showDifferences by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var storyConfirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) { uri = selected; password = ""; vm.inspect(context, selected, "") }
    }
    SectionHeader(title = stringResource(R.string.portability_import_title))
    Text(stringResource(R.string.safe_import_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy && !externalBusy,
        modifier = Modifier.testTag("safe-import-choose").widthIn(min = 160.dp, max = 200.dp).heightIn(min = 48.dp)) {
        Text(stringResource(R.string.portability_choose_archive), style = MaterialTheme.typography.labelLarge)
    }
    if (state.passwordNeeded && state.prepared == null) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.portability_import_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { uri?.let { vm.inspect(context, it, password) } }, enabled = !state.busy) { Text(stringResource(R.string.portability_analyze_archive)) }
    }
    state.route?.let { route ->
        val label = when (route) {
            Routes.CHARACTERS -> categoryLabel(PortableDataCategory.CHARACTERS)
            Routes.SESSIONS -> categoryLabel(PortableDataCategory.CONVERSATIONS)
            Routes.KNOWLEDGE -> categoryLabel(PortableDataCategory.KNOWLEDGE)
            Routes.SKILLS -> stringResource(R.string.skills_title)
            Routes.PLUGINS -> stringResource(R.string.plugins_title)
            Routes.DB_PROFILE -> stringResource(R.string.safe_import_database)
            else -> stringResource(R.string.settings_config_transfer)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onNavigate(route) }) { Text(stringResource(R.string.safe_import_open_dedicated)) }
    }
    if (state.busy) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(state.progress.ifBlank { stringResource(R.string.safe_import_checking) }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::cancel) { Text(stringResource(R.string.common_cancel)) }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    state.prepared?.let { prepared ->
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.safe_import_recognized, prepared.preview.sourceVersion.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val groups = buildList {
            prepared.preview.categories.forEach { summary ->
                if (summary.category == PortableDataCategory.CONVERSATIONS) {
                    val independent = summary.details.filter { it.key.removePrefix("table:") in INDEPENDENT_MEMORY_TABLES }
                    val other = summary.details - independent.toSet()
                    if (other.isNotEmpty()) add(ImportGroup(summary.category, categoryLabel(summary.category), other))
                    if (independent.isNotEmpty()) add(ImportGroup(summary.category, stringResource(R.string.safe_import_independent_memory), independent))
                } else add(ImportGroup(summary.category, categoryLabel(summary.category), summary.details))
            }
        }
        if (groups.isEmpty()) Text(stringResource(R.string.safe_import_empty), style = MaterialTheme.typography.bodyMedium)
        groups.forEach { item ->
            val keys = item.details.mapTo(linkedSetOf()) { it.key }
            val groupTag = "safe-import-${item.category.id}-${if (keys.any { it.removePrefix("table:") in INDEPENDENT_MEMORY_TABLES }) "memory" else "data"}"
            val selected = state.selected[item.category].orEmpty()
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = keys.all(selected::contains), enabled = !state.busy,
                    onCheckedChange = { vm.select(item.category, keys, it) }, modifier = Modifier.testTag(groupTag))
                Column(Modifier.weight(1f).clickable(enabled = !state.busy) { group = item }.padding(vertical = 8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.safe_import_selected_count, keys.count(selected::contains), keys.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { group = item }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.safe_import_contents)) }
            }
        }
        if ("credentials_bundle" in state.selected[PortableDataCategory.CREDENTIALS].orEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.choices.restoreCredentials, onCheckedChange = { vm.choices(state.choices.copy(restoreCredentials = it)) }, enabled = !state.busy)
                Text(stringResource(R.string.safe_import_credentials_consent), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.safe_import_defaults), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.plan?.let { plan ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.safe_import_summary, plan.rows.counts.added + plan.fileCounts.added,
                plan.rows.counts.same + plan.fileCounts.same, plan.rows.counts.different + plan.fileCounts.different + plan.settingDifferences.size + if (plan.storyDifferent) 1 else 0),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            TextButton(onClick = { showDifferences = true }, enabled = !state.busy) { Text(stringResource(R.string.safe_import_changes)) }
            if (plan.rows.sensitiveFieldsOmitted > 0) Text(stringResource(R.string.safe_import_secrets_skipped, plan.rows.sensitiveFieldsOmitted),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (plan.issues.isNotEmpty()) TextButton(onClick = { showIssues = true }, enabled = !state.busy) {
                Text(stringResource(R.string.safe_import_dependencies, plan.issues.size), color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (state.planValid && state.plan?.issues?.isEmpty() == true) confirm = true else vm.preview(context) },
                enabled = !state.busy && !externalBusy && state.selected.values.any { it.isNotEmpty() },
                modifier = Modifier.testTag("safe-import-execute").widthIn(min = 160.dp, max = 200.dp).heightIn(min = 48.dp)) {
                Text(stringResource(if (state.planValid && state.plan?.issues?.isEmpty() == true) R.string.safe_import_execute else R.string.safe_import_preview))
            }
            if (state.planValid) TextButton(onClick = { vm.preview(context) }, enabled = !state.busy) { Text(stringResource(R.string.safe_import_refresh)) }
        }
    }
    state.result?.let { result ->
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.safe_import_done), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.safe_import_result, result.newRows, result.updatedRows, result.importedFiles, result.sameItems, result.keptItems, result.coreMemories), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.safe_import_character_result, result.newCharacters, result.sameNameCopies), style = MaterialTheme.typography.bodySmall)
        if (result.credentialsRestored > 0) Text(stringResource(R.string.safe_import_credentials_result, result.credentialsRestored), style = MaterialTheme.typography.bodySmall)
        if (result.settingsUpdated > 0) Text(stringResource(R.string.safe_import_settings_result, result.settingsUpdated), style = MaterialTheme.typography.bodySmall)
        if (result.storyUpdated) Text(stringResource(R.string.safe_import_story_result), style = MaterialTheme.typography.bodySmall)
    }
    group?.let { item ->
        NekoDialog(onDismiss = { group = null }, title = item.title, confirmText = stringResource(R.string.common_confirm), onConfirm = { group = null }, cancelText = null, contentScrollable = true) {
            item.details.forEach { detail ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = detail.key in state.selected[item.category].orEmpty(), onCheckedChange = { vm.select(item.category, setOf(detail.key), it) }, enabled = !state.busy)
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(importDetailLabel(detail), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.portability_item_count, detail.itemCount), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (showDifferences) state.plan?.let { plan ->
        NekoDialog(onDismiss = { showDifferences = false }, title = stringResource(R.string.safe_import_changes),
            message = stringResource(R.string.safe_import_defaults), confirmText = stringResource(R.string.common_confirm), onConfirm = { showDifferences = false }, cancelText = null, contentScrollable = true) {
            plan.rows.differences.forEach { row ->
                Text(row.label, style = MaterialTheme.typography.titleSmall)
                row.fields.forEach { field ->
                    val checked = field.column in state.choices.fields[row.key].orEmpty()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(checked, enabled = !state.busy, onCheckedChange = { value ->
                            val fields = state.choices.fields[row.key].orEmpty()
                            vm.choices(state.choices.copy(fields = state.choices.fields + (row.key to if (value) fields + field.column else fields - field.column)))
                        })
                        Column(Modifier.weight(1f)) {
                            Text(importFieldLabel(field.column), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.safe_import_current, field.current), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.safe_import_incoming, field.incoming), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            plan.fileDifferences.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(file.key in state.choices.files, enabled = !state.busy, onCheckedChange = { value -> vm.choices(state.choices.copy(files = if (value) state.choices.files + file.key else state.choices.files - file.key)) })
                    Text(file.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            plan.settingDifferences.forEach { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(field.column in state.choices.settings, enabled = !state.busy, onCheckedChange = { value -> vm.choices(state.choices.copy(settings = if (value) state.choices.settings + field.column else state.choices.settings - field.column)) })
                    Column { Text(importFieldLabel(field.column), style = MaterialTheme.typography.labelLarge); Text(stringResource(R.string.safe_import_current, field.current), style = MaterialTheme.typography.bodySmall); Text(stringResource(R.string.safe_import_incoming, field.incoming), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (plan.storyDifferent) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox("plot_story" in state.choices.files, enabled = !state.busy, onCheckedChange = { value ->
                    if (value) storyConfirm = true else vm.choices(state.choices.copy(files = state.choices.files - "plot_story"))
                })
                Text(stringResource(R.string.safe_import_story), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { vm.preview(context, more = true) }, enabled = !state.busy) { Text(stringResource(R.string.safe_import_more)) }
        }
    }
    if (showIssues) state.plan?.let { plan ->
        NekoDialog(onDismiss = { showIssues = false }, title = stringResource(R.string.safe_import_dependencies_title),
            confirmText = stringResource(R.string.safe_import_back_selection), onConfirm = { showIssues = false }, cancelText = null, contentScrollable = true) {
            plan.issues.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp)) }
            plan.dependencyOptions.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(option.detail.key in state.selected[option.category].orEmpty(), enabled = !state.busy,
                        onCheckedChange = { vm.select(option.category, setOf(option.detail.key), it) })
                    Text(importDetailLabel(option.detail), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.choices.allowSameName, enabled = !state.busy, onCheckedChange = { vm.choices(state.choices.copy(allowSameName = it)) })
                Text(stringResource(R.string.safe_import_same_name_consent), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirm) NekoDialog(onDismiss = { confirm = false }, title = stringResource(R.string.safe_import_execute),
        message = stringResource(R.string.safe_import_confirm), confirmText = stringResource(R.string.safe_import_execute),
        onConfirm = { confirm = false; vm.commit(context) }, onCancel = { confirm = false })
    if (storyConfirm) NekoDialog(onDismiss = { storyConfirm = false }, title = stringResource(R.string.safe_import_story_confirm_title),
        message = stringResource(R.string.safe_import_story_confirm_message), confirmText = stringResource(R.string.common_confirm),
        onConfirm = { storyConfirm = false; vm.choices(state.choices.copy(files = state.choices.files + "plot_story")) },
        onCancel = { storyConfirm = false })
}

private val INDEPENDENT_MEMORY_TABLES = setOf("companion_scopes", "companion_memories", "companion_sources", "companion_receipts",
    "companion_forgotten_sources", "companion_pending", "companion_vectors", "companion_clarifications", "companion_owners")

/** Recognition only: dedicated formats retain their own existing validation/permission screens. */
private fun recognizeDedicatedImport(context: Context, uri: Uri): String? = runCatching {
    val prefix = context.contentResolver.openInputStream(uri)?.use { input -> ByteArray(4096).let { data -> val count = input.read(data); data.copyOf(count.coerceAtLeast(0)) } } ?: return null
    if (prefix.take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte())) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 64 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: return null
        require(bytes.size <= 64 * 1024 * 1024)
        return if (com.nekobot.app.ServiceContainer.localRepository.extractCharacterJsonFromPng(bytes) != null) Routes.CHARACTERS else null
    }
    val text = String(prefix, Charsets.UTF_8).trimStart()
    if (text.startsWith("SQLite format 3")) return Routes.DB_PROFILE
    if (text.startsWith("[") || text.startsWith("{")) {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= 4 * 1024 * 1024) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
            out.toByteArray()
        } ?: return null
        if (raw.size > 4 * 1024 * 1024) return null
        val json = JsonParser.parseString(String(raw, Charsets.UTF_8))
        if (json.isJsonArray) return Routes.SESSIONS
        if (json.isJsonObject) {
            val obj = json.asJsonObject
            if (obj.has("source_format")) return null
            if (listOf("session", "sessions", "messages").any(obj::has)) return Routes.SESSIONS
            if (obj.has("spec") || obj.has("name") && listOf("description", "personality", "first_mes").any(obj::has)) return Routes.CHARACTERS
        }
    }
    if (prefix.size >= 2 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()) {
        val temp = File.createTempFile("import-identify-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 512L * 1024 * 1024); output.write(buffer, 0, count) }
            } }
            ZipFile(temp).use { zip ->
                val names = zip.entries().asSequence().take(50001).map { it.name }.toSet()
                require(names.size <= 50000)
                when {
                    "plugin.json" in names -> Routes.PLUGINS
                    names.any { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) } -> Routes.SKILLS
                    names.any { it.substringAfterLast('/') == "character.json" } -> Routes.CHARACTERS
                    names.any { it.endsWith(".db") } -> Routes.DB_PROFILE
                    "config.nbotcfg" in names -> Routes.CONFIG_TRANSFER
                    "word/document.xml" in names || "META-INF/container.xml" in names -> Routes.KNOWLEDGE
                    else -> null
                }
            }
        } finally { temp.delete() }
    } else if (text.startsWith("%PDF") || context.contentResolver.getType(uri)?.startsWith("text/") == true) Routes.KNOWLEDGE else null
}.getOrNull()

@Composable private fun importDetailLabel(detail: PortableCategoryDetail): String {
    val key = detail.key.removePrefix("table:")
    return when (key) {
        "companion_memories" -> stringResource(R.string.safe_import_memory_bodies)
        "companion_sources" -> stringResource(R.string.safe_import_memory_sources)
        "companion_scopes", "companion_owners" -> stringResource(R.string.safe_import_memory_ownership)
        "companion_receipts", "companion_pending" -> stringResource(R.string.safe_import_memory_progress)
        "companion_forgotten_sources" -> stringResource(R.string.safe_import_memory_forgotten)
        "companion_vectors" -> stringResource(R.string.safe_import_memory_vectors)
        "companion_clarifications" -> stringResource(R.string.safe_import_memory_clarifications)
        else -> portableDetailLabel(detail)
    }
}

@Composable private fun importFieldLabel(column: String): String = when (column) {
    "content" -> stringResource(R.string.safe_import_field_content)
    "name", "title" -> stringResource(R.string.safe_import_field_name)
    "category" -> stringResource(R.string.safe_import_field_category)
    "alwaysProvide" -> stringResource(R.string.safe_import_field_core)
    "description" -> stringResource(R.string.safe_import_field_description)
    "updated_at", "updatedAt" -> stringResource(R.string.safe_import_field_updated)
    "expiresAt", "expires_at" -> stringResource(R.string.safe_import_field_expiry)
    "status" -> stringResource(R.string.safe_import_field_status)
    "current" -> stringResource(R.string.safe_import_field_current)
    else -> column
}
