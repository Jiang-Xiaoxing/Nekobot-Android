package com.nekobot.app.ui.screens.aiconfig

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.AiBalanceProviders
import com.nekobot.app.data.local.ai.AiBalanceRecord
import com.nekobot.app.data.local.ai.AiBalanceSource
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 可查询余额的密钥选项（只保留脱敏后的展示值）。 */
data class BalanceKeyOption(
    val id: String,
    val name: String,
    val hint: String
)

/** 可查询余额的模型配置选项（直接复用模型自带的密钥与 Base URL）。 */
data class BalanceModelOption(
    val id: String,
    val name: String,
    val detail: String,
    val apiKey: String,
    val baseUrl: String,
    val proxyUrl: String
)

class AiBalanceViewModel : BaseViewModel() {

    private val _records = MutableStateFlow<List<AiBalanceRecord>>(emptyList())
    val records: StateFlow<List<AiBalanceRecord>> = _records.asStateFlow()

    private val _keys = MutableStateFlow<List<BalanceKeyOption>>(emptyList())
    val keys: StateFlow<List<BalanceKeyOption>> = _keys.asStateFlow()

    private val _models = MutableStateFlow<List<BalanceModelOption>>(emptyList())
    val models: StateFlow<List<BalanceModelOption>> = _models.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { ServiceContainer.localRepository.listAiBalanceRecords() }
                .onSuccess { _records.value = it }
            loadSources()
        }
    }

    private suspend fun loadSources() {
        val keyList = when (val result = unified.listApiKeys()) {
            is Resource.Success -> result.data.orEmpty()
            is Resource.Error -> {
                showError(result.message)
                emptyList()
            }
            is Resource.Loading -> emptyList()
        }
        _keys.value = keyList.map {
            BalanceKeyOption(id = it.id.orEmpty(), name = it.displayName, hint = it.key.orEmpty())
        }
        _models.value = if (isLocalMode) {
            runCatching { ServiceContainer.localRepository.listAiModels() }.getOrDefault(emptyList())
                .map { model ->
                    BalanceModelOption(
                        id = model.id,
                        name = model.name.ifBlank { model.model },
                        detail = listOf(model.model, model.baseUrl).filter { it.isNotBlank() }.joinToString(" · "),
                        apiKey = model.apiKey,
                        baseUrl = model.baseUrl,
                        proxyUrl = model.proxyUrl
                    )
                }
        } else {
            when (val result = repo.listAiModels()) {
                is Resource.Success -> result.data.orEmpty().map { model ->
                    BalanceModelOption(
                        id = model.id.orEmpty(),
                        name = model.displayName,
                        detail = listOfNotNull(model.model, model.baseUrl).filter { it.isNotBlank() }.joinToString(" · "),
                        apiKey = model.apiKey.orEmpty(),
                        baseUrl = model.baseUrl.orEmpty(),
                        proxyUrl = ""
                    )
                }
                is Resource.Error -> {
                    showError(result.message)
                    emptyList()
                }
                is Resource.Loading -> emptyList()
            }
        }
    }

    /** 手动输入的密钥查询。 */
    fun queryManual(apiKey: String, baseUrl: String, provider: String) {
        if (apiKey.isBlank()) {
            showError(string(R.string.ai_balance_key_required))
            return
        }
        startQuery(
            apiKey = apiKey,
            baseUrl = baseUrl,
            provider = provider,
            source = AiBalanceSource.MANUAL,
            label = "",
            proxyUrl = ""
        )
    }

    /** 使用已保存密钥查询：查询时才读取明文。 */
    fun querySavedKey(id: String, provider: String, baseUrl: String = "") {
        viewModelScope.launch {
            when (val result = unified.getApiKey(id)) {
                is Resource.Success -> {
                    val key = result.data.key.orEmpty()
                    if (key.isBlank()) {
                        showError(string(R.string.ai_balance_key_required))
                        return@launch
                    }
                    val name = _keys.value.firstOrNull { it.id == id }?.name.orEmpty()
                    startQuery(
                        apiKey = key,
                        baseUrl = baseUrl,
                        provider = provider,
                        source = AiBalanceSource.API_KEY,
                        label = name,
                        proxyUrl = ""
                    )
                }
                is Resource.Error -> showError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }

    /** 使用现有模型配置（Base URL + 密钥）查询。 */
    fun queryModel(option: BalanceModelOption) {
        if (option.apiKey.isBlank() || option.apiKey.contains("****")) {
            showError(string(R.string.ai_balance_model_key_hidden))
            return
        }
        startQuery(
            apiKey = option.apiKey,
            baseUrl = option.baseUrl,
            provider = AiBalanceProviders.AUTO,
            source = AiBalanceSource.MODEL,
            label = option.name,
            proxyUrl = option.proxyUrl
        )
    }

    private fun startQuery(
        apiKey: String,
        baseUrl: String,
        provider: String,
        source: String,
        label: String,
        proxyUrl: String
    ) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val record = ServiceContainer.localRepository.queryAiBalance(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    providerHint = provider,
                    proxyUrl = proxyUrl,
                    source = source,
                    label = label
                )
                _records.value = ServiceContainer.localRepository.listAiBalanceRecords()
                if (record.isSuccess) {
                    showToast(string(R.string.ai_balance_query_success))
                } else {
                    showError(record.error ?: string(R.string.ai_balance_query_failed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.common_unknown_error))
            } finally {
                setLoading(false)
            }
        }
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            runCatching { ServiceContainer.localRepository.deleteAiBalanceRecord(id) }
                .onFailure { showError(it.message ?: string(R.string.common_unknown_error)) }
            _records.value = ServiceContainer.localRepository.listAiBalanceRecords()
            showToast(string(R.string.ai_balance_record_deleted))
        }
    }

    fun clearRecords() {
        viewModelScope.launch {
            runCatching { ServiceContainer.localRepository.clearAiBalanceRecords() }
                .onFailure { showError(it.message ?: string(R.string.common_unknown_error)) }
            _records.value = emptyList()
            showToast(string(R.string.ai_balance_records_cleared))
        }
    }
}

