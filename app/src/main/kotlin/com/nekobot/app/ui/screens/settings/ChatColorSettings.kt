package com.nekobot.app.ui.screens.settings

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private enum class ChatColorTarget(val title: Int) {
    THEME(R.string.style_theme_color),
    USER(R.string.style_user_bubble_color),
    ASSISTANT(R.string.style_ai_bubble_color)
}

@Composable
internal fun ChatColorSettings(themeColor: String?, userMessageColor: String?, assistantMessageColor: String?,
    options: List<Pair<String?, String>>, onThemeColor: (String?) -> Unit,
    onUserMessageColor: (String?) -> Unit, onAssistantMessageColor: (String?) -> Unit) {
    val textResources = LocalResources.current
    var choosing by remember { mutableStateOf<ChatColorTarget?>(null) }
    val themeDefault = defaultPrimaryColor()
    val defaults = chatBubbleAppearance(null)
    val userDefault = defaults.userColor
    val bubbleOptions = listOf<Pair<String?, String>>(
        null to textResources.getString(R.string.style_bubble_color_default),
        "#FFFFFF" to textResources.getString(R.string.style_color_white),
        "#FF6B97" to textResources.getString(R.string.style_color_pink)
    ) + options.filter { it.first != null }
    fun selectedColor(target: ChatColorTarget) = when (target) {
        ChatColorTarget.THEME -> themeColor
        ChatColorTarget.USER -> userMessageColor
        ChatColorTarget.ASSISTANT -> assistantMessageColor
    }
    fun defaultColor(target: ChatColorTarget) = when (target) {
        ChatColorTarget.THEME -> themeDefault
        ChatColorTarget.USER -> userDefault
        ChatColorTarget.ASSISTANT -> defaults.assistantColor
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Text(textResources.getString(R.string.single_role_colors), style = MaterialTheme.typography.titleMedium)
        ChatColorTarget.entries.forEachIndexed { index, target ->
            if (index > 0) AppearanceDivider()
            val hex = selectedColor(target)
            val choices = if (target == ChatColorTarget.THEME) options else bubbleOptions
            AppearanceSettingRow(textResources.getString(target.title),
                if (target == ChatColorTarget.THEME) textResources.getString(R.string.single_role_buttons_navigation_and_accents) else null,
                onClick = { choosing = target }) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(parseHexColor(hex) ?: defaultColor(target))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                Text(choices.firstOrNull { it.first == hex }?.second ?: textResources.getString(R.string.aimodel_editor_provider_custom), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    choosing?.let { target ->
        ColorChoiceDialog(textResources.getString(target.title), selectedColor(target),
            if (target == ChatColorTarget.THEME) options else bubbleOptions, defaultColor(target),
            onDismiss = { choosing = null }, onSelect = { hex ->
            when (target) {
                ChatColorTarget.THEME -> onThemeColor(hex)
                ChatColorTarget.USER -> onUserMessageColor(hex)
                ChatColorTarget.ASSISTANT -> onAssistantMessageColor(hex)
            }
            choosing = null
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoiceDialog(title: String, selected: String?, options: List<Pair<String?, String>>, defaultColor: Color,
    onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    val textResources = LocalResources.current
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
                        Box(Modifier.size(36.dp).clip(CircleShape).background(parseHexColor(hex) ?: defaultColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        Spacer(Modifier.height(6.dp))
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text(textResources.getString(R.string.style_custom_color)) },
                placeholder = { Text("#RRGGBB") }, supportingText = { Text(textResources.getString(R.string.single_role_enter_a_six_digit_color_code_such_as_8b6cff)) },
                isError = custom.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSelect(normalized.uppercase()) }) { Text(textResources.getString(R.string.single_role_use_custom_color)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(textResources.getString(R.string.common_cancel)) } })
}

@Composable
internal fun ChatAppearancePreview(messageColor: String?, assistantMessageColor: String? = null) {
    val textResources = LocalResources.current
    val appearance = chatBubbleAppearance(messageColor, assistantMessageColor)
    GlassCard(Modifier.fillMaxWidth()) {
        Text(textResources.getString(R.string.single_role_chat_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.clip(chatBubbleShape(true)).background(appearance.userBrush).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(textResources.getString(R.string.single_role_how_was_your_day), color = appearance.textColor(true), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.clip(chatBubbleShape(false)).background(appearance.assistantColor)
            .border(1.dp, appearance.assistantBorder, chatBubbleShape(false)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(textResources.getString(R.string.single_role_pretty_good_i_was_just_thinking_of_talking_to_you), color = appearance.assistantText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
