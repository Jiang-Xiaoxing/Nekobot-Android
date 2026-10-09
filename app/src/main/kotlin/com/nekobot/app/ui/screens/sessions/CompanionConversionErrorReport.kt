package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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

/** 报告由数据层生成；复制内容不混入含私人原话的迁入回执。 */
@Composable
internal fun CompanionConversionErrorReport(report: String) {
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable(report) { mutableStateOf(false) }
    var copied by remember(report) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.companion_conversion_hide_report else R.string.companion_conversion_view_report))
        }
        if (expanded) SelectionContainer {
            Text(report, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
        }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)); copied = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("companion_copy_error_report")) {
            Text(stringResource(if (copied) R.string.companion_conversion_copied else R.string.companion_conversion_copy_error_report))
        }
        Text(stringResource(R.string.companion_conversion_report_hint), style = MaterialTheme.typography.bodySmall)
    }
}
