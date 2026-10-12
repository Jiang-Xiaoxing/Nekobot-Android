package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.model.CustomReasoningParam

/** “自定义”思考强度的参数名/参数值输入；每次修改立即保存。 */
@Composable
internal fun CustomReasoningFields(
    initial: CustomReasoningParam,
    enabled: Boolean,
    onChange: (CustomReasoningParam) -> Unit,
    modifier: Modifier = Modifier
) {
    var path by remember(initial) { mutableStateOf(initial.path) }
    var value by remember(initial) { mutableStateOf(initial.value) }
    val error = CustomReasoningParam(path, value).validationError()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(path, { path = it; onChange(CustomReasoningParam(it, value)) }, Modifier.fillMaxWidth(),
            enabled = enabled, singleLine = true, label = { Text("参数名") }, placeholder = { Text("reasoning_effort") })
        OutlinedTextField(value, { value = it; onChange(CustomReasoningParam(path, it)) }, Modifier.fillMaxWidth(),
            enabled = enabled, singleLine = true, label = { Text("参数值") }, placeholder = { Text("high") })
        Text("发送时用这组替换自动换算的思考参数", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
