package com.marzvpn.app.api

import com.marzvpn.app.model.PlanCode
import java.net.URI

/**
 * Payment for the signed-in client's own VPN profile (docs/app/RELEASE-1-ANDROID-CLAUDE.md):
 * `POST /v1/billing/intent` returns a one-use, 120-second link into the channel that provisioned the
 * profile (marzvpn.com or the Telegram bot). The link is not a VPN key or a sign-in; it is opened
 * once, never stored and never logged. Plain Kotlin (no Android types): unit-tested on the JVM.
 */
enum class CheckoutAction(val wire: String) { Buy("buy"), Renew("renew"), Change("change_plan") }

enum class PaymentChannel(val wire: String) { Website("website"), TelegramBot("telegram_bot") }

data class CheckoutIntent(val channel: PaymentChannel, val url: String, val expiresInSeconds: Int, val operationId: String = "")

/** What the app can tell the user when no payment link was opened. */
enum class CheckoutProblem {
    /** The server has the integrated payment switched off (404): no other checkout is opened instead. */
    FeatureOff,
    SignedOut,
    PilotClosed,
    /** The chosen profile is not (or no longer) this account's: the list is refreshed. */
    ProfileGone,
    /** Several profiles: the user has to pick one. */
    ChooseProfile,
    /** The account has no VPN profile: a renewal or plan change is never turned into a new purchase. */
    NoProfile,
    /** Ownership must be checked by support; nothing is merged automatically. */
    Review,
    /** Payment side temporarily unavailable: try later. */
    Unavailable,
    Network,
    TooMany,
    /** The device clock is too far off for the signed request. */
    Clock,
    /** The 120-second link was not opened in time. */
    Expired,
    Generic,
}

object CheckoutRules {
    const val SITE_HOST = "marzvpn.com"
    const val BOT_HOST = "t.me"
    const val BOT_USERNAME = "MarzReality_bot"
    private val handoff = Regex("[A-Za-z0-9_-]{32}")

    /** App language to the API's locale (en | ru | fa | zh-cn). */
    fun apiLocale(language: String): String = when (language) {
        "ru" -> "ru"
        "fa" -> "fa"
        "zh" -> "zh-cn"
        else -> "en"
    }

    /**
     * Accepts only the two exact links the server builds for this request: the website checkout of the
     * requested plan in the requested language, or the bot's `apppay_` start link. Anything else (another
     * host, path, plan, port, user info or an extra parameter) is refused and nothing is opened.
     */
    fun validate(channel: String, url: String, plan: String, locale: String): PaymentChannel? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1) return null
        return when (channel) {
            "website" -> {
                val prefix = if (locale == "en") "" else "/$locale"
                val fragment = uri.rawFragment ?: return null
                val ok = uri.host == SITE_HOST && uri.rawPath == "$prefix/checkout" && uri.rawQuery == "plan=$plan&app=1" &&
                    fragment.startsWith("app=") && handoff.matches(fragment.removePrefix("app="))
                if (ok) PaymentChannel.Website else null
            }
            "telegram_bot" -> {
                val query = uri.rawQuery ?: return null
                val ok = uri.host == BOT_HOST && uri.rawPath == "/$BOT_USERNAME" && uri.rawFragment == null &&
                    query.startsWith("start=apppay_") && handoff.matches(query.removePrefix("start=apppay_"))
                if (ok) PaymentChannel.TelegramBot else null
            }
            else -> null
        }
    }

    /** Maps the API's stable error codes (and the client's own) to what the user is told. */
    fun problem(code: String, httpStatus: Int): CheckoutProblem = when (code) {
        "not_found" -> CheckoutProblem.FeatureOff
        "signed_out", "unauthorized", "reauth_required" -> CheckoutProblem.SignedOut
        "pilot_closed" -> CheckoutProblem.PilotClosed
        "vpn_identity_not_found" -> CheckoutProblem.ProfileGone
        "select_existing_profile" -> CheckoutProblem.ChooseProfile
        "existing_profile_required" -> CheckoutProblem.NoProfile
        "verified_email_required", "website_referral_requires_bot", "billing_ownership_requires_review", "vpn_ownership_conflict", "unsupported_billing_profile" -> CheckoutProblem.Review
        "website_billing_unavailable", "upstream_unavailable" -> CheckoutProblem.Unavailable
        "device_signature_required", "invalid_device_signature" -> CheckoutProblem.Clock
        "network" -> CheckoutProblem.Network
        "handoff_expired_or_used", "handoff_expired" -> CheckoutProblem.Expired
        else -> when {
            httpStatus == 404 -> CheckoutProblem.FeatureOff // an API without the endpoint
            httpStatus == 429 -> CheckoutProblem.TooMany
            httpStatus >= 500 -> CheckoutProblem.Unavailable
            else -> CheckoutProblem.Generic
        }
    }
}

