package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.CompanionConversionModel
import com.nekobot.app.data.local.ai.CompanionConversionRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionConversionModelPicker(models: List<CompanionConversionModel>, selected: String?,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, onManage: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.companion_conversion_model_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.companion_conversion_model_hint), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f)) {
                if (models.isEmpty()) item { Text(stringResource(R.string.companion_conversion_empty_models), Modifier.padding(vertical = 24.dp)) }
                items(models, key = { it.id }) { model ->
                    ListItem(headlineContent = { Text(model.name) }, supportingContent = { Text("${model.provider}\n${model.model}") },
                        leadingContent = { RadioButton(selected == model.id, onClick = null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("conversion_model_${model.id}").clickable { onSelect(model.id) })
                }
            }
            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.companion_conversion_manage_models))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionConversionRulesSheet(onDismiss: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("conversion_rules_sheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.companion_conversion_rules), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.companion_conversion_rule_version, CompanionConversionRules.version),
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).testTag("conversion_rules_scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.companion_conversion_rules_hint), style = MaterialTheme.typography.bodyMedium) }
                item { SelectionContainer { Text(CompanionConversionRules.explanation, style = MaterialTheme.typography.bodyLarge) } }
                item { TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("conversion_rule_format")) { Text(stringResource(R.string.companion_conversion_output_format)) } }
                if (expanded) item { SelectionContainer { Text(CompanionConversionRules.format, style = MaterialTheme.typography.bodyMedium) } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(CompanionConversionRules.prompt)); copied = true }) {
                    Text(stringResource(if (copied) R.string.companion_conversion_copied else R.string.companion_conversion_copy_rules))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}
