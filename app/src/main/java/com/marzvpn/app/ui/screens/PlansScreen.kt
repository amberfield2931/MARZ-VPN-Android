package com.marzvpn.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.marzvpn.app.CheckoutUi
import com.marzvpn.app.Links
import com.marzvpn.app.api.CheckoutAction
import com.marzvpn.app.api.CheckoutFlow
import com.marzvpn.app.api.CheckoutProblem
import com.marzvpn.app.api.PaymentChannel
import com.marzvpn.app.api.SubscriptionView
import com.marzvpn.app.api.VpnProfile
import com.marzvpn.app.model.Catalog
import com.marzvpn.app.model.Entitlement
import com.marzvpn.app.model.PlanCode
import com.marzvpn.app.ui.BigButton
import com.marzvpn.app.ui.BigOutlinedButton
import com.marzvpn.app.ui.MarzCard
import com.marzvpn.app.ui.MarzScaffold
import com.marzvpn.app.ui.PreviewNotice
import com.marzvpn.app.ui.currentLocale
import com.marzvpn.app.ui.formatDate
import io.nekohasekai.sfa.R

/**
 * Plans. Signed out: the public website checkout, presented as a new purchase. Signed in: payment
 * for one of the account's own profiles through a signed one-use link ([onPay]); with several
 * profiles the user picks one first. A failure never falls back to a guest checkout.
 */
