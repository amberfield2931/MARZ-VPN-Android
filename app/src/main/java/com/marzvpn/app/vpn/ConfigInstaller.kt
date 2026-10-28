package com.marzvpn.app.vpn

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps exactly one engine profile, "MARZ VPN", whose file is the config delivered by the App API.
 * The file lives in the app's private storage; it is never exported, shared or shown.
 */
class ConfigInstaller(private val context: Context) {
    private val prefs = context.getSharedPreferences("marz_engine", Context.MODE_PRIVATE)

    var managedProfileId: Long
        get() = prefs.getLong(KEY_PROFILE, -1L)
        private set(value) = prefs.edit().putLong(KEY_PROFILE, value).apply()

    private fun file() = File(File(context.filesDir, "configs").also { it.mkdirs() }, "marz-account.json")

    /** What an install did. Only [Changed] needs a running VPN to restart. */
    enum class Install { Unchanged, Refreshed, Changed }

    /** Validates with the same libbox the engine uses, then installs and selects the profile. */
    suspend fun install(apiConfig: String): Result<Install> = withContext(Dispatchers.IO) {
        writeLock.withLock { installLocked(apiConfig) }
    }

    private suspend fun installLocked(apiConfig: String): Result<Install> =
        runCatching {
            // 4.5 added a loopback latency inbound to the config; 4.5.1 installs the App API config
            // as is again (plus the URL test defaults), so its leftover settings go.
            if (prefs.contains("probe_port") || prefs.contains("probe_secret")) prefs.edit().remove("probe_port").remove("probe_secret").apply()
            val config = withUrlTestDefaults(apiConfig)
            Libbox.checkConfig(config)
            val target = file()
            val old = if (target.exists()) target.readText() else null
            val change = when {
                old == config -> Install.Unchanged
                old != null && material(old) == material(config) -> Install.Refreshed
                else -> Install.Changed
            }
            if (change != Install.Unchanged) write(target, config)
            val existing = managedProfileId.takeIf { it != -1L }?.let { ProfileManager.get(it) }
            if (existing == null) {
                val profile = Profile(name = "MARZ VPN", typed = TypedProfile().apply {
                    type = TypedProfile.Type.Local
                    path = target.path
                })
                profile.userOrder = ProfileManager.nextOrder()
                managedProfileId = ProfileManager.create(profile, andSelect = true).id
            } else if (Settings.selectedProfile != existing.id) {
                Settings.selectedProfile = existing.id
            }
            change
        }

    /**
     * The config without the values Marzban picks at random each time it generates the links: the
     * REALITY server name (one of the inbound's SNIs) and short id (one of its short ids). Any of
     * them works, so a config that differs only there is not a new connection: restarting the engine
     * for it (on every sync, i.e. every time the app opens) only tears the tunnel down, and on mobile
     * networks makes Android bring up a new data network (often a new public address).
     */
    private fun material(config: String): String = runCatching {
        val json = org.json.JSONObject(config)
        val outbounds = json.optJSONArray("outbounds") ?: return@runCatching config
        for (i in 0 until outbounds.length()) {
            val tls = outbounds.getJSONObject(i).optJSONObject("tls") ?: continue
            tls.remove("server_name")
            tls.optJSONObject("reality")?.remove("short_id")
        }
        json.toString()
    }.getOrDefault(config)

