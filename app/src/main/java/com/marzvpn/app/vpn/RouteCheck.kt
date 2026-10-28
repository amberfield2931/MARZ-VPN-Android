package com.marzvpn.app.vpn

/** What the route list shows next to a route after the cold check (no numbers). */
enum class RouteAvailability {
    /** Being checked, or nothing known (VPN off, route not in the running config). */
    Unknown,
    /** A real connection through this route answered (VLESS+REALITY and an HTTPS request). */
    Ok,
    /** Two checks in a row found no working connection through this route from this network. */
    Failed,
}

/**
 * One cold check of the given routes from the current network, inside the running VPN engine: each
 * route is URL-tested on its own (a new VLESS+REALITY connection to that server and a small HTTPS
 * request through it). A single-route test never changes the selected route or closes connections
 * (unlike testing the "auto"/"proxy" groups, which can switch Automatic and interrupt its connections).
 *
 * A route is [RouteAvailability.Ok] when a test result newer than the start of this check appears, and
 * [RouteAvailability.Failed] only when two rounds give no result: one timeout is never shown as red.
 * Plain Kotlin: unit-tested on the JVM.
 */
class RouteCheck(
    /** Starts the engine's test of one route; false when the engine refused (stays Unknown). */
    private val test: suspend (tag: String) -> Boolean,
    /** Unix seconds of the route's last successful test, or null (failed tests leave none). */
    private val testedAt: (tag: String) -> Long?,
    private val nowSeconds: () -> Long,
    private val sleep: suspend (ms: Long) -> Unit,
) {
    suspend fun run(tags: List<String>, onUpdate: (Map<String, RouteAvailability>) -> Unit) {
        val result = tags.associateWith { RouteAvailability.Unknown }.toMutableMap()
        onUpdate(result.toMap())
        var open = tags
        for (round in 1..ROUNDS) {
            val start = nowSeconds()
            open = open.filter { test(it) }
            val deadline = start + WAIT_SECONDS
            while (open.isNotEmpty() && nowSeconds() < deadline) {
                sleep(POLL_MS)
                val done = open.filter { tag -> testedAt(tag)?.let { it >= start } == true }
                if (done.isNotEmpty()) {
                    done.forEach { result[it] = RouteAvailability.Ok }
                    open = open - done.toSet()
                    onUpdate(result.toMap())
                }
            }
        }
        open.forEach { result[it] = RouteAvailability.Failed }
        onUpdate(result.toMap())
    }

    companion object {
        const val ROUNDS = 2
        /** The engine gives one test 15 s (sing-box TCPTimeout); a little more for the answer to arrive. */
        const val WAIT_SECONDS = 20L
        const val POLL_MS = 500L
    }
}
