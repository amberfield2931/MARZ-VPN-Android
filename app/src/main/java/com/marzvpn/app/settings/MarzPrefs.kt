package com.marzvpn.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.marzvpn.app.model.RouteKind
import com.marzvpn.app.model.Server
import com.marzvpn.app.model.routeVariant
import com.marzvpn.app.model.ServerChoice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ThemeMode { System, Light, Dark }

/** Languages the MARZ UI ships. "System" follows the device and falls back to English. */
enum class AppLanguage(val tag: String?) {
    System(null),
    Russian("ru"),
    English("en"),
    Persian("fa"),
    ChineseSimplified("zh-CN"),
    ;

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (tag.isEmpty()) return System
            val locale = Locale.forLanguageTag(tag.split(',').first())
            return when (locale.language) {
                "ru" -> Russian
                "en" -> English
                "fa" -> Persian
                "zh" -> ChineseSimplified
                else -> System
            }
        }

        /** Per-app language (Android 13+ system setting; AppCompat storage below 13). */
        fun apply(language: AppLanguage) {
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList(),
            )
        }
    }
}

/**
 * Small UI preferences only. Tokens and account data never go here; the cached route list holds
 * only route ids (hashes), countries, labels and stated route types — no hosts, keys or UUIDs.
 */
class MarzPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("marz_ui", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.System)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var welcomeSeen: Boolean
        get() = prefs.getBoolean(KEY_WELCOME, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOME, value).apply()

    var serverChoice: ServerChoice
        get() = prefs.getString(KEY_SERVER, null)?.let { ServerChoice.Specific(it) } ?: ServerChoice.Auto
        set(value) = prefs.edit().putString(KEY_SERVER, (value as? ServerChoice.Specific)?.id).apply()

    /** Last route list from the App API, so the saved choice and names survive a process restart. */
    var routes: List<Server>?
        get() = prefs.getString(KEY_ROUTES, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val path = o.optJSONArray("path")
                    Server(
                        id = o.getString("id"), countryCode = o.optString("country"),
                        label = o.optString("label").ifEmpty { null },
                        kind = RouteKind.of(o.optString("kind").ifEmpty { null }),
                        path = path?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                        sourceLabel = o.optString("source_label").ifEmpty { null },
                        variant = routeVariant(o.optString("variant").ifEmpty { null }, o.optString("source_label").ifEmpty { null }),
                    )
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        set(value) {
            val raw = value?.let { list ->
                JSONArray(list.map { s ->
                    JSONObject().put("id", s.id).put("country", s.countryCode).put("label", s.label ?: "")
                        .put("kind", s.kind?.name?.lowercase() ?: "").put("path", JSONArray(s.path))
                        .put("source_label", s.sourceLabel ?: "").put("variant", s.variant ?: "")
                }).toString()
            }
            prefs.edit().putString(KEY_ROUTES, raw).apply()
        }

    /** Last valid public catalog (GET /v1/catalog, public prices only), re-validated when read. */
    var catalog: String?
        get() = prefs.getString(KEY_CATALOG, null)
        set(value) = prefs.edit().putString(KEY_CATALOG, value).apply()

    /** Asked once for notification permission (Android 13+), so the VPN notification can be shown. */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ASKED, value).apply()

    /** Debug builds only: lab App API URL set in developer tools. */
    var apiUrlOverride: String?
        get() = prefs.getString(KEY_API_URL, null)
        set(value) = prefs.edit().putString(KEY_API_URL, value).apply()

    /** When the update check last ran, wall clock ms. 0: never. */
    var updateCheckedAt: Long
        get() = prefs.getLong(KEY_UPDATE_AT, 0)
        set(value) = prefs.edit().putLong(KEY_UPDATE_AT, value).apply()

    /** A newer published build found by the last check, shown until a check says otherwise. */
    val updateFound: Pair<String, Long>?
        get() {
            val name = prefs.getString(KEY_UPDATE_NAME, null) ?: return null
            return name to prefs.getLong(KEY_UPDATE_CODE, 0)
        }

    fun setUpdateFound(name: String?, code: Long) {
        prefs.edit().putString(KEY_UPDATE_NAME, name).putLong(KEY_UPDATE_CODE, code).apply()
    }

    companion object {
        private const val KEY_UPDATE_AT = "update_checked_at"
        private const val KEY_UPDATE_NAME = "update_found_name"
        private const val KEY_UPDATE_CODE = "update_found_code"
        private const val KEY_API_URL = "api_url_override"
        private const val KEY_THEME = "theme"
        private const val KEY_WELCOME = "welcome_seen"
        private const val KEY_SERVER = "server"
        private const val KEY_ROUTES = "routes"
        private const val KEY_CATALOG = "catalog"
        private const val KEY_NOTIFY_ASKED = "notify_asked"
    }
}
