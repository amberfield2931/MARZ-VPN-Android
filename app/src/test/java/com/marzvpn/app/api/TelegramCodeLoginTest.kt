package com.marzvpn.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** docs/app/TELEGRAM-CODE-LOGIN.md: start answers, the pending record, /confirm 202 and /code errors. */
class TelegramCodeLoginTest {
    private val now = 1_800_000_000L
    private val verifier = "v".repeat(64)

    private val codeAnswer = mapOf<String, Any?>(
        "login_id" to "L".repeat(32), "flow" to "code", "ref" to "K7QP-9M2X",
        "verification_url" to "https://marzvpn.com/ru/device/#c=claimclaimclaimclaimclaimclaimclaim",
        "telegram_url" to "https://t.me/MarzReality_bot?start=login_K7QP9M2X", "bot_username" to "MarzReality_bot",
        "expires_in" to 600, "code_ttl" to 300, "poll_interval" to 3,
    )
    private val classicAnswer = mapOf<String, Any?>(
        "login_id" to "L".repeat(32), "telegram_url" to "https://t.me/MarzReality_bot?start=applogin_" + "L".repeat(32),
        "match_code" to "42", "expires_in" to 300, "poll_interval" to 2,
    )

    @Test fun codeAnswerIsKeptWithEverythingNeededToResume() {
        val record = TelegramLoginRecord.fromStart(codeAnswer::get, verifier, now)!!
        assertEquals("code", record["flow"])
        assertEquals(now + 600, record["expires_at"])
        assertEquals(verifier, record["verifier"])
        assertEquals("K7QP-9M2X", record["ref"])
        assertEquals(codeAnswer["verification_url"], record["verification_url"])
        assertEquals(300L, record["code_ttl"])
        assertFalse("no number to match in the code ceremony", record.containsKey("match_code"))
        val login = TelegramLoginRecord.login(record::get)!!
        assertEquals(TelegramLogin.Flow.Code, login.flow)
        assertEquals(codeAnswer["telegram_url"], login.telegramUrl)
        assertEquals("K7QP-9M2X", login.ref)
        assertEquals(300, login.codeTtlSeconds)
        assertEquals(3, login.pollIntervalSeconds)
        assertEquals(now + 600, login.expiresAtEpochSeconds)
    }

    @Test fun noVerificationUrlFallsBackToTheClassicRecordExactlyAs103() {
        val record = TelegramLoginRecord.fromStart(classicAnswer::get, verifier, now)!!
        // The same five fields 1.0.3 wrote, nothing else (a downgrade to 1.0.3 still reads it).
        assertEquals(setOf("login_id", "verifier", "expires_at", "telegram_url", "match_code"), record.keys)
        assertEquals(now + 300, record["expires_at"])
        val login = TelegramLoginRecord.login(record::get)!!
        assertEquals(TelegramLogin(classicAnswer["telegram_url"] as String, "42", now + 300), login)
        assertEquals(TelegramLogin.Flow.Match, login.flow)
        // An older server that ignores flow/locale: also classic, even when it echoes nothing else.
        val older = classicAnswer - "expires_in" - "poll_interval"
        assertEquals(now + 300, TelegramLoginRecord.fromStart(older::get, verifier, now)!!["expires_at"])
    }

    @Test fun pendingRecordWrittenBy103StillResumesAfterTheUpdate() {
        // As stored by 1.0.3 (JSON numbers come back as Integer or Long).
        val old = mapOf<String, Any?>(
            "login_id" to "abc", "verifier" to verifier, "expires_at" to (now + 120).toInt(),
            "telegram_url" to "https://t.me/MarzReality_bot?start=applogin_abc", "match_code" to "07",
        )
        val login = TelegramLoginRecord.login(old::get)!!
        assertEquals(TelegramLogin.Flow.Match, login.flow)
        assertEquals("07", login.matchCode)
        assertEquals(now + 120, login.expiresAtEpochSeconds)
        // Incomplete records are not resumed (as before).
        assertNull(TelegramLoginRecord.login((old - "match_code")::get))
        assertNull(TelegramLoginRecord.login((old - "telegram_url")::get))
    }

