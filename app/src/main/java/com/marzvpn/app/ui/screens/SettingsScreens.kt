package com.marzvpn.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.marzvpn.app.Links
import com.marzvpn.app.SampleKind
import com.marzvpn.app.model.AccountState
import com.marzvpn.app.model.Identity
import com.marzvpn.app.model.Server
import com.marzvpn.app.model.ServerChoice
import com.marzvpn.app.settings.AppLanguage
import com.marzvpn.app.settings.ThemeMode
import com.marzvpn.app.ui.BigButton
import com.marzvpn.app.ui.BigOutlinedButton
import com.marzvpn.app.ui.MarzCard
import com.marzvpn.app.ui.MarzScaffold
import com.marzvpn.app.ui.PreviewNotice
import com.marzvpn.app.ui.currentLocale
import com.marzvpn.app.ui.ltr
import com.marzvpn.app.vpn.ConfigSource
import com.marzvpn.app.vpn.EngineState
import androidx.compose.runtime.LaunchedEffect
import com.marzvpn.app.RouteLatency
import com.marzvpn.app.model.RouteNames
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import com.marzvpn.app.vpn.RouteAvailability
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun ServersScreen(
    /** Cold check results by server id; empty while the VPN is off. */
    availability: Map<String, RouteAvailability>,
    servers: List<Server>,
    fromAccount: Boolean,
    choice: ServerChoice,
    connected: Boolean,
    activeId: String?,
    latency: Map<String, RouteLatency>,
    onSelect: (ServerChoice) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val locale = currentLocale()
    val context = LocalContext.current
    val active = activeId?.let { id -> servers.firstOrNull { it.id == id } }
    MarzScaffold(title = stringResource(R.string.marz_servers_title), onBack = onBack) {
        TextButton(onClick = onRefresh, enabled = connected && fromAccount) {
            Text(stringResource(R.string.marz_route_check_again))
        }
        MarzCard {
            ServerOption(
                flag = "⚡",
                title = stringResource(R.string.marz_server_auto),
                subtitle = if (connected && choice == ServerChoice.Auto && active != null) {
                    RouteNames.full(context, active, servers, locale)
                } else {
                    stringResource(R.string.marz_server_auto_hint)
                },
                selected = choice == ServerChoice.Auto,
                trailing = null,
            ) { onSelect(ServerChoice.Auto) }
            servers.sortedBy { RouteNames.title(it, locale) }.forEach { server ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ServerOption(
                    flag = server.flag,
                    title = RouteNames.title(server, locale),
                    subtitle = RouteNames.detail(context, server, servers, locale),
                    selected = (choice as? ServerChoice.Specific)?.id == server.id,
                    // Warm delay of the route in use only, measured through the tunnel ("measuring" until then).
                    trailing = if (connected && fromAccount) latencyText(latency[server.id], measuring = server.id == activeId) else null,
                    availability = if (fromAccount) availability[server.id] ?: RouteAvailability.Unknown else null,
                ) { onSelect(ServerChoice.Specific(server.id)) }
            }
        }
        if (!fromAccount) PreviewNotice(stringResource(R.string.marz_servers_preview_note))
        else if (!connected) Text(stringResource(R.string.marz_route_check_needs_vpn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Only the route in use has a value: its delay, "—" after a failed check, "measuring" before the first. */
@Composable
private fun latencyText(l: RouteLatency?, measuring: Boolean): Pair<String, Boolean>? = when (l?.state) {
    RouteLatency.State.Measured -> ltr(stringResource(R.string.marz_latency_ms, l.ms)) to false
    RouteLatency.State.NoData -> "—" to false
    null -> if (measuring) stringResource(R.string.marz_latency_measuring) to false else null
}

/** Green: works from this network; red: failed twice; grey: checking or unknown. No numbers. */
@Composable
private fun AvailabilityDot(a: RouteAvailability) {
    val (color, label) = when (a) {
        RouteAvailability.Ok -> Color(0xFF2EA44F) to R.string.marz_route_ok
        RouteAvailability.Failed -> MaterialTheme.colorScheme.error to R.string.marz_route_failed
        RouteAvailability.Unknown -> MaterialTheme.colorScheme.outlineVariant to R.string.marz_route_unknown
    }
    val description = stringResource(label)
    Box(Modifier.size(12.dp).clip(CircleShape).background(color).semantics { contentDescription = description })
}

@Composable
private fun ServerOption(flag: String, title: String, subtitle: String?, selected: Boolean, trailing: Pair<String, Boolean>?, availability: RouteAvailability? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Text(
                trailing.first,
                style = MaterialTheme.typography.labelMedium,
                color = if (trailing.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (availability != null) {
            AvailabilityDot(availability)
            Spacer(Modifier.width(8.dp))
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
fun SettingsScreen(
    account: AccountState,
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    emailLink: com.marzvpn.app.api.EmailLinkFlow.State,
    onEmailLinkSend: (String) -> Unit,
    onEmailLinkConfirm: (String) -> Unit,
    onEmailLinkReset: () -> Unit,
    onEmailLinkRefresh: () -> Unit,
    onEmailUnlink: (String) -> Unit,
    /** Debug builds only; null in release so the developer entry does not exist at all. */
    onDeveloper: (() -> Unit)?,
    developerUnlocked: Boolean,
    onVersionTap: () -> Unit,
    update: com.marzvpn.app.update.UpdateUi = com.marzvpn.app.update.UpdateUi.Idle,
    /** Null in a store build: the store updates it. */
    onCheckUpdates: (() -> Unit)? = null,
    onUpdateDownload: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val locale = currentLocale()
    var languageDialog by rememberSaveable { mutableStateOf(false) }

    MarzScaffold(title = stringResource(R.string.marz_settings_title), onBack = onBack) {
        SectionTitle(stringResource(R.string.marz_settings_account))
        MarzCard {
            when (account) {
                AccountState.SignedOut -> {
                    Text(stringResource(R.string.marz_subscription_signed_out), style = MaterialTheme.typography.titleMedium)
                    BigButton(stringResource(R.string.marz_login), onLogin)
                }
                is AccountState.SignedIn -> {
                    if (account.isSample) PreviewNotice(stringResource(R.string.marz_sample_banner))
                    account.account.identities.forEach { id ->
                        Text(
                            stringResource(if (id.kind == Identity.Kind.Telegram) R.string.marz_identity_telegram else R.string.marz_identity_email, ltr(id.display)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (!account.isSample && account.account.identities.any { it.kind == Identity.Kind.Telegram }) {
                        EmailLinkSection(account.account, emailLink, onEmailLinkSend, onEmailLinkConfirm,
                            onEmailLinkReset, onEmailLinkRefresh, onEmailUnlink)
                    }
                    BigOutlinedButton(stringResource(R.string.marz_logout), onLogout, enabled = !account.isSample)
                }
            }
        }

        SectionTitle(stringResource(R.string.marz_settings_appearance))
        MarzCard {
            SettingsRow(Icons.Rounded.Language, stringResource(R.string.marz_settings_language), languageName(AppLanguage.current())) { languageDialog = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.marz_settings_theme), style = MaterialTheme.typography.titleMedium)
            ThemeSelector(theme, onTheme)
        }

        SectionTitle(stringResource(R.string.marz_settings_support))
        MarzCard {
            SettingsRow(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.marz_support_telegram), "@MarzReality_bot") { Links.open(context, Links.BOT) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Rounded.Email, stringResource(R.string.marz_support_email), Links.SUPPORT_EMAIL) { Links.email(context) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Rounded.Public, stringResource(R.string.marz_support_site), "marzvpn.com") { Links.openInBrowserTab(context, Links.siteUrl(locale)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Rounded.Description, stringResource(R.string.marz_support_terms), null) { Links.openInBrowserTab(context, Links.termsUrl(locale)) }
        }

        SectionTitle(stringResource(R.string.marz_settings_about))
        MarzCard {
            // In debug builds 7 taps on the version unlock the developer entry; release builds ignore taps.
            Text(
                stringResource(R.string.marz_version, ltr(BuildConfig.VERSION_NAME)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onDeveloper != null) Modifier.clickable(onClick = onVersionTap) else Modifier)
                    .padding(vertical = 4.dp),
            )
            if (onCheckUpdates != null) {
                UpdateSection(update, onCheckUpdates, onUpdateDownload)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Rounded.Description, stringResource(R.string.marz_license_title), stringResource(R.string.marz_license_value)) {
                Links.openInBrowserTab(context, Links.LICENSE)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Rounded.Description, stringResource(R.string.marz_source_code_title), stringResource(R.string.marz_source_code_value)) {
                Links.openInBrowserTab(context, Links.SOURCE)
            }
            Text(stringResource(R.string.marz_open_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onDeveloper != null && developerUnlocked) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(Icons.Rounded.Build, stringResource(R.string.marz_developer_title), null, onDeveloper)
            }
        }
    }

    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            title = { Text(stringResource(R.string.marz_settings_language)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = AppLanguage.current() == lang, role = Role.RadioButton) {
                                    languageDialog = false
                                    AppLanguage.apply(lang)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = AppLanguage.current() == lang, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(languageName(lang), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { languageDialog = false }) { Text(stringResource(R.string.marz_cancel)) } },
        )
    }
}

/**
 * Compact three-way theme switch. Labels never wrap: if the longest one does not fit a segment
 * (narrow screen or large system font), the control becomes a vertical list instead.
 */
@Composable
private fun ThemeSelector(theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    val modes = ThemeMode.entries
    val labels = modes.map {
        stringResource(
            when (it) {
                ThemeMode.System -> R.string.marz_theme_system
                ThemeMode.Light -> R.string.marz_theme_light
                ThemeMode.Dark -> R.string.marz_theme_dark
            },
        )
    }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 4.dp
        val segmentPadding = 4.dp
        val segment = (maxWidth - gap * (modes.size - 1)) / modes.size
        val widest = with(density) { labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp() }
        if (widest + segmentPadding * 2 <= segment) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                modes.forEachIndexed { i, mode ->
                    val selected = theme == mode
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { onTheme(mode) },
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Box(Modifier.padding(horizontal = segmentPadding), contentAlignment = Alignment.Center) {
                            Text(labels[i], style = style, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        } else {
            Column {
                modes.forEachIndexed { i, mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = theme == mode, role = Role.RadioButton) { onTheme(mode) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == mode, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(labels[i], style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun languageName(lang: AppLanguage): String = when (lang) {
    AppLanguage.System -> stringResource(R.string.marz_language_system)
    // Language names are shown in their own language so users can always find theirs.
    AppLanguage.Russian -> "Русский"
    AppLanguage.English -> "English"
    AppLanguage.Persian -> "فارسی"
    AppLanguage.ChineseSimplified -> "简体中文"
}

@Composable
fun DiagnosticsScreen(
    engineState: EngineState,
    engineError: String?,
    configSource: ConfigSource,
    coreVersion: String,
    sample: SampleKind?,
    onSample: (SampleKind?) -> Unit,
    onEngineTools: () -> Unit,
    apiUrl: String,
    onApiUrl: (String?) -> Unit,
    onBack: () -> Unit,
) {
    var confirmTools by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(apiUrl) }
    MarzScaffold(title = stringResource(R.string.marz_developer_title), onBack = onBack) {
        MarzCard {
            DiagLine(stringResource(R.string.marz_diag_app_version), BuildConfig.VERSION_NAME)
            DiagLine(stringResource(R.string.marz_diag_package), BuildConfig.APPLICATION_ID)
            DiagLine(stringResource(R.string.marz_diag_channel), Links.channel.name)
            DiagLine(stringResource(R.string.marz_diag_core), coreVersion)
            DiagLine(stringResource(R.string.marz_diag_engine_state), engineState.name)
            DiagLine(stringResource(R.string.marz_diag_config_source), configSource.name)
            DiagLine(stringResource(R.string.marz_diag_app_api), apiUrl.ifBlank { stringResource(R.string.marz_diag_app_api_absent) })
            if (engineError != null) DiagLine(stringResource(R.string.marz_diag_last_error), engineError)
        }

        SectionTitle(stringResource(R.string.marz_diag_api_title))
        MarzCard {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("App API URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.marz_diag_api_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BigOutlinedButton(stringResource(R.string.marz_diag_api_save), { onApiUrl(url) })
        }

        SectionTitle(stringResource(R.string.marz_diag_samples_title))
        MarzCard {
            Text(stringResource(R.string.marz_diag_samples_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val options = listOf<SampleKind?>(null) + SampleKind.entries
            options.forEach { kind ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = sample == kind, role = Role.RadioButton) { onSample(kind) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = sample == kind, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (kind) {
                            null -> stringResource(R.string.marz_diag_sample_off)
                            SampleKind.Trial -> stringResource(R.string.marz_trial_title)
                            SampleKind.Basic -> "Basic"
                            SampleKind.Unlimited -> "Unlimited"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        SectionTitle(stringResource(R.string.marz_diag_engineer_title))
        MarzCard {
            Text(stringResource(R.string.marz_diag_engineer_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BigOutlinedButton(stringResource(R.string.marz_diag_engineer_open), { confirmTools = true }, icon = Icons.Rounded.Build)
        }
    }

    if (confirmTools) {
        AlertDialog(
            onDismissRequest = { confirmTools = false },
            title = { Text(stringResource(R.string.marz_diag_engineer_title)) },
            text = { Text(stringResource(R.string.marz_diag_engineer_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTools = false
                    onEngineTools()
                }) { Text(stringResource(R.string.marz_diag_engineer_open)) }
            },
            dismissButton = { TextButton(onClick = { confirmTools = false }) { Text(stringResource(R.string.marz_cancel)) } },
        )
    }
}

@Composable
private fun UpdateSection(update: com.marzvpn.app.update.UpdateUi, onCheck: () -> Unit, onDownload: () -> Unit) {
    val check = stringResource(R.string.marz_update_check)
    val body = MaterialTheme.typography.bodyMedium
    when (update) {
        com.marzvpn.app.update.UpdateUi.Idle -> com.marzvpn.app.ui.BigOutlinedButton(check, onCheck)
        com.marzvpn.app.update.UpdateUi.Checking -> {
            Text(stringResource(R.string.marz_update_checking), style = body)
            com.marzvpn.app.ui.BigOutlinedButton(check, onCheck, enabled = false)
        }
        com.marzvpn.app.update.UpdateUi.Latest -> {
            Text(stringResource(R.string.marz_update_latest), style = body)
            com.marzvpn.app.ui.BigOutlinedButton(check, onCheck)
        }
        is com.marzvpn.app.update.UpdateUi.Available -> {
            Text(stringResource(R.string.marz_update_available, ltr(update.version)), style = MaterialTheme.typography.titleSmall)
            com.marzvpn.app.ui.BigButton(stringResource(R.string.marz_update_download), onDownload)
            com.marzvpn.app.ui.BigOutlinedButton(check, onCheck)
        }
        com.marzvpn.app.update.UpdateUi.Failed -> {
            Text(stringResource(R.string.marz_update_failed), style = body)
            com.marzvpn.app.ui.BigOutlinedButton(stringResource(R.string.marz_update_retry), onCheck)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = false, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (value != null) Text(ltr(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
    }
}
