package com.marzvpn.app.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/**
 * Plans the app can offer. The wire codes are the App API's (they stay "unlimited" / "unlimited_6m" for
 * Pro); the app never shows a wire code. A Pro code is offered only with the server's Pro descriptor.
 */
enum class PlanCode(val wire: String) {
    Basic("basic"),
    Pro("unlimited"),
    Pro6m("unlimited_6m"),
    ;

    val isPro: Boolean get() = this != Basic

    companion object {
        fun fromWire(code: String): PlanCode? = entries.firstOrNull { it.wire == code }
    }
}

/** Pro terms from the catalog (`plans[].pro`). The app only displays them; the server enforces them. */
data class ProTerms(
    /** Full-speed data per package, decimal bytes (300 GB = 300 * 10^9). */
    val fullSpeedBytes: Long,
    /** Speed after the full-speed data of a package is used, bit/s. */
    val afterFullSpeedBps: Long,
    val packageDays: Int,
    val packages: Int,
)

/** One plan of the catalog. [priceUsd] is the server's decimal string (e.g. "4.00"). */
data class PlanOffer(
    val code: PlanCode,
    val priceUsd: String,
    val periodDays: Int,
    /** Basic: the plan's data (binary bytes, 100 GiB). Null for Pro. */
    val quotaBytes: Long?,
    val pro: ProTerms? = null,
)

data class TrialOffer(val days: Int, val quotaBytes: Long)

data class PlanCatalog(val plans: List<PlanOffer>, val trial: TrialOffer)

/**
 * The public catalog (GET /v1/catalog, no sign-in). Only valid entries are offered: Basic, and a Pro
 * code only when its entry carries product "pro" and a consistent Pro descriptor. Everything else is
 * hidden (with Pro off the server lists the previous plan without a descriptor: the app offers Basic only).
 * There is no built-in Pro offer: without a catalog (never fetched, offline) the app offers Basic only.
 */
object Catalog {
    const val GIB = 1024L * 1024 * 1024

    /** Sent with a Pro checkout request: this app showed the Pro descriptor (server: PRO_CATALOG_ACK). */
    const val PRO_ACK = "pro-1"

    val builtInTrial = TrialOffer(days = 3, quotaBytes = 3 * GIB)

    /** Used only when no valid catalog was ever received: Basic, $2 / 30 days / 100 GiB. */
    val builtIn = PlanCatalog(listOf(PlanOffer(PlanCode.Basic, "2.00", 30, 100 * GIB)), builtInTrial)

    private val price = Regex("[0-9]{1,4}(\\.[0-9]{1,2})?")
    private const val PACKAGE_DAYS = 30

    /** A usable catalog, or null (not JSON, no valid plan): the caller keeps the cached or built-in one. */
    fun parse(json: String): PlanCatalog? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        val list = root["plans"] as? JsonArray ?: return null
        val plans = LinkedHashMap<PlanCode, PlanOffer>()
        for (item in list) {
            val offer = (item as? JsonObject)?.let(::plan) ?: continue
            if (offer.code !in plans) plans[offer.code] = offer
        }
        if (plans.isEmpty()) return null
        val trial = (root["trial"] as? JsonObject)?.let { t ->
            val days = long(t["days"])
            val quota = long(t["quota_bytes"])
            if (days != null && days in 1..365 && quota != null && quota > 0) TrialOffer(days.toInt(), quota) else null
        } ?: builtInTrial
        return PlanCatalog(plans.values.sortedBy { it.code.ordinal }, trial)
    }

    private fun plan(o: JsonObject): PlanOffer? {
        val code = string(o["code"])?.let(PlanCode::fromWire) ?: return null
        val priceUsd = string(o["price_usd"])?.takeIf { price.matches(it) && BigDecimal(it).signum() > 0 } ?: return null
        val period = long(o["period_days"])?.takeIf { it in 1..3660 }?.toInt() ?: return null
        val quota = o["quota_bytes"]
        if (code == PlanCode.Basic) {
            // Basic is the plan with a data allowance; anything else under its code is not understood.
            if (o["pro"] != null && o["pro"] !is JsonNull) return null
            val bytes = long(quota)?.takeIf { it > 0 } ?: return null
            return PlanOffer(code, priceUsd, period, bytes)
        }
        if (string(o["product"]) != "pro") return null
        if (quota != null && quota !is JsonNull) return null
        val p = o["pro"] as? JsonObject ?: return null
        val fullSpeed = long(p["full_speed_bytes"])?.takeIf { it > 0 } ?: return null
        val after = long(p["after_full_speed_bps"])?.takeIf { it > 0 } ?: return null
        val packageDays = long(p["package_days"])?.takeIf { it == PACKAGE_DAYS.toLong() } ?: return null
        val packages = long(p["packages"])?.takeIf { it in 1..120 } ?: return null
        if (packages * packageDays != period.toLong()) return null
        // "Unlimited data" is shown only for a plan the server states has no data cap.
        val cap = p["data_cap"]
        if (cap != null && cap !is JsonNull) return null
        return PlanOffer(code, priceUsd, period, null, ProTerms(fullSpeed, after, packageDays.toInt(), packages.toInt()))
    }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON integer (not a string, not a fraction). */
    private fun long(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull
}

