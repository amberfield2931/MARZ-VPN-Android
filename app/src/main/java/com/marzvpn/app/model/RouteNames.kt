package com.marzvpn.app.model

import android.content.Context
import android.view.View
import io.nekohasekai.sfa.R
import java.util.Locale

/** Route names for the UI and the VPN notification, in the app language. Never guesses a route type. */
object RouteNames {
    fun title(server: Server, locale: Locale): String =
        server.label ?: server.countryName(locale).let { name -> server.variant?.let { "$name $it" } ?: name }

    fun detail(context: Context, server: Server, all: List<Server>, locale: Locale): String? =
        when (val d = describeRoute(server, all)) {
            RouteDescription.Direct -> context.getString(R.string.marz_route_direct)
            RouteDescription.Alternative -> context.getString(R.string.marz_route_alternative)
            is RouteDescription.Cascade -> {
                val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val names = d.countries.map { "\u2068" + countryName(it, locale) + "\u2069" }
                context.getString(R.string.marz_route_cascade, names.joinToString(if (rtl) " ← " else " → "))
            }
            is RouteDescription.Variant -> context.getString(R.string.marz_route_variant, d.index, d.count)
            RouteDescription.Other -> context.getString(R.string.marz_route_other)
            null -> null
        }

    fun full(context: Context, server: Server, all: List<Server>, locale: Locale): String =
        listOfNotNull(title(server, locale), detail(context, server, all, locale)).joinToString(" · ")

    private fun countryName(code: String, locale: Locale): String = countryDisplayName(code, locale)
}
