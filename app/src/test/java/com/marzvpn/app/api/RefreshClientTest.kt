package com.marzvpn.app.api

import com.marzvpn.app.api.RefreshClient.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The client side of refresh recovery against a fake App API with the backend's rules
 * (REFRESH-RECOVERY-CLAUDE.md): one rotation per operation id, the same successor again for that id
 * within 60 s of the rotation and at most twice, any other reuse revokes the family.
 */
class RefreshClientTest {
    class Clock : AuthClock {
        var now = 1_000_000L
        var bootId = 7
        override fun wall() = now
        override fun elapsed() = now - 900_000L
        override fun boot() = bootId
        override suspend fun sleep(ms: Long) { now += ms; yield() }
    }

    class Store : AuthStore {
        var state = RefreshClient.State(RefreshClient.Session("a0", "r0", "s0"))
        var failWrites = false
        var failNextWrites = 0
        var writes = 0
        override fun read() = state
        override fun write(state: RefreshClient.State): Boolean {
            if (failWrites || failNextWrites-- > 0) return false
            writes++
            this.state = state
            return true
        }
    }

    /** The App API's rotation rules. [mode]: "on", "off" (409 recovery unavailable), "old" (no endpoint: 404). */
    class Server(private val clock: Clock) {
        var mode = "on"
        private var n = 0
        val active = mutableSetOf("r0")
        private class Receipt(val id: String, val successor: String, val at: Long, var recoveries: Int)
        private val receipts = mutableMapOf<String, Receipt>()
        var revoked = false
        val successors = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        fun handle(path: String, parent: String, id: String): RefreshClient.Reply {
            if (path == RefreshClient.CANCEL) {
                if (mode == "old") return RefreshClient.Reply(404, "not_found")
                if (parent !in active && parent !in receipts) return RefreshClient.Reply(401, "invalid_refresh")
                revoked = true
                active.clear()
                cancelled += id
                return RefreshClient.Reply(200, "")
            }
            if (mode == "old") return RefreshClient.Reply(404, "not_found")
            if (mode == "off") return RefreshClient.Reply(409, "refresh_recovery_unavailable")
            if (revoked) return RefreshClient.Reply(401, "invalid_refresh")
            if (parent in active) {
                active.remove(parent)
                val s = "r${++n}"
                active += s
                successors += s
                receipts[parent] = Receipt(id, s, clock.now, 0)
                return ok(s)
            }
            val r = receipts[parent] ?: return RefreshClient.Reply(401, "invalid_refresh")
            if (r.id == id && clock.now - r.at < 60_000 && r.recoveries < 2 && r.successor in active) {
                r.recoveries++
                return ok(r.successor)
            }
            revoked = true // authenticated reuse outside the rules
            active.clear()
            return RefreshClient.Reply(401, "refresh_reused")
        }

        private fun ok(s: String) = RefreshClient.Reply(200, "", RefreshClient.Tokens("a" + s.drop(1), s, "s" + s.drop(1)))
    }

    /** Transport: [drop] answers are lost after the server processed them; [down] = no connection at all. */
    class Net(private val server: Server) : RefreshTransport {
        var drop = 0
        var down = false
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun post(path: String, parent: String, id: String, beforeBody: () -> Boolean): RefreshClient.Reply {
            if (down) throw IOException("no route")
            if (!beforeBody()) throw IOException("not saved")
            calls += Triple(path, parent, id)
            val reply = server.handle(path, parent, id)
            gate?.await()
            if (drop > 0) { drop--; throw IOException("reply lost") }
            return reply
        }
    }

    private var ids = 0
    private fun newId() = "id${++ids}".padEnd(43, 'x')

    private class Fixture(val t: RefreshClientTest) {
        val clock = Clock()
        val store = Store()
        val server = Server(clock)
        val net = Net(server)
        fun client() = RefreshClient(store, net, clock) { t.newId() }
    }