/** 金额展示：按币种加常见符号，未知币种追加代码。 */
internal fun formatBalanceAmount(currency: String, amount: Double?): String {
    if (amount == null) return "—"
    val code = currency.uppercase(Locale.US)
    val symbol = when (code) {
        "CNY", "RMB" -> "¥"
        "USD" -> "$"
        "EUR" -> "€"
        else -> ""
    }
    val text = if (kotlin.math.abs(amount) >= 1000) {
        String.format(Locale.US, "%,.2f", amount)
    } else {
        String.format(Locale.US, "%.2f", amount)
    }
    return if (symbol.isEmpty()) "$text $code".trim() else "$symbol$text"
}

@Composable
private fun balanceProviderLabel(provider: String): String = stringResource(
    when (provider) {
        AiBalanceProviders.DEEPSEEK -> R.string.ai_balance_provider_deepseek
        AiBalanceProviders.OPENROUTER -> R.string.ai_balance_provider_openrouter
        AiBalanceProviders.MOONSHOT -> R.string.ai_balance_provider_moonshot
        AiBalanceProviders.SILICONFLOW -> R.string.ai_balance_provider_siliconflow
        AiBalanceProviders.OPENAI_COMPATIBLE -> R.string.ai_balance_provider_openai_compatible
        else -> R.string.ai_balance_provider_auto
    }
)

@Composable
private fun balanceSourceLabel(source: String): String = stringResource(
    when (source) {
        AiBalanceSource.API_KEY -> R.string.ai_balance_source_key
        AiBalanceSource.MODEL -> R.string.ai_balance_source_model
        else -> R.string.ai_balance_source_manual
    }
)

