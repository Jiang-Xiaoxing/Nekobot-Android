package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.AppMode

/** Settings navigation and preferences only. Connection details stay on the next page. */
@Composable
internal fun SettingsHomeContent(
    scrollState: ScrollState,
    appMode: AppMode,
    language: String,
    version: String,
    openLatestSessionOnLaunch: Boolean,
    recentSessionsIncludeArchived: Boolean,
    appLockEnabled: Boolean,
    appLockAvailable: Boolean,
    imagePromptOptimizeEnabled: Boolean,
    onOpenLatestSessionChange: (Boolean) -> Unit,
    onIncludeArchivedChange: (Boolean) -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onImagePromptOptimizeChange: (Boolean) -> Unit,
    onLanguageClick: () -> Unit,
    onConnectionClick: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    errorContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings-home-scroll")
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        errorContent()
        SettingsHomeGroup(stringResource(R.string.settings_home_common)) {
            if (appMode == AppMode.LOCAL) {
                SettingsHomeNavRow(
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.settings_local_ai_config),
                    subtitle = stringResource(R.string.settings_home_models_summary),
                    tag = "settings-home-local_ai_models",
                    onClick = { onNavigate("local_ai_models") }
                )
                SettingsHomeDivider()
            }
            SettingsHomeNavRow(
                icon = Icons.Filled.Language,
                title = stringResource(R.string.settings_language),
                subtitle = language,
                tag = "settings-home-language",
                onClick = onLanguageClick
            )
            SettingsHomeDivider()
            SettingsHomeSwitchRow(
                icon = Icons.Filled.History,
                title = stringResource(R.string.settings_home_open_latest),
                checked = openLatestSessionOnLaunch,
                tag = "settings-home-open-latest",
                onCheckedChange = onOpenLatestSessionChange
            )
            SettingsHomeDivider()
            SettingsHomeSwitchRow(
                icon = Icons.Filled.Forum,
                title = stringResource(R.string.settings_home_include_archived),
                checked = recentSessionsIncludeArchived,
                tag = "settings-home-include-archived",
                onCheckedChange = onIncludeArchivedChange
            )
        }

        SettingsHomeGroup(stringResource(R.string.settings_home_privacy_experience)) {
            SettingsHomeSwitchRow(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.settings_app_lock),
                subtitle = stringResource(R.string.settings_home_lock_summary),
                checked = appLockEnabled,
                enabled = appLockAvailable,
                tag = "settings-home-app-lock",
                onCheckedChange = onAppLockChange
            )
            if (appMode == AppMode.LOCAL) {
                SettingsHomeDivider()
                SettingsHomeSwitchRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.settings_message_image_prompt_optimize),
                    subtitle = stringResource(R.string.settings_home_image_prompt_summary),
                    checked = imagePromptOptimizeEnabled,
                    tag = "settings-home-image-prompt",
                    onCheckedChange = onImagePromptOptimizeChange
                )
            }
        }

        SettingsHomeGroup(stringResource(R.string.settings_home_data_backup)) {
            SettingsHomeNavRow(
                icon = Icons.Filled.ImportExport,
                title = stringResource(R.string.settings_data_portability),
                subtitle = stringResource(R.string.settings_home_portability_summary),
                tag = "settings-home-data_portability",
                onClick = { onNavigate("data_portability") }
            )
            SettingsHomeDivider()
            SettingsHomeNavRow(
                icon = Icons.Filled.CloudUpload,
                title = stringResource(R.string.settings_webdav_backup),
                subtitle = stringResource(R.string.settings_webdav_backup_desc),
                tag = "settings-home-webdav_backup",
                onClick = { onNavigate("webdav_backup") }
            )
            if (appMode != AppMode.LOCAL) {
                SettingsHomeDivider()
                SettingsHomeNavRow(
                    icon = Icons.Filled.ImportExport,
                    title = stringResource(R.string.settings_config_transfer),
                    subtitle = stringResource(R.string.settings_config_transfer_desc),
                    tag = "settings-home-config_transfer",
                    onClick = { onNavigate("config_transfer") }
                )
            }
            SettingsHomeDivider()
            SettingsHomeNavRow(
                icon = Icons.Filled.Build,
                title = stringResource(R.string.settings_data_maintenance),
                subtitle = stringResource(R.string.settings_data_maintenance_desc),
                tag = "settings-home-data_maintenance",
                onClick = { onNavigate("data_maintenance") }
            )
        }

        SettingsHomeGroup(stringResource(R.string.settings_home_advanced)) {
            SettingsHomeNavRow(
                icon = Icons.Filled.Psychology,
                title = stringResource(R.string.agent_settings_title),
                subtitle = stringResource(R.string.settings_home_agent_summary),
                tag = "settings-home-agent_settings",
                onClick = { onNavigate("agent_settings") }
            )
            if (appMode != AppMode.LOCAL) {
                SettingsHomeDivider()
                SettingsHomeNavRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.settings_feature_switches),
                    subtitle = stringResource(R.string.settings_feature_switches_desc),
                    tag = "settings-home-feature_switches",
                    onClick = { onNavigate("feature_switches") }
                )
                SettingsHomeDivider()
                SettingsHomeNavRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.settings_system_settings),
                    tag = "settings-home-system_settings",
                    onClick = { onNavigate("system_settings") }
                )
            }
            SettingsHomeDivider()
            SettingsHomeNavRow(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.settings_home_connection),
                subtitle = stringResource(
                    if (appMode == AppMode.LOCAL) R.string.settings_local_mode
                    else R.string.settings_server_mode
                ),
                tag = "settings-home-connection",
                onClick = onConnectionClick
            )
        }

        SettingsHomeGroup(stringResource(R.string.settings_home_support_about)) {
            SettingsHomeNavRow(
                icon = Icons.Filled.Description,
                title = stringResource(
                    if (appMode == AppMode.LOCAL) R.string.settings_local_logs
                    else R.string.settings_logs_view
                ),
                tag = "settings-home-logs",
                onClick = { onNavigate("logs") }
            )
            SettingsHomeDivider()
            SettingsHomeNavRow(
                icon = Icons.Filled.BugReport,
                title = stringResource(R.string.diagnostic_center_title),
                subtitle = stringResource(R.string.settings_home_diagnostics_summary),
                tag = "settings-home-diagnostic_center",
                onClick = { onNavigate("diagnostic_center") }
            )
            SettingsHomeDivider()
            SettingsHomeNavRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_about),
                subtitle = "v$version",
                tag = "settings-home-about",
                onClick = { onNavigate("about") }
            )
        }
    }
}

@Composable
internal fun SettingsHomeGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsHomeDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

@Composable
internal fun SettingsHomeNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsHomeRowLabel(icon, title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun SettingsHomeSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsHomeRowLabel(icon, title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        // The whole row is a single accessible switch and has one event handler.
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun SettingsHomeRowLabel(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    modifier: Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
