package com.nekobot.app.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.ai.usesFusedCompanionMemory
import com.nekobot.app.data.local.db.LocalSessionEntity
import kotlinx.coroutines.CancellationException

/** Lists owners only; memory contents remain in each owner's independent store. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentCharacterMemoryDirectoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val revision by ServiceContainer.dataSourceRevision.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val repo = remember(revision) { ServiceContainer.localRepository }
    var owners by remember(repo) { mutableStateOf<List<LocalSessionEntity>?>(null) }
    var names by remember(repo) { mutableStateOf(emptyMap<String, String>()) }
    var error by remember(repo) { mutableStateOf<String?>(null) }
    LaunchedEffect(repo, appMode) {
        owners = null
        names = emptyMap()
        error = null
        if (appMode != AppMode.LOCAL) return@LaunchedEffect
        try {
            repo.observeSessions().collect { sessions ->
                val labels = repo.listCharacters().mapNotNull { c -> c.id?.let { it to c.name.orEmpty() } }.toMap()
                check(repo === ServiceContainer.localRepository) { "资料库已切换，请重新打开" }
                names = labels
                owners = sessions.filter { it.usesFusedCompanionMemory() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { owners = emptyList(); error = failure.message }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.agent_character_memory_title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.agent_character_memory_back)) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("agent-character-memory-owners"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.agent_character_memory_help)) }
            if (appMode != AppMode.LOCAL) item { Text(stringResource(R.string.agent_character_memory_local_only)) }
            else {
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                if (owners == null) item { CircularProgressIndicator() }
                else if (owners!!.isEmpty() && error == null) item { Text(stringResource(R.string.agent_character_memory_empty)) }
                items(owners.orEmpty(), key = { it.id }) { session ->
                    val name = names[session.characterId]?.takeIf(String::isNotBlank)
                        ?: session.name.takeIf(String::isNotBlank) ?: stringResource(R.string.agent_character_memory_unnamed)
                    Card(onClick = {
                        if (repo === ServiceContainer.localRepository && ServiceContainer.prefs.isLocalMode) onOpen(session.id)
                    }, modifier = Modifier.fillMaxWidth().testTag("agent-character-owner-${session.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            if (session.name.isNotBlank() && session.name != name) Text(session.name, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.agent_character_memory_open), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