/** Receipt for ONE actual paid+fulfilled order, never an entitlement delta/browser return. */
data class CheckoutReceipt(val id: String, val profileId: String, val channel: PaymentChannel, val state: String)

object PaymentReceipts {
    private val operation = Regex("bill_[A-Za-z0-9_-]{22}")
    val states = setOf("awaiting_checkout", "pending", "fulfilling", "paid", "failed", "expired", "cancelled", "unavailable")
    fun valid(id: String, profile: String, state: String) = operation.matches(id) && profile.isNotBlank() && state in states
    fun confirms(expected: String, profile: String, receipt: CheckoutReceipt?) = receipt != null &&
        valid(receipt.id, receipt.profileId, receipt.state) && receipt.id == expected && receipt.profileId == profile && receipt.state == "paid"
}

/**
 * What an attempt's server state allows on the plans screen. Returning from the browser proves nothing,
 * so an unpaid attempt (no order yet, or an order waiting for payment) never locks the plans: the user
 * can go back to paying, pick another plan or check again. Only a paid order that is being credited
 * holds new payments, so the same money is not spent twice.
 */
object CheckoutFlow {
    val terminal = setOf("failed", "expired", "cancelled")

    /** Paid, now being credited: a second payment would only double it. */
    fun blocksNewPayment(state: String) = state == "fulfilling"

    /** Unpaid and still accepted by the server: a fresh one-use link for the same plan can be opened. */
    fun canReturnToPayment(state: String) = state in setOf("awaiting_checkout", "pending", "unavailable")

    /** Nothing more to wait for on this attempt; a new link for the same plan is offered instead. */
    fun canGetNewLink(state: String) = state in terminal

    /**
     * After a restart only an attempt with a real order is shown again (waiting for payment, being
     * credited or paid); a link that was opened but never led to an order is stale local history.
     */
    fun restoreOnLaunch(state: String) = state in setOf("pending", "fulfilling", "paid")

    /** Still worth asking the server about: an earlier attempt can be paid after a new one started. */
    fun stillOpen(state: String) = state !in terminal && state != "paid"
}

/** The plan, action, profile and channel of the last request, repeated for a fresh one-use link. */
data class CheckoutRequest(val plan: PlanCode, val action: CheckoutAction, val profileId: String?, val channel: PaymentChannel)

/**
 * Earlier attempts of this account that may still be paid after the user chose another plan or opened
 * a new link (memory only, a few at most, never persisted). A paid one is credited by the server for
 * its own order and profile; the app only follows it to show the actual receipt.
 */
class EarlierAttempts(private val max: Int = 3) {
    data class Attempt(val id: String, val profileId: String, val channel: PaymentChannel, val state: String)
    private val list = ArrayList<Attempt>()

    val all: List<Attempt> get() = list.toList()
    fun isEmpty() = list.isEmpty()

    fun keep(attempt: Attempt) {
        if (!CheckoutFlow.stillOpen(attempt.state) || attempt.profileId.isBlank()) return
        list.removeAll { it.id == attempt.id }
        list.add(0, attempt)
        while (list.size > max) list.removeAt(list.lastIndex)
    }

    /** A server answer for [id]: kept while still open, dropped when finished or paid. */
    fun update(id: String, state: String) {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        if (CheckoutFlow.stillOpen(state)) list[i] = list[i].copy(state = state) else list.removeAt(i)
    }

    fun drop(id: String) { list.removeAll { it.id == id } }
    fun clear() = list.clear()
}

/** Token refresh answers (plain Kotlin, unit-tested). */
object RefreshRules {
    /** Codes with which the App API ends a session for good (`POST /v1/auth/refresh`). */
    private val ending = setOf("refresh_reused", "invalid_refresh", "pilot_closed")

    /**
     * True only when the server rejected the session itself. HTTP 5xx, 429, timeouts, offline and
     * signature/clock errors keep the session: they say nothing about it, and a later try can succeed.
     */
    fun ends(httpStatus: Int, error: String): Boolean = httpStatus == 401 && error in ending
}