    @Test fun storageNamesAndKeyAliasesAreUnchangedSoTheSessionSurvivesTheUpdate() {
        assertEquals("tokens", SecureStore.TOKENS)
        assertEquals("pending_telegram", SecureStore.PENDING_TELEGRAM)
        assertEquals("refresh_cancels", SecureStore.REFRESH_CANCELS)
        assertEquals("cache_account", SecureStore.CACHE_ACCOUNT)
        assertEquals("cache_subscription", SecureStore.CACHE_SUBSCRIPTION)
        val source = File("src/main/java/com/marzvpn/app/api/SecureStore.kt").readText()
        assertTrue(source.contains("private const val ALIAS = \"marz_device_key_v1\""))
        assertTrue(source.contains("private const val ALIAS = \"marz_tokens_v1\""))
        assertTrue(source.contains("getSharedPreferences(\"marz_secure\""))
    }

    @Test fun unusableOrUnsafeStartAnswersAreRefused() {
        assertNull(TelegramLoginRecord.fromStart((codeAnswer - "login_id")::get, verifier, now))
        assertNull(TelegramLoginRecord.fromStart((codeAnswer + ("telegram_url" to "tg://resolve?domain=x"))::get, verifier, now))
        assertNull(TelegramLoginRecord.fromStart((codeAnswer + ("telegram_url" to "https://t.me.evil.test/x"))::get, verifier, now))
        assertNull(TelegramLoginRecord.fromStart((codeAnswer + ("verification_url" to "http://marzvpn.com/device/"))::get, verifier, now))
        assertNull(TelegramLoginRecord.fromStart((codeAnswer + ("ref" to "<script>"))::get, verifier, now))
        assertNull(TelegramLoginRecord.fromStart((codeAnswer - "ref")::get, verifier, now))
        // Out-of-range numbers are clamped, not trusted.
        val odd = codeAnswer + ("poll_interval" to 0) + ("expires_in" to 999_999)
        val record = TelegramLoginRecord.fromStart(odd::get, verifier, now)!!
        assertEquals(2L, record["poll_interval"])
        assertEquals(now + 3_600, record["expires_at"])
    }

    @Test fun confirm202CarriesProgressAndAttemptsWhenPresent() {
        val p = TelegramLoginRecord.pending(mapOf("state" to "pending", "progress" to "code_sent", "attempts_left" to 4)::get)
        assertEquals(TelegramPoll(LoginProgress.Pending, "code_sent", 4), p)
        // Classic 202 (and progress: null once decided): nothing extra.
        assertEquals(TelegramPoll(LoginProgress.Pending), TelegramLoginRecord.pending(mapOf("state" to "pending", "progress" to null)::get))
    }

    @Test fun codeErrorsMapPerContract() {
        assertEquals(ApiResult.Ok(CodeAnswer.Wrong(3)), TelegramCodeRules.codeAnswer(400, "invalid_code", 3))
        assertEquals(ApiResult.Ok(CodeAnswer.KeepWaiting), TelegramCodeRules.codeAnswer(409, "login_already_approved", null))
        for ((status, code) in listOf(400 to "invalid_code_format", 410 to "code_expired", 409 to "code_not_requested",
            429 to "rate_limited", 0 to "network", 503 to "upstream_unavailable")) {
            assertEquals(ApiResult.Error(code, status), TelegramCodeRules.codeAnswer(status, code, null))
            assertFalse("$code keeps the request", TelegramCodeRules.endsLogin(code))
        }
        for (code in listOf("login_code_attempts", "login_expired", "login_rejected", "login_not_found", "login_already_used", "pilot_closed")) {
            assertTrue("$code ends the request", TelegramCodeRules.endsLogin(code))
        }
    }

    @Test fun onlyConnectionAndServerTroubleKeepPollingACodeRequest() {
        assertTrue(TelegramCodeRules.transientPoll(0))
        assertTrue(TelegramCodeRules.transientPoll(429))
        assertTrue(TelegramCodeRules.transientPoll(502))
        assertFalse(TelegramCodeRules.transientPoll(403))
        assertFalse(TelegramCodeRules.transientPoll(410))
        assertFalse(TelegramCodeRules.transientPoll(404))
    }
}
