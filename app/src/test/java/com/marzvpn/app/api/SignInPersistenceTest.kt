package com.marzvpn.app.api

import org.junit.Assert.*
import org.junit.Test

class SignInPersistenceTest {
    @Test fun malformedResponseDoesNotOverwriteAnySession() {
        for (pair in listOf(null to "new-refresh", "new-access" to null, "" to "new-refresh", "new-access" to " ")) {
            var writes = 0
            val result = SignInPersistence.save(pair.first, pair.second, "session") { writes++;true }
            assertEquals(ApiResult.Error("invalid_response"), result);assertEquals(0, writes)
        }
    }
    @Test fun writeFailureMustNotBeReportedAsSignedIn() {
        val store = RefreshClientTest.Store().apply { failWrites = true }
        val before = store.read()
        val result = SignInPersistence.save("new-access", "new-refresh", "new-session") {
            store.write(RefreshClient.State(RefreshClient.Session(it.access, it.refresh, it.sessionId)))
        }
        assertEquals(ApiResult.Error("storage_unavailable"), result)
        assertEquals(before, store.read())
    }
    @Test fun successRequiresTheCompletePairInOneWrite() {
        var written: RefreshClient.Tokens? = null
        val result = SignInPersistence.save("new-access", "new-refresh", "new-session") { written = it;true }
        assertTrue(result is ApiResult.Ok)
        assertEquals(RefreshClient.Tokens("new-access", "new-refresh", "new-session"), written)
    }
}
