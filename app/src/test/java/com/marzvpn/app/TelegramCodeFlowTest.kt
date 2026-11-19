package com.marzvpn.app

import com.marzvpn.app.api.ApiResult
import com.marzvpn.app.api.AppApi
import com.marzvpn.app.api.CodeAnswer
import com.marzvpn.app.api.LoginProgress
import com.marzvpn.app.api.PreviewAppApi
import com.marzvpn.app.api.TelegramLogin
import com.marzvpn.app.api.TelegramPoll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code ceremony on screen: polling, code entry, their serialization and every way it ends. */
class TelegramCodeFlowTest {
    private val pending = ApiResult.Ok(TelegramPoll(LoginProgress.Pending))
    private val done = ApiResult.Ok(TelegramPoll(LoginProgress.Done))

    private class Api : AppApi by PreviewAppApi {
        val polls = ArrayDeque<ApiResult<TelegramPoll>>()
        val codes = ArrayDeque<ApiResult<CodeAnswer>>()
        var pollCount = 0;var codeCount = 0;var cancels = 0
        var inCall = false;var overlapped = false
        var pollGate: CompletableDeferred<Unit>? = null
        var codeGate: CompletableDeferred<Unit>? = null
        val entered = mutableListOf<String>()
        override suspend fun pollTelegramLogin(): ApiResult<TelegramPoll> {
            if (inCall) overlapped = true
            inCall = true
            try {
                pollCount++;pollGate?.await()
                return polls.removeFirstOrNull() ?: ApiResult.Ok(TelegramPoll(LoginProgress.Pending))
            } finally { inCall = false }
        }
        override suspend fun enterTelegramCode(code: String): ApiResult<CodeAnswer> {
            if (inCall) overlapped = true
            inCall = true
            try {
                codeCount++;entered += code;codeGate?.await()
                return codes.removeFirstOrNull() ?: ApiResult.Error("network")
            } finally { inCall = false }
        }
        override fun cancelTelegramLogin() { cancels++ }
    }

    private class Fixture {
        var now = 1_000L;var signIns = 0
        val api = Api()
        val ui = MutableStateFlow<TelegramUi>(TelegramUi.Starting)
        val flow = TelegramCodeFlow(api, ui, { now }, { ms -> now += ms / 1000;yield() }, { signIns++ })
        val login = TelegramLogin("https://t.me/MarzReality_bot?start=login_K7QP9M2X", "", now + 600, TelegramLogin.Flow.Code,
            "K7QP-9M2X", "https://marzvpn.com/device/#c=x", 300, 3)
        val code get() = ui.value as TelegramUi.Code
    }

    @Test fun pollShowsProgressAndSignsInWhenThePageApproves() = runBlocking {
        val f = Fixture()
        f.api.polls += ApiResult.Ok(TelegramPoll(LoginProgress.Pending, "code_sent", 5))
        val job = launch { f.flow.await(f.login) }
        yield()
        assertEquals("code_sent", f.code.progress);assertEquals(5, f.code.attemptsLeft);assertEquals("K7QP-9M2X", f.code.ref)
        f.api.polls += done
        job.join()
        assertEquals(TelegramUi.Idle, f.ui.value);assertEquals(1, f.signIns);assertEquals(0, f.api.cancels)
    }

    @Test fun wrongCodeKeepsTheRequestThenTheRightOneSignsInAndStopsPolling() = runBlocking {
        val f = Fixture()
        val job = launch { f.flow.await(f.login) }
        yield()
        f.api.codes += ApiResult.Ok(CodeAnswer.Wrong(3))
        f.flow.enter("111111")
        assertEquals("invalid_code", f.code.error);assertEquals(3, f.code.wrongAttemptsLeft);assertFalse(f.code.busy)
        f.api.codes += ApiResult.Ok(CodeAnswer.SignedIn)
        f.flow.enter("12 34-56")
        job.join()
        assertEquals(listOf("111111", "123456"), f.api.entered)
        assertEquals(TelegramUi.Idle, f.ui.value);assertEquals(1, f.signIns)
        val polls = f.api.pollCount
        yield()
        assertEquals("no poll after signing in (the pending record is gone)", polls, f.api.pollCount)
        assertFalse(f.api.overlapped)
    }

