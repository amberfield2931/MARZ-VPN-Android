package com.marzvpn.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marzvpn.app.TrafficState
import com.marzvpn.app.api.CheckoutAction
import com.marzvpn.app.model.AccountState
import com.marzvpn.app.model.Entitlement
import com.marzvpn.app.model.Server
import com.marzvpn.app.model.ServerChoice
import com.marzvpn.app.ui.BigButton
import com.marzvpn.app.ui.BigOutlinedButton
import com.marzvpn.app.ui.LimeButton
import com.marzvpn.app.ui.MarzCard
import com.marzvpn.app.ui.MarzScaffold
import com.marzvpn.app.ui.PreviewNotice
import com.marzvpn.app.ui.ScreenColumn
import com.marzvpn.app.ui.currentLocale
import com.marzvpn.app.ui.formatBytes
import com.marzvpn.app.ui.formatDate
import com.marzvpn.app.ui.theme.LocalStatusColors
import com.marzvpn.app.vpn.ConfigSource
import com.marzvpn.app.vpn.EngineState
import androidx.compose.ui.platform.LocalContext
import com.marzvpn.app.Links
import com.marzvpn.app.model.RouteNames
import androidx.compose.material.icons.automirrored.rounded.Send
import com.marzvpn.app.ui.ltr
import io.nekohasekai.sfa.R

@Composable
fun HomeScreen(
    account: AccountState,
    engineState: EngineState,
    engineError: String?,
    configSource: ConfigSource,
    servers: List<Server>,
    serverChoice: ServerChoice,
    onToggle: () -> Unit,
    syncError: String? = null,
    activeServerId: String? = null,
    routeMissing: Boolean = false,
    traffic: TrafficState = TrafficState.Unknown,
    liveMs: Int? = null,
    onServers: () -> Unit,
    /** Opens the plans for this action (renew, change plan or buy) of the account's own profile. */
    onPlans: (CheckoutAction) -> Unit,
    onLogin: () -> Unit,
    onSettings: () -> Unit,
    /** A newer published version to mention, or null. */
    updateVersion: String? = null,
    onUpdateDownload: () -> Unit = {},
    onUpdateLater: () -> Unit = {},
) {
    MarzScaffold(
        title = null,
        onBack = null,
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.marz_settings_title))
            }
        },
        rawContent = {
            val sample = account is AccountState.SignedIn && account.isSample
            val config = LocalConfiguration.current
            if (config.screenWidthDp > config.screenHeightDp && config.screenWidthDp >= 600) {
                // Landscape: button on one side, details on the other, each scrolling on its own.
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        ScreenColumn(horizontalAlignment = Alignment.CenterHorizontally) {
                            ConnectSection(account, engineState, traffic, engineError, configSource, onToggle, buttonSize = 150.dp)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        ScreenColumn {
                            if (updateVersion != null) UpdateNotice(updateVersion, onUpdateDownload, onUpdateLater)
                            if (sample) PreviewNotice(stringResource(R.string.marz_sample_banner))
                            SyncNotice(syncError, sample, onLogin)
                            ServerRow(servers, serverChoice, engineState == EngineState.Connected, activeServerId, routeMissing, liveMs, onServers)
                            SubscriptionCard(account, onPlans, onLogin)
                            BonusCard(account)
                        }
                    }
                }
            } else {
                ScreenColumn {
                    if (updateVersion != null) UpdateNotice(updateVersion, onUpdateDownload, onUpdateLater)
                    if (sample) PreviewNotice(stringResource(R.string.marz_sample_banner))
                    SyncNotice(syncError, sample, onLogin)
                    ConnectSection(account, engineState, traffic, engineError, configSource, onToggle)
                    ServerRow(servers, serverChoice, engineState == EngineState.Connected, activeServerId, routeMissing, liveMs, onServers)
                    SubscriptionCard(account, onPlans, onLogin)
                    BonusCard(account)
                }
            }
        },
    )
}

@Composable
private fun UpdateNotice(version: String, onDownload: () -> Unit, onLater: () -> Unit) {
    MarzCard {
        Text(stringResource(R.string.marz_update_available, ltr(version)), style = MaterialTheme.typography.titleSmall)
        BigButton(stringResource(R.string.marz_update_download), onDownload)
        BigOutlinedButton(stringResource(R.string.marz_update_later), onLater)
    }
}

