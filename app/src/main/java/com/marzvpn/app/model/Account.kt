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

    /**
     * Pro (kind "pro"): 300 GB at full speed per 30-day package, then a reduced speed. [status] is the
     * server's package view; null when it is missing or not consistent (the UI says it is being checked).
     */
    data class Pro(
        override val endsAt: Instant,
        override val bonusBytes: Long?,
        val status: ProStatus?,
    ) : Entitlement

    /**
     * Paid rights bought before Pro (kind "unlimited"): no data limit until [endsAt]. The previous plan's
     * name is never shown and it is never offered again.
     */
    data class PaidNoLimit(
        override val endsAt: Instant,
        override val bonusBytes: Long?,
    ) : Entitlement

    /** A paid plan whose details the server is still checking (kind "subscription", tier "verifying"). */
    data class PaidChecking(
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

/** Pro package state from the App API (GET /subscription, entitlement.pro). Display only. */
data class ProStatus(
    val usedBytes: Long,
    val limitBytes: Long,
    val remainingFullSpeedBytes: Long,
    val limited: Boolean,
    /** The speed after the full-speed package is used (bit/s); set while [limited]. */
    val limitedSpeedBps: Long?,
    val nextPackageAt: Instant?,
    val planEnd: Instant,
    /** The controller's numbers are older than it allows: shown as "checking". */
    val stale: Boolean,
)
