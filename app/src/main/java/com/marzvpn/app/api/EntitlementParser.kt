package com.marzvpn.app.api

import com.marzvpn.app.model.Entitlement
import com.marzvpn.app.model.ProStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * The App API entitlement (GET /v1/subscription: effective.entitlement, all[].entitlement) to the UI
 * model. Plain Kotlin, unit-tested on the JVM.
 *
 * Kinds: trial, basic, pro (with the package view "pro"), unlimited (paid rights bought before Pro, may
 * carry tier "legacy_unlimited"), subscription (tier "verifying": the server is checking the plan
 * details); anything else is shown with the server's own numbers and no plan name.
 */
object EntitlementParser {
    fun parse(json: String): Entitlement = parse(Json.parseToJsonElement(json) as JsonObject)

    fun parse(e: JsonObject): Entitlement {
        val ends = Instant.ofEpochSecond(long(e["ends_at"]) ?: 0)
        val quota = long(e["quota_bytes"])
        val used = long(e["used_bytes"]) ?: 0L
        val bonus = (e["bonus"] as? JsonObject)?.let { long(it["reserve_bytes"]) }?.takeIf { it > 0 }
        return when (string(e["kind"])) {
            "pro" -> Entitlement.Pro(ends, bonus, (e["pro"] as? JsonObject)?.let(::proStatus))
            "unlimited" -> Entitlement.PaidNoLimit(ends, bonus)
            "subscription" -> Entitlement.PaidChecking(ends, bonus)
            "trial" -> Entitlement.Trial(ends, quota ?: 0, used, bonus)
            "basic" -> if (quota != null) Entitlement.Basic(ends, quota, used, bonus) else Entitlement.Other(ends, null, null, bonus)
            else -> Entitlement.Other(ends, quota, if (quota != null) used else null, bonus)
        }
    }

    /** Null when the package view is incomplete or contradicts itself: the UI then says "checking". */
    fun proStatus(p: JsonObject): ProStatus? {
        val used = long(p["used_bytes"])?.takeIf { it >= 0 } ?: return null
        val limit = long(p["limit_bytes"])?.takeIf { it > 0 } ?: return null
        val remaining = (long(p["remaining_full_speed_bytes"]) ?: (limit - used)).coerceIn(0, limit)
        val limited = bool(p["limited"]) ?: (string(p["speed_mode"])?.let { it == "throttled" } ?: return null)
        val speed = long(p["normal_speed_limit_bps"])?.takeIf { it > 0 }
        if (limited && speed == null) return null
        val planEnd = long(p["plan_end"])?.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: return null
        val next = long(p["next_package_at"])?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
        return ProStatus(used, limit, remaining, limited, speed, next, planEnd, bool(p["stale"]) ?: false)
    }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun long(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun bool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull
}
