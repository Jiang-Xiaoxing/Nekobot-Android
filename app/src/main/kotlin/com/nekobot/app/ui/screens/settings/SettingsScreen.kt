package com.nekobot.app.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.update.UpdateChecker
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.MarkdownText
import com.nekobot.app.ui.components.NekoDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 用于格式化 JSON 输出 */
private val prettyGson = GsonBuilder().setPrettyPrinting().setLenient().disableHtmlEscaping().create()

/**
 * 系统设置页 ViewModel
 */
class SettingsViewModel : BaseViewModel() {

    private val _settingsJson = MutableStateFlow("")
    val settingsJson: StateFlow<String> = _settingsJson.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    private val _serverUrl = MutableStateFlow(ServiceContainer.prefs.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _appMode = MutableStateFlow(ServiceContainer.prefs.appMode)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    /** 切换运行模式：写入 prefs + 广播全局 Flow + 重建网络 */
    fun switchMode(mode: AppMode) {
        ServiceContainer.switchAppMode(mode)
        _appMode.value = mode
        ServiceContainer.rebuildNetwork()
        showToast(if (mode == AppMode.LOCAL) string(R.string.settings_switched_to_local) else string(R.string.settings_switched_to_server))
    }

    init {
        // 本地模式无需调用远程 getSettings（避免 401 错误）
        if (ServiceContainer.prefs.appMode != AppMode.LOCAL) {
            loadSettings()
        }
    }

    fun loadSettings() {
        launchResult(
            block = { repo.getSettings() },
            onSuccess = { json ->
                _settingsJson.value = prettyGson.toJson(json)
            }
        )
    }

    fun saveSettings(jsonStr: String) {
        try {
            val json = JsonParser.parseString(jsonStr)
            launchResult(
                block = { repo.updateSettings(json) },
                onSuccess = { showToast(string(R.string.settings_settings_saved)) }
            )
        } catch (e: Exception) {
            showError(string(R.string.settings_json_format_error, e.message ?: ""))
        }
    }

    fun reloadConfig() {
        launchResult(
            block = { repo.reloadConfig() },
            onSuccess = { showToast(string(R.string.settings_config_reloaded)) }
        )
    }

    fun logout() {
        launchResult(
            block = { repo.logout() },
            onSuccess = { _loggedOut.value = true }
        )
    }

    fun saveServerUrl(url: String) {
        ServiceContainer.prefs.serverUrl = url
        ServiceContainer.rebuildNetwork()
        _serverUrl.value = ServiceContainer.prefs.serverUrl
        showToast(string(R.string.settings_server_url_updated))
    }

    // ==================== 更新检查 ====================

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()

    /** 检查更新：仅使用 GitHub 官方服务。 */
    fun checkForUpdate(context: android.content.Context, currentVersion: String) {
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            when (val res = UpdateChecker.checkForUpdate(context, currentVersion)) {
                is UpdateChecker.CheckResult.UpToDate -> {
                    _updateState.value = UpdateUiState.UpToDate
                }
                is UpdateChecker.CheckResult.Available -> {
                    _updateState.value = UpdateUiState.Available(res.info)
                }
                is UpdateChecker.CheckResult.Error -> {
                    _updateState.value = UpdateUiState.Error(res.message)
                }
            }
        }
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateUiState.Idle
    }

    /** 下载 APK；完成后调用 onReady 触发安装。 */
    fun downloadApk(
        context: android.content.Context,
        asset: UpdateChecker.ReleaseAsset,
        onReady: (java.io.File) -> Unit
    ) {
        _downloadState.value = DownloadUiState.Downloading(0)
        viewModelScope.launch {
            val res = UpdateChecker.downloadApk(context, asset) { progress ->
                when (progress) {
                    is UpdateChecker.DownloadResult.Progress -> {
                        _downloadState.value = DownloadUiState.Downloading(progress.percent)
                    }
                    is UpdateChecker.DownloadResult.Error -> {
                        _downloadState.value = DownloadUiState.Idle
                    }
                    is UpdateChecker.DownloadResult.Done -> {
                        _downloadState.value = DownloadUiState.Done(progress.file)
                    }
                }
            }
            when (res) {
                is UpdateChecker.DownloadResult.Done -> onReady(res.file)
                is UpdateChecker.DownloadResult.Error -> {
                    _downloadState.value = DownloadUiState.Idle
                    showError(string(R.string.update_download_failed, res.message))
                }
                else -> {}
            }
        }
    }

