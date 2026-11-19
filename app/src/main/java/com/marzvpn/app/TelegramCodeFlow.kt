package com.marzvpn.app

import com.marzvpn.app.api.ApiResult
import com.marzvpn.app.api.AppApi
import com.marzvpn.app.api.CodeAnswer
import com.marzvpn.app.api.LoginProgress
import com.marzvpn.app.api.TelegramCodeRules
import com.marzvpn.app.api.TelegramLogin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The code ceremony of the Telegram sign-in on screen ([TelegramUi.Code]). The /confirm poll runs the
 * whole time (the code may also be typed on the marzvpn.com page); a code typed in the app is sent in
 * between polls, never at the same time, so exactly one of them completes the sign-in. The request
 * itself is kept by the [AppApi] (encrypted), so it survives app switching and process death.
 */
class TelegramCodeFlow(
    private val api: AppApi,
    private val ui: MutableStateFlow<TelegramUi>,
    private val nowSeconds: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val signedIn: suspend () -> Unit,
) {
    private val mutex = Mutex()

    /** The request this flow is following; null after it ended (signed in, failed, cancelled). */
    @Volatile private var active: TelegramLogin? = null

    /** Stops following the request (Cancel/Back). Results arriving later are ignored. */
    fun stop() {
        active = null
    }

    private fun edit(login: TelegramLogin, change: (TelegramUi.Code) -> TelegramUi.Code) {
        if (active !== login) return
        ui.update { if (it is TelegramUi.Code) change(it) else it }
    }

    private suspend fun finish(login: TelegramLogin) {
        if (active !== login) return
        active = null
        ui.value = TelegramUi.Idle
        signedIn()
    }

    private fun fail(login: TelegramLogin, code: String) {
        if (active !== login) return
        active = null
        ui.value = TelegramUi.Failed(code)
    }

    /** Shows the request and polls /confirm until signed in, ended or expired. */
    suspend fun await(login: TelegramLogin) {
        active = login
        ui.value = TelegramUi.Code(login.telegramUrl, login.ref, login.expiresAtEpochSeconds, login.codeTtlSeconds)
        while (nowSeconds() < login.expiresAtEpochSeconds) {
            val r = mutex.withLock {
                if (active !== login) return
                api.pollTelegramLogin()
            }
            if (active !== login) return
            when (r) {
                is ApiResult.Ok -> if (r.value.state == LoginProgress.Done) {
                    finish(login)
                    return
                } else {
                    edit(login) { it.copy(progress = r.value.progress ?: it.progress, attemptsLeft = r.value.attemptsLeft ?: it.attemptsLeft) }
                }
                // No connection, busy or overloaded server: the request is kept, polling goes on.
                is ApiResult.Error -> if (!TelegramCodeRules.transientPoll(r.httpStatus) && r.code != "network") {
                    fail(login, r.code)
                    return
                }
                ApiResult.NotAvailable -> return
            }
            sleep(login.pollIntervalSeconds * 1_000L)
        }
        mutex.withLock {
            if (active !== login) return
            api.cancelTelegramLogin()
            fail(login, "login_expired")
        }
    }

    /** One code typed in the app. Ignored while one is being checked or when no request is followed. */
    suspend fun enter(code: String) {
        val login = active ?: return
        val shown = ui.value as? TelegramUi.Code ?: return
        if (shown.busy || shown.approved) return
        val digits = code.filter { it in '0'..'9' }
        if (digits.length != 6) {
            edit(login) { it.copy(error = "invalid_code_format", wrongAttemptsLeft = null) }
            return
        }
        edit(login) { it.copy(busy = true, error = null, wrongAttemptsLeft = null) }
        val r = mutex.withLock {
            if (active !== login) return
            api.enterTelegramCode(digits)
        }
        if (active !== login) return
        when (r) {
            is ApiResult.Ok -> when (val answer = r.value) {
                CodeAnswer.SignedIn -> finish(login)
                // Approved on the page first: the next poll signs in.
                CodeAnswer.KeepWaiting -> edit(login) { it.copy(busy = false, approved = true) }
                is CodeAnswer.Wrong -> edit(login) {
                    it.copy(busy = false, error = "invalid_code", wrongAttemptsLeft = answer.attemptsLeft, attemptsLeft = answer.attemptsLeft ?: it.attemptsLeft)
                }
            }
            is ApiResult.Error -> if (TelegramCodeRules.endsLogin(r.code)) {
                fail(login, r.code)
            } else {
                // The request goes on (expired code, code not requested yet, no connection…): the user may retry.
                edit(login) { it.copy(busy = false, error = r.code) }
            }
            ApiResult.NotAvailable -> edit(login) { it.copy(busy = false) }
        }
    }
}
