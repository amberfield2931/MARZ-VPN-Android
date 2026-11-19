package com.marzvpn.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marzvpn.app.EmailUi
import com.marzvpn.app.Links
import com.marzvpn.app.TelegramUi
import com.marzvpn.app.model.Catalog
import com.marzvpn.app.model.PlanFormat
import com.marzvpn.app.model.PlanOffer
import com.marzvpn.app.model.TrialOffer
import com.marzvpn.app.ui.BigButton
import com.marzvpn.app.ui.BigOutlinedButton
import com.marzvpn.app.ui.Bullet
import com.marzvpn.app.ui.LimeButton
import com.marzvpn.app.ui.MarzCard
import com.marzvpn.app.ui.MarzLogo
import com.marzvpn.app.ui.MarzScaffold
import com.marzvpn.app.ui.PreviewBadge
import com.marzvpn.app.ui.PreviewNotice
import com.marzvpn.app.ui.ScreenColumn
import com.marzvpn.app.ui.currentLocale
import com.marzvpn.app.ui.ltr
import com.marzvpn.app.ui.formatBitrate
import com.marzvpn.app.ui.formatBytes
import com.marzvpn.app.ui.formatGb
import com.marzvpn.app.ui.safeArea
import com.marzvpn.app.ui.theme.PriceStyle
import io.nekohasekai.sfa.R
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun WelcomeScreen(
    onTelegram: () -> Unit,
    onEmail: () -> Unit,
    onPlans: () -> Unit,
    onContinue: () -> Unit,
    trial: TrialOffer = Catalog.builtInTrial,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        ScreenColumn(Modifier.safeArea(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            PreviewBadge()
            MarzLogo(88.dp)
            Text("MARZ VPN", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.marz_welcome_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bullet(stringResource(R.string.marz_welcome_point_one_tap))
                Bullet(stringResource(R.string.marz_welcome_point_no_setup))
                Bullet(stringResource(R.string.marz_welcome_point_one_account))
            }
            BigButton(stringResource(R.string.marz_login_telegram), onTelegram, icon = Icons.AutoMirrored.Rounded.Send)
            BigOutlinedButton(stringResource(R.string.marz_login_email), onEmail, icon = Icons.Rounded.Email)
            TrialCard(trial)
            LimeButton(stringResource(R.string.marz_view_plans), onPlans)
            TextButton(onClick = onContinue) { Text(stringResource(R.string.marz_continue_without_login)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun TrialCard(trial: TrialOffer = Catalog.builtInTrial) {
    val locale = currentLocale()
    MarzCard {
        Text(stringResource(R.string.marz_trial_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.marz_trial_terms, trial.days, formatBytes(trial.quotaBytes, locale)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.marz_trial_preview_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The plan's name: "Basic", "Pro", or "Pro 6 months (180 days)" from the server's package count. */
@Composable
fun planName(plan: PlanOffer): String {
    val packages = plan.pro?.packages ?: return "Basic"
    return if (packages <= 1) "Pro" else pluralStringResource(R.plurals.marz_plan_pro_months, packages, packages, plan.periodDays)
}

/**
 * One catalog plan. Every number comes from the catalog: Basic's data in binary units (GiB), Pro's
 * full-speed data in decimal units (GB) and its speed after that from bit/s.
 */
@Composable
fun PlanCard(plan: PlanOffer, buttonText: String, onBuy: () -> Unit, enabled: Boolean = true, current: Boolean = false, catalog: List<PlanOffer> = emptyList()) {
    val locale = currentLocale()
    val pro = plan.pro
    MarzCard(highlighted = pro != null) {
        // The badge sits above the name: a long badge never squeezes the name onto two lines.
        if (current || pro != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary) {
                Text(stringResource(if (current) R.string.marz_pay_your_plan else R.string.marz_plan_pro_badge), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Text(planName(plan), style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(PlanFormat.usd(plan.priceUsd), style = PriceStyle)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.marz_plan_period, plan.periodDays),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (pro == null) {
            plan.quotaBytes?.let { Bullet(stringResource(R.string.marz_plan_traffic, formatBytes(it, locale))) }
            Bullet(stringResource(R.string.marz_plan_all_servers))
            Bullet(stringResource(R.string.marz_plan_basic_bonus))
        } else {
            val fullSpeed = formatGb(pro.fullSpeedBytes, locale)
            val after = formatBitrate(pro.afterFullSpeedBps, locale)
            if (pro.packages <= 1) {
                Bullet(stringResource(R.string.marz_plan_pro_full_speed, fullSpeed, pro.packageDays))
                Bullet(stringResource(R.string.marz_plan_pro_after, after, fullSpeed))
                Bullet(stringResource(R.string.marz_plan_pro_resets, pro.packageDays))
            } else {
                Bullet(stringResource(R.string.marz_plan_pro_packages, pro.packages, pro.packageDays, fullSpeed))
                Bullet(stringResource(R.string.marz_plan_pro_then, after))
            }
            Bullet(stringResource(R.string.marz_plan_all_servers))
            PlanFormat.proSavings(catalog, plan)?.let { saved ->
                Text(
                    stringResource(R.string.marz_plan_pro_save, saved, pro.packages, pro.packageDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.size(2.dp))
        if (Links.webCheckoutAllowed) {
            if (pro != null) LimeButton(buttonText, onBuy, enabled = enabled)
            else BigButton(buttonText, onBuy, enabled = enabled)
        } else {
            BigButton(stringResource(R.string.marz_store_purchase_later), {}, enabled = false)
        }
    }
}

@Composable
fun LoginChooserScreen(onTelegram: () -> Unit, onEmail: () -> Unit, onBack: () -> Unit) {
    MarzScaffold(title = stringResource(R.string.marz_login), onBack = onBack) {
        Text(stringResource(R.string.marz_login_chooser_text), style = MaterialTheme.typography.bodyLarge)
        BigButton(stringResource(R.string.marz_login_telegram), onTelegram, icon = Icons.AutoMirrored.Rounded.Send)
        Text(stringResource(R.string.marz_login_chooser_telegram_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigOutlinedButton(stringResource(R.string.marz_login_email), onEmail, icon = Icons.Rounded.Email)
        Text(stringResource(R.string.marz_login_chooser_email_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TelegramLoginScreen(
    available: Boolean,
    state: TelegramUi,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onOpenTelegram: (String) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onEnterCode: (String) -> Unit = {},
    onEmail: () -> Unit = {},
) {
    // Returning to this screen (from Telegram, or after the app was restarted) continues the same request.
    LaunchedEffect(Unit) { onResume() }
    MarzScaffold(title = stringResource(R.string.marz_login_telegram), onBack = {
        onCancel()
        onBack()
    }) {
        when (state) {
            is TelegramUi.Waiting -> TelegramCodeStep(state, onOpenTelegram, onCancel)
            is TelegramUi.Code -> TelegramCodeEntryStep(state, onOpenTelegram, onEnterCode, onEmail, onCancel)
            else -> {
                // Before the request exists the app does not know which ceremony the server offers:
                // its steps are shown on the next screen.
                Text(stringResource(R.string.marz_login_telegram_intro), style = MaterialTheme.typography.bodyLarge)
                if (state is TelegramUi.Failed) ErrorText(loginError(state.code))
                BigButton(
                    stringResource(if (state is TelegramUi.Failed) R.string.marz_login_telegram_new_code else R.string.marz_login_telegram_get_code),
                    onStart,
                    enabled = available && state !is TelegramUi.Starting,
                )
                if (state is TelegramUi.Starting) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                }
                if (!available) PreviewNotice(stringResource(R.string.marz_login_unavailable_preview))
            }
        }
    }
}

/**
 * Code ceremony: open the bot on this phone, press "Get code", type the 6-digit code here. The typed
 * code survives rotation and process death (saved state); the request itself is kept by the App API
 * client. The poll goes on meanwhile: the code may also be typed on the marzvpn.com page.
 */
@Composable
private fun TelegramCodeEntryStep(
    state: TelegramUi.Code,
    onOpenTelegram: (String) -> Unit,
    onEnterCode: (String) -> Unit,
    onEmail: () -> Unit,
    onCancel: () -> Unit,
) {
    var code by rememberSaveable(state.ref) { mutableStateOf("") }
    // The last code sent by itself at 6 digits: the same code is not sent again without a change.
    var sent by rememberSaveable(state.ref) { mutableStateOf("") }
    val canSend = code.length == 6 && !state.busy && !state.approved
    LaunchedEffect(code, canSend) {
        if (canSend && code != sent) {
            sent = code
            onEnterCode(code)
        }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state.expiresAtEpochSeconds) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val left = (state.expiresAtEpochSeconds - now).coerceAtLeast(0)
    // The bot button and the code field come first: back from Telegram, the field is in view above the keyboard.
    BigButton(stringResource(R.string.marz_login_tgcode_open), { onOpenTelegram(state.telegramUrl) }, icon = Icons.AutoMirrored.Rounded.Send)
    MarzCard(highlighted = true) {
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it in '0'..'9' }.take(6) },
            label = { Text(stringResource(R.string.marz_login_tgcode_code_label)) },
            singleLine = true,
            enabled = !state.approved,
            textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSend) { sent = code; onEnterCode(code) } }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null && !state.approved) ErrorText(telegramCodeError(state.error, state.wrongAttemptsLeft))
        val delivery = state.progress == "delivery_failed" && !state.approved
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!delivery) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                stringResource(
                    when {
                        state.approved -> R.string.marz_login_tgcode_status_approved
                        delivery -> R.string.marz_login_tgcode_status_failed
                        state.progress == "code_sent" -> R.string.marz_login_tgcode_status_sent
                        state.progress == "code_requested" -> R.string.marz_login_tgcode_status_requested
                        else -> R.string.marz_login_tgcode_status_waiting
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (delivery) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (delivery) Text(stringResource(R.string.marz_login_tgcode_bot_unreachable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigButton(stringResource(R.string.marz_login_tgcode_confirm), {
            sent = code
            onEnterCode(code)
        }, enabled = canSend)
        Text(
            stringResource(R.string.marz_login_tgcode_request, ltr(state.ref), ltr(String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60))),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.marz_login_tgcode_valid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    MarzCard {
        Step(1, stringResource(R.string.marz_login_tgcode_step1))
        Step(2, stringResource(R.string.marz_login_tgcode_step2))
        Step(3, stringResource(R.string.marz_login_tgcode_step3))
    }
    Text(stringResource(R.string.marz_login_tgcode_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.marz_login_tgcode_no_telegram), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BigOutlinedButton(stringResource(R.string.marz_login_tgcode_email), onEmail, icon = Icons.Rounded.Email)
    TextButton(onClick = onCancel) { Text(stringResource(R.string.marz_cancel)) }
}

/** Errors of a code typed in the app; never raw codes. */
@Composable
private fun telegramCodeError(code: String, attemptsLeft: Int?): String = when (code) {
    "invalid_code" -> if (attemptsLeft != null) stringResource(R.string.marz_login_tgcode_err_wrong, attemptsLeft)
        else stringResource(R.string.marz_login_tgcode_err_wrong_plain)
    "invalid_code_format" -> stringResource(R.string.marz_login_tgcode_err_format)
    "code_expired" -> stringResource(R.string.marz_login_tgcode_err_expired)
    "code_not_requested" -> stringResource(R.string.marz_login_tgcode_err_not_requested)
    "rate_limited", "too_many_attempts" -> stringResource(R.string.marz_error_too_many)
    "storage_unavailable" -> stringResource(R.string.marz_error_storage)
    "network", "upstream_unavailable", "internal_error", "try_again" -> stringResource(R.string.marz_login_tgcode_err_network)
    else -> if (code.startsWith("http_5")) stringResource(R.string.marz_login_tgcode_err_network) else stringResource(R.string.marz_error_generic)
}

/** The number first, Telegram only on request: the user must be able to read and remember it. */
@Composable
private fun TelegramCodeStep(state: TelegramUi.Waiting, onOpenTelegram: (String) -> Unit, onCancel: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state.expiresAtEpochSeconds) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val left = (state.expiresAtEpochSeconds - now).coerceAtLeast(0)
    Text(stringResource(R.string.marz_login_telegram_remember), style = MaterialTheme.typography.titleMedium)
    MarzCard(highlighted = true) {
        Text(stringResource(R.string.marz_login_telegram_code_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            state.matchCode, // digits are left-to-right in every locale; no isolate marks around them
            style = PriceStyle.copy(fontSize = 80.sp, letterSpacing = 12.sp),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.marz_login_telegram_expires_in, ltr(String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60))),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
    Text(stringResource(R.string.marz_login_telegram_code_hint), style = MaterialTheme.typography.bodyMedium)
    BigButton(stringResource(R.string.marz_login_telegram_open), { onOpenTelegram(state.telegramUrl) }, icon = Icons.AutoMirrored.Rounded.Send)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.marz_login_telegram_waiting), style = MaterialTheme.typography.titleMedium)
    }
    TextButton(onClick = onCancel) { Text(stringResource(R.string.marz_cancel)) }
}

@Composable
fun EmailLoginScreen(
    available: Boolean,
    state: EmailUi,
    onSend: (String) -> Unit,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
    onBack: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    MarzScaffold(title = stringResource(R.string.marz_login_email), onBack = onBack) {
        when (state) {
            is EmailUi.EnterCode -> {
                Text(stringResource(R.string.marz_login_email_code_sent, ltr(state.email)), style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it in '0'..'9' }.take(6) },
                    label = { Text(stringResource(R.string.marz_login_email_code_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.error != null) ErrorText(loginError(state.error, codeEntered = true))
                BigButton(stringResource(R.string.marz_login_email_verify), { onVerify(code) }, enabled = code.length == 6 && !state.busy)
                ResendCode(state, onResend = {
                    code = ""
                    onResend()
                })
                TextButton(onClick = {
                    code = ""
                    onChangeEmail()
                }) { Text(stringResource(R.string.marz_login_email_change)) }
                Text(stringResource(R.string.marz_login_email_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Text(stringResource(R.string.marz_login_email_intro), style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text(stringResource(R.string.marz_email_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state is EmailUi.Failed) ErrorText(loginError(state.code))
                BigButton(
                    stringResource(R.string.marz_login_email_send_code),
                    { onSend(email) },
                    enabled = available && state !is EmailUi.Sending && email.contains('@') && email.length >= 5,
                    icon = Icons.Rounded.Email,
                )
                Text(stringResource(R.string.marz_login_email_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.marz_login_email_no_trial), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!available) PreviewNotice(stringResource(R.string.marz_login_unavailable_preview))
            }
        }
    }
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

/** "New code" on the code step: available a minute after the last request, with the wait shown. */
@Composable
private fun ResendCode(state: EmailUi.EnterCode, onResend: () -> Unit) {
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    val left = ((com.marzvpn.app.EMAIL_RESEND_MS - (now - state.sentAt)) / 1000).coerceAtLeast(0)
    LaunchedEffect(state.sentAt, left > 0) {
        while (android.os.SystemClock.elapsedRealtime() - state.sentAt < com.marzvpn.app.EMAIL_RESEND_MS) {
            delay(1_000)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    if (left > 0) {
        Text(stringResource(R.string.marz_login_email_resend_wait, left.toInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        TextButton(onClick = onResend, enabled = !state.busy) { Text(stringResource(R.string.marz_login_email_resend)) }
    }
}

/** Stable API error codes to user-facing text; never shows raw codes. [codeEntered]: on the code step. */
@Composable
fun loginError(code: String, codeEntered: Boolean = false): String = stringResource(
    when (code) {
        // The code was not used up by a temporary failure: the same code can be sent again.
        "upstream_unavailable", "internal_error" -> if (codeEntered) R.string.marz_error_email_retry_code else R.string.marz_error_network
        "network" -> if (codeEntered) R.string.marz_error_email_retry_code else R.string.marz_error_network
        "email_unavailable", "email_not_configured" -> R.string.marz_error_email_disabled
        "code_expired" -> R.string.marz_error_email_code_expired
        "login_expired" -> R.string.marz_error_expired
        "login_rejected" -> R.string.marz_error_rejected
        "login_match_failed" -> R.string.marz_error_match_failed
        "login_code_attempts" -> R.string.marz_login_tgcode_err_attempts
        "login_already_used" -> R.string.marz_login_tgcode_err_ended
        "pilot_closed" -> R.string.marz_error_pilot_closed
        "email_login_disabled" -> R.string.marz_error_email_disabled
        "storage_unavailable" -> R.string.marz_error_storage
        "invalid_code" -> R.string.marz_error_invalid_code
        "too_many_attempts", "rate_limited" -> R.string.marz_error_too_many
        "invalid_email" -> R.string.marz_error_invalid_email
        else -> R.string.marz_error_generic
    },
)

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(String.format(currentLocale(), "%d", number), style = MaterialTheme.typography.titleSmall)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
