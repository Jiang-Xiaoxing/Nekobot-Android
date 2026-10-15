package com.nekobot.app.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

internal fun companionFeedbackIntent(email: String): Intent {
    require(email.isNotBlank()) { "反馈邮箱尚未配置" }
    return Intent(
        Intent.ACTION_SENDTO,
        Uri.parse("mailto:" + Uri.encode(email, "@") + "?subject=" + Uri.encode("单会话角色测试反馈") +
            "&body=" + Uri.encode("问题或建议：\n\n操作步骤：\n\n预期与实际情况：\n\n应用版本、手机型号：\n\n"))
    )
}

/** 邮箱可通过 Android 资源覆盖；仅由用户点击后打开邮件草稿，不自动发送或附加聊天。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompanionFeedbackEntry(
    email: String = stringResource(R.string.companion_feedback_email),
    onWrite: (() -> Unit)? = null, onCopy: (() -> Unit)? = null
) {
    val address = email.trim()
    val configured = address.isNotBlank()
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = lerp(MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.colorScheme.primary, 0.25f))) {
            Text("测试反馈与建议", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("单会话角色 · 测试反馈", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { open = false }) { Icon(Icons.Outlined.Close, "关闭反馈") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (configured) address else "反馈邮箱尚未配置", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            Text("单会话角色功能仍在测试中，欢迎反馈问题与建议。",
                style = MaterialTheme.typography.bodyMedium)
            Text("请记录操作步骤、预期与实际情况，以及应用版本和手机型号。", style = MaterialTheme.typography.bodyMedium)
            Text("点击写邮件只会打开草稿，发送由你自行确认。",
                style = MaterialTheme.typography.bodyMedium)
            if (!configured) Text("维护者配置反馈邮箱后，可打开邮件草稿或复制邮箱。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = configured, onClick = { if (onWrite != null) onWrite() else try {
                    context.startActivity(companionFeedbackIntent(address))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(context, "未找到邮件应用，可以复制邮箱后手动发送。", Toast.LENGTH_LONG).show()
                } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("写邮件") }
                FilledTonalButton(enabled = configured, onClick = { if (onCopy != null) onCopy() else {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("反馈邮箱", address))
                    Toast.makeText(context, "邮箱已复制", Toast.LENGTH_SHORT).show()
                } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary)) { Text("复制邮箱") }
            }
        }
    }
}
