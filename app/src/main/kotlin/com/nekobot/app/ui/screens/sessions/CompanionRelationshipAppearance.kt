package com.nekobot.app.ui.screens.sessions

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.nekobot.app.data.local.ai.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun RelationshipSection(title: String, badge: String? = null, content: @Composable ColumnScope.() -> Unit) {
    RelationshipPaper {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JournalHeading(title, Modifier.weight(1f))
                if (badge != null) RelationshipBadge(badge)
            }
            content()
        }
    }
}

@Composable
private fun RelationshipBadge(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun relationshipTime(value: String?, unknown: String): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }
        .getOrElse { _ -> it.take(19).replace('T', ' ') }
} ?: unknown

@Composable
internal fun RelationshipCurrentState(view: CompanionRelationshipView, onDetail: (String, String, String?) -> Unit) {
    val textResources = LocalResources.current
    val state = view.state
    val fresh = view.narrative
    val feeling = fresh?.feelings
    val legacy = view.legacy
    val hasNew = fresh != null
    val knownOld = legacy?.mood?.takeUnless { it == "尚未评估" || it.isBlank() }
    val values = if (hasNew) listOf(textResources.getString(R.string.sessions_detail_mood) to feeling?.mood, textResources.getString(R.string.single_role_emotional_intensity) to feeling?.intensity, textResources.getString(R.string.state_history_metric_energy) to feeling?.energy)
        else listOf(textResources.getString(R.string.sessions_detail_mood) to knownOld, textResources.getString(R.string.single_role_emotional_intensity) to null, textResources.getString(R.string.state_history_metric_energy) to legacy?.energy?.takeUnless { it == "尚未评估" || it.isBlank() })
    RelationshipSection(textResources.getString(R.string.single_role_last_recorded_feelings), if (hasNew) textResources.getString(R.string.single_role_written_assessment) else null) {
        val observedAt = if (hasNew) feeling?.observedAt else legacy?.observedAt
        if (observedAt != null) Text(textResources.getString(R.string.single_role_original_message_time_1_s, relationshipTime(observedAt, textResources.getString(R.string.common_unknown))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RelationshipFeelingDeck(state.characterId, values.map { (label, value) ->
            JournalFeeling(label,
                if (hasNew && feeling != null && !view.feelingsHaveSources) textResources.getString(R.string.single_role_sources_need_verification) else value ?: if (hasNew) textResources.getString(R.string.single_role_no_supporting_evidence_yet) else textResources.getString(R.string.single_role_not_recorded_yet),
                onDetail = {
                        val explanation = when (label) {
                            textResources.getString(R.string.single_role_emotional_intensity) -> textResources.getString(R.string.single_role_the_assessment_model_describes_how_strong_the_emotion_was_separat)
                            textResources.getString(R.string.state_history_metric_energy) -> textResources.getString(R.string.single_role_the_character_s_mental_and_energy_state_at_that_time)
                            else -> textResources.getString(R.string.single_role_the_character_s_mood_at_the_time_of_the_original_messages_it_does)
                        }
                        val reason = if (hasNew) feeling?.reason else legacy?.reason
                        val invalid = if (hasNew && feeling != null && !view.feelingsHaveSources) textResources.getString(R.string.single_role_original_sources_have_changed_this_is_temporarily_excluded_from_c) else ""
                        val refs = if (hasNew) feeling?.sources else legacy?.sources
                        onDetail(label, "${value ?: textResources.getString(R.string.single_role_not_recorded_yet)}\n\n$explanation" + reason?.takeIf { it.isNotBlank() }?.let { textResources.getString(R.string.single_role_evidence_1_s, it) }.orEmpty() + invalid,
                            refs?.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it) })
                })
        })
        Text(textResources.getString(R.string.single_role_these_feelings_were_assessed_then_and_may_no_longer_apply_now),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val status = when {
            view.busy -> textResources.getString(R.string.single_role_updating_showing_the_previous_result)
            !state.enabled -> textResources.getString(R.string.single_role_state_references_and_updates_are_off)
            state.historyReviewRequired -> textResources.getString(R.string.single_role_waiting_for_history_range_confirmation)
            state.awaitingHistory -> textResources.getString(R.string.single_role_waiting_for_backfill_decision)
            state.notice != null && !CompanionRelationshipRecovery.obsoleteInitializationNotice(state.notice) -> textResources.getString(R.string.single_role_last_update_did_not_complete)
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun RelationshipSummary(view: CompanionRelationshipView, working: Boolean, onUpdate: () -> Unit,
    showUpdate: Boolean = true, onDetail: (String, String, String?) -> Unit) {
    val textResources = LocalResources.current
    val narrative = view.narrative
    RelationshipSection(textResources.getString(R.string.single_role_long_term_relationship)) {
        CompanionRelationshipPolicy.dimensions.keys.forEachIndexed { index, key ->
                    val label = relationshipDimensionLabel(key)
                    val field = narrative?.relationships?.get(key)
                    val oldText = view.legacy?.shortDescriptions?.get(key)?.takeUnless { key in narrative?.withdrawnKeys.orEmpty() }
                    val value = field?.description ?: oldText ?: textResources.getString(R.string.single_role_not_assessed_yet)
                    val score = relationshipScorePresentation(key, field?.score)
                    Surface(onClick = {
                        val text = if (field != null) field.description + field.reason.takeIf { it.isNotBlank() }?.let { textResources.getString(R.string.single_role_assessment_evidence_1_s, it) }.orEmpty()
                            else (view.legacy?.descriptions?.get(key)?.takeIf { oldText != null } ?: textResources.getString(R.string.single_role_no_character_self_description_available_yet))
                        onDetail(label, text, field?.sources?.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it) })
                    }, modifier = Modifier.fillMaxWidth(), color = androidx.compose.ui.graphics.Color.Transparent) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(vertical = 14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                JournalEmblem(index, Modifier.padding(top = 4.dp).size(38.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(value, style = MaterialTheme.typography.bodyMedium)
                                }
                                score.score?.let { number ->
                                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(number.toString(), style = MaterialTheme.typography.labelSmall,
                                            color = androidx.compose.ui.graphics.Color(0xFF999999))
                                        if (score.showSparkle) Icon(Icons.Filled.AutoAwesome, null, Modifier.size(12.dp), tint = score.color)
                                    }
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(top = 10.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            RelationshipScoreBar(score)
                        }
                    }
            if (index < CompanionRelationshipPolicy.dimensions.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(textResources.getString(R.string.single_role_tap_to_read_the_character_s_own_description),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            JournalEmblem(7, Modifier.size(width = 70.dp, height = 52.dp))
        }
        if (showUpdate) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .55f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (view.state.historyReviewRequired) textResources.getString(R.string.single_role_history_awaiting_confirmation_1_s_rounds, view.pending) else textResources.getString(R.string.single_role_unassessed_conversation_1_s_rounds, view.pending), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onUpdate, enabled = view.state.enabled && !view.busy && !working && view.pending > 0 && !view.state.awaitingHistory) { Text(textResources.getString(R.string.single_role_update_now)) }
            }
        }
    }
}

@Composable
internal fun relationshipDimensionLabel(key: String): String = androidx.compose.ui.res.stringResource(when (key) {
    "affection" -> R.string.single_role_dimension_affection
    "trust" -> R.string.single_role_dimension_trust
    "familiarity" -> R.string.single_role_dimension_familiarity
    "dependency" -> R.string.single_role_dimension_dependency
    "security" -> R.string.single_role_dimension_security
    "jealousy" -> R.string.single_role_dimension_jealousy
    else -> R.string.common_unknown
})