@Composable
private fun ConnectSection(
    account: AccountState,
    state: EngineState,
    traffic: TrafficState,
    error: String?,
    source: ConfigSource,
    onToggle: () -> Unit,
    buttonSize: androidx.compose.ui.unit.Dp = 196.dp,
) {
    val colors = LocalStatusColors.current
    val canConnect = source != ConfigSource.None || state != EngineState.Disconnected
    val connected = state == EngineState.Connected
    // The service runs, but checks through the tunnel confirmed that no data gets through. The indicator stays on:
    // the user left the VPN on and it comes back by itself. Only the text below says what is happening.
    val noData = connected && traffic == TrafficState.Lost
    val busy = state == EngineState.Connecting || state == EngineState.Disconnecting || state == EngineState.Unknown
    val indicator = indicatorColor(canConnect, state, traffic)
    val container by animateColorAsState(
        when (indicator) {
            IndicatorColor.Unavailable -> MaterialTheme.colorScheme.surfaceVariant
            IndicatorColor.Connected -> colors.connected
            IndicatorColor.Idle -> colors.idle
        },
        label = "connect-color",
    )
    val content = when (indicator) {
        IndicatorColor.Unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        IndicatorColor.Connected -> colors.onConnected
        IndicatorColor.Idle -> colors.onIdle
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(1f, 0.55f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse-alpha")

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val label = stringResource(
            when (state) {
                EngineState.Unknown -> R.string.marz_status_checking
                EngineState.Disconnected -> R.string.marz_status_disconnected
                EngineState.Connecting -> R.string.marz_status_connecting
                EngineState.Connected -> when (traffic) {
                    TrafficState.Lost -> R.string.marz_status_no_traffic
                    TrafficState.NoNetwork -> R.string.marz_status_no_network
                    else -> R.string.marz_status_connected
                }
                EngineState.Disconnecting -> R.string.marz_status_disconnecting
            },
        )
        Box(
            Modifier
                .size(buttonSize)
                .border(10.dp, container.copy(alpha = 0.25f), CircleShape)
                .padding(16.dp)
                .clip(CircleShape)
                .clickable(enabled = canConnect && !busy, role = Role.Button, onClickLabel = label, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(buttonSize - 32.dp).alpha(if (busy) pulseAlpha else 1f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (canConnect) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Lock,
                        contentDescription = label,
                        modifier = Modifier.size(buttonSize * 0.37f),
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        val hint = when {
            error != null -> stringResource(R.string.marz_connect_error, error)
            connected && traffic == TrafficState.Lost -> stringResource(R.string.marz_no_traffic_hint)
            connected && traffic == TrafficState.NoNetwork -> stringResource(R.string.marz_no_network_hint)
            source == ConfigSource.None && account is AccountState.SignedIn -> stringResource(R.string.marz_connect_no_plan)
            source == ConfigSource.None -> stringResource(R.string.marz_connect_need_login)
            source == ConfigSource.EngineerProfile -> stringResource(R.string.marz_connect_engineer_profile)
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null || noData) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The saved choice (what the user picked) and, while connected, the route the engine really uses
 * (from sing-box, not from the saved setting). A saved route the account no longer offers is
 * reported as such, never shown as Auto.
 */
@Composable
private fun ServerRow(servers: List<Server>, choice: ServerChoice, connected: Boolean, activeId: String?, routeMissing: Boolean, liveMs: Int?, onClick: () -> Unit) {
    val locale = currentLocale()
    val context = LocalContext.current
    val chosenId = (choice as? ServerChoice.Specific)?.id
    val chosen = chosenId?.let { id -> servers.firstOrNull { it.id == id } }
    val active = activeId?.let { id -> servers.firstOrNull { it.id == id } }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (routeMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(chosen?.flag ?: if (chosenId != null) "⚠️" else "⚡", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.marz_server_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        chosen != null -> RouteNames.full(context, chosen, servers, locale)
                        chosenId != null -> stringResource(R.string.marz_route_missing_title)
                        else -> stringResource(R.string.marz_server_auto)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                when {
                    routeMissing || (chosenId != null && chosen == null) -> Text(
                        stringResource(R.string.marz_route_missing_text),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                    connected && active != null && (chosenId == null || active.id != chosenId) -> Text(
                        RouteNames.full(context, active, servers, locale) +
                            (liveMs?.let { " · " + ltr(stringResource(R.string.marz_latency_ms, it)) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    connected && liveMs != null -> Text(
                        ltr(stringResource(R.string.marz_latency_ms, liveMs)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/**
 * Account data could not be updated. "reauth_required": a token rotation may have happened and could
 * not be recovered, so the user signs in again; the account view and the VPN stay meanwhile.
 */
@Composable
private fun SyncNotice(syncError: String?, sample: Boolean, onLogin: () -> Unit) {
    if (syncError == null || sample) return
    if (syncError == "reauth_required") {
        MarzCard {
            Text(stringResource(R.string.marz_reauth_text), style = MaterialTheme.typography.bodyLarge)
            BigButton(stringResource(R.string.marz_login), onLogin)
        }
    } else {
        PreviewNotice(stringResource(R.string.marz_sync_error))
    }
}

@Composable
private fun SubscriptionCard(account: AccountState, onPlans: (CheckoutAction) -> Unit, onLogin: () -> Unit) {
    val locale = currentLocale()
    MarzCard {
        Text(stringResource(R.string.marz_subscription_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (account) {
            AccountState.SignedOut -> {
                Text(stringResource(R.string.marz_subscription_signed_out), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.marz_subscription_signed_out_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BigButton(stringResource(R.string.marz_login), onLogin)
                BigOutlinedButton(stringResource(R.string.marz_buy_subscription), { onPlans(CheckoutAction.Buy) })
            }
            is AccountState.SignedIn -> when (val e = account.entitlement) {
                null -> NoSubscription(onPlans)
                else -> if (account.subscriptionState != "active") InactiveSubscription(e, account.subscriptionState, locale, onPlans) else when (e) {
                is Entitlement.Trial -> {
                    Text(stringResource(R.string.marz_trial_title), style = MaterialTheme.typography.titleLarge)
                    Expiry(e, locale)
                    Traffic(e.quotaBytes, e.usedBytes, locale)
                    LimeButton(stringResource(R.string.marz_buy_subscription), { onPlans(CheckoutAction.Buy) })
                }
                is Entitlement.Basic -> {
                    Text("Basic", style = MaterialTheme.typography.titleLarge)
                    Expiry(e, locale)
                    Traffic(e.quotaBytes, e.usedBytes, locale)
                    RenewButtons(onPlans)
                }
                is Entitlement.Unlimited -> {
                    Text("Unlimited", style = MaterialTheme.typography.titleLarge)
                    Expiry(e, locale)
                    Text(stringResource(R.string.marz_plan_no_traffic_limit), style = MaterialTheme.typography.bodyLarge)
                    RenewButtons(onPlans)
                }
                is Entitlement.Other -> {
                    Text(stringResource(R.string.marz_subscription_active), style = MaterialTheme.typography.titleLarge)
                    Expiry(e, locale)
                    val quota = e.quotaBytes
                    val used = e.usedBytes
                    if (quota != null && used != null) Traffic(quota, used, locale)
                    RenewButtons(onPlans)
                }
                is Entitlement.FreeAd -> {
                    // Reserved for the future Free programme; never produced by this build.
                    Text(stringResource(R.string.marz_subscription_none), style = MaterialTheme.typography.titleLarge)
                }
            }
            }
        }
    }
}

@Composable
private fun NoSubscription(onPlans: (CheckoutAction) -> Unit) {
    Text(stringResource(R.string.marz_subscription_none), style = MaterialTheme.typography.titleLarge)
    LimeButton(stringResource(R.string.marz_buy_subscription), { onPlans(CheckoutAction.Buy) })
}

/** Expired, traffic used up or suspended: never shown as active; offer to renew or buy. */
@Composable
private fun InactiveSubscription(e: Entitlement, state: String, locale: java.util.Locale, onPlans: (CheckoutAction) -> Unit) {
    Text(
        stringResource(
            when (state) {
                "limited" -> R.string.marz_subscription_limited
                "suspended" -> R.string.marz_subscription_suspended
                else -> R.string.marz_subscription_expired
            },
        ),
        style = MaterialTheme.typography.titleLarge,
    )
    if (state == "expired") {
        Text(stringResource(R.string.marz_subscription_ended, formatDate(e.endsAt, locale)), style = MaterialTheme.typography.bodyLarge)
    }
    if (state == "suspended") {
        Text(stringResource(R.string.marz_subscription_suspended_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        // An ended Trial is followed by a first paid plan on the same profile; a paid plan is renewed.
        if (e is Entitlement.Trial) LimeButton(stringResource(R.string.marz_buy_subscription), { onPlans(CheckoutAction.Buy) })
        else LimeButton(stringResource(R.string.marz_renew), { onPlans(CheckoutAction.Renew) })
    }
}

@Composable
private fun Expiry(e: Entitlement, locale: java.util.Locale) {
    Text(stringResource(R.string.marz_subscription_until, formatDate(e.endsAt, locale)), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Traffic(quota: Long, used: Long, locale: java.util.Locale) {
    val remaining = (quota - used).coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.marz_traffic_remaining, formatBytes(remaining, locale), formatBytes(quota, locale)),
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(
            progress = { if (quota > 0) remaining.toFloat() / quota else 0f },
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small),
        )
    }
}

@Composable
private fun RenewButtons(onPlans: (CheckoutAction) -> Unit) {
    // Stacked: labels stay on one line in every language and on narrow screens.
    BigButton(stringResource(R.string.marz_renew), { onPlans(CheckoutAction.Renew) })
    BigOutlinedButton(stringResource(R.string.marz_change_plan), { onPlans(CheckoutAction.Change) })
}

@Composable
private fun BonusCard(account: AccountState) {
    val locale = currentLocale()
    val context = LocalContext.current
    MarzCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CardGiftcard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.marz_bonus_title), style = MaterialTheme.typography.titleMedium)
        }
        val bonus = (account as? AccountState.SignedIn)?.entitlement?.bonusBytes
        if (account is AccountState.SignedIn && bonus != null) {
            Text(stringResource(R.string.marz_bonus_balance, formatBytes(bonus, locale)), style = MaterialTheme.typography.bodyLarge)
        }
        // Referral links and earned bonuses live in the bot until the App API offers them. The rule shown is the
        // bot's: +7 days and +25 GiB for every confirmed payment of an invited friend.
        Text(stringResource(R.string.marz_bonus_text, formatBytes(25 * com.marzvpn.app.model.Catalog.GIB, locale)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigOutlinedButton(stringResource(R.string.marz_referral_open_bot), { Links.open(context, Links.BOT) }, icon = Icons.AutoMirrored.Rounded.Send)
        Text(stringResource(R.string.marz_referral_bot_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