/** Display formatting of catalog and plan numbers (plain Kotlin, unit-tested). */
object PlanFormat {
    /** Unit names in the UI language (from the string resources). */
    data class Units(val gib: String, val mib: String, val gb: String, val mb: String, val mbps: String, val kbps: String) {
        companion object {
            val EN = Units("GiB", "MiB", "GB", "MB", "Mbps", "kbps")

            /** Same spelling as marzvpn.com in that language (binary units keep their GiB/ГиБ label). */
            fun of(locale: java.util.Locale): Units = when (locale.language) {
                "ru" -> Units("ГиБ", "МиБ", "ГБ", "МБ", "Мбит/с", "кбит/с")
                "fa" -> Units("GiB", "MiB", "گیگابایت", "مگابایت", "مگابیت‌برثانیه", "کیلوبیت‌برثانیه")
                else -> EN
            }
        }
    }

    /** Binary units (Basic, Trial, bonuses): 100 GiB = 107,374,182,400 bytes, labelled GiB. */
    fun binary(bytes: Long, locale: java.util.Locale, u: Units): String {
        val gib = bytes.toDouble() / (1024.0 * 1024 * 1024)
        return if (gib >= 1) {
            val whole = gib >= 10 || gib == Math.floor(gib)
            String.format(locale, if (whole) "%.0f %s" else "%.1f %s", gib, u.gib)
        } else {
            String.format(locale, "%.0f %s", bytes.toDouble() / (1024.0 * 1024), u.mib)
        }
    }

    /**
     * Decimal units (Pro): 300 GB = 300,000,000,000 bytes, labelled GB. Rounded down, so the amount
     * left is never overstated (299.9 GB left is "299 GB", not "300 GB").
     */
    fun decimal(bytes: Long, locale: java.util.Locale, u: Units): String {
        val b = bytes.coerceAtLeast(0)
        val gb = BigDecimal(b).movePointLeft(9)
        return when {
            b >= 10_000_000_000L -> String.format(locale, "%d %s", gb.toLong(), u.gb)
            b >= 1_000_000_000L -> String.format(locale, "%s %s", decimalOne(gb, locale), u.gb)
            else -> String.format(locale, "%d %s", b / 1_000_000L, u.mb)
        }
    }

    /** Bit rate: 2,000,000 bit/s = "2 Mbps"; 1,500,000 = "1.5 Mbps"; 250,000 = "250 kbps". */
    fun bitrate(bps: Long, locale: java.util.Locale, u: Units): String =
        if (bps >= 1_000_000L) String.format(locale, "%s %s", decimalOne(BigDecimal(bps).movePointLeft(6), locale), u.mbps)
        else String.format(locale, "%d %s", bps / 1000L, u.kbps)

    private fun decimalOne(value: BigDecimal, locale: java.util.Locale): String {
        val v = value.setScale(1, java.math.RoundingMode.DOWN)
        return if (v.stripTrailingZeros().scale() <= 0) String.format(locale, "%d", v.toLong())
        else String.format(locale, "%.1f", v.toDouble())
    }

    /** "$4" for "4.00", "$2.50" for "2.5". */
    fun usd(price: String): String {
        val v = runCatching { BigDecimal(price) }.getOrNull() ?: return "$$price"
        return "$" + if (v.stripTrailingZeros().scale() <= 0) v.toBigInteger().toString() else v.setScale(2).toPlainString()
    }

    /**
     * What the multi-package Pro saves against paying the 30-day Pro once per package, from the server's
     * prices. Null when it saves nothing (or either plan is missing).
     */
    fun proSavings(catalog: List<PlanOffer>, long: PlanOffer): String? {
        val packages = long.pro?.packages ?: return null
        if (packages < 2) return null
        val single = catalog.firstOrNull { it.pro != null && it.pro.packages == 1 && it.periodDays == it.pro.packageDays } ?: return null
        val saved = BigDecimal(single.priceUsd).multiply(BigDecimal(packages)).subtract(BigDecimal(long.priceUsd))
        return if (saved.signum() > 0) usd(saved.toPlainString()) else null
    }
}
