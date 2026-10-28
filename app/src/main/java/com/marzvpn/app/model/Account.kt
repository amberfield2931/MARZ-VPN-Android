package com.marzvpn.app.model

import java.time.Instant

/** What the UI knows about the user. Real values come only from the App API (not implemented yet). */
sealed interface AccountState {
    /** No session. The preview build is always here unless a sample is shown from Diagnostics. */
    data object SignedOut : AccountState

    data class SignedIn(
        val account: Account,
        val entitlement: Entitlement?,
        /** True only for the Diagnostics UI sample; the UI then shows a "sample, not your data" banner. */
        val isSample: Boolean = false,
        /** App API subscription state: active | limited | expired | suspended | none. */
        val subscriptionState: String = "active",
    ) : AccountState
}

data class Account(
    val id: String,
    val identities: List<Identity>,
    /** The account's VPN profiles and the channel that provisioned each one ("bot" or "site"). */
    val vpnSources: Map<String, String> = emptyMap(),
)

data class Identity(val kind: Kind, val display: String, val id: String = "") {
    enum class Kind { Telegram, Email }
}

/** Mirrors the App API entitlement read model (docs/APP-API.md, GET /subscription). */
sealed interface Entitlement {
    val endsAt: Instant
    val bonusBytes: Long?

    data class Trial(
        override val endsAt: Instant,
        val quotaBytes: Long,
        val usedBytes: Long,
        override val bonusBytes: Long? = null,
    ) : Entitlement

    data class Basic(
        override val endsAt: Instant,
        val quotaBytes: Long,
        val usedBytes: Long,
        override val bonusBytes: Long?,
    ) : Entitlement

    data class Unlimited(
        override val endsAt: Instant,
        override val bonusBytes: Long?,
    ) : Entitlement

    /** Older or special profiles: Marzban's own numbers, without inventing a plan name. */
    data class Other(
        override val endsAt: Instant,
        val quotaBytes: Long?,
        val usedBytes: Long?,
        override val bonusBytes: Long?,
    ) : Entitlement

    /**
     * Reserved for the future ad-rewarded Free access (100 MB or 4 h, whichever comes first).
     * Never produced by this build; the server will be the only source of truth for its end.
     */
    data class FreeAd(
        override val endsAt: Instant,
        val quotaBytes: Long,
        val usedBytes: Long,
    ) : Entitlement {
        override val bonusBytes: Long? get() = null
    }
}

enum class PlanCode { Basic, Unlimited }

/** Public offer shown before sign-in. The App API (GET /catalog) will replace this built-in copy. */
data class PlanOffer(
    val code: PlanCode,
    val priceUsd: String,
    val periodDays: Int,
    /** null = no traffic limit. */
    val quotaBytes: Long?,
)

data class TrialOffer(val days: Int, val quotaBytes: Long)

object Catalog {
    const val GIB = 1024L * 1024 * 1024
    val plans = listOf(
        PlanOffer(PlanCode.Basic, "2", 30, 100 * GIB),
        PlanOffer(PlanCode.Unlimited, "4", 30, null),
    )
    val trial = TrialOffer(days = 3, quotaBytes = 3 * GIB)
}
