package com.marzvpn.app.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteCheckTest {
    /** A fake engine: [answers] = for each route, per round, after how many ms a result appears (null = none). */
    private class Engine(val answers: Map<String, List<Long?>>) {
        var now = 1_000_000L // ms
        val tested = mutableMapOf<String, Long>() // seconds
        val rounds = mutableMapOf<String, Int>()
        val pending = mutableListOf<Pair<String, Long>>()
        var refuse = emptySet<String>()
        val calls = mutableListOf<String>()

        suspend fun test(tag: String): Boolean {
            calls += tag
            if (tag in refuse) return false
            val round = rounds.merge(tag, 1, Int::plus)!!
            answers[tag]?.getOrNull(round - 1)?.let { pending += tag to now + it }
            return true
        }

        fun testedAt(tag: String): Long? {
            pending.filter { it.second <= now }.forEach { tested[it.first] = it.second / 1000 }
            pending.removeAll { it.second <= now }
            return tested[tag]
        }
    }

    private fun run(e: Engine, tags: List<String>): List<Map<String, RouteAvailability>> = runBlocking {
        val updates = mutableListOf<Map<String, RouteAvailability>>()
        RouteCheck({ e.test(it) }, { e.testedAt(it) }, { e.now / 1000 }, { e.now += it }).run(tags) { updates += it }
        updates
    }

    @Test
    fun workingRoutesTurnGreenFailedOnesRedOnlyAfterTwoRounds() {
        // Spectrum Wi-Fi: Russia direct does not answer, Russia alternative (cascade) does.
        val e = Engine(mapOf("ru-direct" to listOf(null, null), "ru-alt" to listOf(1_200L), "us" to listOf(300L)))
        val updates = run(e, listOf("ru-direct", "ru-alt", "us"))
        assertEquals(RouteAvailability.Unknown, updates.first()["ru-direct"]) // grey while checking
        val last = updates.last()
        assertEquals(RouteAvailability.Ok, last["ru-alt"])
        assertEquals(RouteAvailability.Ok, last["us"])
        assertEquals(RouteAvailability.Failed, last["ru-direct"])
        assertEquals(2, e.calls.count { it == "ru-direct" }) // tested again before red
        assertEquals(1, e.calls.count { it == "us" })
        // Red never appears before the second round is over.
        val firstRed = updates.indexOfFirst { it["ru-direct"] == RouteAvailability.Failed }
        assertEquals(updates.size - 1, firstRed)
    }

    @Test
    fun oneTimeoutIsNotRed() {
        // Mobile network: Russia direct times out once, then works.
        val e = Engine(mapOf("ru-direct" to listOf(null, 2_000L)))
        assertEquals(RouteAvailability.Ok, run(e, listOf("ru-direct")).last()["ru-direct"])
    }

    @Test
    fun anOldResultDoesNotCount() {
        val e = Engine(mapOf("us" to listOf(null, null)))
        e.tested["us"] = e.now / 1000 - 600 // tested 10 minutes ago, before this check
        assertEquals(RouteAvailability.Failed, run(e, listOf("us")).last()["us"])
    }

    @Test
    fun aRouteTheEngineCannotTestStaysGrey() {
        val e = Engine(mapOf("new" to listOf(100L)))
        e.refuse = setOf("new")
        assertEquals(RouteAvailability.Unknown, run(e, listOf("new")).last()["new"])
    }

    @Test fun freshRecheckReturnsGreenAfterTheRouteRecovers() {
        val e = Engine(mapOf("route" to listOf(null, null, 500L)))
        assertEquals(RouteAvailability.Failed, run(e, listOf("route")).last()["route"])
        assertEquals(RouteAvailability.Ok, run(e, listOf("route")).last()["route"])
    }

    @Test fun cancelledForegroundCheckNeverPublishesAFailedVerdict() = runBlocking {
        val updates = mutableListOf<Map<String, RouteAvailability>>()
        try {
            RouteCheck({ true }, { null }, { 1000L }, { throw kotlinx.coroutines.CancellationException() })
                .run(listOf("route")) { updates += it }
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf(mapOf("route" to RouteAvailability.Unknown)), updates)
    }
}
