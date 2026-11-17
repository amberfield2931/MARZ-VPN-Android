package com.marzvpn.app.api

import com.marzvpn.app.model.Account
import com.marzvpn.app.model.Entitlement
import com.marzvpn.app.model.PlanCode
import com.marzvpn.app.model.Server

/**
 * Client side of the MARZ App API (docs/app/APP-API.md). Kept separate from the UI and from the
 * VPN engine. Tokens and the device key live inside the implementation (Android Keystore).
 */
interface AppApi {
    /** False when the build has no App API configured: every call returns [ApiResult.NotAvailable]. */
    val isAvailable: Boolean

    fun isSignedIn(): Boolean

    suspend fun startTelegramLogin(): ApiResult<TelegramLogin>

    /** The Telegram login started on this device and not finished yet (survives app restarts). */
    fun pendingTelegramLogin(): TelegramLogin?

    /** Polls the pending Telegram login started on this device. */
    suspend fun pollTelegramLogin(): ApiResult<LoginProgress>
    fun cancelTelegramLogin()

    suspend fun startEmailLogin(email: String, locale: String): ApiResult<Unit>
    suspend fun verifyEmailCode(email: String, code: String): ApiResult<Unit>
    /** Add an independently proven email without replacing the current Telegram session. */
    suspend fun startEmailLink(email: String, locale: String): ApiResult<Unit> = ApiResult.NotAvailable
    suspend fun confirmEmailLink(email: String, code: String): ApiResult<Account> = ApiResult.NotAvailable
    suspend fun unlinkEmail(identityId: String): ApiResult<Unit> = ApiResult.NotAvailable
    suspend fun logout(): ApiResult<Unit>

    /** Sends session revocations left over from an offline logout (bounded; nothing when none). */
    suspend fun deliverPendingCancellations()

    suspend fun account(): ApiResult<Account>

    /** Last account + subscription seen online (encrypted at rest), for showing state offline. */
    fun offlineSnapshot(): Pair<Account, SubscriptionView>?
    suspend fun subscription(): ApiResult<SubscriptionView>
    suspend fun servers(): ApiResult<List<Server>>

    /** GET /v1/catalog: public, no sign-in. The raw JSON; the caller validates it. */
    suspend fun catalog(): ApiResult<String> = ApiResult.NotAvailable

    /** A complete sing-box config for the account's active profile (GET /vpn/config). */
    suspend fun vpnConfig(serverId: String?): ApiResult<String>

    /**
     * Signed, one-use payment link for one of the account's own VPN profiles ([vpnIdentity] from
     * [SubscriptionView.profiles]; null only when the account has none, which the API refuses).
     * Only links that pass [CheckoutRules.validate] are returned.
     */
    suspend fun checkoutStatus(id: String? = null): ApiResult<CheckoutReceipt?> = ApiResult.NotAvailable

    suspend fun createCheckoutIntent(plan: PlanCode, action: CheckoutAction, vpnIdentity: String?, locale: String, channel: PaymentChannel? = null): ApiResult<CheckoutIntent>
}

enum class LoginProgress { Pending, Done }

/** [matchCode] is shown in the app; the user picks the same number in the bot (anti-phishing). */
data class TelegramLogin(val telegramUrl: String, val matchCode: String, val expiresAtEpochSeconds: Long)

/** One of the account's VPN profiles (opaque id, never a Marzban username or UUID). */
data class VpnProfile(val id: String, val entitlement: Entitlement?, val state: String, val queued: Int = 0)

data class SubscriptionView(
    val state: String,
    val entitlement: Entitlement?,
    val canConnect: Boolean,
    /** The profile [entitlement] belongs to. */
    val effectiveId: String? = null,
    /** Every profile of the account (usually one). */
    val profiles: List<VpnProfile> = emptyList(),
)

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** [code] is the API's stable error string, or "network" / "signed_out" from the client. */
    data class Error(val code: String, val httpStatus: Int = 0) : ApiResult<Nothing>
    data object NotAvailable : ApiResult<Nothing>
}

/** Builds without an App API URL (release until the service is approved): nothing is faked. */
object PreviewAppApi : AppApi {
    override val isAvailable = false
    override fun isSignedIn() = false
    override suspend fun startTelegramLogin() = ApiResult.NotAvailable
    override fun pendingTelegramLogin(): TelegramLogin? = null
    override suspend fun pollTelegramLogin() = ApiResult.NotAvailable
    override fun cancelTelegramLogin() {}
    override suspend fun startEmailLogin(email: String, locale: String) = ApiResult.NotAvailable
    override suspend fun verifyEmailCode(email: String, code: String) = ApiResult.NotAvailable
    override suspend fun logout() = ApiResult.NotAvailable
    override suspend fun deliverPendingCancellations() {}
    override suspend fun account() = ApiResult.NotAvailable
    override fun offlineSnapshot(): Pair<Account, SubscriptionView>? = null
    override suspend fun subscription() = ApiResult.NotAvailable
    override suspend fun servers() = ApiResult.NotAvailable
    override suspend fun vpnConfig(serverId: String?) = ApiResult.NotAvailable
    override suspend fun createCheckoutIntent(plan: PlanCode, action: CheckoutAction, vpnIdentity: String?, locale: String, channel: PaymentChannel?) = ApiResult.NotAvailable
}
