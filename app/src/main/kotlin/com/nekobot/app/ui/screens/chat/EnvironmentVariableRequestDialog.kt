package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.AskUserQuestionAnswer
import com.nekobot.app.data.local.ai.AskUserQuestionRequest
import com.nekobot.app.data.local.ai.SandboxEnvironment
import com.nekobot.app.ui.components.EnvironmentVariableInputs
import com.nekobot.app.ui.components.NekoDialog

@Composable
fun EnvironmentVariableRequestDialog(
    request: AskUserQuestionRequest,
    onAnswer: (List<AskUserQuestionAnswer>) -> Unit,
    onSkip: () -> Unit,
) {
    val names = remember(request.requestId) {
        mutableStateMapOf<Int, String>().apply { request.environmentVariables.forEachIndexed { i, v -> put(i, v.name) } }
    }
    val values = remember(request.requestId) { mutableStateMapOf<Int, String>() }
    var global by remember(request.requestId) { mutableStateOf(request.environmentScope == "global") }
    val valid = names.values.all(SandboxEnvironment::isValidName) && names.values.distinct().size == names.size
    NekoDialog(
        onDismiss = onSkip,
        title = stringResource(R.string.env_request_title),
        confirmText = stringResource(R.string.common_save),
        confirmEnabled = valid,
        onConfirm = {
            onAnswer(request.environmentVariables.mapIndexed { index, _ ->
                AskUserQuestionAnswer(names.getValue(index), "", emptyList(), values[index].orEmpty(),
                    if (global) "global" else "session")
            })
        },
        cancelText = stringResource(R.string.chat_ask_question_skip),
        onCancel = onSkip,
        dismissOnClickOutside = false,
        contentScrollable = true,
    ) {
        Text(stringResource(R.string.env_request_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !global, onClick = { global = false }, label = { Text(stringResource(R.string.env_session_scope)) })
            FilterChip(selected = global, onClick = { global = true }, label = { Text(stringResource(R.string.env_global_scope)) })
        }
        request.environmentVariables.forEachIndexed { index, field ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (field.description.isNotBlank()) Text(field.description, style = MaterialTheme.typography.bodyMedium)
                EnvironmentVariableInputs(
                    name = names.getValue(index), value = values[index].orEmpty(),
                    onNameChange = { names[index] = it }, onValueChange = { values[index] = it },
                    duplicate = names.entries.any { it.key != index && it.value == names[index] },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