    fun dismissDownloadState() {
        _downloadState.value = DownloadUiState.Idle
    }
}

/** 更新检查 UI 状态。 */
sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data object UpToDate : UpdateUiState()
    data class Available(val info: UpdateChecker.ReleaseInfo) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

/** 下载进度 UI 状态。 */
sealed class DownloadUiState {
    data object Idle : DownloadUiState()
    data class Downloading(val percent: Int) : DownloadUiState()
    data class Done(val file: java.io.File) : DownloadUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit, onNavigate: (String) -> Unit, onBack: () -> Unit = onLogout) {
    val vm: SettingsViewModel = viewModel()
    val loggedOut by vm.loggedOut.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val appMode by vm.appMode.collectAsStateWithLifecycle()
    val recentSessionsIncludeArchived by ServiceContainer.prefs.recentSessionsIncludeArchivedFlow.collectAsStateWithLifecycle()
    val openLatestSessionOnLaunch by ServiceContainer.prefs.openLatestSessionOnLaunchFlow.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val downloadState by vm.downloadState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 本地编辑状态
    var serverUrlInput by remember(serverUrl) { mutableStateOf(serverUrl) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var connectionVisible by rememberSaveable { mutableStateOf(false) }
    val homeScrollState = rememberScrollState()
    val connectionScrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var navigationInFlight by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) navigationInFlight = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun navigateOnce(route: String) {
        if (navigationInFlight || lifecycleOwner.lifecycle.currentState != Lifecycle.State.RESUMED) return
        navigationInFlight = true
        onNavigate(route)
    }
    BackHandler(enabled = connectionVisible) { connectionVisible = false }
    val appLockState = remember { mutableStateOf(ServiceContainer.prefs.appLockEnabled) }
    var appLockEnabled by appLockState
    val pendingAppLockState = remember { mutableStateOf<Boolean?>(null) }
    val activity = remember(context) { context.findFragmentActivity() }
    val appLockPrompt = remember(activity) {
        activity?.let { host ->
            BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        val enabled = pendingAppLockState.value ?: return
                        ServiceContainer.prefs.appLockEnabled = enabled
                        appLockState.value = enabled
                        pendingAppLockState.value = null
                        // 立即更新 FLAG_SECURE，保证下次切后台时最近任务页预览已被隐藏
                        if (enabled) {
                            host.window.setFlags(
                                WindowManager.LayoutParams.FLAG_SECURE,
                                WindowManager.LayoutParams.FLAG_SECURE
                            )
                        } else {
                            host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                        Toast.makeText(
                            context,
                            context.getString(
                                if (enabled) {
                                    R.string.settings_app_lock_enabled
                                } else {
                                    R.string.settings_app_lock_disabled
                                }
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        pendingAppLockState.value = null
                        Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    fun requestAppLockChange(enabled: Boolean) {
        if (enabled == appLockState.value || pendingAppLockState.value != null) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val available = activity != null &&
            BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (!available || appLockPrompt == null) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_app_lock_unavailable),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        pendingAppLockState.value = enabled
        appLockPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.settings_app_lock_auth_title))
                .setSubtitle(
                    context.getString(
                        if (enabled) {
                            R.string.settings_app_lock_enable_auth_desc
                        } else {
                            R.string.settings_app_lock_disable_auth_desc
                        }
                    )
                )
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText(context.getString(R.string.common_cancel))
                .build()
        )
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(loggedOut) {
        if (loggedOut) {
            onLogout()
        }
    }

    ErrorToast(message = error, onShown = vm::clearError)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (connectionVisible) R.string.settings_home_connection else R.string.more_category_app))
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (connectionVisible) connectionVisible = false else onBack() },
                        modifier = Modifier.testTag("settings-home-back")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
            if (connectionVisible) {
                SettingsConnectionContent(
                    scrollState = connectionScrollState,
                    appMode = appMode,
                    serverUrlInput = serverUrlInput,
                    username = ServiceContainer.prefs.username,
                    loading = loading,
                    onModeChange = { mode -> if (mode != vm.appMode.value) vm.switchMode(mode) },
                    onServerUrlChange = { serverUrlInput = it },
                    onSaveServerUrl = { vm.saveServerUrl(serverUrlInput) },
                    onReloadConfig = { vm.reloadConfig() },
                    onLogout = { vm.logout() }
                )
            } else {
                SettingsHomeContent(
                    scrollState = homeScrollState,
                    appMode = appMode,
                    language = stringResource(
                        when (ServiceContainer.prefs.language) {
                            PrefsManager.LANGUAGE_ZH -> R.string.language_chinese
                            PrefsManager.LANGUAGE_ZH_TW -> R.string.language_chinese_traditional
                            PrefsManager.LANGUAGE_EN -> R.string.language_english
                            PrefsManager.LANGUAGE_JA -> R.string.language_japanese
                            PrefsManager.LANGUAGE_KO -> R.string.language_korean
                            else -> R.string.language_system
                        }
                    ),
                    version = getAppVersion(context),
                    openLatestSessionOnLaunch = openLatestSessionOnLaunch,
                    recentSessionsIncludeArchived = recentSessionsIncludeArchived,
                    appLockEnabled = appLockEnabled,
                    appLockAvailable = pendingAppLockState.value == null,
                    onOpenLatestSessionChange = { ServiceContainer.prefs.openLatestSessionOnLaunch = it },
                    onIncludeArchivedChange = { ServiceContainer.prefs.recentSessionsIncludeArchived = it },
                    onAppLockChange = ::requestAppLockChange,
                    onLanguageClick = { showLanguagePicker = true },
                    onConnectionClick = { connectionVisible = true },
                    onNavigate = ::navigateOnce
                )
            }

            LoadingOverlay(visible = loading || updateState is UpdateUiState.Checking, message = if (updateState is UpdateUiState.Checking) stringResource(R.string.update_checking) else stringResource(R.string.common_loading))
        }
    }

    // 检查结果：已是最新版本
    if (updateState is UpdateUiState.UpToDate) {
        NekoDialog(
            onDismiss = { vm.dismissUpdateState() },
            title = stringResource(R.string.settings_check_update),
            message = stringResource(R.string.update_no_new),
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { vm.dismissUpdateState() },
            cancelText = null,
            onCancel = null
        )
    }

    // 检查结果：出错
    if (updateState is UpdateUiState.Error) {
        val errMsg = (updateState as UpdateUiState.Error).message
        NekoDialog(
            onDismiss = { vm.dismissUpdateState() },
            title = stringResource(R.string.settings_check_update),
            message = stringResource(R.string.update_check_failed, errMsg),
            confirmText = stringResource(R.string.common_confirm),
            onConfirm = { vm.dismissUpdateState() },
            cancelText = null,
            onCancel = null
        )
    }

    // 发现新版本：发布详情 + 下载
    if (updateState is UpdateUiState.Available) {
        val info = (updateState as UpdateUiState.Available).info
        UpdateDetailDialog(
            info = info,
            currentVersion = getAppVersion(context),
            downloadState = downloadState,
            onDismiss = { vm.dismissUpdateState() },
            onDownload = { asset ->
                vm.downloadApk(context, asset) { file ->
                    runCatching {
                        context.startActivity(UpdateChecker.buildInstallIntent(context, file))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_install_failed),
                            Toast.LENGTH_LONG
                        ).show()
                        // 回退：用浏览器打开 release 页面
                        runCatching {
                            context.startActivity(UpdateChecker.buildReleasesPageIntent())
                        }
                    }
                }
            },
            onOpenInBrowser = {
                runCatching { context.startActivity(UpdateChecker.buildReleasesPageIntent()) }
            }
        )
    }

    // 语言选择弹窗
    if (showLanguagePicker) {
        LanguagePickerDialog(
            currentLanguage = ServiceContainer.prefs.language,
            onSelect = { tag ->
                ServiceContainer.prefs.language = tag
                ServiceContainer.refreshLocale()
                showLanguagePicker = false
                // 重建 Activity 使新 locale 生效
                (context as? Activity)?.recreate()
            },
            onDismiss = { showLanguagePicker = false }
        )
    }

    // 日志查看已迁移到独立页面（Routes.LOGS），此处不再弹出日志对话框
}

