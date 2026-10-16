package com.nekobot.app.ui.screens.settings

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

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
    val textResources = LocalResources.current
    var choosing by remember { mutableStateOf<String?>(null) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text(textResources.getString(R.string.single_role_colors), style = MaterialTheme.typography.titleMedium)
        listOf(textResources.getString(R.string.style_theme_color) to textResources.getString(R.string.single_role_buttons_navigation_and_accents), textResources.getString(R.string.single_role_message_colors) to textResources.getString(R.string.single_role_set_both_chat_bubble_colors_together)).forEachIndexed { index, (label, hint) ->
            if (index > 0) AppearanceDivider()
            val hex = if (index == 0) themeColor else messageColor
            AppearanceSettingRow(label, hint, onClick = { choosing = label }) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(parseHexColor(hex) ?: defaultPrimaryColor()))
                Text(options.firstOrNull { it.first == hex }?.second ?: textResources.getString(R.string.aimodel_editor_provider_custom), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    choosing?.let { label ->
        val selected = if (label == textResources.getString(R.string.style_theme_color)) themeColor else messageColor
        ColorChoiceDialog(label, selected, options, onDismiss = { choosing = null }, onSelect = { hex ->
            if (label == textResources.getString(R.string.style_theme_color)) onThemeColor(hex) else onMessageColor(hex)
            choosing = null
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoiceDialog(title: String, selected: String?, options: List<Pair<String?, String>>,
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
                        Box(Modifier.size(36.dp).clip(CircleShape).background(parseHexColor(hex) ?: defaultPrimaryColor()))
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
internal fun ChatAppearancePreview(messageColor: String?) {
    val textResources = LocalResources.current
    val appearance = chatBubbleAppearance(messageColor)
    GlassCard(Modifier.fillMaxWidth()) {
        Text(textResources.getString(R.string.single_role_chat_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.clip(chatBubbleShape(true)).background(appearance.userBrush).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(textResources.getString(R.string.single_role_how_was_your_day), color = appearance.assistantText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.clip(chatBubbleShape(false)).background(appearance.userBrush).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(textResources.getString(R.string.single_role_pretty_good_i_was_just_thinking_of_talking_to_you), color = appearance.assistantText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
