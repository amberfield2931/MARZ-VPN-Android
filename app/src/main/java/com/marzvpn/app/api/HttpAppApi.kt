package com.marzvpn.app.api

import android.content.Context
import android.os.Build
import android.util.Base64
import com.marzvpn.app.model.Account
import com.marzvpn.app.model.Entitlement
import com.marzvpn.app.model.Identity
import com.marzvpn.app.model.PlanCode
import com.marzvpn.app.model.RouteKind
import com.marzvpn.app.model.Server
import com.marzvpn.app.model.routeVariant
import kotlinx.coroutines.Dispatchers
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/**
 * HTTPS client for the MARZ App API. Every session is bound to [DeviceKey]; refresh, logout and
 * login completion are signed. Access tokens refresh transparently once on 401.
 */
class HttpAppApi(context: Context, private val baseUrl: String) : AppApi {
    private val appContext = context.applicationContext
    private val store = SecureStore(appContext)
    override val isAvailable = true

    private class Response(val code: Int, val body: String) {
        val json: JSONObject get() = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val error: String get() = json.optString("error", "http_$code")
    }

    // ---- transport -------------------------------------------------------------------------

    private suspend fun send(method: String, path: String, body: JSONObject?, bearer: String?, signed: Boolean): Response =
        withContext(Dispatchers.IO) {
            val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
            // App API traffic must not go through a VPN/system proxy configured by others.
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.useCaches = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("X-App-Platform", "android")
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (signed) {
                    val ts = (System.currentTimeMillis() / 1000).toString()
                    val pathOnly = path.substringBefore('?')
                    val message = "$method\n$pathOnly\n$ts\n${sha256Hex(bytes)}".toByteArray()
                    conn.setRequestProperty("X-Device-Timestamp", ts)
                    conn.setRequestProperty("X-Device-Signature", DeviceKey.sign(message))
                }
                if (method != "GET") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                Response(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Authenticated calls refresh once on 401 through [auth] (rotation with recovery of a lost reply).
     * Disk writes of the session happen on Dispatchers.IO.
     */
    private suspend fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = false, signed: Boolean = false): Response =
        withContext(Dispatchers.IO) {
            try {
                if (!auth) return@withContext send(method, path, body, null, signed)
                val access = this@HttpAppApi.auth.access() ?: return@withContext Response(401, """{"error":"signed_out"}""")
                val first = send(method, path, body, access, signed)
                if (first.code != 401) return@withContext first
                val outcome = this@HttpAppApi.auth.refresh(access)
                // Outcome only: never tokens, ids, signatures or bodies.
                com.marzvpn.app.diag.DiagJournal.log("api", "refresh", when (outcome) {
                    is RefreshClient.Outcome.Transient -> "kept (code ${outcome.reply.error} http ${outcome.reply.code})"
                    else -> outcome.toString()
                })
                when (outcome) {
                    RefreshClient.Outcome.Done -> {
                        val again = this@HttpAppApi.auth.access() ?: return@withContext Response(401, """{"error":"signed_out"}""")
                        send(method, path, body, again, signed)
                    }
                    RefreshClient.Outcome.Rejected -> {
                        store.clearSession() // the server ended the session: account data goes too
                        Response(401, """{"error":"signed_out"}""")
                    }
                    // The rotation may have happened and cannot be recovered: sign in again (data and VPN stay).
                    RefreshClient.Outcome.Reauth -> Response(401, """{"error":"reauth_required"}""")
                    // Not a sign-out: the session, the account snapshot and the VPN config stay; a later sync retries.
                    is RefreshClient.Outcome.Transient -> Response(outcome.reply.code, JSONObject().put("error", outcome.reply.error).toString())
                }
            } catch (_: IOException) {
                Response(0, """{"error":"network"}""")
            }
        }

    // ---- session: durable state, signed recovery transport, clocks -----------------------------

    private val authStore = object : AuthStore {
        override fun read(): RefreshClient.State {
            val t = store.get(SecureStore.TOKENS)
            val session = t?.let {
                val p = it.optJSONObject("pending")
                RefreshClient.Session(
                    it.getString("access"), it.getString("refresh"), it.optString("session"),
                    p?.let { j ->
                        RefreshClient.Pending(
                            j.getString("parent"), j.getString("id"),
                            j.optJSONObject("uncertain")?.let { m -> RefreshClient.Mark(m.getLong("wall"), m.getLong("elapsed"), m.getInt("boot")) },
                            j.optInt("sent"),
                            j.optBoolean("stalled"),
                        )
                    },
                )
            }
            val c = store.get(SecureStore.REFRESH_CANCELS)?.optJSONArray("items") ?: JSONArray()
            val cancels = (0 until c.length()).map { i ->
                val o = c.getJSONObject(i)
                RefreshClient.Cancel(o.getString("parent"), o.getString("id"), o.getLong("created"), o.optInt("attempts"))
            }
            return RefreshClient.State(session, cancels)
        }

        override fun write(state: RefreshClient.State): Boolean {
            val session = state.session?.let { s ->
                JSONObject().put("access", s.access).put("refresh", s.refresh).put("session", s.sessionId).apply {
                    s.pending?.let { p ->
                        put("pending", JSONObject().put("parent", p.parent).put("id", p.id).put("sent", p.sent).put("stalled", p.stalled).apply {
                            p.uncertainSince?.let { m -> put("uncertain", JSONObject().put("wall", m.wall).put("elapsed", m.elapsed).put("boot", m.boot)) }
                        })
                    }
                }
            }
            val cancels = state.cancels.takeIf { it.isNotEmpty() }?.let { list ->
                JSONObject().put("items", JSONArray().apply {
                    list.forEach { put(JSONObject().put("parent", it.parent).put("id", it.id).put("created", it.createdWall).put("attempts", it.attempts)) }
                })
            }
            return store.commit(mapOf(SecureStore.TOKENS to session, SecureStore.REFRESH_CANCELS to cancels))
        }
    }

    private val transport = object : RefreshTransport {
        private var lastTs = 0L

        override suspend fun post(path: String, parent: String, id: String, beforeBody: () -> Boolean): RefreshClient.Reply =
            withContext(Dispatchers.IO) {
                val bytes = JSONObject().put("refresh_token", parent).put("request_id", id).toString().toByteArray()
                // Every attempt gets its own timestamp, so its own signature (a processed proof is never reused).
                var ts = System.currentTimeMillis() / 1000
                while (ts <= lastTs) {
                    kotlinx.coroutines.delay(250)
                    ts = System.currentTimeMillis() / 1000
                }
                lastTs = ts
                val conn = URL(baseUrl.trimEnd('/') + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.useCaches = false
                    conn.doOutput = true
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("X-App-Platform", "android")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-Device-Timestamp", ts.toString())
                    conn.setRequestProperty("X-Device-Signature", DeviceKey.sign("POST\n$path\n$ts\n${sha256Hex(bytes)}".toByteArray()))
                    conn.connect()
                    if (!beforeBody()) throw IOException("operation not saved")
                    conn.outputStream.use { it.write(bytes) }
                    val code = conn.responseCode
                    val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                    val tokens = if (code == 200) runCatching {
                        RefreshClient.Tokens(j.getString("access_token"), j.getString("refresh_token"), j.optString("session_id"))
                            .takeIf { it.access.isNotEmpty() && it.refresh.isNotEmpty() }
                    }.getOrNull() else null
                    RefreshClient.Reply(code, j.optString("error", "http_$code"), tokens, j.optInt("retry_after", 0).takeIf { it > 0 })
                } finally {
                    conn.disconnect()
                }
            }
    }

    private val clock = object : AuthClock {
        override fun wall() = System.currentTimeMillis()
        override fun elapsed() = SystemClock.elapsedRealtime()
        override fun boot(): Int = runCatching { Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        override suspend fun sleep(ms: Long) = kotlinx.coroutines.delay(ms)
    }

    private val auth = RefreshClient(authStore, transport, clock) { randomUrlSafe(32) }

    private fun saveTokens(json: JSONObject): ApiResult<Unit> = SignInPersistence.save(
        json.opt("access_token") as? String, json.opt("refresh_token") as? String,
        json.opt("session_id") as? String, auth::saveSignIn,
    )

    private fun device() = JSONObject()
        .put("id", store.deviceId())
        .put("name", (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).take(60))
        .put("public_key", DeviceKey.publicKey())
        .put("platform", "android")

    private fun <T> fail(r: Response): ApiResult<T> = ApiResult.Error(r.error, r.code)

    // ---- auth ------------------------------------------------------------------------------

    override fun isSignedIn() = auth.isSignedIn()

    override suspend fun startTelegramLogin(locale: String): ApiResult<TelegramLogin> {
        val verifier = randomUrlSafe(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        // flow=code asks for the code ceremony; a server without it (or with it switched off) answers with
        // the classic number-matching request, which is then shown exactly as before.
        val r = call("POST", "/v1/auth/telegram/start", JSONObject().put("device", device()).put("code_challenge", challenge)
            .put("flow", TelegramLoginRecord.FLOW_CODE).put("locale", locale))
        if (r.code != 200) return fail(r)
        val j = r.json
        val record = TelegramLoginRecord.fromStart({ jsonField(j, it) }, verifier, System.currentTimeMillis() / 1000)
            ?: return ApiResult.Error("invalid_response", 200)
        // The request is kept (encrypted), so returning from Telegram (or an app restart) shows the same
        // request and keeps waiting for it instead of starting a new one.
        val saved = store.commit(mapOf(SecureStore.PENDING_TELEGRAM to JSONObject().apply { record.forEach { (k, v) -> put(k, v) } }))
        if (!saved) return ApiResult.Error("storage_unavailable")
        return TelegramLoginRecord.login { record[it] }?.let { ApiResult.Ok(it) } ?: ApiResult.Error("invalid_response", 200)
    }

    override fun pendingTelegramLogin(): TelegramLogin? {
        val p = store.get(SecureStore.PENDING_TELEGRAM) ?: return null
        return TelegramLoginRecord.login { jsonField(p, it) }
    }

    override suspend fun pollTelegramLogin(): ApiResult<TelegramPoll> {
        val pending = store.get(SecureStore.PENDING_TELEGRAM) ?: return ApiResult.Error("login_not_found")
        val r = call(
            "POST", "/v1/auth/telegram/confirm",
            JSONObject().put("login_id", pending.getString("login_id")).put("code_verifier", pending.getString("verifier")),
            signed = true,
        )
        return when (r.code) {
            202 -> ApiResult.Ok(TelegramLoginRecord.pending(r.json.let { j -> { name: String -> jsonField(j, name) } }))
            200 -> completeTelegramLogin(r)
            0 -> fail(r)
            else -> {
                // A code request outlives a busy or overloaded server (it has its own 10-minute expiry).
                val keep = pending.optString("flow") == TelegramLoginRecord.FLOW_CODE && TelegramCodeRules.transientPoll(r.code)
                if (!keep) store.put(SecureStore.PENDING_TELEGRAM, null)
                fail(r)
            }
        }
    }

    /** A 200 with tokens from /confirm or /code: signed in only once the pair is durable. */
    private fun completeTelegramLogin(r: Response): ApiResult<TelegramPoll> =
        when (val saved = saveTokens(r.json)) {
            is ApiResult.Ok -> {
                store.put(SecureStore.PENDING_TELEGRAM, null)
                ApiResult.Ok(TelegramPoll(LoginProgress.Done))
            }
            is ApiResult.Error -> saved
            ApiResult.NotAvailable -> ApiResult.NotAvailable
        }

    override suspend fun enterTelegramCode(code: String): ApiResult<CodeAnswer> {
        val pending = store.get(SecureStore.PENDING_TELEGRAM) ?: return ApiResult.Error("login_not_found")
        if (pending.optString("flow") != TelegramLoginRecord.FLOW_CODE) return ApiResult.Error("login_not_found")
        val r = call(
            "POST", "/v1/auth/telegram/code",
            JSONObject().put("login_id", pending.getString("login_id")).put("code_verifier", pending.getString("verifier"))
                .put("code", code),
            signed = true,
        )
        if (r.code == 200) {
            return when (val done = completeTelegramLogin(r)) {
                is ApiResult.Ok -> ApiResult.Ok(CodeAnswer.SignedIn)
                is ApiResult.Error -> done
                ApiResult.NotAvailable -> ApiResult.NotAvailable
            }
        }
        val j = r.json
        val attemptsLeft = if (j.has("attempts_left")) j.optInt("attempts_left") else null
        val answer = TelegramCodeRules.codeAnswer(r.code, r.error, attemptsLeft)
        if (answer is ApiResult.Error && TelegramCodeRules.endsLogin(answer.code)) store.put(SecureStore.PENDING_TELEGRAM, null)
        return answer
    }

    override fun cancelTelegramLogin() = store.put(SecureStore.PENDING_TELEGRAM, null)

    override suspend fun startEmailLogin(email: String, locale: String): ApiResult<Unit> {
        val r = call("POST", "/v1/auth/email/start", JSONObject().put("email", email).put("locale", locale))
        return if (r.code == 202) ApiResult.Ok(Unit) else fail(r)
    }

    override suspend fun verifyEmailCode(email: String, code: String): ApiResult<Unit> {
        val r = call("POST", "/v1/auth/email/verify", JSONObject().put("email", email).put("code", code).put("device", device()), signed = true)
        if (r.code != 200) return fail(r)
        return saveTokens(r.json)
    }

    override suspend fun startEmailLink(email: String, locale: String): ApiResult<Unit> {
        val r = call("POST", "/v1/account/identities/email/start", JSONObject().put("email", email).put("locale", locale), auth = true, signed = true)
        return if (r.code == 202) ApiResult.Ok(Unit) else fail(r)
    }

    override suspend fun confirmEmailLink(email: String, code: String): ApiResult<Account> {
        val r = call("POST", "/v1/account/identities/email/confirm", JSONObject().put("email", email).put("code", code), auth = true, signed = true)
        // A link returns an account view, never a replacement access/refresh pair.
        return if (r.code == 200) runCatching { ApiResult.Ok(parseAccount(r.json)) }
            .getOrElse { ApiResult.Error("invalid_response", 200) } else fail(r)
    }

    override suspend fun unlinkEmail(identityId: String): ApiResult<Unit> {
        if (!Regex("[A-Za-z0-9_-]{1,100}").matches(identityId)) return ApiResult.Error("invalid_identity")
        val r = call("DELETE", "/v1/account/identities/email/$identityId", auth = true, signed = true)
        return if (r.code == 200) ApiResult.Ok(Unit) else fail(r)
    }

    /**
     * Signed out here at once (also offline); then the server revokes the session: plain logout with a
     * valid access token, otherwise the signed cancellation of the refresh operation (kept for later
     * delivery when offline). A refresh reply arriving afterwards never restores the account.
     */
    override suspend fun logout(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        auth.logout { access ->
            try {
                send("POST", "/v1/auth/logout", JSONObject(), access, true).code
            } catch (_: IOException) {
                0
            }
        }
        store.clearSession()
        ApiResult.Ok(Unit)
    }

    override suspend fun deliverPendingCancellations() = withContext(Dispatchers.IO) { auth.deliverCancels() }

    // ---- data ------------------------------------------------------------------------------

    override suspend fun account(): ApiResult<Account> {
        val r = call("GET", "/v1/account", auth = true)
        if (r.code != 200) return fail(r)
        store.put(SecureStore.CACHE_ACCOUNT, r.json)
        return ApiResult.Ok(parseAccount(r.json))
    }

    override fun offlineSnapshot(): Pair<Account, SubscriptionView>? {
        if (!isSignedIn()) return null
        val a = store.get(SecureStore.CACHE_ACCOUNT) ?: return null
        val s = store.get(SecureStore.CACHE_SUBSCRIPTION) ?: return null
        return runCatching { parseAccount(a) to parseSubscription(s) }.getOrNull()
    }

    private fun parseAccount(json: JSONObject): Account {
        val ids = json.optJSONArray("identities") ?: JSONArray()
        val vpns = json.optJSONArray("vpn_identities") ?: JSONArray()
        return Account(
            id = json.getString("id"),
            identities = (0 until ids.length()).map { i ->
                val o = ids.getJSONObject(i)
                Identity(if (o.getString("kind") == "telegram") Identity.Kind.Telegram else Identity.Kind.Email, o.getString("display"), o.optString("id"))
            },
            vpnSources = (0 until vpns.length()).associate { i ->
                val o = vpns.getJSONObject(i)
                o.getString("id") to o.optString("source")
            },
        )
    }

    override suspend fun subscription(): ApiResult<SubscriptionView> {
        val r = call("GET", "/v1/subscription", auth = true)
        if (r.code != 200) return fail(r)
        store.put(SecureStore.CACHE_SUBSCRIPTION, r.json)
        return ApiResult.Ok(parseSubscription(r.json))
    }

    private fun parseSubscription(json: JSONObject): SubscriptionView {
        val state = json.optString("state", "none")
        val effective = json.optJSONObject("effective")
        val e = effective?.optJSONObject("entitlement")
        val all = json.optJSONArray("all") ?: JSONArray()
        val profiles = (0 until all.length()).mapNotNull { i ->
            val o = all.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("vpn_identity").ifEmpty { return@mapNotNull null }
            val pe = o.optJSONObject("entitlement")
            VpnProfile(id, pe?.let(::entitlement), pe?.optString("state", "none") ?: "none", pe?.optJSONArray("queued")?.length() ?: 0)
        }
        return SubscriptionView(state, e?.let(::entitlement), state == "active", effective?.optString("vpn_identity")?.ifEmpty { null }, profiles)
    }

    /** Public catalog (no sign-in, not signed): the raw answer, validated by [com.marzvpn.app.model.Catalog.parse]. */
    override suspend fun catalog(): ApiResult<String> {
        val r = call("GET", "/v1/catalog")
        return if (r.code == 200) ApiResult.Ok(r.body) else fail(r)
    }

    override suspend fun servers(): ApiResult<List<Server>> {
        val r = call("GET", "/v1/servers", auth = true)
        if (r.code == 403) return ApiResult.Ok(emptyList()) // no active plan: nothing to choose
        if (r.code != 200) return fail(r)
        val arr = r.json.optJSONArray("servers") ?: JSONArray()
        return ApiResult.Ok(
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val country = if (o.isNull("country")) "" else o.optString("country")
                // Known country: the app shows its localised name; unknown: the server's own label.
                val path = o.optJSONArray("path")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.length == 2 } }
                Server(id = o.getString("id"), countryCode = country,
                    label = if (country.isEmpty()) o.optString("label", "").ifEmpty { null } else null,
                    kind = RouteKind.of(o.optString("kind", "").ifEmpty { null }), path = path ?: emptyList(),
                    sourceLabel = o.optString("label", "").ifEmpty { null },
                    variant = routeVariant(o.optString("variant", "").ifEmpty { null }, o.optString("label", "").ifEmpty { null }))
            },
        )
    }