/** 语言选择弹窗：跟随系统 / 简体中文 / 繁體中文 / English / 日本語 / 한국어。 */
@Composable
private fun LanguagePickerDialog(
    currentLanguage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        PrefsManager.LANGUAGE_SYSTEM to stringResource(R.string.language_system),
        PrefsManager.LANGUAGE_ZH to stringResource(R.string.language_chinese),
        PrefsManager.LANGUAGE_ZH_TW to stringResource(R.string.language_chinese_traditional),
        PrefsManager.LANGUAGE_EN to stringResource(R.string.language_english),
        PrefsManager.LANGUAGE_JA to stringResource(R.string.language_japanese),
        PrefsManager.LANGUAGE_KO to stringResource(R.string.language_korean)
    )
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_language),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        cancelText = null,
        onCancel = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            options.forEach { (tag, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(tag) }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (tag == currentLanguage) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

/** 获取当前应用版本名；失败时返回 "unknown"。 */
private fun getAppVersion(context: android.content.Context): String {
    return runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")
}

/**
 * 发布详情弹窗：展示 tag、发布时间、markdown 发布说明，并提供下载/在浏览器打开按钮。
 * 下载中显示进度条；无 APK 资产时禁用下载按钮。
 */
@Composable
fun UpdateDetailDialog(
    info: UpdateChecker.ReleaseInfo,
    currentVersion: String,
    downloadState: DownloadUiState,
    onDismiss: () -> Unit,
    onDownload: (UpdateChecker.ReleaseAsset) -> Unit,
    onOpenInBrowser: () -> Unit,
    showIgnoreOption: Boolean = false,
    onIgnoreVersion: ((String) -> Unit)? = null
) {
    val apkAsset = info.apkAsset
    val isDownloading = downloadState is DownloadUiState.Downloading
    val percent = (downloadState as? DownloadUiState.Downloading)?.percent ?: 0
    var ignoreThisVersion by remember(info.tagName) { mutableStateOf(false) }
    val hasLongReleaseNotes = info.body.length > 500 || info.body.lineSequence().count() > 6
    var releaseNotesExpanded by remember(info.tagName, info.body) { mutableStateOf(false) }

    NekoDialog(
        onDismiss = {
            if (ignoreThisVersion) onIgnoreVersion?.invoke(info.tagName)
            onDismiss()
        },
        title = stringResource(R.string.update_new_available, info.tagName),
        confirmText = if (apkAsset != null) stringResource(R.string.update_download) else stringResource(R.string.update_view_releases),
        confirmIcon = if (apkAsset != null) Icons.Filled.Download else null,
        confirmIconContentDescription = stringResource(R.string.update_download),
        confirmEnabled = !isDownloading,
        onConfirm = {
            if (apkAsset != null) {
                ignoreThisVersion = false
                onDownload(apkAsset)
            } else {
                onOpenInBrowser()
            }
        },
        cancelText = stringResource(R.string.update_open_in_browser),
        onCancel = { onOpenInBrowser() },
        contentScrollable = true
    ) {
        // 版本信息
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_current_version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "v$currentVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_latest_version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = info.tagName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 发布时间
        Text(
            text = "${stringResource(R.string.update_published_at)}：${formatIsoTime(info.publishedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        // 发布说明
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.update_release_notes),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (hasLongReleaseNotes) {
                IconButton(onClick = { releaseNotesExpanded = !releaseNotesExpanded }) {
                    Icon(
                        imageVector = if (releaseNotesExpanded) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = stringResource(
                            if (releaseNotesExpanded) {
                                R.string.update_collapse_release_notes
                            } else {
                                R.string.update_expand_release_notes
                            }
                        )
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (info.body.isBlank()) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (hasLongReleaseNotes && !releaseNotesExpanded) {
            Text(
                text = info.body,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            MarkdownText(
                text = info.body,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // 资产说明
        if (apkAsset == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.update_no_asset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${apkAsset.name} · ${formatSize(apkAsset.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showIgnoreOption) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ignoreThisVersion = !ignoreThisVersion },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = ignoreThisVersion,
                    onCheckedChange = { ignoreThisVersion = it }
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.update_ignore_this_version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // 下载进度
        if (isDownloading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.update_downloading,
                    percent
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 把 ISO 时间字符串（如 2024-05-01T12:34:56Z）格式化为可读形式。失败时原样返回。 */
fun formatIsoTime(iso: String): String {
    if (iso.isBlank()) return "—"
    return runCatching {
        // 截取到分钟：YYYY-MM-DD HH:mm
        val core = iso.replace("T", " ").replace("Z", "")
        core.substring(0, minOf(16, core.length))
    }.getOrDefault(iso)
}

/** 格式化文件大小。 */
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) {
        size /= 1024
        idx++
    }
    return if (idx == 0) "${bytes} ${units[idx]}" else String.format("%.1f %s", size, units[idx])
}
