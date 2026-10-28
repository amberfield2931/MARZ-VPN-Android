package com.marzvpn.app.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Refresh-token rotation with recovery of a lost reply (backend docs/app/REFRESH-RECOVERY-CLAUDE.md).
 *
 * One rotation = one operation: a random `request_id` saved durably with its parent refresh token
 * before the first request, reused for every retry (also after a timeout or process death) until the
 * operation ends, and sent only to `POST /v1/auth/refresh/recover` (never the legacy endpoint). Each
 * attempt carries a fresh device signature (the transport signs every request).
 *
 * The server can return a lost successor only within 60 s of its rotation. The client starts counting
 * at the first attempt that may have reached it (connection made, body about to be sent; recorded
 * durably before the body) and stops at [CUTOFF_MS] so the last attempt still ends inside that window.
 * After that, or on an unexplained conflict, the operation is "stalled": no more old-token requests,
 * the user signs in again; the VPN config and the account view stay.
 *
 * Logout first removes the session locally (a late reply can no longer restore it), then revokes it on
 * the server: `/v1/auth/logout` with a still valid access token, otherwise `/v1/auth/refresh/cancel`
 * with the parent token and the operation's id. Undelivered cancellations are kept and retried a few
 * times. Plain Kotlin (no Android types): unit-tested on the JVM with fake store/transport/clock.
 */