@Composable
fun PlansScreen(
    signedIn: Boolean,
    action: CheckoutAction,
    subscription: SubscriptionView?,
    sources: Map<String, String>,
    checkout: CheckoutUi,
    onPay: (PlanCode, CheckoutAction, String?, PaymentChannel) -> Unit,
    onCheckNow: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {},
    onChooseAnother: () -> Unit = {},
) {
    if (!signedIn) {
        GuestPlans(onBack)
        return
    }
    val profiles = subscription?.profiles.orEmpty()
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    // One profile: that one. Several: only an explicit choice (never guessed).
    val profileId = if (profiles.size > 1) chosen?.takeIf { id -> profiles.any { it.id == id } } else profiles.singleOrNull()?.id
    val profile = profiles.firstOrNull { it.id == profileId }
    val current = planOf(profile?.entitlement ?: subscription?.entitlement?.takeIf { profiles.size <= 1 })
    // Only a running request or a paid order being credited holds the plans; returning unpaid never does.
    val busy = checkout == CheckoutUi.Requesting || (checkout is CheckoutUi.Opened && CheckoutFlow.blocksNewPayment(checkout.state))
    val canPay = Links.webCheckoutAllowed && !busy && (profiles.size <= 1 || profileId != null)

    val title = when (action) {
        CheckoutAction.Renew -> R.string.marz_pay_title_renew
        CheckoutAction.Change -> R.string.marz_pay_title_change
        CheckoutAction.Buy -> R.string.marz_pay_title_buy
    }
    MarzScaffold(title = stringResource(title), onBack = onBack) {
        CheckoutStatus(checkout, onCheckNow = onCheckNow, onDone = onDone, canRetry = canRetry, onRetry = onRetry, onChooseAnother = onChooseAnother)
        Text(stringResource(R.string.marz_pay_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (profiles.size > 1) {
            ProfileChooser(profiles, sources, profileId) { chosen = it }
        }
        // The current plan first when renewing, the other plan first when changing.
        val plans = Catalog.plans.sortedBy { (it.code == current) == (action == CheckoutAction.Change) }
        plans.forEach { plan ->
            val name = if (plan.code == PlanCode.Unlimited) "Unlimited" else "Basic"
            // A paid plan is renewed or changed; a Trial (or no plan) buys its first paid plan on the same profile.
            val planAction = when {
                current == null -> if (profile?.entitlement is Entitlement.Other || (profiles.size <= 1 && subscription?.entitlement is Entitlement.Other)) action else CheckoutAction.Buy
                plan.code == current -> CheckoutAction.Renew
                else -> CheckoutAction.Change
            }
            val label = when (planAction) {
                CheckoutAction.Renew -> stringResource(R.string.marz_pay_renew_plan, name)
                CheckoutAction.Change -> stringResource(R.string.marz_pay_switch_plan, name)
                CheckoutAction.Buy -> stringResource(R.string.marz_plan_choose, name)
            }
            PlanCard(plan, label + " · " + stringResource(R.string.marz_pay_open_site), onBuy = { onPay(plan.code, planAction, profileId, PaymentChannel.Website) }, enabled = canPay, current = plan.code == current)
            if (sources[profileId] == "bot") {
                BigOutlinedButton(label + " · Telegram", { onPay(plan.code, planAction, profileId, PaymentChannel.TelegramBot) }, enabled = canPay)
            }
        }
        Text(
            stringResource(R.string.marz_payment_methods_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun planOf(e: Entitlement?): PlanCode? = when (e) {
    is Entitlement.Basic -> PlanCode.Basic
    is Entitlement.Unlimited -> PlanCode.Unlimited
    else -> null
}

@Composable
private fun ProfileChooser(profiles: List<VpnProfile>, sources: Map<String, String>, selected: String?, onSelect: (String) -> Unit) {
    val locale = currentLocale()
    MarzCard {
        Text(stringResource(R.string.marz_pay_choose_profile), style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
            profiles.forEach { p ->
                val e = p.entitlement
                val plan = when (e) {
                    is Entitlement.Basic -> "Basic"
                    is Entitlement.Unlimited -> "Unlimited"
                    is Entitlement.Trial -> stringResource(R.string.marz_trial_title)
                    null -> stringResource(R.string.marz_pay_profile_none)
                    else -> null // older or special profile: no invented plan name
                }
                // Never "active" for a profile that is not: ended, traffic used up or suspended are said as such.
                val until = when {
                    e == null -> null
                    p.state == "active" -> stringResource(R.string.marz_subscription_until, formatDate(e.endsAt, locale))
                    p.state == "limited" -> stringResource(R.string.marz_subscription_limited)
                    p.state == "suspended" -> stringResource(R.string.marz_subscription_suspended)
                    else -> stringResource(R.string.marz_subscription_ended, formatDate(e.endsAt, locale))
                }
                val source = when (sources[p.id]) {
                    "bot" -> stringResource(R.string.marz_pay_profile_bot)
                    "site" -> stringResource(R.string.marz_pay_profile_site)
                    else -> null
                }
                Row(
                    Modifier.fillMaxWidth().selectable(selected = p.id == selected, onClick = { onSelect(p.id) }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = p.id == selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(listOfNotNull(plan, until, source).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun CheckoutStatus(checkout: CheckoutUi, onCheckNow: () -> Unit, onDone: () -> Unit, canRetry: Boolean, onRetry: () -> Unit, onChooseAnother: () -> Unit) {
    val context = LocalContext.current
    val locale = currentLocale()
    when (checkout) {
        CheckoutUi.Idle -> {}
        CheckoutUi.Requesting -> MarzCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.marz_pay_preparing), style = MaterialTheme.typography.bodyLarge)
            }
        }
        is CheckoutUi.Opened -> MarzCard {
            Text(
                stringResource(if (checkout.channel == PaymentChannel.TelegramBot) R.string.marz_pay_opened_bot else R.string.marz_pay_opened_site),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (checkout.checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
            Text(stringResource(when (checkout.state) {
                "failed", "cancelled" -> R.string.marz_pay_receipt_failed
                "expired" -> R.string.marz_pay_err_expired
                "unavailable" -> R.string.marz_pay_err_unavailable
                "fulfilling" -> R.string.marz_pay_receipt_fulfilling
                "awaiting_checkout" -> R.string.marz_pay_no_order
                else -> R.string.marz_pay_waiting
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (checkout.state == "pending") {
                Text(stringResource(R.string.marz_pay_earlier_kept), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BigOutlinedButton(stringResource(R.string.marz_pay_check_now), onCheckNow, enabled = !checkout.checking)
            // The used link cannot be reopened (one use, the site keeps its session in the closed tab):
            // "return" asks for a fresh link for the same plan; the earlier attempt keeps being followed.
            if (canRetry && CheckoutFlow.canReturnToPayment(checkout.state)) BigButton(stringResource(R.string.marz_pay_return), onRetry)
            if (canRetry && CheckoutFlow.canGetNewLink(checkout.state)) BigButton(stringResource(R.string.marz_pay_new_link), onRetry)
            if (!CheckoutFlow.blocksNewPayment(checkout.state)) BigOutlinedButton(stringResource(R.string.marz_pay_choose_other), onChooseAnother)
        }
        is CheckoutUi.Confirmed -> MarzCard(highlighted = true) {
            Text(stringResource(R.string.marz_pay_confirmed), style = MaterialTheme.typography.titleMedium)
            checkout.entitlement?.let {
                Text(stringResource(R.string.marz_subscription_until, formatDate(it.endsAt, locale)), style = MaterialTheme.typography.bodyLarge)
            }
            BigButton(stringResource(R.string.marz_pay_done), onDone)
        }
        is CheckoutUi.Failed -> MarzCard {
            val p = checkout.problem
            Text(stringResource(problemText(p, checkout.action, checkout.hasProfiles)))
            when {
                p == CheckoutProblem.FeatureOff -> {
                    Text(
                        stringResource(
                            when (checkout.source) {
                                "bot" -> R.string.marz_pay_err_off_bot
                                "site" -> R.string.marz_pay_err_off_site
                                else -> R.string.marz_pay_err_off_unknown
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // The bot knows its customers by Telegram ID; a website profile is renewed from its email link.
                    if (checkout.source == "bot") BotButton()
                }
                p == CheckoutProblem.NoProfile && checkout.action == CheckoutAction.Buy && !checkout.hasProfiles -> {
                    BigOutlinedButton(stringResource(R.string.marz_pay_open_site), { Links.openInBrowserTab(context, Links.siteUrl(locale)) })
                    BotButton()
                }
                p == CheckoutProblem.NoProfile || p == CheckoutProblem.Review ->
                    BigOutlinedButton(stringResource(R.string.marz_pay_contact_support), { Links.email(context) })
                canRetry && p in setOf(CheckoutProblem.Expired, CheckoutProblem.Network, CheckoutProblem.Unavailable) ->
                    BigButton(stringResource(R.string.marz_pay_new_link), onRetry)
                else -> {}
            }
        }
    }
}

@Composable
private fun BotButton() {
    val context = LocalContext.current
    BigOutlinedButton(stringResource(R.string.marz_referral_open_bot), { Links.open(context, Links.BOT) }, icon = Icons.AutoMirrored.Rounded.Send)
}

private fun problemText(p: CheckoutProblem, action: CheckoutAction, hasProfiles: Boolean): Int = when (p) {
    CheckoutProblem.FeatureOff -> R.string.marz_pay_err_off
    CheckoutProblem.SignedOut -> R.string.marz_pay_err_signed_out
    CheckoutProblem.PilotClosed -> R.string.marz_error_pilot_closed
    CheckoutProblem.ProfileGone -> R.string.marz_pay_err_profile_gone
    CheckoutProblem.ChooseProfile -> R.string.marz_pay_err_choose
    CheckoutProblem.NoProfile -> if (action == CheckoutAction.Buy && !hasProfiles) R.string.marz_pay_err_no_profile_first else R.string.marz_pay_err_no_profile
    CheckoutProblem.Review -> R.string.marz_pay_err_review
    CheckoutProblem.Unavailable -> R.string.marz_pay_err_unavailable
    CheckoutProblem.Network -> R.string.marz_pay_err_network
    CheckoutProblem.TooMany -> R.string.marz_error_too_many
    CheckoutProblem.Clock -> R.string.marz_pay_err_clock
    CheckoutProblem.Expired -> R.string.marz_pay_err_expired
    CheckoutProblem.Generic -> R.string.marz_pay_err_generic
}

/** Public Android release uses the existing Telegram purchase path while email admission is off. */
@Composable
private fun GuestPlans(onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = currentLocale()
    var pendingPlan by rememberSaveable { mutableStateOf<PlanCode?>(null) }

    MarzScaffold(title = stringResource(R.string.marz_plans_title), onBack = onBack) {
        Text(stringResource(R.string.marz_plans_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Catalog.plans.forEach { plan ->
            val name = if (plan.code == PlanCode.Unlimited) "Unlimited" else "Basic"
            PlanCard(plan, stringResource(R.string.marz_plan_choose, name), onBuy = { pendingPlan = plan.code })
        }
        TrialCard()
        if (Links.webCheckoutAllowed) {
            BigOutlinedButton(stringResource(R.string.marz_pay_telegram_stars), { Links.open(context, Links.BOT) }, icon = Icons.AutoMirrored.Rounded.Send)
        }
        PreviewNotice(stringResource(R.string.marz_checkout_preview_note))
        Text(
            stringResource(R.string.marz_payment_methods_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingPlan = null },
            title = { Text(stringResource(R.string.marz_checkout_dialog_title)) },
            text = { Text(stringResource(R.string.marz_checkout_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPlan = null
                    Links.openInBrowserTab(context, Links.checkoutUrl(plan, locale))
                }) { Text(stringResource(R.string.marz_checkout_dialog_continue)) }
            },
            dismissButton = { TextButton(onClick = { pendingPlan = null }) { Text(stringResource(R.string.marz_cancel)) } },
        )
    }
}
