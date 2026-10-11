package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.chatBubbleAppearance
import com.nekobot.app.ui.components.chatBubbleShape
import com.nekobot.app.ui.theme.defaultPrimaryColor
import com.nekobot.app.ui.theme.parseHexColor

@Composable
internal fun ChatColorSettings(themeColor: String?, messageColor: String?, options: List<Pair<String?, String>>,
    onThemeColor: (String?) -> Unit, onMessageColor: (String?) -> Unit) {
    var choosing by remember { mutableStateOf<String?>(null) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("颜色", style = MaterialTheme.typography.titleMedium)
        listOf("主题色" to "按钮、导航和强调色", "消息颜色" to "同时设置双方聊天气泡").forEachIndexed { index, (label, hint) ->
            if (index > 0) AppearanceDivider()
            val hex = if (index == 0) themeColor else messageColor
            AppearanceSettingRow(label, hint, onClick = { choosing = label }) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(parseHexColor(hex) ?: defaultPrimaryColor()))
                Text(options.firstOrNull { it.first == hex }?.second ?: "自定义", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    choosing?.let { label ->
        val selected = if (label == "主题色") themeColor else messageColor
        ColorChoiceDialog(label, selected, options, onDismiss = { choosing = null }, onSelect = { hex ->
            if (label == "主题色") onThemeColor(hex) else onMessageColor(hex)
            choosing = null
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoiceDialog(title: String, selected: String?, options: List<Pair<String?, String>>,
    onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    var custom by remember { mutableStateOf(selected.orEmpty()) }
    val normalized = custom.trim().let { if (it.startsWith("#")) it else "#$it" }
    val valid = Regex("#[0-9a-fA-F]{6}").matches(normalized)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (hex, name) ->
                    Column(Modifier.widthIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (hex == selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { onSelect(hex) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(parseHexColor(hex) ?: defaultPrimaryColor()))
                        Spacer(Modifier.height(6.dp))
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("自定义颜色") },
                placeholder = { Text("#RRGGBB") }, supportingText = { Text("输入六位颜色代码，例如 #8B6CFF") },
                isError = custom.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSelect(normalized.uppercase()) }) { Text("使用自定义颜色") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ChatAppearancePreview(messageColor: String?) {
    val appearance = chatBubbleAppearance(messageColor)
    GlassCard(Modifier.fillMaxWidth()) {
        Text("聊天预览", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.clip(chatBubbleShape(true)).background(appearance.userBrush).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("今天过得怎么样？", color = appearance.assistantText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.clip(chatBubbleShape(false)).background(appearance.userBrush).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("还不错，正想和你聊聊。", color = appearance.assistantText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
