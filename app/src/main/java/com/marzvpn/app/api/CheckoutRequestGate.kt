package com.marzvpn.app.api

/** An abandoned request must never open a browser for a different screen/account. */
class CheckoutRequestGate {
    data class Ticket internal constructor(internal val epoch: Long, internal val account: String?)
    private var epoch = 0L
    fun begin(account: String?) = Ticket(++epoch, account)
    fun cancel() { epoch++ }
    fun accepts(ticket: Ticket, account: String?) = ticket.epoch == epoch && ticket.account != null && ticket.account == account
}
