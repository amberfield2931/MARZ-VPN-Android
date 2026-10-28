package com.marzvpn.app

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marzvpn.app.api.ApiResult
import com.marzvpn.app.api.AppApi
import com.marzvpn.app.api.CheckoutAction
import com.marzvpn.app.api.CheckoutFlow
import com.marzvpn.app.api.CheckoutIntent
import com.marzvpn.app.api.CheckoutProblem
import com.marzvpn.app.api.CheckoutRequest
import com.marzvpn.app.api.CheckoutRules
import com.marzvpn.app.api.EarlierAttempts
import com.marzvpn.app.api.PaymentChannel
import com.marzvpn.app.api.SubscriptionView
import com.marzvpn.app.api.VpnProfile
import com.marzvpn.app.api.PaymentReceipts
import com.marzvpn.app.api.HttpAppApi
import com.marzvpn.app.api.LoginProgress
import com.marzvpn.app.api.PreviewAppApi
import com.marzvpn.app.api.TelegramLogin
import com.marzvpn.app.model.Account
import com.marzvpn.app.model.AccountState
import com.marzvpn.app.model.Catalog
import com.marzvpn.app.model.Entitlement
import com.marzvpn.app.model.Identity
import com.marzvpn.app.model.PlanCode
import com.marzvpn.app.model.Server
import com.marzvpn.app.model.ServerCatalog
import com.marzvpn.app.model.ServerChoice
import com.marzvpn.app.settings.MarzPrefs
import com.marzvpn.app.settings.ThemeMode
import com.marzvpn.app.vpn.ConfigInstaller
import com.marzvpn.app.vpn.ConfigSource
import com.marzvpn.app.vpn.EngineState
import com.marzvpn.app.vpn.RouteMonitor
import com.marzvpn.app.vpn.NetworkIdentity
import com.marzvpn.app.vpn.VpnEngine
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import com.marzvpn.app.diag.DiagJournal
import com.marzvpn.app.vpn.TunnelCheck
import com.marzvpn.app.vpn.RouteAvailability
import com.marzvpn.app.vpn.RouteCheck
import java.time.Instant
import java.time.temporal.ChronoUnit

/** UI sample states for reviewing layouts from developer tools. Always labelled as samples on screen. */
enum class SampleKind { Trial, Basic, Unlimited }

/** Telegram login on this device. */
/** What a route row shows (only the route in use is measured): a warm delay, or "—" after a failed check. */
data class RouteLatency(val state: State, val ms: Int) {
    enum class State { Measured, NoData }
}

/**
 * Whether the VPN really carries data, from [TunnelCheck] through the tunnel (never from the service
 * state alone). [Lost]/[NoNetwork] only after two failed checks in a row: [Lost] while the phone's own
 * network is online (so it is the VPN path), [NoNetwork] when the phone has no working network at all.
 */
enum class TrafficState { Unknown, Ok, Lost, NoNetwork }

sealed interface TelegramUi {
    data object Idle : TelegramUi
    data object Starting : TelegramUi
    data class Waiting(val telegramUrl: String, val matchCode: String, val expiresAtEpochSeconds: Long) : TelegramUi
    data class Failed(val code: String) : TelegramUi
}

/** Paying for the account's own profile through a signed, one-use link (see [CheckoutRules]). */
sealed interface CheckoutUi {
    data object Idle : CheckoutUi
    data object Requesting : CheckoutUi
    /** The link was handed to the browser or Telegram; the result is read from the App API only. */
    data class Opened(val channel: PaymentChannel, val profileId: String, val operationId: String, val state: String = "awaiting_checkout", val checking: Boolean = false) : CheckoutUi
    data class Confirmed(val entitlement: Entitlement?) : CheckoutUi
    /** Nothing was opened. [source]: the profile's channel ("bot" or "site"), for what to do instead. */
    data class Failed(val problem: CheckoutProblem, val action: CheckoutAction, val source: String?, val hasProfiles: Boolean) : CheckoutUi
}

/** Email login: enter address, then the 6-digit code. */
sealed interface EmailUi {
    data object EnterEmail : EmailUi
    data object Sending : EmailUi
    /** [sentAt]: elapsedRealtime of the last code request (a new code may be asked for a minute later). */
    data class EnterCode(val email: String, val busy: Boolean = false, val error: String? = null, val sentAt: Long = android.os.SystemClock.elapsedRealtime()) : EmailUi
    data class Failed(val code: String) : EmailUi
}

/** A new email code can be asked for one minute after the previous one. */
const val EMAIL_RESEND_MS = 60_000L

class MarzViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val prefs = MarzPrefs(app)
    val engine = VpnEngine(app as Application)

    /** Debug builds may point at a lab App API (developer tools); release uses the built-in URL only. */
    val apiUrl: String = (if (BuildConfig.DEBUG) prefs.apiUrlOverride else null) ?: BuildConfig.MARZ_APP_API_URL
    val api: AppApi = if (apiUrl.isNotBlank()) HttpAppApi(app, apiUrl) else PreviewAppApi

    private val _theme = MutableStateFlow(prefs.themeMode)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _account = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _canConnect = MutableStateFlow(false)
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _sample = MutableStateFlow<SampleKind?>(null)
    val sample: StateFlow<SampleKind?> = _sample.asStateFlow()

    /** Before sign-in the picker shows the built-in country list (never used for configs). */
    private val _servers = MutableStateFlow(ServerCatalog.preview)
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()
    private val _serversFromAccount = MutableStateFlow(false)
    /** The saved route is no longer offered by the account (removed or changed). Never replaced silently. */
    private val _routeMissing = MutableStateFlow(false)
    val routeMissing: StateFlow<Boolean> = _routeMissing.asStateFlow()

    /** What the running engine really uses, and each route's measured delay (only while connected). */
    private val routeMonitor = RouteMonitor(viewModelScope)
    val activeServerId: StateFlow<String?> = routeMonitor.snapshot
        .map { RouteMonitor.serverId(it.activeTag) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** The route in use, checked through the tunnel while the app is on screen (see [TunnelCheck]). */
    private val _traffic = MutableStateFlow(TrafficState.Unknown)
    private val _liveMs = MutableStateFlow<Int?>(null)
    private val _checkFailures = MutableStateFlow(0)
    private var checkJob: Job? = null
    private var visible = false
    private var checkEpoch = 0
    private var checkNetwork: String? = null
    val traffic: StateFlow<TrafficState> = combine(_traffic, engine.state) { t, state ->
        if (state == EngineState.Connected) t else TrafficState.Unknown
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TrafficState.Unknown)
    val liveMs: StateFlow<Int?> = combine(_liveMs, traffic) { ms, t -> ms.takeIf { t == TrafficState.Ok } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Only the route in use has a value: its delay, or "—" after a failed check. Other routes: nothing. */
    val latency: StateFlow<Map<String, RouteLatency>> = combine(activeServerId, liveMs, _checkFailures, engine.state) { id, ms, failures, state ->
        when {
            state != EngineState.Connected || id == null -> emptyMap()
            ms != null -> mapOf(id to RouteLatency(RouteLatency.State.Measured, ms))
            failures > 0 -> mapOf(id to RouteLatency(RouteLatency.State.NoData, 0))
            else -> emptyMap()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Cold check of every route while the route list is open (server id -> availability). */
    private val _availability = MutableStateFlow<Map<String, RouteAvailability>>(emptyMap())
    val availability: StateFlow<Map<String, RouteAvailability>> = _availability.asStateFlow()
    private var routeCheckJob: Job? = null
    private var routeListVisible = false

    /** Screens other components ask to open (the VPN notification's "Change server"). */
    private val _openRequests = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val openRequests: SharedFlow<String> = _openRequests
    val serversFromAccount: Boolean get() = _serversFromAccount.value

    private val _serverChoice = MutableStateFlow(prefs.serverChoice)
    val serverChoice: StateFlow<ServerChoice> = _serverChoice.asStateFlow()

    private val _configSource = MutableStateFlow(ConfigSource.None)
    val configSource: StateFlow<ConfigSource> = _configSource.asStateFlow()

    private val _telegram = MutableStateFlow<TelegramUi>(TelegramUi.Idle)
    val telegram: StateFlow<TelegramUi> = _telegram.asStateFlow()
    private var telegramJob: Job? = null

    private val _email = MutableStateFlow<EmailUi>(EmailUi.EnterEmail)
    val email: StateFlow<EmailUi> = _email.asStateFlow()

    val emailLink = com.marzvpn.app.api.EmailLinkFlow(api,
        current = { (_account.value as? AccountState.SignedIn)?.takeUnless { it.isSample }?.account },
        updated = { view ->
            val state = _account.value as? AccountState.SignedIn
            if (state != null && !state.isSample && state.account.id == view.id) _account.value = state.copy(account = view)
        }, elapsed = { android.os.SystemClock.elapsedRealtime() })

    fun sendEmailLink(email: String, locale: String) { viewModelScope.launch { emailLink.send(email, locale) } }
    fun confirmEmailLink(code: String) { viewModelScope.launch { emailLink.confirm(code) } }
    fun unlinkEmail(identityId: String) { viewModelScope.launch { emailLink.unlink(identityId) } }
    fun refreshEmailLink() { viewModelScope.launch { emailLink.refresh() } }

    /** Last subscription read (profiles for payment). */
    private val _subscription = MutableStateFlow<SubscriptionView?>(null)
    val subscription: StateFlow<SubscriptionView?> = _subscription.asStateFlow()
    private val _checkout = MutableStateFlow<CheckoutUi>(CheckoutUi.Idle)
    val checkout: StateFlow<CheckoutUi> = _checkout.asStateFlow()
    /** A payment link waiting to be opened by the UI: in memory only, taken once, refused when too old. */
    private class PendingLink(val intent: CheckoutIntent, val requestedAt: Long)
    private val _checkoutLink = MutableStateFlow<PendingLink?>(null)
    val checkoutLinkReady: StateFlow<Boolean> = _checkoutLink.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var paymentWatch: Job? = null
    private var openedAt = 0L
    private var checkoutRequest: Job? = null
    private val checkoutGate = com.marzvpn.app.api.CheckoutRequestGate()
    /** Repeated by "return to payment" / "new link"; memory only. */
    private var lastRequest: CheckoutRequest? = null
    private val _canRetryCheckout = MutableStateFlow(false)
    val canRetryCheckout: StateFlow<Boolean> = _canRetryCheckout.asStateFlow()
    /** Attempts left behind for another plan or a new link: followed until finished, never cancelled. */
    private val earlierAttempts = EarlierAttempts()

    /** Emitted when a new config arrived while connected; the activity restarts the engine. */
    /** Config installs one at a time (sync and route choices), see [installConfig]. */
    private val installMutex = kotlinx.coroutines.sync.Mutex()
    private var switchJob: Job? = null

    // Replayed until the activity takes it (it may subscribe after the request, e.g. right at start).
    private val _restart = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val restartRequests: SharedFlow<Unit> = _restart.asSharedFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeRestart() = _restart.resetReplayCache()

    val engineState: StateFlow<EngineState> get() = engine.state
    val engineError: StateFlow<String?> get() = engine.lastError

    val welcomeSeen: Boolean get() = prefs.welcomeSeen

    /** Developer tools exist only in debug builds; unlocked per process by 7 taps on the version. */
    val developerToolsAvailable: Boolean = BuildConfig.DEBUG
    private val _developerUnlocked = MutableStateFlow(false)
    val developerUnlocked: StateFlow<Boolean> = _developerUnlocked.asStateFlow()
    private var versionTaps = 0

    init {
        engine.bind()
        refreshConfigSource()
        // MARZ VPN updates come from marzvpn.com / stores, never from upstream sing-box releases.
        viewModelScope.launch(Dispatchers.IO) {
            Settings.dataStore.initialize()
            Settings.updateCheckPrompted = true
            Settings.checkUpdateEnabled = false
            Settings.autoUpdateEnabled = false
        }
        if (api.isSignedIn()) {
            // Last known account and plan at once (encrypted snapshot): no "signed out" page while the
            // App API is slow or unreachable after a restart. The sync below replaces it.
            api.offlineSnapshot()?.let { (account, sub) ->
                _account.value = AccountState.SignedIn(account, sub.entitlement, subscriptionState = sub.state)
                _canConnect.value = sub.canConnect
                _subscription.value = sub
            }
            // Last known routes: the saved choice and its name survive a process restart and an
            // unreachable App API; they are refreshed by the sync below.
            prefs.routes?.let {
                _servers.value = it
                _serversFromAccount.value = true
            }
            sync()
        } else {
            resumeTelegramLogin()
        }
        viewModelScope.launch {
            engine.state.collect {
                if (it == EngineState.Connected) routeMonitor.start() else routeMonitor.stop()
                // Results of a check are about the engine that ran it.
                restartRouteCheck()
                // Results of an earlier connection (or engine instance) are never shown again.
                if (it != EngineState.Connected) resetTraffic()
                if (it == EngineState.Connected) restartChecks()
            }
        }
        // Another route in use: earlier results are not about it; check it now.
        viewModelScope.launch {
            activeServerId.collect { resetTraffic(); restartChecks() }
        }
        // Update from 4.5: its loopback latency inbound leaves the installed config (one restart if connected).
        viewModelScope.launch {
            if (engine.installer.dropLegacyProbe()) {
                DiagJournal.log("app", "4.5 probe removed from the installed config")
                val state = engine.state.first { it != EngineState.Unknown }
                if (state == EngineState.Connected) _restart.tryEmit(Unit)
            }
        }
    }

    override fun onCleared() {
        engine.unbind()
    }

    // ---- account sync ----------------------------------------------------------------------

    /** Account, subscription, servers and (for an active plan) the VPN config, in that order. */
    fun sync() {
        if (!api.isAvailable || _sample.value != null) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                syncNow()
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun syncNow() {
        val account = when (val r = api.account()) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return handleError(r)
            ApiResult.NotAvailable -> return
        }
        val sub = when (val r = api.subscription()) {
            is ApiResult.Ok -> r.value
            is ApiResult.Error -> return handleError(r)
            ApiResult.NotAvailable -> return
        }
        _account.value = AccountState.SignedIn(account, sub.entitlement, subscriptionState = sub.state)
        _canConnect.value = sub.canConnect
        _subscription.value = sub
        checkPayment(sub)
        val routes = (api.servers() as? ApiResult.Ok)?.value
        routes?.takeIf { it.isNotEmpty() }?.let { migrateChoice(_servers.value, it) }
        routes?.takeIf { it.isNotEmpty() }?.let {
            _servers.value = it
            _serversFromAccount.value = true
            prefs.routes = it
        }
        if (sub.canConnect) {
            installConfig(routesKnown = !routes.isNullOrEmpty())
        } else {
            // No active plan: the engine must not keep an old config of this account.
            engine.installer.remove()
            refreshConfigSource()
        }
        _syncError.value = null
        DiagJournal.log("api", "sync ok", "vpn=${engine.state.value}")
    }

    /**
     * Installs the config for the saved choice. A saved route that the account no longer offers is
     * reported ([routeMissing]) and the installed config is kept: the VPN is never moved to another
     * route (or to Auto) without the user choosing it. Without a fresh route list nothing is changed.
     */
    private suspend fun installConfig(routesKnown: Boolean) = installMutex.withLock {
        val chosen = (_serverChoice.value as? ServerChoice.Specific)?.id
        if (chosen != null && _servers.value.none { it.id == chosen }) {
            if (routesKnown) _routeMissing.value = true
            refreshConfigSource()
            return@withLock
        }
        _routeMissing.value = false
        when (val r = api.vpnConfig(chosen)) {
            is ApiResult.Ok -> {
                // The user picked another route meanwhile: that newer choice installs its own config.
                if ((_serverChoice.value as? ServerChoice.Specific)?.id != chosen) return@withLock
                val result = engine.installer.install(r.value)
                DiagJournal.log("api", "config", result.fold({
                    when (it) {
                        ConfigInstaller.Install.Unchanged -> "unchanged"
                        ConfigInstaller.Install.Refreshed -> "refreshed (only random SNI/short id differ: no restart)"
                        ConfigInstaller.Install.Changed -> "changed"
                    }
                }, { "install failed: ${it.javaClass.simpleName}" }) + " vpn=${engine.state.value}")
                result.exceptionOrNull()?.let { _syncError.value = "config" }
                // Only a real change restarts a running VPN; the refreshed file is used at the next connect.
                if (result.getOrNull() == ConfigInstaller.Install.Changed && engine.state.value == EngineState.Connected) _restart.tryEmit(Unit)
            }
            is ApiResult.Error -> handleError(r)
            ApiResult.NotAvailable -> {}
        }
        refreshConfigSource()
    }

    private suspend fun handleError(e: ApiResult.Error) {
        DiagJournal.log("api", "error", "code=${e.code} vpn=${engine.state.value}")
        if (e.code == "signed_out") {
            signOutLocally()
            return
        }
        _syncError.value = e.code
        // API unreachable: keep showing the last known account; the installed config still connects.
        if (_account.value == AccountState.SignedOut) {
            api.offlineSnapshot()?.let { (account, sub) ->
                _account.value = AccountState.SignedIn(account, sub.entitlement, subscriptionState = sub.state)
                _canConnect.value = sub.canConnect
            }
        }
        refreshConfigSource()
    }

    private suspend fun signOutLocally() {
        checkoutGate.cancel()
        checkoutRequest?.cancel()
        if (_configSource.value == ConfigSource.Account) engine.stop("signed out")
        engine.installer.remove()
        _account.value = AccountState.SignedOut
        _canConnect.value = false
        _subscription.value = null
        _checkoutLink.value = null
        paymentWatch?.cancel()
        earlierAttempts.clear()
        lastRequest = null
        _canRetryCheckout.value = false
        if (_checkout.value !is CheckoutUi.Failed) _checkout.value = CheckoutUi.Idle
        _servers.value = ServerCatalog.preview
        _serversFromAccount.value = false
        _routeMissing.value = false
        prefs.routes = null
        refreshConfigSource()
    }

    // ---- Telegram login --------------------------------------------------------------------

    /**
     * Creates a login request and shows its number. Telegram is NOT opened here: the user reads the
     * number first and opens the bot with a button. Polling runs until done, failed or expired.
     */
    fun startTelegramLogin() {
        telegramJob?.cancel()
        _telegram.value = TelegramUi.Starting
        telegramJob = viewModelScope.launch {
            when (val r = api.startTelegramLogin()) {
                is ApiResult.Ok -> awaitTelegramLogin(r.value)
                is ApiResult.Error -> _telegram.value = TelegramUi.Failed(r.code)
                ApiResult.NotAvailable -> _telegram.value = TelegramUi.Idle
            }
        }
    }

    /**
     * Picks up a login request that is still pending (after returning from Telegram or an app
     * restart): same number, same request, polling continues. Never creates a new request.
     */
    fun resumeTelegramLogin() {
        if (telegramJob?.isActive == true) return
        val pending = api.pendingTelegramLogin() ?: return
        if (pending.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000) {
            api.cancelTelegramLogin()
            _telegram.value = TelegramUi.Failed("login_expired")
            return
        }
        telegramJob = viewModelScope.launch { awaitTelegramLogin(pending) }
    }

    private suspend fun awaitTelegramLogin(login: TelegramLogin) {
        _telegram.value = TelegramUi.Waiting(login.telegramUrl, login.matchCode, login.expiresAtEpochSeconds)
        while (System.currentTimeMillis() / 1000 < login.expiresAtEpochSeconds) {
            when (val r = api.pollTelegramLogin()) {
                is ApiResult.Ok -> if (r.value == LoginProgress.Done) {
                    _telegram.value = TelegramUi.Idle
                    syncNow()
                    return
                }
                is ApiResult.Error -> if (r.code != "network") {
                    _telegram.value = TelegramUi.Failed(r.code)
                    return
                }
                ApiResult.NotAvailable -> return
            }
            delay(2_000)
        }
        api.cancelTelegramLogin()
        _telegram.value = TelegramUi.Failed("login_expired")
    }

    fun cancelTelegramLogin() {
        telegramJob?.cancel()
        api.cancelTelegramLogin()
        _telegram.value = TelegramUi.Idle
    }

    // ---- email login -----------------------------------------------------------------------

    fun sendEmailCode(email: String, locale: String) {
        _email.value = EmailUi.Sending
        viewModelScope.launch {
            _email.value = when (val r = api.startEmailLogin(email.trim(), locale)) {
                is ApiResult.Ok -> EmailUi.EnterCode(email.trim())
                is ApiResult.Error -> EmailUi.Failed(r.code)
                ApiResult.NotAvailable -> EmailUi.EnterEmail
            }
        }
    }

    fun verifyEmailCode(code: String, onDone: () -> Unit) {
        val current = _email.value as? EmailUi.EnterCode ?: return
        _email.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val r = api.verifyEmailCode(current.email, code.trim())) {
                is ApiResult.Ok -> {
                    _email.value = EmailUi.EnterEmail
                    syncNow()
                    onDone()
                }
                is ApiResult.Error -> _email.value = current.copy(busy = false, error = r.code)
                ApiResult.NotAvailable -> _email.value = EmailUi.EnterEmail
            }
        }
    }

    /**
     * The user asks for a new code (e.g. the old one expired): one request per tap, at most once a minute;
     * the new code replaces the old one on the server. Never repeated in the background.
     */
    fun resendEmailCode(locale: String) {
        val current = _email.value as? EmailUi.EnterCode ?: return
        if (current.busy || android.os.SystemClock.elapsedRealtime() - current.sentAt < EMAIL_RESEND_MS) return
        _email.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            _email.value = when (val r = api.startEmailLogin(current.email, locale)) {
                is ApiResult.Ok -> EmailUi.EnterCode(current.email)
                is ApiResult.Error -> current.copy(busy = false, error = r.code)
                ApiResult.NotAvailable -> current.copy(busy = false)
            }
        }
    }

    fun resetEmail() {
        _email.value = EmailUi.EnterEmail
    }

    fun logout() {
        viewModelScope.launch {
            api.logout()
            signOutLocally()
        }
    }

    // ---- payment for the account's own profile --------------------------------------------

    /**
     * Asks the App API for a one-use payment link for [profileId] (one of the account's profiles; with
     * several the user must have picked one). Nothing else is ever opened instead: on any error the
     * user is told what to do, without a guest checkout. Repeated taps while a request runs are ignored.
     */
    fun startCheckout(plan: PlanCode, action: CheckoutAction, profileId: String?, language: String, channel: PaymentChannel) {
        val before = _checkout.value
        if (!Links.webCheckoutAllowed || before == CheckoutUi.Requesting) return
        // A paid order being credited holds a second payment; an unpaid attempt never locks the plans.
        if (before is CheckoutUi.Opened && CheckoutFlow.blocksNewPayment(before.state)) return
        val sub = _subscription.value
        val profiles = sub?.profiles.orEmpty()
        val id = profileId ?: profiles.singleOrNull()?.id
        val source = id?.let { (account.value as? AccountState.SignedIn)?.account?.vpnSources?.get(it) }
        if (id == null && profiles.size > 1) {
            _checkout.value = CheckoutUi.Failed(CheckoutProblem.ChooseProfile, action, null, true)
            return
        }
        // The attempt left behind stays valid on the server and is still followed: if it is paid later,
        // that order is credited (by the server, for its own profile) and its receipt is shown.
        if (before is CheckoutUi.Opened) keepEarlier(before)
        lastRequest = CheckoutRequest(plan, action, id, channel)
        _canRetryCheckout.value = true
        _checkout.value = CheckoutUi.Requesting
        _checkoutLink.value = null
        val accountId = (account.value as? AccountState.SignedIn)?.account?.id
        val ticket = checkoutGate.begin(accountId)
        checkoutRequest = viewModelScope.launch {
            val requestedAt = android.os.SystemClock.elapsedRealtime()
            val response = api.createCheckoutIntent(plan, action, id, CheckoutRules.apiLocale(language), channel)
            if (!checkoutGate.accepts(ticket, (account.value as? AccountState.SignedIn)?.account?.id)) return@launch
            when (val r = response) {
                is ApiResult.Ok -> {
                    DiagJournal.log("pay", "link ready", "channel=${r.value.channel} action=${action.wire} plan=$plan")
                    _checkout.value = CheckoutUi.Opened(r.value.channel, id ?: "", r.value.operationId)
                    _checkoutLink.value = PendingLink(r.value, requestedAt)
                }
                is ApiResult.Error -> {
                    val problem = CheckoutRules.problem(r.code, r.httpStatus)
                    // "code <x>" with a space: a long "code=<x>" would look like a token to the journal's masking.
                    DiagJournal.log("pay", "no link", "code ${r.code} http ${r.httpStatus} -> $problem")
                    _checkout.value = CheckoutUi.Failed(problem, action, source, profiles.isNotEmpty())
                    when (problem) {
                        CheckoutProblem.SignedOut -> if (!api.isSignedIn()) signOutLocally()
                        CheckoutProblem.ProfileGone, CheckoutProblem.ChooseProfile, CheckoutProblem.NoProfile -> syncNow()
                        else -> {}
                    }
                }
                ApiResult.NotAvailable -> _checkout.value = CheckoutUi.Failed(CheckoutProblem.FeatureOff, action, source, profiles.isNotEmpty())
            }
        }
    }

    /**
     * The UI takes the link once and opens it. A link older than its lifetime (120 s, counted from the
     * request) is not opened: the user taps again for a new one.
     */
    fun takeCheckoutLink(): CheckoutIntent? {
        val pending = _checkoutLink.value ?: return null
        _checkoutLink.value = null
        val age = android.os.SystemClock.elapsedRealtime() - pending.requestedAt
        val opened = _checkout.value as? CheckoutUi.Opened
        if (opened == null || age > (pending.intent.expiresInSeconds - 5) * 1000L) {
            DiagJournal.log("pay", "link expired before opening", "age=${age / 1000}s")
            _checkout.value = CheckoutUi.Failed(CheckoutProblem.Expired, CheckoutAction.Buy, null, true)
            return null
        }
        DiagJournal.log("pay", "link opened", "channel=${pending.intent.channel}")
        openedAt = android.os.SystemClock.elapsedRealtime()
        watchPayment()
        return pending.intent
    }

    /** "Return to payment" / "Get a new link": a fresh one-use link for the same plan, profile and channel. */
    fun retryCheckout(language: String) {
        val r = lastRequest ?: return
        startCheckout(r.plan, r.action, r.profileId, language, r.channel)
    }

    /** "Choose another plan": the status card goes away, the attempt keeps being followed. */
    fun chooseAnotherPlan() {
        val opened = _checkout.value as? CheckoutUi.Opened
        if (opened != null && CheckoutFlow.blocksNewPayment(opened.state)) return
        if (opened != null) keepEarlier(opened)
        _checkout.value = CheckoutUi.Idle
        watchPayment()
    }

    private fun keepEarlier(o: CheckoutUi.Opened) {
        if (o.operationId.isNotBlank()) earlierAttempts.keep(EarlierAttempts.Attempt(o.operationId, o.profileId, o.channel, o.state))
    }

    fun resetCheckout() {
        checkoutGate.cancel()
        checkoutRequest?.cancel()
        checkoutRequest = null
        paymentWatch?.cancel()
        _checkoutLink.value = null
        _checkout.value = CheckoutUi.Idle
        watchPayment() // earlier attempts, if any, are still followed
    }

    /** Leaving plans cancels an un-opened request/link; an already opened payment keeps its status. */
    fun cancelPendingCheckout() {
        if (_checkout.value == CheckoutUi.Requesting || _checkoutLink.value != null) resetCheckout()
    }

    /** Refresh actual order receipt plus current subscription; browser return proves nothing. */
    fun refreshPayment() { if (!_syncing.value) sync() }

    private var paymentRestoredFor: String? = null
    private suspend fun checkPayment(sub: SubscriptionView) {
        val accountId = (account.value as? AccountState.SignedIn)?.account?.id ?: return
        if (checkEarlierAttempts(sub, accountId)) return
        val opened = _checkout.value as? CheckoutUi.Opened
        if (opened == null && (_checkout.value != CheckoutUi.Idle || paymentRestoredFor == accountId)) return
        if (opened != null) _checkout.value = opened.copy(checking = true)
        val reply = api.checkoutStatus(opened?.operationId)
        if ((account.value as? AccountState.SignedIn)?.account?.id != accountId) return
        val now = _checkout.value
        if (opened != null && (now !is CheckoutUi.Opened || now.operationId != opened.operationId)) return
        if (opened == null && now != CheckoutUi.Idle) return
        if (reply !is ApiResult.Ok) {
            if (opened != null) _checkout.value = opened.copy(state = "unavailable")
            return
        }
        paymentRestoredFor = accountId
        val receipt = reply.value ?: return
        if (sub.profiles.none { it.id == receipt.profileId }) return
        if (opened != null && (receipt.profileId != opened.profileId || receipt.channel != opened.channel)) {
            _checkout.value = opened.copy(state = "unavailable")
            return
        }
        if (PaymentReceipts.confirms(opened?.operationId ?: receipt.id, opened?.profileId ?: receipt.profileId, receipt)) {
            _checkout.value = CheckoutUi.Confirmed(sub.profiles.firstOrNull { it.id == receipt.profileId }?.entitlement)
        } else if (opened != null || CheckoutFlow.restoreOnLaunch(receipt.state)) {
            // After a restart a link that never led to an order is not brought back: the plans stay free.
            if (opened == null) openedAt = android.os.SystemClock.elapsedRealtime()
            _checkout.value = CheckoutUi.Opened(receipt.channel, receipt.profileId, receipt.id, receipt.state)
        }
    }

    /**
     * Earlier attempts (another plan chosen, a new link opened) are asked about by their own id. A paid
     * one shows its own receipt; the current unpaid attempt is then followed as an earlier one in turn.
     * True when the screen now shows such a receipt.
     */
    private suspend fun checkEarlierAttempts(sub: SubscriptionView, accountId: String): Boolean {
        for (attempt in earlierAttempts.all) {
            val reply = api.checkoutStatus(attempt.id)
            if ((account.value as? AccountState.SignedIn)?.account?.id != accountId) return true
            val receipt = (reply as? ApiResult.Ok)?.value
            if (receipt == null) {
                // Gone on the server (404) means nothing to follow; a network error keeps it for later.
                if (reply is ApiResult.Error && reply.httpStatus == 404) earlierAttempts.drop(attempt.id)
                continue
            }
            if (receipt.profileId != attempt.profileId || receipt.channel != attempt.channel) {
                earlierAttempts.drop(attempt.id)
                continue
            }
            earlierAttempts.update(attempt.id, receipt.state)
            if (CheckoutFlow.blocksNewPayment(receipt.state) && sub.profiles.any { it.id == receipt.profileId }) {
                // An earlier invoice was paid and is being credited: shown again, so nobody pays twice.
                val now = _checkout.value
                if (now == CheckoutUi.Requesting) return true
                if (now is CheckoutUi.Opened && now.operationId != attempt.id) keepEarlier(now)
                earlierAttempts.drop(attempt.id)
                _checkout.value = CheckoutUi.Opened(receipt.channel, receipt.profileId, receipt.id, receipt.state)
                return true
            }
            if (PaymentReceipts.confirms(attempt.id, attempt.profileId, receipt) && sub.profiles.any { it.id == receipt.profileId }) {
                DiagJournal.log("pay", "earlier attempt paid", "state ${receipt.state}")
                val now = _checkout.value
                if (now is CheckoutUi.Opened && now.operationId != attempt.id) keepEarlier(now)
                if (now == CheckoutUi.Requesting) return true // the new link is on its way; shown on the next check
                _checkout.value = CheckoutUi.Confirmed(sub.profiles.firstOrNull { it.id == receipt.profileId }?.entitlement)
                return true
            }
        }
        return false
    }

    /** Visible only, bounded15min. No URLs, tokens or receipt payloads are persisted. */
    private fun watchPayment() {
        paymentWatch?.cancel()
        fun following(): Boolean {
            val active = _checkout.value as? CheckoutUi.Opened
            return (active != null && active.state !in CheckoutFlow.terminal) || !earlierAttempts.isEmpty()
        }
        if (!visible || !following()) return
        paymentWatch = viewModelScope.launch {
            // Immediate server check after returning from a browser, then at most once/20s.
            while (following() && android.os.SystemClock.elapsedRealtime() - openedAt < 15 * 60_000L) {
                refreshPayment()
                delay(20_000)
            }
        }
    }

    // ---- preferences / servers -------------------------------------------------------------

    fun markWelcomeSeen() {
        prefs.welcomeSeen = true
    }

    fun setTheme(mode: ThemeMode) {
        prefs.themeMode = mode
        _theme.value = mode
    }

    fun selectServer(choice: ServerChoice) {
        prefs.serverChoice = choice
        _serverChoice.value = choice
        _routeMissing.value = false
        if (!_canConnect.value || _sample.value != null) return
        switchJob?.cancel() // only the latest choice counts
        switchJob = viewModelScope.launch { switchTo(choice) }
    }

    /**
     * A route switch is local: the choice is saved into the installed config and the running engine
     * switches in place (no App API call through the VPN, no restart, the tunnel stays up). Only a
     * route the installed config does not have yet needs the account's config (and then a restart).
     */
    private suspend fun switchTo(choice: ServerChoice) {
        val tag = (choice as? ServerChoice.Specific)?.id?.let(RouteMonitor::tagOf) ?: RouteMonitor.AUTO_TAG
        if (engine.installer.setSelected(tag)) {
            val connected = engine.state.value == EngineState.Connected
            val inPlace = connected && routeMonitor.select(tag)
            DiagJournal.log("route", "user chose", "$tag " + when { !connected -> "(saved, VPN off)"; inPlace -> "(switched in place)"; else -> "(engine did not take it: restart)" })
            if (connected && !inPlace) _restart.tryEmit(Unit)
            return
        }
        DiagJournal.log("route", "user chose", "$tag (not in the installed config: account config needed)")
        installConfig(routesKnown = _serversFromAccount.value)
    }

    // ---- route availability (cold check when the route list opens) -------------------------

    /**
     * Once per opening of the route list: each route of the running config is tested on its own from
     * the current network (see [RouteCheck]). Only while the VPN is connected: without the engine
     * there is no safe way to test a route, so nothing is shown. Never restarts or switches the VPN.
     */
    fun startRouteCheck() {
        val wasOpen = routeListVisible
        routeListVisible = true
        if (wasOpen && routeCheckJob?.isActive == true) return
        restartRouteCheck()
    }

    private fun restartRouteCheck() {
        routeCheckJob?.cancel()
        routeCheckJob = null
        _availability.value = emptyMap()
        if (!routeListVisible || !visible || engine.state.value != EngineState.Connected || !_serversFromAccount.value || _sample.value != null) return
        routeCheckJob = viewModelScope.launch {
            // The groups view may still be connecting right after the list opened.
            var waited = 0
            while (routeMonitor.snapshot.value.delays.isEmpty() && waited < 5_000) { delay(250); waited += 250 }
            val inConfig = routeMonitor.snapshot.value.delays.keys
            val tags = _servers.value.map { RouteMonitor.tagOf(it.id) }.filter { it in inConfig }
            if (tags.isEmpty()) return@launch
            DiagJournal.log("route", "cold check", "routes=${tags.size}")
            RouteCheck({ routeMonitor.testRoute(it) }, routeMonitor::testedAt, { System.currentTimeMillis() / 1000 }, { delay(it) })
                .run(tags) { m -> _availability.value = m.mapNotNull { (tag, a) -> RouteMonitor.serverId(tag)?.let { it to a } }.toMap() }
            val r = _availability.value.values
            DiagJournal.log("route", "cold check done", "ok=${r.count { it == RouteAvailability.Ok }} failed=${r.count { it == RouteAvailability.Failed }} unknown=${r.count { it == RouteAvailability.Unknown }}")
        }
    }

    /** The list was closed (or the VPN stopped): the check ends and its results are forgotten. */
    fun stopRouteCheck() {
        routeListVisible = false
        routeCheckJob?.cancel()
        routeCheckJob = null
        _availability.value = emptyMap()
    }

    // ---- route latency (measured by sing-box urltest through each route, only while connected) --

    /**
     * The saved route's id is gone but exactly one new route is the same route (same country,
     * label, stated type and chain), e.g. after the App API changed its id scheme: keep the choice.
     * Anything ambiguous is left to [routeMissing] and the user.
     */
    private fun migrateChoice(old: List<Server>, new: List<Server>) {
        val id = (_serverChoice.value as? ServerChoice.Specific)?.id ?: return
        if (new.any { it.id == id }) return
        val was = old.firstOrNull { it.id == id } ?: return
        // Same country and label; the stated type must agree (an older list may not have stated one yet).
        val same = new.filter {
            it.countryCode == was.countryCode && was.sourceLabel != null && it.sourceLabel == was.sourceLabel &&
                (was.kind == null || (it.kind == was.kind && it.path == was.path))
        }
        val match = same.singleOrNull() ?: return
        prefs.serverChoice = ServerChoice.Specific(match.id)
        _serverChoice.value = ServerChoice.Specific(match.id)
    }

    private fun resetTraffic() {
        checkEpoch++
        _traffic.value = TrafficState.Unknown
        _liveMs.value = null
        _checkFailures.value = 0
    }

    /** The app is on screen (or not): check the route in use now and then every 30 s, only while visible. */
    fun onForeground(isVisible: Boolean) {
        DiagJournal.log("app", if (isVisible) "on screen" else "in background", "vpn=${engine.state.value}")
        visible = isVisible
        restartRouteCheck()
        restartChecks()
        // A revocation left from an offline logout is delivered when the app is opened (bounded).
        if (isVisible && api.isAvailable) viewModelScope.launch { api.deliverPendingCancellations() }
        if (isVisible) watchPayment() else paymentWatch?.cancel()
    }

    private fun restartChecks() {
        checkJob?.cancel()
        checkJob = null
        if (!visible) return
        checkJob = viewModelScope.launch {
            while (true) {
                if (engine.state.value == EngineState.Connected) checkTunnel()
                // After a failure, look again sooner: to confirm it, or to see it is over.
                delay(if (_checkFailures.value > 0) 15_000 else 30_000)
            }
        }
    }

    /**
     * One check through the tunnel. A single failure only hides the delay ("—"); a loss of data is
     * shown after two failures in a row. Never disconnects, reconnects or switches the route.
     */
    private suspend fun checkTunnel() {
        val network = NetworkIdentity.key(getApplication())
        if (network != checkNetwork) {
            val hadNetwork = checkNetwork != null
            checkNetwork = network
            resetTraffic() // Wi-Fi <-> mobile: earlier results say nothing about this network
            if (hadNetwork) restartRouteCheck()
        }
        val epoch = checkEpoch
        val result = TunnelCheck.run()
        DiagJournal.log("check", "tunnel (app on screen)", when (result) {
            is TunnelCheck.Result.Ok -> "ok ${result.ms?.let { "${it}ms" } ?: "(second site)"}"
            is TunnelCheck.Result.Failed -> "FAILED dns=${result.dns}"
        })
        if (epoch != checkEpoch || engine.state.value != EngineState.Connected) return
        when (result) {
            is TunnelCheck.Result.Ok -> {
                if (_traffic.value == TrafficState.Lost || _traffic.value == TrafficState.NoNetwork) DiagJournal.log("check", "status shown", "Ok (recovered)")
                _checkFailures.value = 0
                _liveMs.value = result.ms
                _traffic.value = TrafficState.Ok
            }
            is TunnelCheck.Result.Failed -> {
                _checkFailures.value += 1
                _liveMs.value = null
                android.util.Log.w("MarzTraffic", "check through the tunnel failed (dns=${result.dns}, in a row=${_checkFailures.value})")
                if (_checkFailures.value >= 2) {
                    val state = if (NetworkIdentity.underlyingOnline(getApplication())) TrafficState.Lost else TrafficState.NoNetwork
                    if (_traffic.value != state) DiagJournal.log("check", "status shown", state.name)
                    _traffic.value = state
                }
            }
        }
    }

    fun notificationPermissionAsked() = prefs.notificationPermissionAsked

    fun markNotificationPermissionAsked() {
        prefs.notificationPermissionAsked = true
    }

    fun requestOpen(screen: String) {
        _openRequests.tryEmit(screen)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeOpenRequest() {
        _openRequests.resetReplayCache()
    }

    fun refreshConfigSource() {
        viewModelScope.launch { _configSource.value = engine.configSource() }
    }

    /** Returns a VPN permission intent to launch, or null. Never reports success by itself. */
    suspend fun toggleConnection(activity: Activity): Intent? = when (engine.state.value) {
        EngineState.Connected, EngineState.Connecting -> {
            engine.stop("user")
            null
        }
        EngineState.Disconnected -> engine.start(activity, "user")
        EngineState.Disconnecting, EngineState.Unknown -> null
    }

    // ---- developer tools (debug builds only) -----------------------------------------------

    /** Returns true exactly when this tap unlocks the developer entry. */
    fun onVersionTap(): Boolean {
        if (!developerToolsAvailable || _developerUnlocked.value) return false
        versionTaps++
        if (versionTaps >= 7) {
            _developerUnlocked.value = true
            return true
        }
        return false
    }

    /** Debug only: takes effect after the app restarts. */
    fun setApiUrlOverride(url: String?) {
        if (developerToolsAvailable) prefs.apiUrlOverride = url?.trim()?.ifEmpty { null }
    }

    fun showSample(kind: SampleKind?) {
        if (!developerToolsAvailable) return
        _sample.value = kind
        if (kind == null) {
            _account.value = AccountState.SignedOut
            if (api.isSignedIn()) sync()
        } else {
            _account.value = sampleAccount(kind)
        }
    }

    private fun sampleAccount(kind: SampleKind): AccountState.SignedIn {
        val now = Instant.now()
        val entitlement = when (kind) {
            SampleKind.Trial -> Entitlement.Trial(now.plus(2, ChronoUnit.DAYS), Catalog.trial.quotaBytes, 1_288_490_189L)
            SampleKind.Basic -> Entitlement.Basic(now.plus(21, ChronoUnit.DAYS), 100 * Catalog.GIB, 38 * Catalog.GIB, 5 * Catalog.GIB)
            SampleKind.Unlimited -> Entitlement.Unlimited(now.plus(27, ChronoUnit.DAYS), null)
        }
        return AccountState.SignedIn(
            account = Account(id = "sample", identities = listOf(Identity(Identity.Kind.Telegram, "@sample"))),
            entitlement = entitlement,
            isSample = true,
        )
    }
}
