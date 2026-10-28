package com.marzvpn.app.diag

import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.compose.model.toList
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * While the VPN service runs: what the engine really does, for [DiagJournal]. Once a minute the
 * open connections and traffic totals (do sessions pile up? does data still flow?), every route
 * change, the engine's own warnings/errors (deduplicated, at most [MAX_ENGINE_LINES] a minute), and
 * every 2 minutes one real HTTPS request through the tunnel (the app's socket goes through the VPN
 * like any app). Observes only: never restarts, reconnects or switches anything.
 */
object EngineWatch : CommandClient.Handler {
    private const val MAX_ENGINE_LINES = 20
    private const val HTTPS_URL = "https://www.gstatic.com/generate_204"
    private var scope: CoroutineScope? = null
    private var client: CommandClient? = null
    @Volatile private var last: StatusMessage? = null
    private var lastSelected: String? = null
    private var engineLines = 0
    private var lastLine: String? = null
    private var minute = 0L

    @Synchronized
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        client = CommandClient(s, listOf(CommandClient.ConnectionType.Status, CommandClient.ConnectionType.Log, CommandClient.ConnectionType.Groups), this, localOnly = true)
            .also { it.connect() }
        s.launch { statusLoop() }
        // The periodic HTTPS request is test-build diagnostics only: production spends no traffic on it.
        if (!DiagJournal.production) s.launch { httpsLoop() }
    }

    @Synchronized
    fun stop() {
        client?.disconnect()
        client = null
        scope?.cancel()
        scope = null
        last = null
        lastSelected = null
    }

    private suspend fun statusLoop() {
        delay(5_000)
        while (scope?.isActive == true) {
            last?.let {
                DiagJournal.log("engine", "status", "connections=${it.connectionsIn}/${it.connectionsOut} up=${it.uplinkTotal} down=${it.downlinkTotal} mem=${it.memory / 1_000_000}MB")
            }
            delay(60_000)
        }
    }

    private suspend fun httpsLoop() {
        delay(20_000)
        while (scope?.isActive == true) {
            val t = System.nanoTime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URL(HTTPS_URL).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000
                    c.readTimeout = 10_000
                    c.useCaches = false
                    try { c.responseCode } finally { c.disconnect() }
                }
            }
            val ms = (System.nanoTime() - t) / 1_000_000
            result.fold(
                { DiagJournal.log("check", "https through VPN", "ok code=$it ${ms}ms") },
                { DiagJournal.log("check", "https through VPN", "FAILED after ${ms}ms: ${it.javaClass.simpleName} ${it.message.orEmpty()}") },
            )
            delay(120_000)
        }
    }

    override fun onConnected() = DiagJournal.log("engine", "watch connected")

    override fun onDisconnected() = DiagJournal.log("engine", "watch disconnected")

    override fun updateStatus(status: StatusMessage) {
        last = status
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        val selector = newGroups.firstOrNull { it.tag == "proxy" } ?: return
        if (selector.selected != lastSelected) {
            DiagJournal.log("route", "selected", "${lastSelected ?: "-"} -> ${selector.selected}")
            lastSelected = selector.selected
        }
        // The engine's URL test of every route (connections to all servers): when, and how many answered.
        val items = newGroups.firstOrNull { it.tag == "auto" }?.items?.toList() ?: return
        val tested = items.map { it.urlTestTime }.filter { it > 0 }
        val newest = tested.maxOrNull() ?: return
        if (newest != lastUrlTest) {
            lastUrlTest = newest
            DiagJournal.log("engine", "url test of all routes", "tested=${tested.size}/${items.size} answered=${items.count { it.urlTestDelay > 0 }} auto=${newGroups.firstOrNull { it.tag == "auto" }?.selected}")
        }
    }

    private var lastUrlTest = 0L
    private val perConnection = Regex("(inbound|outbound) (packet )?connection (to|from) |inbound DNS packet from |router: found package name")

    override fun appendLogs(message: List<LogEntry>) {
        for (entry in message) {
            // sing-box levels: 0 panic .. 2 error, 3 warn, 4 info, 5 debug, 6 trace. Info and above, without
            // the per-connection lines (one per opened connection would drown the journal).
            if (entry.level > 4 || perConnection.containsMatchIn(entry.message)) continue
            val now = System.currentTimeMillis() / 60_000
            if (now != minute) { minute = now; engineLines = 0 }
            val text = entry.message.replace(Regex("\u001B\\[[0-9;]*m"), "").replace(Regex("^\\S+\\[\\d+]\\s*"), "").trim()
            val key = text.replace(Regex("\\[\\d+ [^]]*]"), "").take(120) // same message, other connection id
            if (key == lastLine || engineLines >= MAX_ENGINE_LINES) continue
            lastLine = key
            engineLines++
            DiagJournal.log("engine", "log", "level=${entry.level} $text")
        }
    }
}