    override suspend fun vpnConfig(serverId: String?): ApiResult<String> {
        val path = "/v1/vpn/config" + (serverId?.let { "?server=" + it.filter(Char::isLetterOrDigit) } ?: "")
        val r = call("GET", path, auth = true)
        return if (r.code == 200) ApiResult.Ok(r.body) else fail(r)
    }

    override suspend fun createCheckoutIntent(plan: PlanCode, action: CheckoutAction, vpnIdentity: String?, locale: String, channel: PaymentChannel?): ApiResult<CheckoutIntent> {
        val planCode = plan.wire
        val body = CheckoutBody.of(plan, action, vpnIdentity, locale, channel)
        val r = call("POST", "/v1/billing/intent", JSONObject(body), auth = true, signed = true)
        if (r.code != 200) return fail(r)
        val j = r.json
        // The answer must be for exactly this request, and the link one of the two exact shapes.
        val receivedChannel = CheckoutRules.validate(j.optString("channel"), j.optString("url"), planCode, locale)
        if (receivedChannel == null || (channel != null && receivedChannel != channel) || !Regex("bill_[A-Za-z0-9_-]{22}").matches(j.optString("id")) || j.optString("plan") != planCode || j.optString("action") != action.wire) {
            return ApiResult.Error("invalid_handoff", r.code)
        }
        return ApiResult.Ok(CheckoutIntent(receivedChannel, j.getString("url"), j.optInt("expires_in", 120).coerceIn(1, 120), j.getString("id")))
    }