    @Test
    fun normalRotationUsesTheRecoveryEndpointOnly() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        assertEquals(Outcome.Done, f.client().refresh("a0"))
        assertEquals("r1", f.store.state.session!!.refresh)
        assertNull(f.store.state.session!!.pending)
        assertEquals(listOf(RefreshClient.RECOVER), f.net.calls.map { it.first })
        assertEquals(43, f.net.calls.single().third.length)
    }

    @Test
    fun lostReplyIsRecoveredWithTheSameOperation() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        f.net.drop = 1
        assertEquals(Outcome.Done, f.client().refresh("a0"))
        assertEquals(listOf("r1"), f.server.successors) // one rotation, the lost successor came back
        assertEquals("r1", f.store.state.session!!.refresh)
        assertEquals(1, f.net.calls.map { it.third }.distinct().size)
        assertEquals(setOf("r0"), f.net.calls.map { it.second }.toSet())
        assertFalse(f.server.revoked)
    }

    /** The first request reaches the server and its reply is lost; after that there is no connection. */
    private fun lostThenDown(f: Fixture) = object : RefreshTransport {
        var n = 0
        override suspend fun post(path: String, parent: String, id: String, beforeBody: () -> Boolean): RefreshClient.Reply {
            if (n++ > 0) throw IOException("down")
            f.net.drop = 1
            return f.net.post(path, parent, id, beforeBody)
        }
    }

    @Test
    fun processDeathAfterALostReplyRecoversOnRestart() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        // First process: reply lost, then no network; the operation stays saved (then the process dies).
        assertTrue(RefreshClient(f.store, lostThenDown(f), f.clock) { newId() }.refresh("a0") is Outcome.Transient)
        val pending = f.store.state.session!!.pending
        assertNotNull(pending)
        assertEquals(1, pending!!.sent)
        // Restarted process (new client on the same store), 20 s later: same id, same successor.
        f.clock.now += 20_000
        assertEquals(Outcome.Done, f.client().refresh("a0"))
        assertEquals(listOf("r1"), f.server.successors)
        assertEquals(pending.id, f.net.calls.last().third)
        assertNull(f.store.state.session!!.pending)
        assertFalse(f.server.revoked)
    }

    @Test
    fun concurrent401sShareOneRotation() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        val c = f.client()
        val gate = CompletableDeferred<Unit>()
        f.net.gate = gate
        val a = async { c.refresh("a0") }
        val b = async { c.refresh("a0") }
        yield()
        gate.complete(Unit)
        assertEquals(Outcome.Done, a.await())
        assertEquals(Outcome.Done, b.await())
        assertEquals(1, f.net.calls.size)
        assertEquals(listOf("r1"), f.server.successors)
    }

    @Test
    fun lateReplyAfterLogoutNeverRestoresTheAccount() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        val c = f.client()
        val gate = CompletableDeferred<Unit>()
        f.net.gate = gate
        val r = async { c.refresh("a0") }
        yield()
        val opId = f.store.state.session!!.pending!!.id
        // Logout while the rotation is in flight: access is invalid on the server, so it is cancelled.
        val logout = async { c.logout { 401 } }
        yield()
        assertNull(f.store.state.session) // signed out locally before the reply arrives
        gate.complete(Unit)
        assertEquals(Outcome.Rejected, r.await())
        logout.await()
        assertNull(f.store.state.session)
        assertTrue(f.server.revoked)
        assertEquals(listOf(opId), f.server.cancelled) // the operation's own id, with its parent
        assertTrue(f.store.state.cancels.isEmpty())
    }

    @Test
    fun noRequestWithoutASavedOperation() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        f.store.failWrites = true
        val out = f.client().refresh("a0")
        assertTrue(out is Outcome.Transient && out.reply.error == "local_storage")
        assertTrue(f.net.calls.isEmpty())
    }

    @Test
    fun unsavedSuccessorIsRecoveredLater() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        val c = f.client()
        // Writes: 1 operation saved, 2 attempt counted, then the successor cannot be saved.
        val saving = object : AuthStore by f.store {
            var count = 0
            override fun write(state: RefreshClient.State): Boolean = if (++count == 3) false else f.store.write(state)
        }
        val failing = RefreshClient(saving, f.net, f.clock) { newId() }
        assertTrue(failing.refresh("a0") is Outcome.Transient)
        assertEquals("r0", f.store.state.session!!.refresh) // old pair and the operation kept
        assertNotNull(f.store.state.session!!.pending)
        assertEquals(Outcome.Done, c.refresh("a0"))
        assertEquals("r1", f.store.state.session!!.refresh)
        assertEquals(listOf("r1"), f.server.successors)
    }

    @Test
    fun repeatedLossStopsBeforeTheServerWouldRevoke() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        f.net.drop = 10
        val c = f.client()
        var out: Outcome = Outcome.Done
        repeat(3) { out = c.refresh("a0") }
        assertEquals(Outcome.Reauth, out)
        assertEquals(3, f.net.calls.size) // rotation + two recoveries, never a fourth (reuse)
        assertFalse(f.server.revoked)
        val before = f.net.calls.size
        assertEquals(Outcome.Reauth, c.refresh("a0")) // stalled: no more old-token requests
        assertEquals(before, f.net.calls.size)
    }

    @Test
    fun recoveryWindowEndsTheOperation() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        assertTrue(RefreshClient(f.store, lostThenDown(f), f.clock) { newId() }.refresh("a0") is Outcome.Transient)
        f.clock.now += 50_000 // the network was down for longer than the recovery window
        val calls = f.net.calls.size
        assertEquals(Outcome.Reauth, f.client().refresh("a0"))
        assertEquals(calls, f.net.calls.size) // no old-token request after the window
        assertTrue(f.store.state.session!!.pending!!.stalled)
        assertFalse(f.server.revoked)
    }

    @Test
    fun rebootMakesTheWindowUncertain() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        RefreshClient(f.store, lostThenDown(f), f.clock) { newId() }.refresh("a0")
        f.clock.bootId = 8 // rebooted a few seconds later: the time passed cannot be trusted
        assertEquals(Outcome.Reauth, f.client().refresh("a0"))
    }

    @Test
    fun serverWithoutRecoveryNeverFallsBackToLegacy() = runBlocking {
        for (mode in listOf("off", "old")) {
            val f = Fixture(this@RefreshClientTest)
            f.server.mode = mode
            val c = f.client()
            val out = c.refresh("a0")
            assertTrue(out is Outcome.Transient)
            val again = c.refresh("a0")
            assertTrue(again is Outcome.Transient)
            assertTrue(f.net.calls.all { it.first == RefreshClient.RECOVER })
            val p = f.store.state.session!!.pending!!
            assertEquals(1, f.net.calls.map { it.third }.distinct().size) // same operation, kept
            assertFalse(p.stalled)
            assertEquals(0, p.sent) // definite answers: nothing rotated, no recovery clock
            assertEquals("r0", f.store.state.session!!.refresh)
        }
    }

    @Test
    fun apiOutageIsNeverASignInButAProxyErrorCounts() = runBlocking {
        // The App API's own 503 (rolled back): any number of them, the session simply waits.
        var f = Fixture(this@RefreshClientTest)
        val api503 = object : RefreshTransport {
            override suspend fun post(path: String, parent: String, id: String, beforeBody: () -> Boolean): RefreshClient.Reply {
                beforeBody()
                return RefreshClient.Reply(503, "upstream_unavailable")
            }
        }
        val c = RefreshClient(f.store, api503, f.clock) { newId() }
        repeat(5) { assertTrue(c.refresh("a0") is Outcome.Transient) }
        assertEquals(0, f.store.state.session!!.pending!!.sent)
        assertFalse(f.store.state.session!!.pending!!.stalled)
        // A proxy's 502 page could hide a committed rotation: three of them end automatic retries.
        f = Fixture(this@RefreshClientTest)
        val proxy = object : RefreshTransport {
            override suspend fun post(path: String, parent: String, id: String, beforeBody: () -> Boolean): RefreshClient.Reply {
                beforeBody()
                return RefreshClient.Reply(502, "http_502")
            }
        }
        val d = RefreshClient(f.store, proxy, f.clock) { newId() }
        assertEquals(Outcome.Reauth, d.refresh("a0")) // decided at once, not at the next sync
    }

    @Test
    fun threeTimeoutsEndTheOperationInTheSameCall() = runBlocking {
        // A hung server: each attempt connects, then the 15 s read timeout; nothing says whether it rotated.
        val f = Fixture(this@RefreshClientTest)
        val hung = object : RefreshTransport {
            override suspend fun post(path: String, parent: String, id: String, beforeBody: () -> Boolean): RefreshClient.Reply {
                beforeBody()
                f.clock.now += 15_000
                throw IOException("read timed out")
            }
        }
        assertEquals(Outcome.Reauth, RefreshClient(f.store, hung, f.clock) { newId() }.refresh("a0"))
        assertTrue(f.store.state.session!!.pending!!.stalled)
        assertEquals("r0", f.store.state.session!!.refresh) // session and data kept for the new sign-in
    }

    @Test
    fun noNetworkAtAllKeepsTheSessionWithoutDeadline() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        f.net.down = true
        val c = f.client()
        assertTrue(c.refresh("a0") is Outcome.Transient)
        f.clock.now += 3_600_000 // an hour offline
        f.net.down = false
        assertEquals(Outcome.Done, c.refresh("a0"))
        assertEquals(listOf("r1"), f.server.successors)
    }

    @Test
    fun rejectedSessionEndsLocally() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        f.server.revoked = true
        assertEquals(Outcome.Rejected, f.client().refresh("a0"))
        assertNull(f.store.state.session)
    }

    @Test
    fun logoutPathsAndLaterDelivery() = runBlocking {
        // Valid access: plain logout, nothing left to cancel.
        var f = Fixture(this@RefreshClientTest)
        f.client().logout { 200 }
        assertNull(f.store.state.session)
        assertTrue(f.store.state.cancels.isEmpty())
        assertTrue(f.net.calls.isEmpty())
        // Offline: signed out at once, the revocation is kept and delivered later.
        f = Fixture(this@RefreshClientTest)
        val c = f.client()
        f.net.down = true
        c.logout { 0 }
        assertNull(f.store.state.session)
        assertEquals(1, f.store.state.cancels.size)
        f.net.down = false
        c.deliverCancels()
        assertTrue(f.store.state.cancels.isEmpty())
        assertTrue(f.server.revoked)
        // A server without the cancel endpoint: a few tries, then dropped (never a refresh instead).
        f = Fixture(this@RefreshClientTest)
        f.server.mode = "old"
        val d = f.client()
        d.logout { 401 }
        repeat(RefreshClient.MAX_CANCEL_ATTEMPTS + 1) { d.deliverCancels() }
        assertTrue(f.store.state.cancels.isEmpty())
        assertTrue(f.net.calls.all { it.first == RefreshClient.CANCEL })
    }

    @Test
    fun signInAfterAStalledOperationCancelsIt() = runBlocking {
        val f = Fixture(this@RefreshClientTest)
        f.net.drop = 10
        val c = f.client()
        repeat(3) { c.refresh("a0") }
        val op = f.store.state.session!!.pending!!
        assertTrue(op.stalled)
        f.server.active += "rNew"
        assertTrue(c.saveSignIn(RefreshClient.Tokens("aNew", "rNew", "sNew")))
        assertEquals("rNew", f.store.state.session!!.refresh)
        assertNull(f.store.state.session!!.pending)
        assertEquals(op.id, f.store.state.cancels.single().id)
        f.net.drop = 0
        c.deliverCancels()
        assertEquals(listOf(op.id), f.server.cancelled)
    }
}
