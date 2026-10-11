package com.nekobot.app.ui.screens.chat

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

internal fun companionFeedbackIntent(email: String): Intent = Intent(Intent.ACTION_SENDTO,
    Uri.parse("mailto:" + Uri.encode(email, "@") + "?subject=" + Uri.encode("单会话角色测试反馈") +
        "&body=" + Uri.encode("问题或建议：\n\n操作步骤：\n\n预期与实际情况：\n\n应用版本、手机型号：\n\n")))

/** 邮箱和头像由本地构建提供；入口不弹窗打扰，点击写邮件只交给用户的邮件应用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompanionFeedbackEntry(
    email: String = stringResource(R.string.companion_feedback_email),
    avatar: Any? = null, onWrite: (() -> Unit)? = null, onCopy: (() -> Unit)? = null
) {
    if (email.isBlank()) return
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
                AsyncImage(avatar ?: R.drawable.companion_feedback_portrait, "维护者头像",
                    Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Text(email, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
            Text("这部分功能还在测试中，开发时间比较紧，有些细节可能还不够完善。如果遇到 Bug 或使用不顺的地方，先向你说声抱歉。",
                style = MaterialTheme.typography.bodyMedium)
            Text("欢迎反馈问题、建议或想法。看到邮件后，我会尽量及时回复和跟进。", style = MaterialTheme.typography.bodyMedium)
            Text("这部分代码主要由我开发，直接联系我更方便定位问题，也能减少原作者重新了解代码的负担。感谢你的理解与帮助！",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (onWrite != null) onWrite() else try {
                    context.startActivity(companionFeedbackIntent(email))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(context, "未找到邮件应用，可以复制邮箱后手动发送。", Toast.LENGTH_LONG).show()
                } }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("写邮件") }
                FilledTonalButton(onClick = { if (onCopy != null) onCopy() else {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("反馈邮箱", email))
                    Toast.makeText(context, "邮箱已复制", Toast.LENGTH_SHORT).show()
                } }, Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary)) { Text("复制邮箱") }
            }
        }
    }
}
