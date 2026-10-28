package com.marzvpn.app.api

import org.junit.Assert.*
import org.junit.Test

class CheckoutRequestGateTest {
    @Test fun cancellationDropsLateProviderReplyWithoutOpeningAnotherCheckout() {
        val gate = CheckoutRequestGate();val ticket = gate.begin("owner");var browserOpens = 0
        gate.cancel()
        if (gate.accepts(ticket, "owner")) browserOpens++
        assertEquals(0, browserOpens)
    }
    @Test fun onlyCurrentRequestForTheSameAuthenticatedAccountCanOpen() {
        val gate = CheckoutRequestGate();val old = gate.begin("owner");val current = gate.begin("owner")
        assertFalse(gate.accepts(old, "owner"));assertFalse(gate.accepts(current, "other"))
        assertTrue(gate.accepts(current, "owner"))
        gate.cancel();assertFalse(gate.accepts(current, "owner"))
    }
    @Test fun missingAccountNeverReceivesAPaymentLink() {
        val gate = CheckoutRequestGate();assertFalse(gate.accepts(gate.begin(null), null))
    }
}
