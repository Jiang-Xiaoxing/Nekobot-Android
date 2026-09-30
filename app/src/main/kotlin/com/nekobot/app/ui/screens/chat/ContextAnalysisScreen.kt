package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.ContextUsageBreakdown
import com.nekobot.app.data.local.ai.ContextUsagePart
import com.nekobot.app.data.local.ai.ContextUsagePartTokens
import com.nekobot.app.data.local.isAgentContextSummary
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.SessionCacheStats
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ContextAnalysisData(
    val breakdown: ContextUsageBreakdown,
    val usedTokens: Long,
    val maxTokens: Int?,
    /** 本会话缓存命中统计：命中率与命中/输入 token 明细。 */
    val cacheStats: SessionCacheStats = SessionCacheStats(),
    /** 参与占比统计的压缩摘要原文（Agent 会话的压缩窗口内），供点击查看。 */
    val summaries: List<Message> = emptyList()
)

private data class ContextAnalysisUiState(
    val loading: Boolean = true,
    val data: ContextAnalysisData? = null,
    val error: String? = null
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ContextAnalysisScreen(
    sessionId: String,
    onBack: () -> Unit
) {
    var state by remember(sessionId) { mutableStateOf(ContextAnalysisUiState()) }
    var refreshKey by remember(sessionId) { mutableStateOf(0) }
    var showSummaryDialog by remember(sessionId) { mutableStateOf(false) }

    suspend fun load() {
        state = ContextAnalysisUiState()
        val result = withContext(Dispatchers.IO) {
            val sessionResult = ServiceContainer.unified.getSession(sessionId)
            val messagesResult = ServiceContainer.unified.listMessages(sessionId)
            val error = when {
                sessionResult is Resource.Error -> sessionResult.message
                messagesResult is Resource.Error -> messagesResult.message
                else -> null
            }
            if (error != null) {
                Result.failure<ContextAnalysisData>(IllegalStateException(error))
            } else {
                val session = when (sessionResult) {
                    is Resource.Success -> sessionResult.data
                    else -> null
                }
                val messages = when (messagesResult) {
                    is Resource.Success -> messagesResult.data
                    else -> emptyList()
                }
                // Agent 会话使用本地完整口径：系统提示词 + 工具定义 + 压缩窗口内消息 +
                // 逐条落库/折叠保存的工具轨迹，圆环与类型占比分母完全一致。
                // 其余情况（服务端模式）退回按消息与提示词估算。
                val isAgentSession = session?.sessionMode.equals("agent", ignoreCase = true)
                val live = if (isAgentSession) {
                    ServiceContainer.unified.agentLiveContextUsage(sessionId)
                } else {
                    null
                }
                val liveBreakdown = live?.breakdown?.takeIf { !it.isEmpty }
                // 与占比统计同一窗口取摘要原文：占比里的「压缩摘要」条目对应这几条消息
                val summaries = if (isAgentSession) {
                    messages.agentContextWindow().filter(Message::isAgentContextSummary)
                } else {
                    emptyList()
                }
                Result.success(
                    ContextAnalysisData(
                        breakdown = liveBreakdown ?: fallbackContextUsageBreakdown(session, messages),
                        usedTokens = live?.totalTokens
                            ?: ServiceContainer.unified.sessionContextTokenUsage(sessionId),
                        maxTokens = ServiceContainer.unified.getActiveContextLength(),
                        cacheStats = ServiceContainer.unified.sessionCacheStats(sessionId),
                        summaries = summaries
                    )
                )
            }
        }
        state = result.fold(
            onSuccess = { ContextAnalysisUiState(loading = false, data = it) },
            onFailure = { ContextAnalysisUiState(loading = false, error = it.message) }
        )
    }

    LaunchedEffect(sessionId, refreshKey) { load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.chat_context_analysis_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey += 1 }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.common_retry)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
            else -> {
                val data = requireNotNull(state.data)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item { ContextCapacityCard(data.usedTokens, data.maxTokens) }
                    item { ContextCacheHitRateCard(data.cacheStats) }
                    item {
                        Column {
                            Text(
                                stringResource(R.string.chat_context_analysis_type_breakdown),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                stringResource(R.string.chat_context_analysis_basis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (data.breakdown.parts.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.chat_context_analysis_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(data.breakdown.parts, key = { it.part.name }) { part ->
                            val summaryClickable =
                                part.part == ContextUsagePart.SUMMARY && data.summaries.isNotEmpty()
                            ContextTypeRow(
                                part,
                                data.breakdown.totalTokens,
                                onClick = if (summaryClickable) {
                                    { showSummaryDialog = true }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
                if (showSummaryDialog && data.summaries.isNotEmpty()) {
                    SummaryContentDialog(
                        summaries = data.summaries,
                        onDismiss = { showSummaryDialog = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextCapacityCard(usedTokens: Long, maxTokens: Int?) {
    val progress = maxTokens
        ?.takeIf { it > 0 }
        ?.let { (usedTokens.toFloat() / it).coerceIn(0f, 1f) }
        ?: 0f
    val percent = (progress * 100).toInt()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    strokeWidth = 6.dp
                )
                Text(
                    if (maxTokens == null) "-" else "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.chat_context_analysis_current),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (maxTokens == null) {
                        stringResource(R.string.chat_context_analysis_tokens, usedTokens)
                    } else {
                        stringResource(R.string.chat_context_analysis_capacity, usedTokens, maxTokens)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 缓存命中率卡片：命中缓存的输入 token 占完整输入 token 的比例。
 * 服务商未上报缓存字段时显示「—」，避免误导性的 0%。
 */
@Composable
private fun ContextCacheHitRateCard(stats: SessionCacheStats) {
    val hitRate = stats.hitRate
    val progress = (hitRate ?: 0.0).toFloat().coerceIn(0f, 1f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                    strokeWidth = 6.dp
                )
                Text(
                    formatCacheHitRate(hitRate),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.chat_context_analysis_cache_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hitRate == null) {
                        stringResource(R.string.chat_context_analysis_cache_hint)
                    } else {
                        stringResource(
                            R.string.chat_context_analysis_cache_detail,
                            stats.cachedInputTokens,
                            stats.inputTokens
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ContextTypeRow(
    part: ContextUsagePartTokens,
    totalTokens: Int,
    onClick: (() -> Unit)? = null
) {
    val color = part.part.displayColor(MaterialTheme.colorScheme)
    val share = if (totalTokens > 0) part.tokens.toFloat() / totalTokens else 0f
    val percent = (share * 100).toInt()
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contextPartLabel(part.part),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(
                        R.string.chat_context_analysis_part_detail,
                        part.tokens,
                        part.itemCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onClick != null) {
                    Text(
                        stringResource(R.string.chat_context_analysis_summary_view_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(
                "$percent%",
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        LinearProgressIndicator(
            progress = { share },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/** 压缩摘要原文弹窗：多条摘要时按占比统计顺序逐段展示。 */
@Composable
private fun SummaryContentDialog(
    summaries: List<Message>,
    onDismiss: () -> Unit
) {
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.chat_context_analysis_summary),
        confirmText = stringResource(R.string.common_close),
        contentScrollable = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            summaries.forEachIndexed { index, summary ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    )
                }
                Text(
                    summary.displayContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
