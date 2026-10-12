package com.nekobot.app.ui.screens.memory

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
                check(repo === ServiceContainer.localRepository) { "资料库已切换，请重新打开" }
                names = labels
                portraits = characters.mapNotNull { c -> c.id?.let { id -> c.avatarUrl?.let { id to it } } }.toMap()
                owners = sessions.filter { it.usesFusedCompanionMemory() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { owners = emptyList(); error = failure.message ?: "暂时无法读取角色，请重试" }
    }
    fun ownerName(session: LocalSessionEntity) = com.nekobot.app.data.model.sessionTitle(session.listTitleOverride,
        names[session.characterId]?.takeIf(String::isNotBlank)
            ?: session.characterName?.takeIf(String::isNotBlank) ?: session.name.ifBlank { "未命名角色" })
    val filtered = owners.orEmpty().filter { query.isBlank() ||
        "${ownerName(it)} ${it.name}".contains(query.trim(), ignoreCase = true) }
    Scaffold(topBar = { TopAppBar(title = {
        Text(stringResource(R.string.agent_character_memory_title), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
    }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("agent-character-memory-owners"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("intro") { CompanionMemoryIntro("相处中的点滴", "每个角色的记忆分别保存。选择一本，看看留下了什么。") }
            if (appMode != AppMode.LOCAL) item("local-only") {
                DirectoryEmpty("请在本地模式查看", stringResource(R.string.agent_character_memory_local_only))
            } else when {
                error != null -> item("error") { CompanionMemoryNotice("暂时没有读到角色", error.orEmpty()) { refresh++ } }
                owners == null -> item("loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在打开记忆册…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                owners!!.isEmpty() -> item("empty") {
                    DirectoryEmpty("这里还没有记忆册", "创建或迁入一个单会话角色后，就能在这里查看它记住的喜好、经历与约定。")
                }
                else -> {
                    item("search") {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            shape = RoundedCornerShape(16.dp), placeholder = { Text("查找角色或会话") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, "清空搜索")
                            } })
                    }
                    item("count") { Text("${filtered.size} 本记忆册", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (filtered.isEmpty()) item("no-matches") {
                        DirectoryEmpty("没有找到这个角色", "试试角色名中的其他字，或清空搜索。")
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
                                    Text(session.name.takeIf { it.isNotBlank() && it != name } ?: "查看记忆与相处经历",
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
