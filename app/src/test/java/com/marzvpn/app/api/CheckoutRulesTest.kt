package com.marzvpn.app.api

import com.marzvpn.app.model.Entitlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CheckoutRulesTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz012-_9" // 32 url-safe characters, like the server's

    @Test
    fun websiteLinkMustMatchPlanLocaleAndShape() {
        val ok = "https://marzvpn.com/ru/checkout?plan=basic&app=1#app=$token"
        assertEquals(PaymentChannel.Website, CheckoutRules.validate("website", ok, "basic", "ru"))
        assertEquals(PaymentChannel.Website, CheckoutRules.validate("website", "https://marzvpn.com/checkout?plan=unlimited&app=1#app=$token", "unlimited", "en"))
        // Another plan, language, host, scheme, port, user info, extra parameter or a bad secret: refused.
        assertNull(CheckoutRules.validate("website", ok, "unlimited", "ru"))
        assertNull(CheckoutRules.validate("website", ok, "basic", "en"))
        assertNull(CheckoutRules.validate("website", ok.replace("marzvpn.com", "marzvpn.com.evil.test"), "basic", "ru"))
        assertNull(CheckoutRules.validate("website", ok.replace("marzvpn.com", "evil.test"), "basic", "ru"))
        assertNull(CheckoutRules.validate("website", ok.replace("https:", "http:"), "basic", "ru"))
        assertNull(CheckoutRules.validate("website", ok.replace("marzvpn.com", "marzvpn.com:8443"), "basic", "ru"))
        assertNull(CheckoutRules.validate("website", ok.replace("https://", "https://user@"), "basic", "ru"))
        assertNull(CheckoutRules.validate("website", ok.replace("app=1", "app=1&next=https://evil.test"), "basic", "ru"))
        assertNull(CheckoutRules.validate("website", "https://marzvpn.com/ru/checkout?plan=basic&app=1", "basic", "ru"))
        assertNull(CheckoutRules.validate("website", "https://marzvpn.com/ru/checkout?plan=basic&app=1#app=short", "basic", "ru"))
        assertNull(CheckoutRules.validate("website", "https://marzvpn.com/ru/pay?plan=basic&app=1#app=$token", "basic", "ru"))
        // A website link announced as the bot channel (or an unknown channel) is refused.
        assertNull(CheckoutRules.validate("telegram_bot", ok, "basic", "ru"))
        assertNull(CheckoutRules.validate("other", ok, "basic", "ru"))
    }

    @Test
    fun botLinkMustBeOurBotWithOneUseStart() {
        val ok = "https://t.me/MarzReality_bot?start=apppay_$token"
        assertEquals(PaymentChannel.TelegramBot, CheckoutRules.validate("telegram_bot", ok, "basic", "en"))
        assertNull(CheckoutRules.validate("telegram_bot", ok.replace("MarzReality_bot", "OtherBot"), "basic", "en"))
        assertNull(CheckoutRules.validate("telegram_bot", ok.replace("apppay_", "applogin_"), "basic", "en"))
        assertNull(CheckoutRules.validate("telegram_bot", "$ok&x=1", "basic", "en"))
        assertNull(CheckoutRules.validate("telegram_bot", "$ok#f", "basic", "en"))
        assertNull(CheckoutRules.validate("telegram_bot", ok.replace("t.me", "telegram.me"), "basic", "en"))
        assertNull(CheckoutRules.validate("website", ok, "basic", "en"))
        assertNull(CheckoutRules.validate("telegram_bot", "not a url", "basic", "en"))
    }

    @Test
    fun localesFollowTheApi() {
        assertEquals("ru", CheckoutRules.apiLocale("ru"))
        assertEquals("fa", CheckoutRules.apiLocale("fa"))
        assertEquals("zh-cn", CheckoutRules.apiLocale("zh"))
        assertEquals("en", CheckoutRules.apiLocale("de"))
    }

    @Test
    fun errorsFromTheContract() {
        assertEquals(CheckoutProblem.FeatureOff, CheckoutRules.problem("not_found", 404))
        assertEquals(CheckoutProblem.FeatureOff, CheckoutRules.problem("http_404", 404))
        assertEquals(CheckoutProblem.SignedOut, CheckoutRules.problem("signed_out", 401))
        assertEquals(CheckoutProblem.PilotClosed, CheckoutRules.problem("pilot_closed", 403))
        assertEquals(CheckoutProblem.ProfileGone, CheckoutRules.problem("vpn_identity_not_found", 404))
        assertEquals(CheckoutProblem.ChooseProfile, CheckoutRules.problem("select_existing_profile", 409))
        assertEquals(CheckoutProblem.NoProfile, CheckoutRules.problem("existing_profile_required", 409))
        assertEquals(CheckoutProblem.Review, CheckoutRules.problem("billing_ownership_requires_review", 409))
        assertEquals(CheckoutProblem.Review, CheckoutRules.problem("vpn_ownership_conflict", 409))
        assertEquals(CheckoutProblem.Review, CheckoutRules.problem("unsupported_billing_profile", 409))
        assertEquals(CheckoutProblem.Unavailable, CheckoutRules.problem("website_billing_unavailable", 503))
        assertEquals(CheckoutProblem.Unavailable, CheckoutRules.problem("upstream_unavailable", 503))
        assertEquals(CheckoutProblem.Unavailable, CheckoutRules.problem("http_502", 502))
        assertEquals(CheckoutProblem.Network, CheckoutRules.problem("network", 0))
        assertEquals(CheckoutProblem.TooMany, CheckoutRules.problem("rate_limited", 429))
        assertEquals(CheckoutProblem.Clock, CheckoutRules.problem("device_signature_required", 401))
        assertEquals(CheckoutProblem.Generic, CheckoutRules.problem("invalid_handoff", 200))
        assertEquals(CheckoutProblem.Generic, CheckoutRules.problem("invalid_billing_request", 400))
    }

    @Test
    fun paymentNeedsTheExactFulfilledOrderReceipt() {
        val id = "bill_" + "a".repeat(22)
        val receipt = CheckoutReceipt(id, "vpn_a", PaymentChannel.Website, "paid")
        assertTrue(PaymentReceipts.confirms(id, "vpn_a", receipt))
        for (state in listOf("pending", "fulfilling", "expired", "failed", "cancelled", "unavailable", "awaiting_checkout")) {
            assertFalse(PaymentReceipts.confirms(id, "vpn_a", receipt.copy(state = state)))
        }
        assertFalse(PaymentReceipts.confirms(id, "foreign", receipt))
        assertFalse(PaymentReceipts.confirms("bill_" + "b".repeat(22), "vpn_a", receipt))
        assertFalse(PaymentReceipts.confirms(id, "vpn_a", null))
        assertFalse(PaymentReceipts.valid("short", "vpn_a", "paid"))
        assertFalse(PaymentReceipts.valid(id, "", "paid"))
        assertFalse(PaymentReceipts.valid(id, "vpn_a", "browser_return"))
        // No before/after entitlement argument: a referral/admin bonus cannot confirm a payment.
    }

    @Test
    fun onlyARejectedSessionEndsIt() {
        // Ends the session: the server's own verdicts on the refresh token.
        assertTrue(RefreshRules.ends(401, "refresh_reused"))
        assertTrue(RefreshRules.ends(401, "invalid_refresh"))
        assertTrue(RefreshRules.ends(401, "pilot_closed"))
        // Keeps it: outages, overload, rate limits, proxies, clock/signature trouble, unknown answers.
        for ((status, code) in listOf(
            503 to "upstream_unavailable", 500 to "internal_error", 502 to "http_502", 504 to "http_504",
            429 to "rate_limited", 0 to "network", 401 to "device_signature_required", 401 to "invalid_device_signature",
            401 to "http_401", 400 to "invalid_request", 403 to "forbidden", 404 to "not_found",
        )) assertFalse("$status $code", RefreshRules.ends(status, code))
    }
}
