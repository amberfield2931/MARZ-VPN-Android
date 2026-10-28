package com.marzvpn.app.api

import com.marzvpn.app.model.Account
import com.marzvpn.app.model.Identity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class EmailLinkFlowTest {
    private val telegram = Account("owner", listOf(Identity(Identity.Kind.Telegram, "Telegram ***001", "tg")), mapOf("profile" to "bot"))
    private val linked = telegram.copy(identities = telegram.identities + Identity(Identity.Kind.Email, "al***@example.test", "email1"))

    private class Api(var view: Account) : AppApi by PreviewAppApi {
        var starts = 0;var confirms = 0;var unlinks = 0;var reads = 0
        var readGate: CompletableDeferred<Unit>? = null
        var gate: CompletableDeferred<Unit>? = null
        var confirmation: ApiResult<Account> = ApiResult.Ok(view)
        var accountError = false
        override suspend fun startEmailLink(email: String, locale: String): ApiResult<Unit> {
            starts++;gate?.await();return ApiResult.Ok(Unit)
        }
        override suspend fun confirmEmailLink(email: String, code: String): ApiResult<Account> {
            confirms++;gate?.await();return confirmation
        }
        override suspend fun unlinkEmail(identityId: String): ApiResult<Unit> { unlinks++;return ApiResult.Ok(Unit) }
        override suspend fun account(): ApiResult<Account> { reads++;readGate?.await();return if (accountError) ApiResult.Error("network") else ApiResult.Ok(view) }
        override suspend fun verifyEmailCode(email: String, code: String): ApiResult<Unit> = error("Link must never replace tokens through email LOGIN")
        override suspend fun startEmailLogin(email: String, locale: String): ApiResult<Unit> = error("Link must require an authenticated session")
        override suspend fun logout(): ApiResult<Unit> = error("Link/unlink must preserve the Telegram session")
    }

    private class Fixture(initial: Account) {
        var current = initial;var updates = 0;var now = 1_000_000L
        val api = Api(initial)
        val flow = EmailLinkFlow(api, { current }, { current = it;updates++ }, { now })
    }

    @Test fun linkChangesOnlyIdentitiesAndNeverCallsEmailLoginOrLogout() = runBlocking {
        val f = Fixture(telegram);f.api.confirmation = ApiResult.Ok(linked)
        f.flow.send(" Alias@example.test ", "ru")
        assertEquals("alias@example.test", f.flow.state.value.email)
        f.flow.confirm("123456")
        assertEquals(linked, f.current);assertEquals(telegram.vpnSources, f.current.vpnSources)
        assertNull(f.flow.state.value.email);assertEquals(1, f.updates)
    }

    @Test fun transientFailureKeepsProofStepForSameCodeRetryWithoutResend() = runBlocking {
        val f = Fixture(telegram);f.flow.send("alias@example.test", "en")
        f.api.confirmation = ApiResult.Error("upstream_unavailable", 503)
        f.flow.confirm("123456")
        assertEquals("alias@example.test", f.flow.state.value.email)
        assertEquals("upstream_unavailable", f.flow.state.value.error)
        assertEquals(telegram, f.current)
        f.api.confirmation = ApiResult.Ok(linked);f.flow.confirm("123456")
        assertEquals(1, f.api.starts);assertEquals(2, f.api.confirms);assertEquals(linked, f.current)
    }

    @Test fun expiredCodeAndResendDoNotCauseBackgroundMailOrClearTheSession() = runBlocking {
        val f = Fixture(telegram);f.flow.send("alias@example.test", "en")
        f.api.confirmation = ApiResult.Error("code_expired", 410);f.flow.confirm("123456")
        f.flow.send("alias@example.test", "en");assertEquals(1, f.api.starts)
        f.now += 60_000;f.flow.send("alias@example.test", "en")
        assertEquals(2, f.api.starts);assertEquals(telegram, f.current)
    }

    @Test fun nonTelegramAccountCannotStartLinkManagement() = runBlocking {
        val f = Fixture(Account("email-account", listOf(Identity(Identity.Kind.Email, "ma***@example.test"))))
        f.flow.send("alias@example.test", "en")
        assertEquals(0, f.api.starts);assertEquals("telegram_reauth_required", f.flow.state.value.error)
    }

    @Test fun duplicateTapsDoNotCreateConcurrentCodeRequests() = runBlocking {
        val f = Fixture(telegram);val gate = CompletableDeferred<Unit>();f.api.gate = gate
        val first = async { f.flow.send("alias@example.test", "en") };yield()
        f.flow.send("alias@example.test", "en")
        assertEquals(1, f.api.starts);gate.complete(Unit);first.await()
        assertFalse(f.flow.state.value.busy)
    }

    @Test fun staleConfirmationCannotOverwriteAnotherSignedInAccount() = runBlocking {
        val f = Fixture(telegram);f.flow.send("alias@example.test", "en")
        val gate = CompletableDeferred<Unit>();f.api.gate = gate;f.api.confirmation = ApiResult.Ok(linked)
        val result = async { f.flow.confirm("123456") };yield()
        val other = telegram.copy(id = "other");f.current = other
        gate.complete(Unit);result.await()
        assertEquals(other, f.current);assertEquals(0, f.updates)
        assertEquals("account_changed", f.flow.state.value.error)
    }

    @Test fun foreignAccountOrChangedVpnBindingResponseIsRejected() = runBlocking {
        for (view in listOf(linked.copy(id = "other"), linked.copy(vpnSources = mapOf("other-profile" to "bot")))) {
            val f = Fixture(telegram);f.flow.send("alias@example.test", "en")
            f.api.confirmation = ApiResult.Ok(view);f.flow.confirm("123456")
            assertEquals(telegram, f.current);assertEquals(0, f.updates)
            assertEquals("account_changed", f.flow.state.value.error)
        }
    }

    @Test fun unlinkUsesOnlyOwnedEmailIdentityAndRefreshesStatus() = runBlocking {
        val f = Fixture(linked);f.api.view = telegram
        f.flow.unlink("foreign");assertEquals(0, f.api.unlinks)
        f.flow.unlink("email1")
        assertEquals(1, f.api.unlinks);assertEquals(telegram, f.current)
    }

    @Test fun unlinkWithLostStatusReplyCanBeReconciledWithoutAnotherMutation() = runBlocking {
        val f = Fixture(linked);f.api.view = telegram;f.api.accountError = true
        f.flow.unlink("email1");assertEquals("network", f.flow.state.value.error)
        assertEquals(linked, f.current)
        f.api.accountError = false;f.flow.refresh()
        assertEquals(telegram, f.current);assertEquals(1, f.api.unlinks)
    }

    @Test fun resetDiscardsPendingAddressAndNeverPersistsOtp() = runBlocking {
        val f = Fixture(telegram);f.flow.send("alias@example.test", "en");f.flow.reset()
        f.flow.confirm("123456")
        assertNull(f.flow.state.value.email);assertEquals(0, f.api.confirms)
        assertEquals(telegram, f.current)
    }
    @Test fun refreshHasLoadingSuccessAndDuplicateTapProtection() = runBlocking {
        val f = Fixture(linked);val gate = CompletableDeferred<Unit>();f.api.readGate = gate
        val first = async { f.flow.refresh() };yield()
        assertTrue(f.flow.state.value.busy);assertFalse(f.flow.state.value.checked)
        f.flow.refresh();assertEquals(1, f.api.reads)
        gate.complete(Unit);first.await()
        assertFalse(f.flow.state.value.busy);assertTrue(f.flow.state.value.checked)
        assertEquals(0, f.api.starts);assertEquals(0, f.api.confirms);assertEquals(0, f.api.unlinks)
    }

    @Test fun refreshFailureIsVisibleAndCannotClaimSuccess() = runBlocking {
        val f = Fixture(linked);f.api.accountError = true;f.flow.refresh()
        assertEquals("network", f.flow.state.value.error);assertFalse(f.flow.state.value.checked)
        f.api.accountError = false;f.flow.refresh();assertTrue(f.flow.state.value.checked)
    }

    @Test fun resetRejectsLateRefreshEvenForSameAccount() = runBlocking {
        val f = Fixture(linked);val gate = CompletableDeferred<Unit>();f.api.readGate = gate
        val first = async { f.flow.refresh() };yield();f.flow.reset();gate.complete(Unit);first.await()
        assertEquals(0, f.updates);assertFalse(f.flow.state.value.checked)
    }

    @Test fun resetRejectsLateConfirmationEvenForSameAccount() = runBlocking {
        val f = Fixture(telegram);f.flow.send("alias@example.test", "en")
        val gate = CompletableDeferred<Unit>();f.api.gate = gate;f.api.confirmation = ApiResult.Ok(linked)
        val first = async { f.flow.confirm("123456") };yield();f.flow.reset();gate.complete(Unit);first.await()
        assertEquals(telegram, f.current);assertEquals(0, f.updates)
    }

    @Test fun emailOnlyAccountCanReadItsOwnStatusWithoutTelegramOrMutation() = runBlocking {
        val email = Account("website", listOf(Identity(Identity.Kind.Email, "we***@example.test", "email")), mapOf("paid" to "site"))
        val f = Fixture(email);f.flow.refresh()
        assertTrue(f.flow.state.value.checked);assertEquals(email, f.current);assertEquals(1, f.api.reads)
        assertEquals(0, f.api.starts);assertEquals(0, f.api.unlinks)
    }

}
