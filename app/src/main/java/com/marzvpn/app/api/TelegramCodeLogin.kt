package com.marzvpn.app.api

/**
 * Telegram sign-in records and answers (docs/app/TELEGRAM-CODE-LOGIN.md), as plain Kotlin so the rules
 * are unit tested without Android. A field reader `(name) -> value` stands for a JSON object (String,
 * Number or null); the caller maps JSON null to null.
 *
 * Two ceremonies share one encrypted pending record (SecureStore.PENDING_TELEGRAM):
 * - classic number matching: {login_id, verifier, expires_at, telegram_url, match_code} (1.0.3 and older);
 * - code: the same fields without match_code, plus flow="code", ref, verification_url, code_ttl, poll_interval.
 * A record without "flow" is classic, so a request started by 1.0.3 resumes unchanged after the update.
 */
object TelegramLoginRecord {
    const val FLOW_CODE = "code"
    private val REF = Regex("[A-Z0-9-]{4,16}")

    private fun str(get: (String) -> Any?, name: String): String = (get(name) as? String).orEmpty()

    private fun long(get: (String) -> Any?, name: String): Long? = when (val v = get(name)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    /**
     * The fields to keep for a 200 answer to /v1/auth/telegram/start. A `verification_url` means the
     * code ceremony; without it the classic number-matching answer is kept exactly as 1.0.3 kept it.
     * Null: the answer is unusable (nothing is stored, nothing is opened).
     */
    fun fromStart(answer: (String) -> Any?, verifier: String, nowSeconds: Long): Map<String, Any>? {
        val loginId = str(answer, "login_id").ifEmpty { return null }
        val telegramUrl = str(answer, "telegram_url").ifEmpty { return null }
        val verification = str(answer, "verification_url")
        if (verification.isEmpty()) {
            return linkedMapOf(
                "login_id" to loginId, "verifier" to verifier,
                "expires_at" to nowSeconds + (long(answer, "expires_in") ?: 300L),
                "telegram_url" to telegramUrl, "match_code" to str(answer, "match_code"),
            )
        }
        // Code ceremony: the link must be the bot's https link and the page an https page; never another scheme.
        val ref = str(answer, "ref")
        if (!telegramUrl.startsWith("https://t.me/") || !verification.startsWith("https://") || !REF.matches(ref)) return null
        return linkedMapOf(
            "login_id" to loginId, "verifier" to verifier,
            "expires_at" to nowSeconds + (long(answer, "expires_in") ?: 600L).coerceIn(30L, 3_600L),
            "telegram_url" to telegramUrl,
            "flow" to FLOW_CODE, "ref" to ref, "verification_url" to verification,
            "code_ttl" to (long(answer, "code_ttl") ?: 300L).coerceIn(30L, 3_600L),
            "poll_interval" to (long(answer, "poll_interval") ?: 3L).coerceIn(2L, 10L),
        )
    }

    /** The login a stored record describes, or null when it cannot be resumed. */
    fun login(record: (String) -> Any?): TelegramLogin? {
        val url = str(record, "telegram_url").ifEmpty { return null }
        val expires = long(record, "expires_at") ?: 0L
        if (str(record, "flow") == FLOW_CODE) {
            val ref = str(record, "ref").ifEmpty { return null }
            return TelegramLogin(
                url, "", expires, TelegramLogin.Flow.Code, ref, str(record, "verification_url"),
                (long(record, "code_ttl") ?: 300L).toInt(), (long(record, "poll_interval") ?: 3L).toInt().coerceIn(2, 10),
            )
        }
        val code = str(record, "match_code").ifEmpty { return null }
        return TelegramLogin(url, code, expires)
    }

    /** A 202 "pending" answer to /confirm: the optional code-ceremony progress and attempts left. */
    fun pending(answer: (String) -> Any?): TelegramPoll =
        TelegramPoll(LoginProgress.Pending, str(answer, "progress").ifEmpty { null }, long(answer, "attempts_left")?.toInt())
}

/** What the app does with an answer of the code ceremony. */
object TelegramCodeRules {
    /** These end the request on the server: the pending record goes, the user starts again. */
    private val FINAL = setOf(
        "login_code_attempts", "login_expired", "login_rejected", "login_not_found", "login_already_used",
        "invalid_code_verifier", "login_match_failed", "pilot_closed", "telegram_profile_required",
    )

    fun endsLogin(code: String): Boolean = code in FINAL

    /** A failed /confirm poll of a code request that is worth repeating: no connection, busy or overloaded server. */
    fun transientPoll(httpStatus: Int): Boolean = httpStatus == 0 || httpStatus == 429 || httpStatus >= 500

    /**
     * A non-200 answer to POST /v1/auth/telegram/code. Wrong code (with the attempts left) and
     * "already approved" (the page approved first: keep polling) are not errors.
     */
    fun codeAnswer(httpStatus: Int, error: String, attemptsLeft: Int?): ApiResult<CodeAnswer> = when (error) {
        "invalid_code" -> ApiResult.Ok(CodeAnswer.Wrong(attemptsLeft))
        "login_already_approved" -> ApiResult.Ok(CodeAnswer.KeepWaiting)
        else -> ApiResult.Error(error, httpStatus)
    }
}

/** Outcome of a code typed in the app. Errors (expired code, too many attempts, network…) are [ApiResult.Error]. */
sealed interface CodeAnswer {
    /** Tokens are saved: the account is signed in. */
    data object SignedIn : CodeAnswer
    /** Approved elsewhere (the marzvpn.com page): the /confirm poll finishes the sign-in. */
    data object KeepWaiting : CodeAnswer
    data class Wrong(val attemptsLeft: Int?) : CodeAnswer
}

/** One /confirm poll: still pending (with the code ceremony's optional progress), or done. */
data class TelegramPoll(val state: LoginProgress, val progress: String? = null, val attemptsLeft: Int? = null)
