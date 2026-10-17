package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryCategorySelector(
    selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, includeAll: Boolean = false,
    counts: Map<String, Int>? = null, total: Int = 0
) {
    val textResources = LocalResources.current
    var opened by rememberSaveable { mutableStateOf(false) }
    val choices = if (includeAll) mapOf("" to textResources.getString(R.string.single_role_all_memories)) + memoryCategoryNames else memoryCategoryNames
    TextButton(onClick = { opened = true }, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(choices[selected].orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(Icons.Filled.ExpandMore, textResources.getString(R.string.single_role_choose_memory_category))
    }
    if (opened) ModalBottomSheet(onDismissRequest = { opened = false }) {
        Text(textResources.getString(R.string.single_role_memory_categories), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            items(choices.toList(), key = { it.first }) { (key, name) ->
                ListItem(headlineContent = { Text(name) },
                    leadingContent = { Icon(memoryCategoryIcon(key), null, tint = memoryCategoryColor(key)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            counts?.let { Text((if (key.isEmpty()) total else it[key] ?: 0).toString()) }
                            if (key == selected) Icon(Icons.Filled.Check, textResources.getString(R.string.sessions_detail_selected), tint = memoryAccent())
                        }
                    }, modifier = Modifier.clickable(enabled = enabled) { onSelect(key); opened = false }.heightIn(min = 56.dp))
            }
        }
    }
}
