package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.RelationshipBackground
import com.nekobot.app.ui.screens.sessions.RelationshipPaper
import com.nekobot.app.ui.screens.sessions.RelationshipScene
import com.nekobot.app.ui.screens.sessions.readableOn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelationshipBackgroundScreen(onBack: () -> Unit) {
    val prefs = ServiceContainer.prefs
    var applied by remember { mutableStateOf(prefs.relationshipBackground.id) }
    var selectedId by rememberSaveable { mutableStateOf(applied) }
    val selected = RelationshipBackground.fromId(selectedId)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text("关系页背景") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Surface(shadowElevation = 3.dp) {
            Button(onClick = {
                prefs.relationshipBackground = selected
                applied = selected.id
                scope.launch { snackbar.showSnackbar("已使用${selected.title}") }
            }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                .heightIn(min = 48.dp).testTag("relationship_background_apply"),
                colors = ButtonDefaults.buttonColors(contentColor = readableOn(MaterialTheme.colorScheme.primary))) {
                Text("使用这个背景")
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("独立设置，不随主题色改变", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).testTag("relationship_background_preview"),
                contentAlignment = Alignment.Center) {
                RelationshipScene(selected, Modifier.matchParentSize())
                RelationshipPaper(Modifier.fillMaxWidth(.76f)) {
                    Text("心情", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("轻松随和，带有一点好奇", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(if (selectedId == applied) "当前：${selected.title}" else "预览：${selected.title}", style = MaterialTheme.typography.bodyMedium)
            BoxWithConstraints {
                val columns = if (maxWidth < 300.dp) 1 else 2
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RelationshipBackground.entries.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                            row.forEach { background ->
                                val isSelected = background == selected
                                Surface(onClick = { selectedId = background.id },
                                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("relationship_background_${background.id}")
                                        .semantics { this.selected = isSelected },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    color = MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        RelationshipScene(background, Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(9.dp)))
                                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(background.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                            if (isSelected) Icon(Icons.Filled.CheckCircle, "已选中", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
