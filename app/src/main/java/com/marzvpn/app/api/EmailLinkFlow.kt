package com.marzvpn.app.api

import com.marzvpn.app.model.Account
import com.marzvpn.app.model.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** In-memory proof state only. Never changes tokens, configuration, route choice or VPN state. */
class EmailLinkFlow(
    private val api: AppApi,
    private val current: () -> Account?,
    private val updated: (Account) -> Unit,
    private val elapsed: () -> Long,
) {
    data class State(val email: String? = null, val busy: Boolean = false, val error: String? = null, val sentAt: Long = 0, val checked: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var pendingAccount: String? = null
    private var generation = 0

    fun reset() { generation++;pendingAccount = null;mutable.value = State() }

    private fun managementAccount(): Account? = current()?.takeIf { a -> a.identities.any { it.kind == Identity.Kind.Telegram } }

    private fun apply(account: Account, original: Account): Boolean {
        if (current()?.id != original.id || account.id != original.id || account.vpnSources != original.vpnSources) return false
        updated(account)
        return true
    }

    private suspend fun run(requireTelegram: Boolean = true, block: suspend (Account, Int) -> Unit) {
        if (!mutex.tryLock()) return
        try {
            val account = if (requireTelegram) managementAccount() else current()
            if (account == null) { mutable.value = State(error = "telegram_reauth_required");return }
            mutable.value = mutable.value.copy(busy = true, error = null, checked = false)
            block(account, generation)
        } finally {
            mutable.value = mutable.value.copy(busy = false)
            mutex.unlock()
        }
    }

    suspend fun send(email: String, locale: String) = run { account, epoch ->
        val value = email.trim().lowercase(java.util.Locale.ROOT)
        if (value.length > 254 || !value.contains('@')) { mutable.value = State(error = "invalid_email");return@run }
        if (mutable.value.email == value && elapsed() - mutable.value.sentAt < 60_000) return@run
        val result = api.startEmailLink(value, locale)
        if (generation != epoch || current()?.id != account.id) return@run
        when (result) {
            is ApiResult.Ok -> { pendingAccount = account.id;mutable.value = State(value, sentAt = elapsed()) }
            is ApiResult.Error -> mutable.value = mutable.value.copy(error = result.code)
            ApiResult.NotAvailable -> mutable.value = State(error = "email_linking_disabled")
        }
    }

    suspend fun confirm(code: String) = run { account, epoch ->
        val email = mutable.value.email ?: return@run
        if (pendingAccount != account.id) { reset();mutable.value = State(error = "account_changed");return@run }
        if (!Regex("[0-9]{6}").matches(code)) { mutable.value = mutable.value.copy(error = "invalid_code");return@run }
        val result = api.confirmEmailLink(email, code)
        if (epoch != generation) return@run
        if (current()?.id != account.id) { mutable.value = mutable.value.copy(error = "account_changed");return@run }
        when (result) {
            is ApiResult.Ok -> {
                val accepted = apply(result.value, account)
                if (epoch == generation) { reset();if (!accepted) mutable.value = State(error = "account_changed") }
            }
            is ApiResult.Error -> if (epoch == generation) mutable.value = mutable.value.copy(error = result.code)
            ApiResult.NotAvailable -> if (epoch == generation) mutable.value = mutable.value.copy(error = "email_linking_disabled")
        }
    }

    suspend fun refresh() = run(requireTelegram = false) { account, epoch ->
        val result = api.account()
        if (epoch != generation) return@run
        if (current()?.id != account.id) { mutable.value = mutable.value.copy(error = "account_changed");return@run }
        when (result) {
            is ApiResult.Ok -> if (apply(result.value, account)) {
                mutable.value = mutable.value.copy(checked = true)
            } else mutable.value = mutable.value.copy(error = "account_changed")
            is ApiResult.Error -> mutable.value = mutable.value.copy(error = result.code)
            ApiResult.NotAvailable -> mutable.value = mutable.value.copy(error = "upstream_unavailable")
        }
    }

    suspend fun unlink(identityId: String) = run { account, epoch ->
        if (account.identities.none { it.kind == Identity.Kind.Email && it.id == identityId && it.id.isNotEmpty() }) {
            mutable.value = mutable.value.copy(error = "identity_not_found");return@run
        }
        val result = api.unlinkEmail(identityId)
        if (epoch != generation) return@run
        if (current()?.id != account.id) { mutable.value = mutable.value.copy(error = "account_changed");return@run }
        when (result) {
            is ApiResult.Ok -> {
                val view = api.account()
                if (epoch != generation) return@run
                if (current()?.id != account.id) { mutable.value = mutable.value.copy(error = "account_changed");return@run }
                when (view) {
                    is ApiResult.Ok -> { if (apply(view.value, account)) reset() else mutable.value = State(error = "account_changed") }
                    is ApiResult.Error -> mutable.value = mutable.value.copy(error = view.code)
                    ApiResult.NotAvailable -> Unit
                }
            }
            is ApiResult.Error -> mutable.value = mutable.value.copy(error = result.code)
            ApiResult.NotAvailable -> mutable.value = mutable.value.copy(error = "email_linking_disabled")
        }
    }
}
