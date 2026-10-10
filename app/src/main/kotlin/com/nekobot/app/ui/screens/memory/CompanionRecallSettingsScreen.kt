package com.nekobot.app.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.local.ai.CompanionRecallSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionRecallSettingsScreen(
    sessionId: String, ownerName: String, ownerAvatar: String?, initial: CompanionRecallSettings,
    busy: Boolean, error: String?, onBack: () -> Unit, onSave: (Int, Int) -> Unit
) {
    var rounds by rememberSaveable(sessionId) { mutableStateOf(initial.rounds.toString()) }
    var tokens by rememberSaveable(sessionId) { mutableStateOf(initial.tokens.toString()) }
    var details by rememberSaveable { mutableStateOf(false) }
    val roundNumber = rounds.toIntOrNull()
    val tokenNumber = tokens.toIntOrNull()
    val validRounds = roundNumber != null && roundNumber in 1..100
    val validTokens = tokenNumber != null && tokenNumber in 256..64000
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("回查设置") }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }, bottomBar = {
        Surface {
            Button(onClick = { onSave(requireNotNull(roundNumber), requireNotNull(tokenNumber)) },
                enabled = !busy && validRounds && validTokens, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp).heightIn(min = 52.dp)) {
                Text(if (busy) "正在保存…" else "保存设置")
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemoryOwnerAvatar(ownerName, ownerAvatar)
                Column {
                    Text(ownerName, style = MaterialTheme.typography.titleMedium)
                    Text("需要更多细节时，再翻一翻旧记忆", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(rounds, { if (it.length <= 3 && it.all { c -> c in '0'..'9' }) rounds = it },
                Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, isError = !validRounds,
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("最多回查轮数") }, suffix = { Text("轮") },
                supportingText = { Text(if (validRounds) "默认 3 轮，一轮可以查多条资料。" else "请输入 1 至 100 的整数。") })
            OutlinedTextField(tokens, { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) tokens = it },
                Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, isError = !validTokens,
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("回查资料上限") }, suffix = { Text("Token") },
                supportingText = { Text(if (validTokens) "默认约 3000 Token，按返回资料的长度估算。" else "请输入 256 至 64000 的整数。") })
            Surface(color = memoryCategoryColor("episode").copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("只管 AI 主动查的资料", style = MaterialTheme.typography.titleSmall)
                    Text("画像、经历、约定、旧记录和原话，共用这一份额度。自动提供的记忆不扣这里的额度。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("达到上限后，会根据已有资料回答；没有查清的细节应如实说明。",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { details = !details }) { Text(if (details) "收起说明" else "额度怎么算？") }
            if (details) Text("一轮指模型发起一批查询、软件返回结果，再由模型继续判断。同一条回复内共用额度，暂停后继续也不会清零。\n\n资料上限包括返回正文和必要说明，按 Token 估算，并受模型剩余上下文限制；它不是整次请求的费用上限。调高数值可能增加等待和模型用量。\n\n设置只影响这个角色，从下一条新消息开始使用。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { CompanionMemoryNotice("设置尚未保存", it) }
        }
    }
}
