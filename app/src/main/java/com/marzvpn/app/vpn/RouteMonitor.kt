package com.marzvpn.app.vpn

import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.compose.model.toList
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Read-only view of the running engine's route groups (libbox "groups" command): which outbound
 * really carries traffic and each route's URL-test delay, measured by sing-box through that route.
 * It selects an outbound only when the user picks a route ([select]); [urlTest] only asks the
 * existing urltest group to measure now.
 *
 * Group tags come from the App API config: selector "proxy" (Auto or one route) and urltest "auto".
 */
class RouteMonitor(scope: CoroutineScope) : CommandClient.Handler {
    data class Delay(val ms: Int, val testedAtSeconds: Long)

    data class Snapshot(val selector: String? = null, val auto: String? = null, val delays: Map<String, Delay> = emptyMap()) {
        /** The outbound tag traffic goes through now: the selector's choice, resolved through urltest. */
        val activeTag: String? get() = if (selector == AUTO_TAG) auto else selector
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    private val client = CommandClient(scope, CommandClient.ConnectionType.Groups, this, localOnly = true)
    private var running = false

    fun start() {
        if (running) return
        running = true
        client.connect()
    }

    fun stop() {
        if (!running) return
        running = false
        client.disconnect()
        _snapshot.value = Snapshot()
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        val selector = newGroups.firstOrNull { it.tag == SELECTOR_TAG }
        val auto = newGroups.firstOrNull { it.tag == AUTO_TAG }
        val delays = auto?.items?.toList()?.associate { item ->
            // libbox reports the test time as Unix seconds; accept milliseconds defensively.
            val t = item.urlTestTime.let { if (it > 100_000_000_000L) it / 1000 else it }
            item.tag to Delay(item.urlTestDelay, t)
        } ?: emptyMap()
        _snapshot.value = Snapshot(selector?.selected, auto?.selected, delays)
    }

    override fun onDisconnected() {
        _snapshot.value = Snapshot()
    }

    /**
     * Switches the running engine to [tag] (a route or Automatic) in place: no restart, no App API,
     * the tunnel stays up. The selector closes the previous route's connections. False if the engine
     * did not take it (e.g. not running yet).
     */
    suspend fun select(tag: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { CommandTarget.standaloneClient().selectOutbound(SELECTOR_TAG, tag) }.isSuccess
    }

    /**
     * Tests one route now (a new connection through it and an HTTPS request; the result arrives in
     * [snapshot]). Only single routes: testing the "auto" or "proxy" group can switch Automatic and
     * close its connections, a single route's test changes nothing but its own result.
     */
    suspend fun testRoute(tag: String): Boolean = withContext(Dispatchers.IO) {
        tag.startsWith(SERVER_PREFIX) && runCatching { CommandTarget.standaloneClient().urlTest(tag) }.isSuccess
    }

    /** Unix seconds of [tag]'s last successful test in the running engine (none after a failure). */
    fun testedAt(tag: String): Long? = _snapshot.value.delays[tag]?.takeIf { it.ms > 0 && it.testedAtSeconds > 0 }?.testedAtSeconds

    companion object {
        const val SELECTOR_TAG = "proxy"
        const val AUTO_TAG = "auto"
        private const val SERVER_PREFIX = "srv-"

        fun serverId(tag: String?): String? = tag?.takeIf { it.startsWith(SERVER_PREFIX) }?.removePrefix(SERVER_PREFIX)
        fun tagOf(serverId: String) = SERVER_PREFIX + serverId
    }
}