class RefreshClient(
    private val store: AuthStore,
    private val transport: RefreshTransport,
    private val clock: AuthClock,
    private val newId: () -> String,
) {
    data class Mark(val wall: Long, val elapsed: Long, val boot: Int)

    /**
     * An unfinished rotation. [uncertainSince]: first attempt that may have rotated the token; [sent]:
     * attempts that may have reached the server without a definite answer (the first rotates, the
     * server recovers at most two more).
     */
    data class Pending(val parent: String, val id: String, val uncertainSince: Mark? = null, val sent: Int = 0, val stalled: Boolean = false)

    data class Session(val access: String, val refresh: String, val sessionId: String, val pending: Pending? = null)

    /** A revocation still to be delivered after logout (never used for anything else). */
    data class Cancel(val parent: String, val id: String, val createdWall: Long, val attempts: Int = 0)

    data class State(val session: Session?, val cancels: List<Cancel> = emptyList())

    data class Tokens(val access: String, val refresh: String, val sessionId: String)

    /** [error]: the API's code; [retryAfter] seconds when given; [tokens] only for a valid 200. */
    data class Reply(val code: Int, val error: String, val tokens: Tokens? = null, val retryAfter: Int? = null)

    sealed interface Outcome {
        data object Done : Outcome
        /** The server ended the session (or it was signed out locally meanwhile). */
        data object Rejected : Outcome
        /** The rotation may have happened and can no longer be recovered: sign in again. */
        data object Reauth : Outcome
        /** Nothing decided; tokens and the pending operation are kept for a later try. */
        data class Transient(val reply: Reply) : Outcome
    }

    private val rotation = Mutex()
    /** Guards every read-modify-write of the stored state (logout does not wait for a running rotation). */
    private val state = Any()

    /**
     * Rotates (or recovers) for a request that got 401 with [staleAccess]. Concurrent callers share
     * one operation; a caller that waited finds the new pair and returns [Outcome.Done].
     */
    suspend fun refresh(staleAccess: String): Outcome = rotation.withLock {
        var attempts = 0
        var replays = 0
        var last = Reply(0, "network")
        while (true) {
            val prepared = synchronized(state) {
                val s = store.read().session ?: return@synchronized Prep.SignedOut
                if (s.pending == null && s.access != staleAccess) return@synchronized Prep.AlreadyDone
                val p = s.pending ?: Pending(parent = s.refresh, id = newId())
                if (s.pending == null && !store.write(store.read().copy(session = s.copy(pending = p)))) return@synchronized Prep.NotSaved
                Prep.Ready(p)
            }
            val p = when (prepared) {
                Prep.SignedOut -> return Outcome.Rejected
                Prep.AlreadyDone -> return Outcome.Done
                Prep.NotSaved -> return Outcome.Transient(Reply(0, "local_storage")) // no request without a saved operation
                is Prep.Ready -> prepared.pending
            }
            if (p.stalled) return Outcome.Reauth
            if (p.uncertainSince != null && since(p.uncertainSince) > CUTOFF_MS) return stall(p)
            // A 4th unanswered attempt would be a reuse for the server (family revoked): sign in instead.
            if (p.sent >= MAX_SENT) return stall(p)
            if (attempts >= MAX_ATTEMPTS) return Outcome.Transient(last)
            attempts++

            // Recorded before the body leaves: if the process dies now, the restart knows the clock runs.
            var markedNow = false // this attempt started the recovery clock
            var counted = false // this attempt is counted in [Pending.sent]
            val reply = try {
                transport.post(RECOVER, p.parent, p.id) {
                    val first = p.uncertainSince == null
                    counted = update(p) { it.copy(uncertainSince = it.uncertainSince ?: clock.mark(), sent = it.sent + 1) }
                    markedNow = counted && first
                    counted
                }
            } catch (_: IOException) {
                null
            }
            /** A definite answer: this attempt rotated nothing, so it neither counts nor starts the clock. */
            fun answered() {
                if (counted) update(p) { it.copy(sent = (it.sent - 1).coerceAtLeast(0), uncertainSince = if (markedNow) null else it.uncertainSince) }
            }
            if (reply == null) {
                last = Reply(0, "network")
                if (!backoff(p, attempts, markedNow || p.uncertainSince != null)) return stopHere(p, last)
                continue
            }
            last = reply
            val tokens = reply.tokens
            when {
                reply.code == 200 && tokens != null && tokens.refresh != p.parent -> return synchronized(state) {
                    val now = store.read()
                    val s = now.session
                    // A late reply after logout, another sign-in or a later pair is never saved.
                    if (s?.pending?.id != p.id || s.refresh != p.parent) return@synchronized Outcome.Rejected
                    if (store.write(now.copy(session = Session(tokens.access, tokens.refresh, tokens.sessionId)))) Outcome.Done
                    else Outcome.Transient(Reply(0, "local_storage")) // old pending kept: a later recovery returns the same pair
                }
                RefreshRules.ends(reply.code, reply.error) -> return synchronized(state) {
                    val now = store.read()
                    if (now.session?.pending?.id == p.id) store.write(now.copy(session = null))
                    Outcome.Rejected
                }
                reply.code == 409 && reply.error == "refresh_request_conflict" -> return stall(p)
                reply.code == 409 && reply.error == "refresh_proof_replayed" -> {
                    answered()
                    if (replays++ >= 1) return Outcome.Transient(reply)
                    clock.sleep(1_100) // a new timestamp, so a really new signature
                }
                reply.code == 429 -> {
                    answered()
                    val wait = (reply.retryAfter ?: 5) * 1000L
                    if (!fits(p, wait) || attempts >= MAX_ATTEMPTS) return Outcome.Transient(reply)
                    clock.sleep(wait)
                }
                reply.code == 200 || (reply.code in 500..599 && reply.error == "http_${reply.code}") -> {
                    // A 5xx that is not the App API's own answer (a proxy page) or an unusable 200 does not
                    // prove that nothing was rotated: it counts against the recovery window.
                    if (!backoff(p, attempts, true)) return stopHere(p, reply)
                }
                reply.code in 500..599 -> {
                    // The App API's own error (e.g. 503 upstream_unavailable): its transaction rolled back.
                    answered()
                    if (!backoff(p, attempts, false)) return Outcome.Transient(reply)
                }
                else -> {
                    // 404 (server without recovery), 409 recovery off / wrong endpoint, 400 malformed id,
                    // 401 signature: a definite "nothing rotated" for this attempt. Never the legacy endpoint.
                    answered()
                    return Outcome.Transient(reply)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Outcome.Transient(last)
    }

    private sealed interface Prep {
        data object SignedOut : Prep
        data object AlreadyDone : Prep
        data object NotSaved : Prep
        data class Ready(val pending: Pending) : Prep
    }

    /** Waits before the next attempt when it still fits the recovery window; false = stop for now. */
    private suspend fun backoff(p: Pending, attempts: Int, uncertain: Boolean): Boolean {
        if (attempts >= MAX_ATTEMPTS) return false
        val wait = 1_000L shl (attempts - 1)
        val current = current(p)
        if (uncertain && current?.uncertainSince != null && !fits(current, wait)) return false
        clock.sleep(wait)
        return true
    }

    /**
     * No more attempts in this call. If the operation can never be completed any more (three attempts
     * that may have reached the server, or no time left in the recovery window), it ends now (sign in
     * again) instead of at the next try; otherwise it waits for a later sync.
     */
    private fun stopHere(p: Pending, reply: Reply): Outcome {
        val cur = current(p) ?: return Outcome.Transient(reply)
        val mark = cur.uncertainSince ?: return Outcome.Transient(reply)
        return if (cur.sent >= MAX_SENT || since(mark) + 1_000 > CUTOFF_MS) stall(cur) else Outcome.Transient(reply)
    }

    private fun fits(p: Pending, wait: Long) = p.uncertainSince == null || since(p.uncertainSince) + wait <= CUTOFF_MS

    private fun current(p: Pending): Pending? = synchronized(state) { store.read().session?.pending?.takeIf { it.id == p.id } }

    /** Durable change of this operation (only while it is still the stored one). */
    private fun update(p: Pending, change: (Pending) -> Pending): Boolean = synchronized(state) {
        val now = store.read()
        val s = now.session ?: return false
        val cur = s.pending?.takeIf { it.id == p.id } ?: return false
        store.write(now.copy(session = s.copy(pending = change(cur))))
    }

    private fun stall(p: Pending): Outcome {
        update(p) { it.copy(stalled = true) }
        return Outcome.Reauth
    }

    /**
     * Milliseconds since [m] on the monotonic clock. After a reboot the time passed is not known for
     * sure (the wall clock can be wrong or moved): "very long", so the operation is not retried.
     */
    private fun since(m: Mark): Long {
        val boot = clock.boot()
        val sameBoot = if (boot >= 0 && m.boot >= 0) boot == m.boot else clock.elapsed() >= m.elapsed
        val d = clock.elapsed() - m.elapsed
        return if (!sameBoot || d < 0) Long.MAX_VALUE else d
    }

    // ---- sign-in, logout, cancellation -------------------------------------------------------

    /** A new sign-in replaces the session; an unfinished operation of the old one is cancelled later. */
    fun saveSignIn(tokens: Tokens): Boolean = synchronized(state) {
        val now = store.read()
        val old = now.session?.pending
        val cancels = if (old != null) (now.cancels + Cancel(old.parent, old.id, clock.wall())).takeLast(MAX_CANCELS) else now.cancels
        store.write(State(Session(tokens.access, tokens.refresh, tokens.sessionId), cancels))
    }

    fun isSignedIn(): Boolean = synchronized(state) { store.read().session != null }

    fun access(): String? = synchronized(state) { store.read().session?.access }

    /** The server ended the session (refresh rejected): local session gone, nothing to cancel. */
    fun dropSession() = synchronized(state) {
        val now = store.read()
        if (now.session != null) store.write(now.copy(session = null))
    }

    /**
     * Explicit logout. Locally signed out first (a late refresh reply cannot bring the account back),
     * then revoked on the server; an undelivered revocation is kept for [deliverCancels].
     * [plainLogout]: `/v1/auth/logout` with the access token, returns the HTTP status (0 offline).
     */
    suspend fun logout(plainLogout: suspend (access: String) -> Int) {
        val (session, cancel) = synchronized(state) {
            val now = store.read()
            val s = now.session ?: return
            val c = Cancel(s.pending?.parent ?: s.refresh, s.pending?.id ?: newId(), clock.wall())
            // Session removed and its cancellation recorded in one durable write; even if that write
            // fails the session is removed (best effort) so the account is not shown as signed in.
            if (!store.write(State(null, (now.cancels + c).takeLast(MAX_CANCELS)))) store.write(now.copy(session = null))
            s to c
        }
        if (session.pending == null) {
            val status = try { plainLogout(session.access) } catch (_: IOException) { 0 }
            if (status == 200) return forget(cancel)
            if (status == 0 || status >= 500) return // kept: delivered later
        }
        deliverCancels()
    }

    /** Sends the kept cancellations (at most [MAX_CANCEL_ATTEMPTS] tries each, for at most a week). */
    suspend fun deliverCancels() {
        val pending = synchronized(state) { store.read().cancels }
        for (c in pending) {
            if (c.attempts >= MAX_CANCEL_ATTEMPTS || clock.wall() - c.createdWall > CANCEL_KEEP_MS) {
                forget(c)
                continue
            }
            val reply = try { transport.post(CANCEL, c.parent, c.id) { true } } catch (_: IOException) { null }
            when {
                reply != null && (reply.code == 200 || reply.code == 400 || (reply.code == 401 && reply.error == "invalid_refresh")) -> forget(c)
                else -> synchronized(state) {
                    // 404 (server without the endpoint), offline, 5xx, signature: tried again later, a few times.
                    val now = store.read()
                    store.write(now.copy(cancels = now.cancels.map { if (it.id == c.id) it.copy(attempts = it.attempts + 1) else it }))
                }
            }
        }
    }

    private fun forget(c: Cancel) = synchronized(state) {
        val now = store.read()
        store.write(now.copy(cancels = now.cancels.filterNot { it.id == c.id }))
        Unit
    }

    companion object {
        const val RECOVER = "/v1/auth/refresh/recover"
        const val CANCEL = "/v1/auth/refresh/cancel"
        /** The server keeps a lost reply for 60 s; the last attempt must still end inside (15 s read timeout). */
        const val CUTOFF_MS = 45_000L
        const val MAX_ATTEMPTS = 4
        const val MAX_SENT = 3
        const val MAX_CANCELS = 3
        const val MAX_CANCEL_ATTEMPTS = 5
        const val CANCEL_KEEP_MS = 7 * 24 * 3600_000L
    }
}

/** Durable encrypted state: [write] returns true only after the change is on disk (one atomic update). */
interface AuthStore {
    fun read(): RefreshClient.State
    fun write(state: RefreshClient.State): Boolean
}

interface RefreshTransport {
    /**
     * One device-signed POST of `{"refresh_token": parent, "request_id": id}` to [path], with a new
     * timestamp and signature. [beforeBody] runs once the connection is made, before the body is sent;
     * false aborts the request (IOException). Throws IOException when no answer arrived.
     */
    suspend fun post(path: String, parent: String, id: String, beforeBody: () -> Boolean): RefreshClient.Reply
}

interface AuthClock {
    fun wall(): Long
    fun elapsed(): Long
    /** Boot counter, or -1 when unknown. */
    fun boot(): Int
    fun mark() = RefreshClient.Mark(wall(), elapsed(), boot())
    suspend fun sleep(ms: Long)
}
