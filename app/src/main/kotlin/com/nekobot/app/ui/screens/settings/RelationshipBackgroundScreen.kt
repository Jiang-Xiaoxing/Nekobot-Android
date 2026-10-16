package com.nekobot.app.ui.screens.settings

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

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
import com.nekobot.app.ui.screens.sessions.RelationshipSkinTheme
import com.nekobot.app.ui.screens.sessions.LocalJournalSkin
import com.nekobot.app.ui.screens.sessions.journalFinish
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelationshipBackgroundScreen(onBack: () -> Unit) {
    val textResources = LocalResources.current
    val prefs = ServiceContainer.prefs
    var applied by remember { mutableStateOf(prefs.relationshipBackground.id) }
    var selectedId by rememberSaveable { mutableStateOf(applied) }
    val selected = RelationshipBackground.fromId(selectedId)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(title = { Text(textResources.getString(R.string.single_role_relationship_page_skins)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, textResources.getString(R.string.common_back)) }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Surface(shadowElevation = 3.dp) {
            Button(onClick = {
                prefs.relationshipBackground = selected
                applied = selected.id
                scope.launch { snackbar.showSnackbar(textResources.getString(R.string.single_role_using_1_s, textResources.getString(selected.titleRes))) }
            }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                .heightIn(min = 48.dp).testTag("relationship_background_apply"),
                colors = ButtonDefaults.buttonColors(contentColor = readableOn(MaterialTheme.colorScheme.primary))) {
                Text(textResources.getString(R.string.single_role_use_this_skin))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(textResources.getString(R.string.single_role_switch_backgrounds_buttons_and_icons_together_independent_of_them), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).testTag("relationship_background_preview"),
                contentAlignment = Alignment.Center) {
                RelationshipScene(selected, Modifier.matchParentSize())
                RelationshipSkinTheme(selected) {
                RelationshipPaper(Modifier.fillMaxWidth(.82f)) {
                    Text(textResources.getString(R.string.sessions_detail_mood), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(textResources.getString(R.string.single_role_relaxed_and_easygoing_with_a_little_curiosity), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val skin = LocalJournalSkin.current
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(textResources.getString(R.string.sessions_detail_mood), textResources.getString(R.string.sessions_detail_intensity), textResources.getString(R.string.state_history_metric_energy)).forEachIndexed { index, label ->
                            Surface(modifier = Modifier.weight(1f), shape = skin.buttonShape,
                                color = if (index == 0) skin.accent else skin.paper,
                                contentColor = if (index == 0) readableOn(skin.accent) else skin.ink,
                                border = BorderStroke(1.dp, skin.rim), shadowElevation = 2.dp) {
                                Box(Modifier.height(36.dp).journalFinish(skin, selected = index == 0), contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                }
            }
            Text(if (selectedId == applied) textResources.getString(R.string.more_current_user, textResources.getString(selected.titleRes)) else textResources.getString(R.string.single_role_preview_1_s, textResources.getString(selected.titleRes)), style = MaterialTheme.typography.bodyMedium)
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
                                            Text(textResources.getString(background.titleRes), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                            if (isSelected) Icon(Icons.Filled.CheckCircle, textResources.getString(R.string.single_role_selected), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
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
