package com.marzvpn.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real traffic through the VPN, sent the way any app sends it: the app's own socket goes through
 * the tunnel (system DNS -> the tunnel's DNS, then the route in use). Nothing is added to the engine
 * config and the VPN itself is never touched: one small check of the route in use, nothing else.
 *
 * [Result.Ok] carries the warm delay of the route in use: one request on a new connection warms the
 * path (handshake, server-side connect, cascade transit) and is discarded, then [ROUNDS] HEAD requests
 * on the same connection; the lowest counts, at least two must succeed. Null when only the second
 * site answered (data flows, but no reliable delay).
 */
object TunnelCheck {
    sealed interface Result {
        data class Ok(val ms: Int?) : Result

        /** Nothing came back through the tunnel; [dns]: names did not resolve either. */
        data class Failed(val dns: Boolean) : Result
    }

    private const val HOST = "captive.apple.com"
    private const val PATH = "/hotspot-detect.html"
    private const val SECOND_HOST = "connectivitycheck.gstatic.com"
    private const val SECOND_PATH = "/generate_204"
    private const val ROUNDS = 3
    private const val TIMEOUT_MS = 5_000

    suspend fun run(): Result = withContext(Dispatchers.IO) {
        warm()?.let { return@withContext Result.Ok(it) }
        if (once(SECOND_HOST, SECOND_PATH)) return@withContext Result.Ok(null)
        Result.Failed(dns = runCatching { InetAddress.getByName(SECOND_HOST) }.isFailure)
    }

    private fun warm(): Int? = runCatching {
        connect(HOST).use { s ->
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val request = head(HOST, PATH, keepAlive = true)
            exchange(input, out, request) // warm-up, not counted
            val times = (1..ROUNDS).mapNotNull {
                runCatching {
                    val t = System.nanoTime()
                    exchange(input, out, request)
                    ((System.nanoTime() - t) / 1_000_000).toInt()
                }.getOrNull()
            }
            if (times.size >= 2) times.min() else null
        }
    }.getOrNull()

    private fun once(host: String, path: String): Boolean = runCatching {
        connect(host).use { s -> exchange(s.getInputStream(), s.getOutputStream(), head(host, path, keepAlive = false)) }
        true
    }.getOrDefault(false)

    private fun connect(host: String) = Socket().apply {
        soTimeout = TIMEOUT_MS
        connect(InetSocketAddress(host, 80), TIMEOUT_MS)
    }

    private fun head(host: String, path: String, keepAlive: Boolean) =
        "HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n".toByteArray()

    /** Sends one HEAD request and reads its response headers (a HEAD response has no body). */
    private fun exchange(input: InputStream, out: OutputStream, request: ByteArray) {
        out.write(request); out.flush()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        var first = true
        while (matched < 4) {
            val b = input.read()
            if (b < 0) error("closed")
            if (first) { check(b == 'H'.code) { "not_http" }; first = false }
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
        }
    }
}
