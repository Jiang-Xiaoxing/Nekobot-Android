package com.nekobot.app.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.SandboxEnvironment
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EnvironmentVariableInputs
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SandboxEnvironmentViewModel : BaseViewModel() {
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values = _values.asStateFlow()

    fun load(sessionId: String?) = launchResult(
        block = { unified.sandboxEnvironment(sessionId) },
        onSuccess = { _values.value = it },
    )

    fun save(sessionId: String?, values: Map<String, String>, onSaved: () -> Unit) = launchResult(
        block = { unified.saveSandboxEnvironment(sessionId, values) },
        onSuccess = {
            _values.value = it
            showToast(string(R.string.env_saved))
            onSaved()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxEnvironmentScreen(sessionId: String? = null, onBack: () -> Unit) {
    val vm: SandboxEnvironmentViewModel = viewModel()
    var sessionScope by remember(sessionId) { mutableStateOf(sessionId != null) }
    val scopeId = sessionId.takeIf { sessionScope }
    val values by vm.values.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember(scopeId) { mutableStateOf(false) }
    var originalName by remember(scopeId) { mutableStateOf<String?>(null) }
    LaunchedEffect(scopeId) { vm.load(scopeId) }
    LaunchedEffect(toast) {
        toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearToast() }
    }

    if (editing) {
        var name by remember(originalName) { mutableStateOf(originalName.orEmpty()) }
        var value by remember(originalName) { mutableStateOf(values[originalName].orEmpty()) }
        val duplicate = name != originalName && values.containsKey(name)
        NekoDialog(
            onDismiss = { if (!loading) editing = false },
            title = stringResource(if (originalName == null) R.string.common_add else R.string.common_edit),
            confirmText = stringResource(R.string.common_save),
            confirmEnabled = !loading && !duplicate && SandboxEnvironment.isValidName(name),
            onConfirm = {
                vm.save(scopeId, values.filterKeys { it != originalName } + (name to value)) { editing = false }
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { if (!loading) editing = false },
        ) {
            EnvironmentVariableInputs(name, value, { name = it }, { value = it }, duplicate)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (sessionId == null) R.string.env_global_title else R.string.env_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { originalName = null; editing = true },
                        enabled = !loading && values.size < SandboxEnvironment.MAX_VARIABLES,
                    ) { Icon(Icons.Filled.Add, stringResource(R.string.common_add)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sessionId != null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sessionScope, onClick = { if (!loading) sessionScope = true },
                        label = { Text(stringResource(R.string.env_session_scope)) })
                    FilterChip(selected = !sessionScope, onClick = { if (!loading) sessionScope = false },
                        label = { Text(stringResource(R.string.env_global_scope)) })
                }
            }
            item {
                Text(stringResource(R.string.env_precedence_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.env_apply_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { message -> item { ErrorToast(message = message, onShown = { vm.clearError() }) } }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!loading && values.isEmpty()) item {
                Text(stringResource(R.string.env_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(values.keys.sorted(), key = { it }) { name ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontFamily = FontFamily.Monospace)
                            Text("••••••••", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { originalName = name; editing = true }, enabled = !loading) {
                            Icon(Icons.Filled.Edit, stringResource(R.string.common_edit))
                        }
                        IconButton(onClick = { vm.save(scopeId, values - name) {} }, enabled = !loading) {
                            Icon(Icons.Filled.DeleteOutline, stringResource(R.string.common_delete))
                        }
                    }
                }
            }
        }
    }
}
