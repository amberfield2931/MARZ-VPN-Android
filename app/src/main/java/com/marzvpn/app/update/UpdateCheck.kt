package com.marzvpn.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** The update check as the screens show it. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object Latest : UpdateUi
    data class Available(val version: String) : UpdateUi
    data object Failed : UpdateUi
}

/** What one manifest says to this app. */
sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Newer(val version: String, val code: Long) : UpdateResult
    data object Invalid : UpdateResult
}

object UpdateRules {
    const val EVERY_MS = 24L * 60 * 60 * 1000
    const val MAX_MANIFEST = 64 * 1024
    private val PUBLISHED = Regex("""\d+\.\d+\.\d+(-\d+)?""")

    /** At most once in 24 hours; a clock moved back must not stop the checks for good. */
    fun due(lastCheck: Long, now: Long) = lastCheck <= 0 || lastCheck > now || now - lastCheck >= EVERY_MS

    /** Android compares build numbers, so a test suffix of the installed build changes nothing. A test version is never offered. */
    fun decide(schema: Int, channel: String?, version: String?, code: Long, installedCode: Long): UpdateResult {
        if (schema != 1 || channel != "stable" || version == null || !PUBLISHED.matches(version) || code <= 0) return UpdateResult.Invalid
        return if (code > installedCode) UpdateResult.Newer(version, code) else UpdateResult.UpToDate
    }

    fun parse(manifest: String, installedCode: Long): UpdateResult = runCatching {
        val root = JSONObject(manifest)
        val entry = root.getJSONObject("platforms").getJSONObject("android")
        decide(root.getInt("schema"), entry.getString("channel"), entry.getString("version"), entry.getLong("version_code"), installedCode)
    }.getOrDefault(UpdateResult.Invalid)
}

/**
 * Reads one small public file. Sends no token, cookie, account or device data: the fixed User-Agent replaces the system one, which
 * names the phone model. Touches neither the VPN nor its service, and nothing is downloaded or installed.
 */
class UpdateClient(private val url: String) {
    suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "MARZ-VPN")
                if (conn.responseCode != 200) return@runCatching null
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > UpdateRules.MAX_MANIFEST) return@runCatching null
                    }
                }
                out.toString("UTF-8")
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
