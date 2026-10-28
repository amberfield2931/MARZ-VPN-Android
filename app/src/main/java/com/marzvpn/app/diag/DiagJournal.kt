package com.marzvpn.app.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Chronological diagnostic journal for real devices: VPN service and engine lifecycle, tunnel,
 * reconnect reasons, Android network changes, the route in use, real traffic checks and errors.
 *
 * Test builds (pilot, lab, debug): the app's external files folder (`adb pull
 * /sdcard/Android/data/<package>/files/marz-diag.log`), mirrored to logcat (tag MarzDiag). Production
 * (release): private app storage only, no logcat. At most ~2 x 1 MB. Never contains full IP addresses,
 * UUIDs, keys, tokens or the hosts users visit: every line goes through [sanitize]. Nothing is sent anywhere.
 */
object DiagJournal {
    private const val TAG = "MarzDiag"
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null
    private val lock = Any()
    private val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Production builds keep the journal private and out of logcat. */
    val production: Boolean = io.nekohasekai.sfa.BuildConfig.BUILD_TYPE == "release"

    fun init(context: Context) {
        runCatching {
            val dir = (if (production) context.filesDir else context.getExternalFilesDir(null)) ?: return
            dir.mkdirs()
            file = File(dir, "marz-diag.log")
        }
    }

    /** One line: `time  area  event  details`. [area]: service, tun, engine, net, route, check, api, app. */
    fun log(area: String, event: String, details: String = "") {
        val line = "${synchronized(time) { time.format(Date()) }}  $area  $event" + if (details.isEmpty()) "" else "  ${sanitize(details)}"
        if (!production) Log.i(TAG, line)
        val target = file ?: return
        synchronized(lock) {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val old = File(target.parentFile, target.name + ".1")
                    old.delete()
                    target.renameTo(old)
                }
                target.appendText(line + "\n")
            }
        }
    }

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val ipv4 = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b")
    private val ipv6 = Regex("\\b([0-9a-fA-F]{1,4}):([0-9a-fA-F]{0,4}):[0-9a-fA-F:]{2,}\\b")
    private val token = Regex("[A-Za-z0-9_\\-+/=]{32,}")
    /** A host name (letters, a dot, a top-level domain): which sites people visit is not ours to keep. */
    private val host = Regex("\\b(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}\\b")

    /** Masks client data: IPv4 -> a.b.x.x, IPv6 -> a:b:…, UUIDs and long tokens removed. */
    fun sanitize(s: String): String = s
        .replace(uuid, "<uuid>")
        .replace(host, "<host>")
        .replace(token, "<token>")
        .replace(ipv4) { "${it.groupValues[1]}.${it.groupValues[2]}.x.x" }
        .replace(ipv6) { "${it.groupValues[1]}:${it.groupValues[2]}:…" }
        .take(400)
}
