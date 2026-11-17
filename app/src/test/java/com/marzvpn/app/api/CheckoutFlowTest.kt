package com.marzvpn.app.api

import com.marzvpn.app.model.PlanCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RC6 owner report: back from the browser without paying, only "Check payment" was left. */
class CheckoutFlowTest {
    private val a = "bill_" + "A".repeat(22)
    private val b = "bill_" + "B".repeat(22)
    private val c = "bill_" + "C".repeat(22)
    private val d = "bill_" + "D".repeat(22)

    @Test
    fun unpaidAttemptNeverLocksThePlans() {
        // No order yet (browser closed), an order waiting for payment, a failed status read, finished ones.
        for (state in listOf("awaiting_checkout", "pending", "unavailable", "failed", "expired", "cancelled", "paid")) {
            assertFalse(state, CheckoutFlow.blocksNewPayment(state))
        }
        // Paid and being credited: a second payment would only be spent twice.
        assertTrue(CheckoutFlow.blocksNewPayment("fulfilling"))
    }

    @Test
    fun returnToPaymentOrNewLinkByState() {
        for (state in listOf("awaiting_checkout", "pending", "unavailable")) {
            assertTrue(state, CheckoutFlow.canReturnToPayment(state))
            assertFalse(state, CheckoutFlow.canGetNewLink(state))
        }
        for (state in listOf("failed", "expired", "cancelled")) {
            assertFalse(state, CheckoutFlow.canReturnToPayment(state))
            assertTrue(state, CheckoutFlow.canGetNewLink(state))
        }
        // Paid / being credited: neither, nothing to pay again.
        for (state in listOf("paid", "fulfilling")) {
            assertFalse(CheckoutFlow.canReturnToPayment(state))
            assertFalse(CheckoutFlow.canGetNewLink(state))
        }
    }

    @Test
    fun restartBringsBackOnlyAttemptsWithAnOrder() {
        // Stale local history (a link opened, no order) is not restored and does not hold the plans.
        for (state in listOf("awaiting_checkout", "expired", "cancelled", "failed", "unavailable")) assertFalse(state, CheckoutFlow.restoreOnLaunch(state))
        for (state in listOf("pending", "fulfilling", "paid")) assertTrue(state, CheckoutFlow.restoreOnLaunch(state))
    }

    @Test
    fun switchingPlansKeepsFollowingTheOldInvoice() {
        val earlier = EarlierAttempts()
        // Basic opened and an order created; the user switches to Pro, then back to Basic.
        earlier.keep(EarlierAttempts.Attempt(a, "p1", PaymentChannel.Website, "pending"))
        earlier.keep(EarlierAttempts.Attempt(b, "p1", PaymentChannel.Website, "awaiting_checkout"))
        assertEquals(listOf(b, a), earlier.all.map { it.id })
        // The unopened Pro link is cancelled by the server when the next link is asked for.
        earlier.update(b, "cancelled")
        assertEquals(listOf(a), earlier.all.map { it.id })
        // The old Basic invoice is paid later: it stays followed until the server says paid, then it is done.
        earlier.update(a, "fulfilling")
        assertEquals("fulfilling", earlier.all.single().state)
        earlier.update(a, "paid")
        assertTrue(earlier.isEmpty())
    }

    @Test
    fun earlierAttemptsAreBoundedAndNeverDuplicated() {
        val earlier = EarlierAttempts(max = 3)
        listOf(a, b, c, d).forEach { earlier.keep(EarlierAttempts.Attempt(it, "p1", PaymentChannel.Website, "pending")) }
        assertEquals(listOf(d, c, b), earlier.all.map { it.id })
        earlier.keep(EarlierAttempts.Attempt(c, "p1", PaymentChannel.Website, "fulfilling"))
        assertEquals(listOf(c, d, b), earlier.all.map { it.id })
        assertEquals("fulfilling", earlier.all.first().state)
        // Finished attempts or ones without a profile are not followed at all.
        earlier.clear()
        earlier.keep(EarlierAttempts.Attempt(a, "p1", PaymentChannel.Website, "expired"))
        earlier.keep(EarlierAttempts.Attempt(b, "", PaymentChannel.Website, "pending"))
        earlier.keep(EarlierAttempts.Attempt(c, "p1", PaymentChannel.Website, "paid"))
        assertTrue(earlier.isEmpty())
        // An unknown id from the server changes nothing.
        earlier.keep(EarlierAttempts.Attempt(a, "p1", PaymentChannel.TelegramBot, "pending"))
        earlier.update(b, "paid")
        earlier.drop(c)
        assertEquals(listOf(a), earlier.all.map { it.id })
    }

    @Test
    fun repeatedAndConcurrentAnswersConfirmOnlyTheRealOrder() {
        // The same "paid" status read twice (polling + "Check payment"), or for another id/profile.
        val receipt = CheckoutReceipt(a, "p1", PaymentChannel.Website, "paid")
        assertTrue(PaymentReceipts.confirms(a, "p1", receipt))
        assertTrue(PaymentReceipts.confirms(a, "p1", receipt.copy()))
        assertFalse(PaymentReceipts.confirms(b, "p1", receipt))
        assertFalse(PaymentReceipts.confirms(a, "p2", receipt))
        assertFalse(PaymentReceipts.confirms(a, "p1", receipt.copy(state = "fulfilling")))
        val earlier = EarlierAttempts()
        earlier.keep(EarlierAttempts.Attempt(a, "p1", PaymentChannel.Website, "pending"))
        earlier.update(a, "paid")
        earlier.update(a, "paid") // a second answer after the first one finished it: no effect
        assertTrue(earlier.isEmpty())
    }

    @Test
    fun retryRepeatsTheSameRequest() {
        val r = CheckoutRequest(PlanCode.Pro, CheckoutAction.Change, "p1", PaymentChannel.Website)
        assertEquals(r, r.copy())
        assertEquals(PlanCode.Pro, r.plan)
        assertEquals("change_plan", r.action.wire)
    }
}
