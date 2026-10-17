package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.platform.LocalResources

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nekobot.app.R

internal fun companionFeedbackIntent(email: String, textResources: android.content.res.Resources): Intent = Intent(Intent.ACTION_SENDTO,
    Uri.parse("mailto:" + Uri.encode(email, "@") + "?subject=" + Uri.encode(textResources.getString(R.string.single_role_single_session_character_test_feedback)) +
        "&body=" + Uri.encode(textResources.getString(R.string.single_role_issue_or_suggestion_steps_expected_and_actual_behavior_app_versio))))

/** 邮箱和头像由本地构建提供；入口不弹窗打扰，点击写邮件只交给用户的邮件应用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompanionFeedbackEntry(
    email: String = stringResource(R.string.companion_feedback_email),
    avatar: Any? = null, onWrite: (() -> Unit)? = null, onCopy: (() -> Unit)? = null
) {
    val textResources = LocalResources.current
    if (email.isBlank()) return
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = lerp(MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.colorScheme.primary, 0.25f))) {
            Text(textResources.getString(R.string.single_role_test_feedback_and_suggestions), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(textResources.getString(R.string.single_role_single_session_character_test_feedback_62), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { open = false }) { Icon(Icons.Outlined.Close, textResources.getString(R.string.single_role_close_feedback)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(avatar ?: R.drawable.companion_feedback_portrait, textResources.getString(R.string.single_role_maintainer_s_portrait),
                    Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Text(email, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            Text(textResources.getString(R.string.single_role_these_features_are_still_being_tested_development_time_is_limited),
                style = MaterialTheme.typography.bodyMedium)
            Text(textResources.getString(R.string.single_role_feedback_suggestions_and_ideas_are_welcome_i_ll_try_to_reply_and), style = MaterialTheme.typography.bodyMedium)
            Text(textResources.getString(R.string.single_role_i_developed_most_of_this_feature_contacting_me_directly_helps_loc),
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (onWrite != null) onWrite() else try {
                    context.startActivity(companionFeedbackIntent(email, textResources))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(context, textResources.getString(R.string.single_role_no_email_app_found_copy_the_address_and_send_it_manually), Toast.LENGTH_LONG).show()
                } }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(textResources.getString(R.string.single_role_write_email)) }
                FilledTonalButton(onClick = { if (onCopy != null) onCopy() else {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(textResources.getString(R.string.single_role_feedback_email), email))
                    Toast.makeText(context, textResources.getString(R.string.single_role_email_address_copied), Toast.LENGTH_SHORT).show()
                } }, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary)) { Text(textResources.getString(R.string.single_role_copy_email_address)) }
            }
        }
    }
}