    @Test fun codeEntryNeverRunsAtTheSameTimeAsAPoll() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<Unit>();f.api.pollGate = gate
        val job = launch { f.flow.await(f.login) }
        yield() // the first poll is in flight
        f.api.codes += ApiResult.Ok(CodeAnswer.SignedIn)
        val entry = async { f.flow.enter("123456") }
        yield();yield()
        assertEquals("the code waits for the poll", 0, f.api.codeCount)
        f.api.pollGate = null;gate.complete(Unit)
        entry.await();job.join()
        assertEquals(1, f.api.codeCount);assertEquals(1, f.signIns);assertFalse(f.api.overlapped)
    }

    @Test fun approvedOnThePageMeansKeepPollingNotAnError() = runBlocking {
        val f = Fixture()
        val job = launch { f.flow.await(f.login) }
        yield()
        f.api.codes += ApiResult.Ok(CodeAnswer.KeepWaiting)
        f.flow.enter("123456")
        assertTrue(f.code.approved);assertEquals(null, f.code.error)
        f.flow.enter("654321") // ignored: already approved
        assertEquals(1, f.api.codeCount)
        f.api.polls += done
        job.join()
        assertEquals(TelegramUi.Idle, f.ui.value);assertEquals(1, f.signIns)
    }

    @Test fun recoverableCodeErrorsKeepTheRequestAndPolling() = runBlocking {
        val f = Fixture()
        val job = launch { f.flow.await(f.login) }
        yield()
        for (e in listOf(ApiResult.Error("code_expired", 410), ApiResult.Error("code_not_requested", 409),
            ApiResult.Error("rate_limited", 429), ApiResult.Error("network"), ApiResult.Error("invalid_code_format", 400))) {
            f.api.codes += e
            f.flow.enter("123456")
            assertEquals(e.code, f.code.error);assertFalse(f.code.busy)
        }
        assertEquals(0, f.api.cancels)
        f.api.polls += done
        job.join()
        assertEquals(1, f.signIns)
    }

    @Test fun tooManyWrongCodesEndTheRequest() = runBlocking {
        val f = Fixture()
        val job = launch { f.flow.await(f.login) }
        yield()
        f.api.codes += ApiResult.Error("login_code_attempts", 403)
        f.flow.enter("999999")
        job.join()
        assertEquals(TelegramUi.Failed("login_code_attempts"), f.ui.value);assertEquals(0, f.signIns)
    }

    @Test fun malformedCodeIsNotSent() = runBlocking {
        val f = Fixture()
        val job = launch { f.flow.await(f.login) }
        yield()
        f.flow.enter("12345")
        assertEquals("invalid_code_format", f.code.error);assertEquals(0, f.api.codeCount)
        f.flow.stop();job.join()
    }

    @Test fun pollSurvivesConnectionAndServerTroubleButNotARejection() = runBlocking {
        val f = Fixture()
        f.api.polls += ApiResult.Error("network");f.api.polls += ApiResult.Error("http_503", 503);f.api.polls += ApiResult.Error("rate_limited", 429)
        f.api.polls += ApiResult.Error("login_rejected", 403)
        f.flow.await(f.login)
        assertEquals(4, f.api.pollCount)
        assertEquals(TelegramUi.Failed("login_rejected"), f.ui.value)
    }

    @Test fun expiryCancelsTheRequest() = runBlocking {
        val f = Fixture()
        f.flow.await(f.login)
        assertEquals(TelegramUi.Failed("login_expired"), f.ui.value);assertEquals(1, f.api.cancels)
        assertEquals(200, f.api.pollCount) // 600 s / 3 s
    }

    @Test fun afterCancelALateAnswerChangesNothing() = runBlocking {
        val f = Fixture()
        val job = launch { f.flow.await(f.login) }
        yield()
        val gate = CompletableDeferred<Unit>();f.api.codeGate = gate
        f.api.codes += ApiResult.Ok(CodeAnswer.Wrong(2))
        val entry = async { f.flow.enter("123456") }
        yield()
        assertTrue(f.code.busy)
        f.flow.stop();f.ui.value = TelegramUi.Idle // what cancelTelegramLogin() does
        gate.complete(Unit);entry.await();job.join()
        assertEquals(TelegramUi.Idle, f.ui.value);assertEquals(0, f.signIns)
    }

    @Test fun aSecondTapWhileACodeIsCheckedIsIgnored() = runBlocking {
        val f = Fixture()
        val job = launch { f.flow.await(f.login) }
        yield()
        val gate = CompletableDeferred<Unit>();f.api.codeGate = gate
        f.api.codes += ApiResult.Ok(CodeAnswer.SignedIn)
        val first = async { f.flow.enter("123456") }
        yield()
        f.flow.enter("123456")
        gate.complete(Unit);first.await();job.join()
        assertEquals(1, f.api.codeCount);assertEquals(1, f.signIns)
    }
}
