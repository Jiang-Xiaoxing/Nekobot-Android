package com.nekobot.app.ui.screens.chat

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
                    Text("消息详情", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = DetailsMuted, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color.White.copy(alpha = 0.07f), CircleShape)) {
                    Icon(Icons.Filled.Close, "关闭消息详情", tint = DetailsAccent)
                }
            }
            HorizontalDivider(Modifier.padding(top = 16.dp), color = DetailsDivider)
            DetailRow("发送时间", details.sentAt, "按手机本地时间显示")
            HorizontalDivider(color = DetailsDivider)
            DetailRow("模型", details.model, "本条消息记录的模型")
            Surface(color = Color.White.copy(alpha = 0.035f), shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, DetailsDivider), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("用了多少 Token", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("合计", color = DetailsMuted, style = MaterialTheme.typography.labelMedium)
                            DetailValue(details.totalTokens, accent = true)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TokenColumn("输入 Token", details.inputTokens, "发给模型的内容", Modifier.weight(1f))
                        TokenColumn("输出 Token", details.outputTokens, "模型生成的内容", Modifier.weight(1f))
                    }
                    HorizontalDivider(color = DetailsDivider)
                    Text("Token 是模型计算文字用量的单位", color = DetailsMuted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            DetailRow("生成速度", details.speed, "平均每秒生成的 Token 数")
            HorizontalDivider(color = DetailsDivider)
            DetailRow("生成耗时", details.duration, "仅统计模型生成，不含工具执行")
            Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444448), contentColor = DetailsAccent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("message-details-close")) {
                Text("关闭", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
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