    override suspend fun checkoutStatus(id: String?): ApiResult<CheckoutReceipt?> {
        val body = JSONObject()
        if (id != null) body.put("id", id)
        val r = call("POST", if (id == null) "/v1/billing/current" else "/v1/billing/status", body, auth = true, signed = true)
        if (r.code != 200) return fail(r)
        val j = r.json
        if (id == null && j.optString("state") == "none") return ApiResult.Ok(null)
        val channel = when (j.optString("channel")) {
            "website" -> PaymentChannel.Website
            "telegram_bot" -> PaymentChannel.TelegramBot
            else -> return ApiResult.Error("invalid_payment_status")
        }
        val receipt = CheckoutReceipt(j.optString("id"), j.optString("vpn_identity"), channel, j.optString("state"))
        if (!PaymentReceipts.valid(receipt.id, receipt.profileId, receipt.state) || (id != null && receipt.id != id)) {
            return ApiResult.Error("invalid_payment_status")
        }
        return ApiResult.Ok(receipt)
    }

    private fun entitlement(e: JSONObject): Entitlement = EntitlementParser.parse(e.toString())

    companion object {
        private val random = SecureRandom()

        fun sha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun b64url(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun randomUrlSafe(bytes: Int): String = b64url(ByteArray(bytes).also(random::nextBytes))
    }
}

/** One field for the plain-Kotlin parsers ([TelegramLoginRecord]): JSON null and absent are both null. */
private fun jsonField(json: JSONObject, name: String): Any? = json.opt(name)?.takeUnless { it == JSONObject.NULL }
