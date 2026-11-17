package com.marzvpn.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val installed = 1000092L

    @Test fun `newer build is offered with its version`() {
        assertEquals(UpdateResult.Newer("1.0.2", 1000093), UpdateRules.decide(1, "stable", "1.0.2", 1000093, installed))
    }

    @Test fun `same or older build is up to date`() {
        assertEquals(UpdateResult.UpToDate, UpdateRules.decide(1, "stable", "1.0.1", 1000092, installed))
        assertEquals(UpdateResult.UpToDate, UpdateRules.decide(1, "stable", "1.0.0", 1000091, installed))
    }

    @Test fun `only a published stable entry counts`() {
        val bad = listOf(
            UpdateRules.decide(2, "stable", "9.9.9", 9999999, installed),
            UpdateRules.decide(1, "beta", "9.9.9", 9999999, installed),
            UpdateRules.decide(1, null, "9.9.9", 9999999, installed),
            UpdateRules.decide(1, "stable", null, 9999999, installed),
            UpdateRules.decide(1, "stable", "1.0.2-rc1", 9999999, installed),
            UpdateRules.decide(1, "stable", "v1.0.2", 9999999, installed),
            UpdateRules.decide(1, "stable", "1.0", 9999999, installed),
            UpdateRules.decide(1, "stable", "1.0.2", 0, installed),
        )
        assertTrue(bad.all { it == UpdateResult.Invalid })
    }

    @Test fun `at most once in 24 hours`() {
        val now = 1_800_000_000_000L
        assertTrue(UpdateRules.due(0, now))
        assertFalse(UpdateRules.due(now, now))
        assertFalse(UpdateRules.due(now - UpdateRules.EVERY_MS + 1, now))
        assertTrue(UpdateRules.due(now - UpdateRules.EVERY_MS, now))
        assertTrue("a clock moved back", UpdateRules.due(now + 1000, now))
    }
}
