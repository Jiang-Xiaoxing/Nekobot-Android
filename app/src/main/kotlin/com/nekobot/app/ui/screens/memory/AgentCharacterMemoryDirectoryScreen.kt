package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.platform.LocalResources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.ai.usesFusedCompanionMemory
import com.nekobot.app.data.local.db.LocalSessionEntity
import kotlinx.coroutines.CancellationException

/** Lists owners only; memory contents remain in each owner's independent store. */
@Composable
internal fun AgentCharacterMemoryDirectoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    CompanionMemoryTheme { CompanionMemoryDirectory(onBack, onOpen) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionMemoryDirectory(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val textResources = LocalResources.current
    val revision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val repo = remember(revision) { ServiceContainer.localRepository }
    var owners by remember(repo) { mutableStateOf<List<LocalSessionEntity>?>(null) }
    var portraits by remember(repo) { mutableStateOf(emptyMap<String, String>()) }
    var names by remember(repo) { mutableStateOf(emptyMap<String, String>()) }
    var error by remember(repo) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(repo, appMode, refresh) {
        owners = null
        names = emptyMap()
        portraits = emptyMap()
        error = null
        if (appMode != AppMode.LOCAL) return@LaunchedEffect
        try {
            repo.observeSessions().collect { sessions ->
                val characters = repo.listCharacters()
                val labels = characters.mapNotNull { c -> c.id?.let { it to c.name.orEmpty() } }.toMap()
                check(repo === ServiceContainer.localRepository) { textResources.getString(R.string.single_role_the_data_profile_changed_reopen_this_page) }
                names = labels
                portraits = characters.mapNotNull { c -> c.id?.let { id -> c.avatarUrl?.let { id to it } } }.toMap()
                owners = sessions.filter { it.usesFusedCompanionMemory() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { owners = emptyList(); error = failure.message ?: textResources.getString(R.string.single_role_could_not_load_characters_please_try_again) }
    }
    fun ownerName(session: LocalSessionEntity) = com.nekobot.app.data.model.sessionTitle(session.listTitleOverride,
        names[session.characterId]?.takeIf(String::isNotBlank)
            ?: session.characterName?.takeIf(String::isNotBlank) ?: session.name.ifBlank { textResources.getString(R.string.agent_character_memory_unnamed) })
    val filtered = owners.orEmpty().filter { query.isBlank() ||
        "${ownerName(it)} ${it.name}".contains(query.trim(), ignoreCase = true) }
    Scaffold(topBar = { TopAppBar(title = {
        Text(stringResource(R.string.agent_character_memory_title), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
    }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, textResources.getString(R.string.common_back)) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("agent-character-memory-owners"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("intro") { CompanionMemoryIntro(textResources.getString(R.string.single_role_moments_together), textResources.getString(R.string.single_role_each_character_s_memories_are_stored_separately_choose_a_journal)) }
            if (appMode != AppMode.LOCAL) item("local-only") {
                DirectoryEmpty(textResources.getString(R.string.single_role_view_in_local_mode), stringResource(R.string.agent_character_memory_local_only))
            } else when {
                error != null -> item("error") { CompanionMemoryNotice(textResources.getString(R.string.single_role_no_characters_loaded_yet), error.orEmpty()) { refresh++ } }
                owners == null -> item("loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(textResources.getString(R.string.single_role_opening_memory_journal), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                owners!!.isEmpty() -> item("empty") {
                    DirectoryEmpty(textResources.getString(R.string.single_role_no_memory_journals_yet), textResources.getString(R.string.single_role_create_or_import_a_single_session_character_to_view_their_remembe))
                }
                else -> {
                    item("search") {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            shape = RoundedCornerShape(16.dp), placeholder = { Text(textResources.getString(R.string.single_role_find_a_character_or_conversation)) },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, textResources.getString(R.string.single_role_clear_search))
                            } })
                    }
                    item("count") { Text(textResources.getString(R.string.single_role_1_s_memory_journals, filtered.size), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (filtered.isEmpty()) item("no-matches") {
                        DirectoryEmpty(textResources.getString(R.string.single_role_character_not_found), textResources.getString(R.string.single_role_try_another_part_of_the_name_or_clear_the_search))
                    }
                    items(filtered, key = { it.id }) { session ->
                        val name = ownerName(session)
                        Surface(onClick = {
                            if (repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) onOpen(session.id)
                        }, modifier = Modifier.fillMaxWidth().testTag("agent-character-owner-${session.id}"),
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                MemoryOwnerAvatar(name, portraits[session.characterId])
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(session.name.takeIf { it.isNotBlank() && it != name } ?: textResources.getString(R.string.single_role_view_memories_and_shared_experiences),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = memoryAccent())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
