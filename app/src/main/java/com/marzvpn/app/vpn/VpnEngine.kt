package com.marzvpn.app.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.marzvpn.app.diag.DiagJournal
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [Unknown]: the service has not answered yet (e.g. right after the activity was recreated). */
enum class EngineState { Unknown, Disconnected, Connecting, Connected, Disconnecting }

/** Where the engine config comes from. The UI shows this so nothing pretends to be an account. */
enum class ConfigSource {
    /** No config: preview build, signed out. Connecting is impossible. */
    None,

    /** An engineer-imported profile from Diagnostics → engine tools (device tests only). */
    EngineerProfile,

    /** Future: config delivered by the App API for the signed-in account. */
    Account,
}

/**
 * Thin wrapper over the upstream sing-box service (BoxService / VPNService). The UI never touches
 * libbox or upstream classes directly, and this class knows nothing about accounts or payments.
 */
class VpnEngine(private val app: Application) : ServiceConnection.Callback {
    private val connection = ServiceConnection(app, this)
    // Unknown until the service reports: a recreated activity must not show "disconnected" (and offer
    // a second start) while the VPN is running, nor "connected" while it is not.
    private val _state = MutableStateFlow(EngineState.Unknown)
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val coreVersion: String by lazy { runCatching { Libbox.version() }.getOrDefault("?") }

    fun bind() {
        connection.connect()
        // BIND_AUTO_CREATE answers within milliseconds; if the binding fails, stop showing "checking".
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (_state.value == EngineState.Unknown) _state.value = EngineState.Disconnected
        }, 3_000)
    }
    fun unbind() = connection.disconnect()

    val installer = ConfigInstaller(app)

    suspend fun configSource(): ConfigSource = withContext(Dispatchers.IO) {
        val id = Settings.selectedProfile
        when {
            id == -1L || ProfileManager.get(id) == null -> ConfigSource.None
            id == installer.managedProfileId -> ConfigSource.Account
            else -> ConfigSource.EngineerProfile
        }
    }

    private val restartLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Apply a new config: if connected, restart so the new routes take effect. One restart at a time;
     * a start still in progress counts as running (its config may already be outdated), and a new
     * instance is never started while the previous one is still stopping.
     */
    suspend fun restartIfRunning(activity: Activity?, reason: String) = restartLock.withLock {
        if (activity == null) return@withLock
        DiagJournal.log("service", "restart requested", reason)
        val settled = kotlinx.coroutines.withTimeoutOrNull(20_000) {
            state.first { it == EngineState.Connected || it == EngineState.Disconnected }
        }
        if (settled != EngineState.Connected) return@withLock DiagJournal.log("service", "restart skipped", "not connected: $settled")
        stop("restart: $reason")
        kotlinx.coroutines.withTimeoutOrNull(20_000) { state.first { it == EngineState.Disconnected } }
            ?: return@withLock DiagJournal.log("service", "restart abandoned", "engine still stopping after 20 s")
        start(activity, "restart: $reason")
        kotlinx.coroutines.withTimeoutOrNull(20_000) { state.first { it == EngineState.Connected || it == EngineState.Disconnected } }
    }

    /** Returns a VPN-permission intent the activity must launch first, or null when started. */
    suspend fun start(activity: Activity, reason: String): Intent? {
        DiagJournal.log("service", "connect", reason)
        _lastError.value = null
        if (configSource() == ConfigSource.None) return null
        val mode = withContext(Dispatchers.IO) {
            if (Settings.rebuildServiceMode()) withContext(Dispatchers.Main) { connection.reconnect() }
            Settings.serviceMode
        }
        if (mode == ServiceMode.VPN) {
            val prepare = try {
                VpnService.prepare(activity)
            } catch (e: Exception) {
                _lastError.value = e.message
                return null
            }
            if (prepare != null) return prepare
        }
        withContext(Dispatchers.Main) {
            ContextCompat.startForegroundService(app, Intent(app, Settings.serviceClass()))
        }
        withContext(Dispatchers.IO) { Settings.startedByUser = true }
        return null
    }

    fun stop(reason: String) {
        DiagJournal.log("service", "disconnect", reason)
        BoxService.stop()
    }

    override fun onServiceStatusChanged(status: Status) {
        _state.value = when (status) {
            Status.Stopped -> EngineState.Disconnected
            Status.Starting -> EngineState.Connecting
            Status.Started -> EngineState.Connected
            Status.Stopping -> EngineState.Disconnecting
        }
    }

    override fun onServiceAlert(type: Alert, message: String?) {
        DiagJournal.log("service", "alert", "$type ${message.orEmpty()}")
        _lastError.value = message ?: type.name
    }
}
