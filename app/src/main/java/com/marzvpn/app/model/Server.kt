package com.marzvpn.app.model

import java.util.Locale

/**
 * One route the user can pick. The list is data (App API GET /servers), not code: new nodes or
 * countries appear without UI changes. Names come from the country code in the app language.
 */
data class Server(
    val id: String,
    /** ISO 3166-1 alpha-2, e.g. "US". */
    val countryCode: String,
    /** Optional server-provided label (city or route), already localised by the API. */
    val label: String? = null,
    val status: Status = Status.Unknown,
    /**
     * How the route is built, only when the App API states it from verified node metadata
     * (never guessed from a name, flag or port). Null = not stated: the app shows no route type.
     */
    val kind: RouteKind? = null,
    /** Countries the traffic passes, entry first, exit last (e.g. RU, US for a cascade). */
    val path: List<String> = emptyList(),
    /** The subscription's own name for the route (not shown); used to recognise a route whose id changed. */
    val sourceLabel: String? = null,
    /** Short server letter when a country has several servers ("A", "L"): shown after the country name. */
    val variant: String? = null,
) {
    enum class Status { Up, Degraded, Down, Unknown }

    fun countryName(locale: Locale): String =
        if (countryCode.length != 2) label ?: id else countryDisplayName(countryCode, locale)

    /** Regional-indicator emoji flag; renders on Samsung/Android without bundled images. */
    val flag: String
        get() {
            val cc = countryCode.uppercase(Locale.ROOT)
            if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
            return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) +
                String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
        }
}

private val VARIANT = Regex("[A-Z0-9]{1,2}")

/** App API `variant`, or the same rule applied to the server's own name ("Sweden A" -> "A"); else null. */
fun routeVariant(apiVariant: String?, sourceLabel: String?): String? {
    apiVariant?.takeIf { VARIANT.matches(it) }?.let { return it }
    val words = sourceLabel?.trim()?.split(Regex("\\s+")).orEmpty()
    return words.lastOrNull()?.takeIf { words.size >= 2 && VARIANT.matches(it) }
}

enum class RouteKind { Direct, Alternative, Cascade;
    companion object {
        fun of(value: String?): RouteKind? = when (value) {
            "direct" -> Direct
            "alternative" -> Alternative
            "cascade" -> Cascade
            else -> null
        }
    }
}

/** What the route list says about one route; text is chosen by the UI in the app language. */
sealed interface RouteDescription {
    data object Direct : RouteDescription
    data object Alternative : RouteDescription
    /** Entry first, exit last; only from stated metadata. */
    data class Cascade(val countries: List<String>) : RouteDescription
    /** No stated type, several such routes to the same country: "route n of m" (API order). */
    data class Variant(val index: Int, val count: Int) : RouteDescription
    /** No stated type, while other routes to the same country have one: just "another route". */
    data object Other : RouteDescription
}

fun describeRoute(server: Server, all: List<Server>): RouteDescription? {
    when (server.kind) {
        RouteKind.Cascade -> if (server.path.size >= 2) return RouteDescription.Cascade(server.path)
        RouteKind.Direct -> return RouteDescription.Direct
        RouteKind.Alternative -> return RouteDescription.Alternative
        null -> {}
    }
    if (server.countryCode.isEmpty()) return null
    val sameCountry = all.filter { it.countryCode == server.countryCode }
    val untyped = sameCountry.filter { it.kind == null }
    return when {
        untyped.size > 1 -> RouteDescription.Variant(untyped.indexOfFirst { it.id == server.id } + 1, untyped.size)
        sameCountry.size > 1 -> RouteDescription.Other
        else -> null
    }
}

/**
 * Country name in the app language. The system name is used, except where it is too long for a
 * phone row and a common short form exists ("Соединенные Штаты" -> "США").
 */
fun countryDisplayName(code: String, locale: Locale): String {
    SHORT_NAMES[code.uppercase(Locale.ROOT)]?.get(locale.language)?.let { return it }
    return Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
}

private val SHORT_NAMES = mapOf(
    "US" to mapOf("ru" to "США", "en" to "USA", "fa" to "آمریکا", "zh" to "美国"),
)

/** Selection is either automatic (server decides / urltest) or a specific server id. */
sealed interface ServerChoice {
    data object Auto : ServerChoice
    data class Specific(val id: String) : ServerChoice
}

object ServerCatalog {
    /**
     * Built-in list for the preview build only, so the picker can be reviewed. It is replaced by the
     * account's list from the App API and is never used to build a VPN config.
     */
    val preview = listOf(
        Server(id = "us", countryCode = "US"),
        Server(id = "se", countryCode = "SE"),
        Server(id = "ru", countryCode = "RU"),
    )
}