    /**
     * Saves the route choice into the installed config (the selector's default), without the network:
     * the next start uses it. False when the config does not offer that route (a new route: the App
     * API config is needed). The running engine is switched separately, see [RouteMonitor.select].
     */
    suspend fun setSelected(tag: String): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val target = file()
                if (!target.exists()) return@runCatching false
                val json = org.json.JSONObject(target.readText())
                val outbounds = json.getJSONArray("outbounds")
                val selector = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "selector" && it.optString("tag") == RouteMonitor.SELECTOR_TAG }
                    ?: return@runCatching false
                val members = selector.getJSONArray("outbounds")
                if ((0 until members.length()).none { members.optString(it) == tag }) return@runCatching false
                if (selector.optString("default") == tag) return@runCatching true
                selector.put("default", tag)
                val config = json.toString()
                Libbox.checkConfig(config)
                write(target, config)
                true
            }.getOrDefault(false)
        }
    }

    /** Atomic replace through a unique temporary file (writes never interleave: callers hold [writeLock]). */
    private fun write(target: File, config: String) {
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            tmp.writeText(config)
            if (!tmp.renameTo(target)) error("config_write_failed")
        } finally {
            tmp.delete()
        }
    }

    /**
     * The URL test of the "auto" group (route comparison and Automatic): plain HTTP to a small
     * endpoint, so a TLS handshake with the test site is not counted as the route's delay, and a
     * test every 10 minutes instead of 3 (less traffic and battery). Only fields the App API left
     * unset are filled; routes, keys, fingerprints and the selection are untouched.
     */
    private fun withUrlTestDefaults(config: String): String = runCatching {
        val json = org.json.JSONObject(config)
        val outbounds = json.getJSONArray("outbounds")
        var changed = false
        for (i in 0 until outbounds.length()) {
            val o = outbounds.getJSONObject(i)
            if (o.optString("type") == "urltest" && o.optString("tag") == RouteMonitor.AUTO_TAG) {
                if (!o.has("url")) { o.put("url", URL_TEST_URL); changed = true }
                if (!o.has("interval")) { o.put("interval", "10m"); changed = true }
            }
            // A route switch (done in the running engine, see RouteMonitor.select) also moves the
            // connections already open, so apps do not stay on the previous route.
            if (o.optString("type") == "selector" && o.optString("tag") == RouteMonitor.SELECTOR_TAG && !o.has("interrupt_exist_connections")) {
                o.put("interrupt_exist_connections", true); changed = true
            }
        }
        if (changed) json.toString() else config
    }.getOrDefault(config)

    /**
     * Removes the 4.5 loopback latency inbound ("marz-probe") and its route rules from the installed
     * config, without the network (an update must not wait for a sync that may itself fail).
     * True when the file changed (a running VPN then needs one restart).
     */
    suspend fun dropLegacyProbe(): Boolean = withContext(Dispatchers.IO) {
        writeLock.withLock { runCatching {
            val target = file()
            if (!target.exists()) return@runCatching false
            val text = target.readText()
            if (!text.contains(LEGACY_PROBE_TAG)) return@runCatching false
            val json = org.json.JSONObject(text)
            val inbounds = json.getJSONArray("inbounds")
            val keptInbounds = org.json.JSONArray()
            for (i in 0 until inbounds.length()) inbounds.getJSONObject(i).takeIf { it.optString("tag") != LEGACY_PROBE_TAG }?.let { keptInbounds.put(it) }
            json.put("inbounds", keptInbounds)
            json.optJSONObject("route")?.optJSONArray("rules")?.let { rules ->
                val kept = org.json.JSONArray()
                for (i in 0 until rules.length()) {
                    val r = rules.get(i)
                    val probeRule = r is org.json.JSONObject && r.optJSONArray("inbound")?.let { a -> (0 until a.length()).any { a.optString(it) == LEGACY_PROBE_TAG } } == true
                    if (!probeRule) kept.put(r)
                }
                json.getJSONObject("route").put("rules", kept)
            }
            val config = json.toString()
            Libbox.checkConfig(config)
            write(target, config)
            true
        }.getOrDefault(false) }
    }

    /** Sign-out: remove the managed profile and its file. */
    suspend fun remove() = withContext(Dispatchers.IO) {
        managedProfileId.takeIf { it != -1L }?.let { id ->
            ProfileManager.get(id)?.let { ProfileManager.delete(it) }
            if (Settings.selectedProfile == id) Settings.selectedProfile = -1L
        }
        managedProfileId = -1L
        file().delete()
    }

    companion object {
        private const val KEY_PROFILE = "managed_profile_id"
        private const val LEGACY_PROBE_TAG = "marz-probe"
        /** One config file per app: installs, route choices and the 4.5 cleanup never write at once. */
        private val writeLock = Mutex()
        /** Same default as Hiddify's connection test: plain HTTP, tiny response, reachable in Russia. */
        const val URL_TEST_URL = "http://captive.apple.com/hotspot-detect.html"
    }
}
