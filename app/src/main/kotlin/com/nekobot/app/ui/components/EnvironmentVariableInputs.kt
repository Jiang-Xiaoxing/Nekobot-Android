package com.nekobot.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.SandboxEnvironment
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField

/** 管理页与工具请求共用输入框，变量值默认隐藏。 */
@Composable
fun EnvironmentVariableInputs(
    name: String,
    value: String,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    duplicate: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    val invalid = name.isNotEmpty() && !SandboxEnvironment.isValidName(name)
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.env_variable_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = invalid || duplicate,
        supportingText = if (invalid || duplicate) {
            { Text(stringResource(if (duplicate) R.string.env_duplicate_name else R.string.env_invalid_name)) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = value,
        onValueChange = { if (SandboxEnvironment.isValidValue(it)) onValueChange(it) },
        label = { Text(stringResource(R.string.env_variable_value)) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    stringResource(if (visible) R.string.env_hide_value else R.string.env_show_value),
                )
            }
        },
    )
}
