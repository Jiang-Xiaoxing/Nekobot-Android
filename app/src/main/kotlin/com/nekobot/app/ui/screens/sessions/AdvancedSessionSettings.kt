package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.PromptSection
import com.nekobot.app.data.local.ai.promptSectionPercentages
import java.util.Locale

/** 默认折叠且折叠时不组合正文，复制、详情和启用操作在原内容中保留。 */
@Composable
internal fun AdvancedDebugCard(
    sessionId: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
internal fun AdvancedSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun promptSectionLabel(section: PromptSection) = when (section) {
    PromptSection.CHARACTER -> "角色设定"
    PromptSection.CORE_MEMORY -> "核心记忆"
    PromptSection.RELATIONSHIP -> "关系状态"
    PromptSection.PLAYER -> "玩家背景"
    PromptSection.OTHER -> "其他提示词"
}

@Composable
internal fun AdvancedPromptUsageCard(counts: Map<PromptSection, Int>?) {
    val percentages = remember(counts) { promptSectionPercentages(counts.orEmpty()) }
    val total = counts.orEmpty().values.sumOf { it.coerceAtLeast(0).toLong() }
    Column(Modifier.fillMaxWidth().testTag("advanced-prompt-usage"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("提示词各段占比", style = MaterialTheme.typography.titleMedium)
        Text("按上次请求的提示词与辅助资料估算 Token", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PromptSection.entries.forEach { section ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(promptSectionLabel(section), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(if (counts == null) "—" else "${percentages.getValue(section)}% · ${(counts[section] ?: 0).coerceAtLeast(0)} Token",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(if (counts == null) "暂无记录，完成一次聊天后再查看" else "合计 $total Token，不含聊天原话和工具定义",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