/**
 * AI 模型余额查询页。
 *
 * 支持手动输入密钥、选择已保存密钥、或直接使用现有模型配置查询；
 * 每次查询结果（含失败原因）持久化后按时间倒序展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiBalanceScreen(onBack: () -> Unit) {
    val vm: AiBalanceViewModel = viewModel()
    val records by vm.records.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var source by remember { mutableStateOf(AiBalanceSource.MANUAL) }
    var manualKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(AiBalanceProviders.AUTO) }
    var selectedKeyId by remember { mutableStateOf<String?>(null) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    var expandedRawIds by remember { mutableStateOf(setOf<String>()) }
    var deleteTarget by remember { mutableStateOf<AiBalanceRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    LaunchedEffect(appMode) { vm.refresh() }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    ErrorToast(message = error, onShown = { vm.clearError() })

    val selectedKey = keys.firstOrNull { it.id == selectedKeyId }
    val selectedModel = models.firstOrNull { it.id == selectedModelId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.ai_balance_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
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
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.ai_balance_refresh),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20) {
                        Text(
                            stringResource(R.string.ai_balance_source_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = source == AiBalanceSource.MANUAL,
                                onClick = { source = AiBalanceSource.MANUAL },
                                label = { Text(stringResource(R.string.ai_balance_source_manual)) }
                            )
                            FilterChip(
                                selected = source == AiBalanceSource.API_KEY,
                                onClick = { source = AiBalanceSource.API_KEY },
                                label = { Text(stringResource(R.string.ai_balance_source_key)) }
                            )
                            FilterChip(
                                selected = source == AiBalanceSource.MODEL,
                                onClick = { source = AiBalanceSource.MODEL },
                                label = { Text(stringResource(R.string.ai_balance_source_model)) }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        when (source) {
                            AiBalanceSource.MANUAL -> {
                                OutlinedTextField(
                                    value = manualKey,
                                    onValueChange = { manualKey = it },
                                    label = { Text(stringResource(R.string.ai_balance_api_key)) },
                                    placeholder = { Text(stringResource(R.string.ai_balance_api_key_hint)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = { baseUrl = it },
                                    label = { Text(stringResource(R.string.ai_balance_base_url)) },
                                    placeholder = { Text("https://api.deepseek.com") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            AiBalanceSource.API_KEY -> {
                                SelectorRow(
                                    label = stringResource(R.string.ai_balance_select_key),
                                    value = selectedKey?.let { "${it.name} · ${it.hint}" },
                                    emptyHint = stringResource(R.string.ai_balance_no_keys),
                                    empty = keys.isEmpty()
                                ) { dismiss ->
                                    keys.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(option.name)
                                                    if (option.hint.isNotBlank()) {
                                                        Text(
                                                            option.hint,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                selectedKeyId = option.id
                                                dismiss()
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = { baseUrl = it },
                                    label = { Text(stringResource(R.string.ai_balance_base_url)) },
                                    placeholder = { Text("https://api.deepseek.com") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            else -> {
                                SelectorRow(
                                    label = stringResource(R.string.ai_balance_select_model),
                                    value = selectedModel?.let { "${it.name} · ${it.detail}" },
                                    emptyHint = stringResource(R.string.ai_balance_no_models),
                                    empty = models.isEmpty()
                                ) { dismiss ->
                                    models.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(option.name)
                                                    if (option.detail.isNotBlank()) {
                                                        Text(
                                                            option.detail,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                selectedModelId = option.id
                                                dismiss()
                                            }
                                        )
                                    }
                                }
                                if (selectedModel != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(
                                            R.string.ai_balance_detected_provider,
                                            balanceProviderLabel(
                                                AiBalanceProviders.detect(selectedModel.apiKey, selectedModel.baseUrl)
                                            )
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        if (source != AiBalanceSource.MODEL) {
                            Text(
                                stringResource(R.string.ai_balance_provider),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            ProviderSelector(
                                provider = provider,
                                onSelect = { provider = it }
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Button(
                            onClick = {
                                when (source) {
                                    AiBalanceSource.MANUAL -> vm.queryManual(manualKey, baseUrl, provider)
                                    AiBalanceSource.API_KEY -> {
                                        val id = selectedKeyId
                                        if (id == null) vm.showError(
                                            ServiceContainer.getString(R.string.ai_balance_key_not_selected)
                                        ) else vm.querySavedKey(id, provider, baseUrl)
                                    }
                                    else -> {
                                        val option = selectedModel
                                        if (option == null) vm.showError(
                                            ServiceContainer.getString(R.string.ai_balance_model_not_selected)
                                        ) else vm.queryModel(option)
                                    }
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Filled.AccountBalanceWallet,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (loading) R.string.ai_balance_querying else R.string.ai_balance_query
                                ),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.ai_balance_history),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (records.isNotEmpty()) {
                            TextButton(onClick = { confirmClear = true }) {
                                Text(
                                    stringResource(R.string.ai_balance_clear_all),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (records.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.ai_balance_empty_title),
                            hint = stringResource(R.string.ai_balance_empty_hint)
                        )
                    }
                } else {
                    items(records, key = { it.id }) { record ->
                        BalanceRecordCard(
                            record = record,
                            expanded = record.id in expandedRawIds,
                            onToggleRaw = {
                                expandedRawIds = if (record.id in expandedRawIds) {
                                    expandedRawIds - record.id
                                } else {
                                    expandedRawIds + record.id
                                }
                            },
                            onDelete = { deleteTarget = record }
                        )
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }
            LoadingOverlay(visible = loading)
        }
    }

    deleteTarget?.let { record ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.ai_balance_delete_title),
            message = stringResource(R.string.ai_balance_delete_message),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                vm.deleteRecord(record.id)
                deleteTarget = null
            }
        )
    }

    if (confirmClear) {
        NekoDialog(
            onDismiss = { confirmClear = false },
            title = stringResource(R.string.ai_balance_clear_title),
            message = stringResource(R.string.ai_balance_clear_message),
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = {
                vm.clearRecords()
                confirmClear = false
            }
        )
    }
}

/** 下拉选择行：显示当前选择或空状态提示。 */
@Composable
private fun SelectorRow(
    label: String,
    value: String?,
    emptyHint: String,
    empty: Boolean,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .clickable(enabled = !empty) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (empty) emptyHint else (value ?: label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (empty || value == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

/** 服务商下拉选择器：auto 表示按密钥与 Base URL 自动识别。 */
@Composable
private fun ProviderSelector(provider: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                balanceProviderLabel(provider),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiBalanceProviders.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(balanceProviderLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 单条查询记录：余额主数值 + 明细 + 原始响应 + 删除。 */
@Composable
private fun BalanceRecordCard(
    record: AiBalanceRecord,
    expanded: Boolean,
    onToggleRaw: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    balanceProviderLabel(record.provider),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${balanceSourceLabel(record.source)} · ${record.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusBadge(record)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (record.isSuccess) {
            Text(
                formatBalanceAmount(record.currency, record.remaining ?: record.total),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (record.total != null) {
                    DetailText(
                        stringResource(R.string.ai_balance_total),
                        formatBalanceAmount(record.currency, record.total)
                    )
                }
                if (record.used != null) {
                    DetailText(
                        stringResource(R.string.ai_balance_used),
                        formatBalanceAmount(record.currency, record.used)
                    )
                }
                if (record.granted != null) {
                    DetailText(
                        stringResource(R.string.ai_balance_granted),
                        formatBalanceAmount(record.currency, record.granted)
                    )
                }
            }
            if (record.endpoint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    record.endpoint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (record.rawJson.isNotBlank()) {
                TextButton(onClick = onToggleRaw, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        stringResource(
                            if (expanded) R.string.ai_balance_hide_raw else R.string.ai_balance_show_raw
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (expanded) {
                    Text(
                        record.rawJson,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(8.dp)
                            .heightIn(max = 220.dp)
                    )
                }
            }
        } else {
            Text(
                record.error ?: stringResource(R.string.ai_balance_query_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = ErrorRed
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                record.createdAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (record.keyHint.isNotBlank()) {
                Text(
                    record.keyHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 120.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DetailText(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusBadge(record: AiBalanceRecord) {
    val text: String
    val color: androidx.compose.ui.graphics.Color
    when {
        !record.isSuccess -> {
            text = stringResource(R.string.ai_balance_status_failed)
            color = ErrorRed
        }
        record.available -> {
            text = stringResource(R.string.ai_balance_status_available)
            color = MaterialTheme.colorScheme.primary
        }
        else -> {
            text = stringResource(R.string.ai_balance_status_unavailable)
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
