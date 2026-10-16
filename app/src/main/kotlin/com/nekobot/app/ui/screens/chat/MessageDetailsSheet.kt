package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val DetailsBackground = Color(0xFF2C2C2E)
private val DetailsText = Color(0xFFF2F2F2)
private val DetailsMuted = Color(0xFFBDBDC7)
private val DetailsAccent = Color(0xFFF69AB1)
private val DetailsDivider = Color.White.copy(alpha = 0.12f)

/** 与长按悬浮工具条配套的消息详情；大字和短屏可滚动，保留底部安全区。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageDetailsSheet(details: MessageDetails, subtitle: String, onDismiss: () -> Unit) {
    val textResources = LocalResources.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DetailsBackground,
        contentColor = DetailsText,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        contentWindowInsets = { WindowInsets.safeDrawing },
        dragHandle = { BottomSheetDefaults.DragHandle(color = DetailsMuted.copy(alpha = 0.45f)) },
        modifier = Modifier.testTag("message-details-sheet")
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(textResources.getString(R.string.single_role_message_details), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = DetailsMuted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color.White.copy(alpha = 0.07f), CircleShape)) {
                    Icon(Icons.Filled.Close, textResources.getString(R.string.single_role_close_message_details), tint = DetailsAccent)
                }
            }
            HorizontalDivider(Modifier.padding(top = 16.dp), color = DetailsDivider)
            DetailRow(textResources.getString(R.string.single_role_sent_at), details.sentAt, textResources.getString(R.string.single_role_shown_in_your_phone_s_local_time))
            HorizontalDivider(color = DetailsDivider)
            DetailRow(textResources.getString(R.string.aimodel_editor_field_model), details.model, textResources.getString(R.string.single_role_model_recorded_for_this_message))
            Surface(color = Color.White.copy(alpha = 0.035f), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, DetailsDivider), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(textResources.getString(R.string.single_role_token_usage), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(textResources.getString(R.string.tokens_total), color = DetailsMuted, style = MaterialTheme.typography.labelMedium)
                            DetailValue(details.totalTokens, accent = true)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TokenColumn(textResources.getString(R.string.stats_token_ratio_input), details.inputTokens, textResources.getString(R.string.single_role_content_sent_to_the_model), Modifier.weight(1f))
                        TokenColumn(textResources.getString(R.string.stats_token_ratio_output), details.outputTokens, textResources.getString(R.string.single_role_content_generated_by_the_model), Modifier.weight(1f))
                    }
                    HorizontalDivider(color = DetailsDivider)
                    Text(textResources.getString(R.string.single_role_tokens_are_units_used_to_measure_text_usage_by_models), color = DetailsMuted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            DetailRow(textResources.getString(R.string.single_role_generation_speed), details.speed, textResources.getString(R.string.single_role_average_tokens_generated_per_second))
            HorizontalDivider(color = DetailsDivider)
            DetailRow(textResources.getString(R.string.single_role_generation_time), details.duration, textResources.getString(R.string.single_role_model_generation_only_excludes_tool_execution))
            Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444448), contentColor = DetailsAccent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("message-details-close")) {
                Text(textResources.getString(R.string.common_close), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.38f))
        Column(Modifier.weight(0.62f), horizontalAlignment = Alignment.End) {
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End) }
            Text(hint, color = DetailsMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TokenColumn(label: String, value: String, hint: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = DetailsMuted, style = MaterialTheme.typography.bodyMedium)
        DetailValue(value)
        Text(hint, color = DetailsMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailValue(value: String, accent: Boolean = false) {
    SelectionContainer {
        Text(value, color = if (accent) DetailsAccent else DetailsText,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
