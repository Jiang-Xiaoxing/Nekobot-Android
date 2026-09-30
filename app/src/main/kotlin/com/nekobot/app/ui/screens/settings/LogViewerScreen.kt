package com.nekobot.app.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.accentSecondary
import com.nekobot.app.ui.theme.accentWarning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志条目：本地日志与服务端日志的统一模型。 */
data class LogEntry(
    val time: String,
    val level: String,
    val message: String
)

/**
 * 日志查看 ViewModel
 *
 * 本地模式读取 [LocalLogger] 的持久化记录，服务器模式读取服务端日志，
 * 两者统一成 [LogEntry] 后交给界面做等级筛选与关键词搜索。
 */
class LogViewerViewModel : BaseViewModel() {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    /** 本地模式：数据来源为本地日志，可清空。 */
    val isLocal: Boolean = ServiceContainer.prefs.appMode == AppMode.LOCAL

    fun load() {
        if (isLocal) {
            _logs.value = LocalLogger.listLogs().map { rec ->
                LogEntry(
                    time = "${rec.date} ${rec.time}",
                    level = rec.level,
                    message = if (rec.tag.isNotBlank()) "[${rec.tag}] ${rec.message}" else rec.message
                )
            }
        } else {
            launchResult(
                block = { repo.listLogs() },
                onSuccess = { _logs.value = parseServerLogs(it) }
            )
        }
    }

    /** 清空本地日志（仅本地模式调用）。 */
    fun clearLocalLogs() {
        LocalLogger.clear()
        _logs.value = emptyList()
        showToast(string(R.string.settings_local_logs_cleared))
    }
}

/** 解析服务端日志 JSON 数组（按时间降序）。 */
private fun parseServerLogs(json: JsonElement?): List<LogEntry> {
    val arr = json?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        LogEntry(
            time = obj.get("time")?.asString ?: "",
            level = obj.get("level")?.asString ?: "info",
            message = obj.get("message")?.asString ?: ""
        )
    }.sortedByDescending { it.time }
}

/** 日志等级筛选（按严重程度降序排列）。 */
private enum class LogLevelFilter {
    ALL, ERROR, WARNING, INFO, DEBUG;

    fun matches(level: String): Boolean {
        val value = level.lowercase()
        return when (this) {
            ALL -> true
            ERROR -> value == LocalLogger.LEVEL_ERROR
            WARNING -> value == LocalLogger.LEVEL_WARNING || value == "warn"
            INFO -> value == LocalLogger.LEVEL_INFO
            DEBUG -> value == LocalLogger.LEVEL_DEBUG
        }
    }
}

/** 关键词匹配：空格分隔的多个关键词需全部命中（忽略大小写）。 */
private fun LogEntry.matchesKeywords(keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return true
    val haystack = "$time $level $message".lowercase()
    return keywords.all { haystack.contains(it) }
}

/**
 * 日志查看页：等级筛选 + 关键词搜索 + 复制 / 清空。
 *
 * 取代设置页原先的「查看 / 清空 / 诊断」三按钮排布，作为单一入口的完整日志界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val vm: LogViewerViewModel = viewModel()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf(LogLevelFilter.ALL) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    // 等级 + 关键词双重过滤
    val filtered = remember(logs, query, levelFilter) {
        val keywords = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        logs.filter { levelFilter.matches(it.level) && it.matchesKeywords(keywords) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(if (vm.isLocal) R.string.settings_local_logs else R.string.settings_logs_view),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.diagnostic_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { copyLogs(context, filtered) },
                        enabled = filtered.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.diagnostic_copy_log),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (vm.isLocal) {
                        IconButton(
                            onClick = { showClearConfirm = true },
                            enabled = logs.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.diagnostic_clear_log),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                error?.let {
                    ErrorBanner(message = it, onRetry = { vm.clearError() })
                    Spacer(Modifier.height(8.dp))
                }

                // 搜索框：实时过滤，按 IME 搜索键收起键盘
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.logs_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.common_clear),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )

                Spacer(Modifier.height(10.dp))

                // 等级筛选
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LogLevelFilter.entries.forEach { option ->
                        FilterChip(
                            selected = levelFilter == option,
                            onClick = { levelFilter = option },
                            label = {
                                Text(
                                    text = stringResource(logLevelLabelRes(option)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.logs_count, filtered.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(
                                if (logs.isEmpty()) R.string.settings_no_logs else R.string.logs_no_match
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(filtered) { entry -> LogRowCard(entry) }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 清空确认：避免误触丢掉现场日志
    if (showClearConfirm) {
        NekoDialog(
            onDismiss = { showClearConfirm = false },
            title = stringResource(R.string.diagnostic_clear_log),
            message = stringResource(R.string.logs_clear_confirm),
            confirmText = stringResource(R.string.common_clear),
            onConfirm = {
                showClearConfirm = false
                vm.clearLocalLogs()
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { showClearConfirm = false }
        )
    }
}

/** 等级筛选标签文案。 */
private fun logLevelLabelRes(filter: LogLevelFilter): Int = when (filter) {
    LogLevelFilter.ALL -> R.string.diagnostic_log_level_all
    LogLevelFilter.ERROR -> R.string.diagnostic_log_level_error
    LogLevelFilter.WARNING -> R.string.diagnostic_log_level_warning
    LogLevelFilter.INFO -> R.string.diagnostic_log_level_info
    LogLevelFilter.DEBUG -> R.string.diagnostic_log_level_debug
}

/** 复制当前筛选结果到剪贴板。 */
private fun copyLogs(context: Context, logs: List<LogEntry>) {
    val text = logs.joinToString("\n") { "${it.time} ${it.level.uppercase()} ${it.message}" }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("logs", text))
    Toast.makeText(context, context.getString(R.string.diagnostic_copied), Toast.LENGTH_SHORT).show()
}

/** 单条日志：左侧等级色条 + 等级标签 + 时间 + 正文。 */
@Composable
private fun LogRowCard(entry: LogEntry) {
    val levelColor = when (entry.level.lowercase()) {
        LocalLogger.LEVEL_ERROR -> MaterialTheme.colorScheme.error
        LocalLogger.LEVEL_WARNING, "warn" -> accentWarning()
        LocalLogger.LEVEL_DEBUG -> accentSecondary()
        else -> MaterialTheme.colorScheme.primary
    }
    val levelLabel = when (entry.level.lowercase()) {
        LocalLogger.LEVEL_WARNING, "warn" -> "WARN"
        else -> entry.level.uppercase()
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 10,
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左侧等级色条
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = levelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = entry.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
