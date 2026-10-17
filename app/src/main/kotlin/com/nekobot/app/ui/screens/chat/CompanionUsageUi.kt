package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.local.ai.ContextUsageBreakdown
import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.model.SessionCacheStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CompanionUsageContent(usedTokens: Long, maxTokens: Int?, breakdown: ContextUsageBreakdown, cache: SessionCacheStats,
    snapshot: Boolean, sentAt: Long?, reportedInput: Int?, hasSummary: Boolean, onSummary: () -> Unit, modifier: Modifier = Modifier) {
    val textResources = LocalResources.current
    val arcs = remember(breakdown) { companionUsageArcs(breakdown.parts) }
    var selectedName by rememberSaveable { mutableStateOf(ContextUsagePart.TOOL_DEFINITIONS.name) }
    val selected = arcs.firstOrNull { it.value.part.name == selectedName } ?: arcs.firstOrNull()
    val onSelect: (ContextUsagePart) -> Unit = { selectedName = it.name }
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val noSnapshot = snapshot && sentAt == null
    LazyColumn(modifier.fillMaxSize().testTag("usage-content"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = scheme.surface) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (snapshot) textResources.getString(R.string.single_role_last_completed_request) else textResources.getString(R.string.single_role_current_context_estimate), style = MaterialTheme.typography.bodyMedium, color = muted)
                    CompanionUsageRing(arcs, selected?.value?.part, onSelect, usedTokens, noSnapshot)
                    selected?.let { arc ->
                        val colour = companionUsagePartColor(arc.value.part, scheme.primary)
                        Column(Modifier.fillMaxWidth().background(lerp(scheme.surface, colour, 0.08f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("usage-selected"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UsageColourDot(colour)
                                Text(companionUsageLabel(arc.value.part), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(companionUsagePercent(arc.fraction), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Text(textResources.getString(R.string.single_role_1_s_tokens_2_s_items, companionUsageNumber(arc.value.tokens.toLong()), arc.value.itemCount), Modifier.padding(start = 18.dp),
                                style = MaterialTheme.typography.bodySmall, color = muted)
                            if (arc.value.part == ContextUsagePart.SUMMARY && hasSummary) TextButton(onClick = onSummary) { Text(textResources.getString(R.string.single_role_view_original_summary)) }
                        }
                    }
                    UsageRequestFacts(maxTokens, reportedInput)
                    Text(textResources.getString(R.string.single_role_chart_proportions_use_local_estimates), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth().testTag("usage-cache"), shape = RoundedCornerShape(16.dp), color = lerp(scheme.surface, scheme.primary, 0.065f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(36.dp).background(scheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Layers, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Text(textResources.getString(R.string.single_role_total_cache_hits_in_this_conversation), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(companionUsagePercent(cache.hitRate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("usage-cache-rate"))
                    }
                    Text(if (cache.hitRate == null) textResources.getString(R.string.single_role_provider_did_not_report_cache_data)
                        else textResources.getString(R.string.single_role_1_s_2_s_input_tokens, companionUsageNumber(cache.cachedInputTokens), companionUsageNumber(cache.inputTokens)),
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        item { Text(textResources.getString(R.string.single_role_content_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
        if (arcs.isEmpty()) item {
            Text(if (noSnapshot) textResources.getString(R.string.single_role_request_records_will_appear_here_after_a_chat_completes) else textResources.getString(R.string.single_role_no_content_to_count_yet),
                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
        } else items(arcs, key = { it.value.part.name }) { arc ->
            val isSelected = selected?.value?.part == arc.value.part
            val label = companionUsageLabel(arc.value.part)
            val colour = companionUsagePartColor(arc.value.part, scheme.primary)
            Surface(shape = RoundedCornerShape(10.dp), color = if (isSelected) lerp(scheme.surface, colour, 0.09f) else Color.Transparent) {
                Row(Modifier.fillMaxWidth().testTag("usage-row-${arc.value.part.name}").semantics {
                    this.selected = isSelected
                    contentDescription = textResources.getString(R.string.single_role_1_s_2_s_tokens_3_s_4_s_items, label, arc.value.tokens, companionUsagePercent(arc.fraction), arc.value.itemCount)
                }.clickable(role = Role.Button, onClickLabel = textResources.getString(R.string.single_role_select_1_s, label)) { onSelect(arc.value.part) }
                    .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UsageColourDot(colour)
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(companionUsageNumber(arc.value.tokens.toLong()), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                        Text(companionUsagePercent(arc.fraction), style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
            }
        }
        item {
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (snapshot) textResources.getString(R.string.single_role_estimated_by_category_from_the_last_request_s_actual_content_may)
                    else textResources.getString(R.string.single_role_estimated_by_category_from_currently_visible_data_may_differ_from), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(sentAt?.let { textResources.getString(R.string.single_role_request_time_1_s, SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))) }
                    ?: if (snapshot) textResources.getString(R.string.single_role_no_completed_requests_yet) else textResources.getString(R.string.single_role_no_request_records_in_the_new_format_yet), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable private fun UsageRequestFacts(maxTokens: Int?, reportedInput: Int?) {
    val textResources = LocalResources.current
    val capacity = maxTokens?.takeIf { it > 0 }?.let { "${companionUsageNumber(it.toLong())} Token" } ?: textResources.getString(R.string.single_role_not_provided)
    val input = reportedInput?.let { "${companionUsageNumber(it.toLong())} Token" } ?: textResources.getString(R.string.single_role_not_reported)
    if (LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UsageFact(textResources.getString(R.string.single_role_model_limit), capacity); UsageFact(textResources.getString(R.string.single_role_api_reported_input), input)
    } else Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { UsageFact(textResources.getString(R.string.single_role_model_limit), capacity) }
        Box(Modifier.weight(1f)) { UsageFact(textResources.getString(R.string.single_role_api_reported_input), input) }
    }
}
@Composable private fun UsageFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun UsageColourDot(colour: Color) { Box(Modifier.size(10.dp).background(colour, CircleShape)) }
@Composable internal fun companionUsageLabel(part: ContextUsagePart): String = when (part) {
    ContextUsagePart.SYSTEM_PROMPT -> stringResource(R.string.single_role_character_settings)
    ContextUsagePart.ASSISTANT_MESSAGES -> stringResource(R.string.single_role_ai_replies)
    ContextUsagePart.AUXILIARY_CONTEXT -> stringResource(R.string.single_role_supporting_material)
    else -> contextPartLabel(part)
}
