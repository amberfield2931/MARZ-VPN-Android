package com.marzvpn.app.model

import com.marzvpn.app.Links
import com.marzvpn.app.api.CheckoutAction
import com.marzvpn.app.api.CheckoutBody
import com.marzvpn.app.api.CheckoutProblem
import com.marzvpn.app.api.CheckoutRules
import com.marzvpn.app.api.EntitlementParser
import com.marzvpn.app.api.PaymentChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class PlanCatalogTest {
    /** Production answer today (Pro off), verbatim. */
    private val proOff = """{"plans": [{"code": "basic", "period_days": 30, "quota_bytes": 107374182400, "price_usd": "2.00"}, {"code": "unlimited", "period_days": 30, "quota_bytes": null, "price_usd": "4.00"}, {"code": "unlimited_6m", "period_days": 180, "quota_bytes": null, "price_usd": "20.00"}], "trial": {"days": 3, "quota_bytes": 3221225472, "channel": "telegram_bot", "bot": "MarzReality_bot"}, "terms_url": "https://marzvpn.com/terms"}"""

    private fun proEntry(code: String, days: Int, price: String, packages: Int, extra: String = "", pro: String? = null) =
        """{"code":"$code","period_days":$days,"quota_bytes":null,"price_usd":"$price","product":"pro","name":"Pro"$extra,""" +
            """"pro":${pro ?: """{"full_speed_bytes":300000000000,"package_days":30,"packages":$packages,"after_full_speed_bps":2000000,"speed_limit_scope":"account","data_cap":null}"""}}"""

    private val basic = """{"code":"basic","period_days":30,"quota_bytes":107374182400,"price_usd":"2.00"}"""

    /** Pro on (APP_API_PRO_ENABLED=1, APP_API_UNLIMITED_6M=1), as app_api/plans.py builds it. */
    private val proOn = """{"plans":[$basic,${proEntry("unlimited", 30, "4.00", 1)},${proEntry("unlimited_6m", 180, "20.00", 6)}],"trial":{"days":3,"quota_bytes":3221225472}}"""

    private fun codes(json: String) = Catalog.parse(json)?.plans?.map { it.code }

    @Test
    fun productionProOffOffersBasicOnly() {
        val c = Catalog.parse(proOff)!!
        assertEquals(listOf(PlanCode.Basic), c.plans.map { it.code })
        val b = c.plans.single()
        assertEquals("2.00", b.priceUsd)
        assertEquals(30, b.periodDays)
        assertEquals(100 * Catalog.GIB, b.quotaBytes)
        assertNull(b.pro)
        assertEquals(TrialOffer(3, 3 * Catalog.GIB), c.trial)
    }

    @Test
    fun proOnOffersBasicProAndPro6m() {
        val c = Catalog.parse(proOn)!!
        assertEquals(listOf(PlanCode.Basic, PlanCode.Pro, PlanCode.Pro6m), c.plans.map { it.code })
        val pro = c.plans[1]
        assertEquals(ProTerms(300_000_000_000L, 2_000_000L, 30, 1), pro.pro)
        assertEquals("4.00", pro.priceUsd)
        assertNull(pro.quotaBytes)
        val six = c.plans[2]
        assertEquals(180, six.periodDays)
        assertEquals(6, six.pro!!.packages)
        assertEquals("$4", PlanFormat.proSavings(c.plans, six))
        assertNull(PlanFormat.proSavings(c.plans, pro))
        // Server order does not matter.
        assertEquals(listOf(PlanCode.Basic, PlanCode.Pro, PlanCode.Pro6m),
            codes("""{"plans":[${proEntry("unlimited_6m", 180, "20.00", 6)},${proEntry("unlimited", 30, "4.00", 1)},$basic]}"""))
    }

    @Test
    fun savingsOnlyWhenPositive() {
        val c = Catalog.parse("""{"plans":[${proEntry("unlimited", 30, "4.00", 1)},${proEntry("unlimited_6m", 180, "24.00", 6)}]}""")!!
        assertNull(PlanFormat.proSavings(c.plans, c.plans[1]))
        val d = Catalog.parse("""{"plans":[${proEntry("unlimited", 30, "4.00", 1)},${proEntry("unlimited_6m", 180, "21.50", 6)}]}""")!!
        assertEquals("$2.50", PlanFormat.proSavings(d.plans, d.plans[1]))
        // Without the 30-day Pro there is nothing to compare with.
        val e = Catalog.parse("""{"plans":[$basic,${proEntry("unlimited_6m", 180, "20.00", 6)}]}""")!!
        assertNull(PlanFormat.proSavings(e.plans, e.plans[1]))
    }

    @Test
    fun malformedOrPartialProBlocksAreHidden() {
        val bad = listOf(
            // no product / wrong product
            """{"code":"unlimited","period_days":30,"quota_bytes":null,"price_usd":"4.00","pro":{"full_speed_bytes":300000000000,"package_days":30,"packages":1,"after_full_speed_bps":2000000}}""",
            proEntry("unlimited", 30, "4.00", 1).replace("\"product\":\"pro\"", "\"product\":\"unlimited\""),
            // no pro block, pro not an object
            """{"code":"unlimited","period_days":30,"quota_bytes":null,"price_usd":"4.00","product":"pro"}""",
            proEntry("unlimited", 30, "4.00", 1, pro = "\"yes\""),
            // zero / missing / string numbers
            proEntry("unlimited", 30, "4.00", 1, pro = """{"full_speed_bytes":0,"package_days":30,"packages":1,"after_full_speed_bps":2000000}"""),
            proEntry("unlimited", 30, "4.00", 1, pro = """{"full_speed_bytes":300000000000,"package_days":30,"packages":1,"after_full_speed_bps":0}"""),
            proEntry("unlimited", 30, "4.00", 1, pro = """{"full_speed_bytes":300000000000,"package_days":30,"packages":1}"""),
            proEntry("unlimited", 30, "4.00", 1, pro = """{"full_speed_bytes":"300000000000","package_days":30,"packages":1,"after_full_speed_bps":2000000}"""),
            proEntry("unlimited", 30, "4.00", 1, pro = """{"full_speed_bytes":300000000000,"package_days":30,"packages":1,"after_full_speed_bps":-1}"""),
            // package days other than 30; packages * 30 != period
            proEntry("unlimited", 30, "4.00", 1, pro = """{"full_speed_bytes":300000000000,"package_days":31,"packages":1,"after_full_speed_bps":2000000}"""),
            proEntry("unlimited", 30, "4.00", 2),
            proEntry("unlimited_6m", 180, "20.00", 5),
            proEntry("unlimited_6m", 180, "20.00", 0),
            // a data cap, a quota
            proEntry("unlimited", 30, "4.00", 1, pro = """{"full_speed_bytes":300000000000,"package_days":30,"packages":1,"after_full_speed_bps":2000000,"data_cap":1000}"""),
            proEntry("unlimited", 30, "4.00", 1).replace("\"quota_bytes\":null", "\"quota_bytes\":107374182400"),
            // bad price
            proEntry("unlimited", 30, "4,00", 1),
            proEntry("unlimited", 30, "0.00", 1),
            proEntry("unlimited", 30, "-4", 1),
            // unknown code with a valid descriptor
            proEntry("pro", 30, "4.00", 1),
            proEntry("unlimited_12m", 360, "40.00", 12),
        )
        for (entry in bad) {
            assertEquals(entry, listOf(PlanCode.Basic), codes("""{"plans":[$basic,$entry]}"""))
        }
    }

    @Test
    fun invalidBasicIsHiddenAndEmptyCatalogIsRejected() {
        assertNull(codes("""{"plans":[{"code":"basic","period_days":30,"quota_bytes":null,"price_usd":"2.00"}]}"""))
        assertNull(codes("""{"plans":[{"code":"basic","period_days":0,"quota_bytes":107374182400,"price_usd":"2.00"}]}"""))
        assertNull(codes("""{"plans":[{"code":"basic","period_days":30,"quota_bytes":107374182400,"price_usd":2}]}"""))
        assertNull(codes("""{"plans":[]}"""))
        assertNull(codes("""{"plans":{}}"""))
        assertNull(codes("not json"))
        assertNull(codes("[]"))
        assertNull(codes(""))
        // Pro-only catalog is still a catalog.
        assertEquals(listOf(PlanCode.Pro), codes("""{"plans":[${proEntry("unlimited", 30, "4.00", 1)}]}"""))
        // A duplicate code: the first one counts.
        assertEquals("2.00", Catalog.parse("""{"plans":[$basic,${basic.replace("2.00", "9.00")}]}""")!!.plans.single().priceUsd)
        // An invalid trial falls back to the built-in one.
        assertEquals(Catalog.builtInTrial, Catalog.parse("""{"plans":[$basic],"trial":{"days":0,"quota_bytes":1}}""")!!.trial)
    }

    @Test
    fun builtInFallbackIsBasicOnly() {
        assertEquals(listOf(PlanCode.Basic), Catalog.builtIn.plans.map { it.code })
        val b = Catalog.builtIn.plans.single()
        assertEquals("$2", PlanFormat.usd(b.priceUsd))
        assertEquals(30, b.periodDays)
        assertEquals(100 * Catalog.GIB, b.quotaBytes)
        assertTrue(Catalog.builtIn.plans.none { it.code.isPro })
    }

    @Test
    fun decimalGbAndBinaryGibAreNotConfused() {
        val en = PlanFormat.Units.EN
        // Pro: decimal.
        assertEquals("300 GB", PlanFormat.decimal(300_000_000_000L, Locale.US, en))
        assertEquals("187 GB", PlanFormat.decimal(187_999_999_999L, Locale.US, en))
        assertEquals("5.2 GB", PlanFormat.decimal(5_250_000_000L, Locale.US, en))
        assertEquals("1 GB", PlanFormat.decimal(1_000_000_000L, Locale.US, en))
        assertEquals("450 MB", PlanFormat.decimal(450_000_000L, Locale.US, en))
        assertEquals("0 MB", PlanFormat.decimal(-5, Locale.US, en))
        // 300 GB is about 279.4 GiB: never shown as "300 GiB".
        assertEquals("279 GiB", PlanFormat.binary(300_000_000_000L, Locale.US, en))
        // Basic: binary, 100 GiB = 107,374,182,400 bytes (would be "107 GB" decimal).
        assertEquals("100 GiB", PlanFormat.binary(107_374_182_400L, Locale.US, en))
        assertEquals("107 GB", PlanFormat.decimal(107_374_182_400L, Locale.US, en))
        assertEquals("3 GiB", PlanFormat.binary(3 * Catalog.GIB, Locale.US, en))
        // Russian spelling, as on marzvpn.com/ru.
        val ru = Locale.forLanguageTag("ru")
        assertEquals("300 ГБ", PlanFormat.decimal(300_000_000_000L, ru, PlanFormat.Units.of(ru)))
        assertEquals("100 ГиБ", PlanFormat.binary(107_374_182_400L, ru, PlanFormat.Units.of(ru)))
        assertEquals("2 Мбит/с", PlanFormat.bitrate(2_000_000L, ru, PlanFormat.Units.of(ru)))
        assertEquals("GB", PlanFormat.Units.of(Locale.SIMPLIFIED_CHINESE).gb)
    }

    @Test
    fun bitrateAndPrice() {
        val en = PlanFormat.Units.EN
        assertEquals("2 Mbps", PlanFormat.bitrate(2_000_000L, Locale.US, en))
        assertEquals("1.5 Mbps", PlanFormat.bitrate(1_500_000L, Locale.US, en))
        assertEquals("250 kbps", PlanFormat.bitrate(250_000L, Locale.US, en))
        assertEquals("$4", PlanFormat.usd("4.00"))
        assertEquals("$20", PlanFormat.usd("20.00"))
        assertEquals("$2.50", PlanFormat.usd("2.5"))
    }

    @Test
    fun intentBodyCarriesCatalogAckOnlyForPro() {
        val basic = CheckoutBody.of(PlanCode.Basic, CheckoutAction.Renew, "p1", "en", PaymentChannel.Website)
        assertEquals("basic", basic["plan"])
        assertFalse(basic.containsKey("catalog"))
        val pro = CheckoutBody.of(PlanCode.Pro, CheckoutAction.Change, "p1", "ru", null)
        assertEquals("unlimited", pro["plan"])
        assertEquals("change_plan", pro["action"])
        assertEquals("pro-1", pro["catalog"])
        assertFalse(pro.containsKey("channel"))
        val six = CheckoutBody.of(PlanCode.Pro6m, CheckoutAction.Buy, null, "zh-cn", PaymentChannel.TelegramBot)
        assertEquals("unlimited_6m", six["plan"])
        assertEquals("pro-1", six["catalog"])
        assertEquals("telegram_bot", six["channel"])
        assertFalse(six.containsKey("vpn_identity"))
        // Earlier servers / a newer protocol: the user is sent to the profile's own channel.
        assertEquals(CheckoutProblem.FeatureOff, CheckoutRules.problem("app_update_required", 409))
    }

    @Test
    fun wireCodesForCheckoutLinks() {
        assertEquals(PlanCode.Pro6m, PlanCode.fromWire("unlimited_6m"))
        assertNull(PlanCode.fromWire("Unlimited"))
        val token = "AbCdEfGhIjKlMnOpQrStUvWxYz012-_9"
        assertEquals(PaymentChannel.Website, CheckoutRules.validate("website", "https://marzvpn.com/checkout?plan=unlimited_6m&app=1#app=$token", "unlimited_6m", "en"))
        assertNull(CheckoutRules.validate("website", "https://marzvpn.com/checkout?plan=unlimited&app=1#app=$token", "unlimited_6m", "en"))
        assertEquals("https://marzvpn.com/checkout?plan=unlimited", Links.checkoutUrl(PlanCode.Pro, Locale.US))
        assertEquals("https://marzvpn.com/ru/checkout?plan=unlimited_6m", Links.checkoutUrl(PlanCode.Pro6m, Locale.forLanguageTag("ru")))
        assertEquals("https://marzvpn.com/zh-cn/checkout?plan=basic", Links.checkoutUrl(PlanCode.Basic, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun entitlementKinds() {
        val pro = EntitlementParser.parse(
            """{"kind":"pro","tier":"pro","state":"active","ends_at":1800000000,"quota_bytes":null,"used_bytes":null,""" +
                """"pro":{"used_bytes":112400000000,"limit_bytes":300000000000,"remaining_full_speed_bytes":187600000000,"speed_mode":"full","limited":false,""" +
                """"normal_speed_limit_bps":null,"package_start":1790000000,"package_end":1792592000,"next_package_at":1792592000,"plan_end":1800000000,"updated_at":1790000100,"stale":false}}""",
        ) as Entitlement.Pro
        assertEquals(Instant.ofEpochSecond(1800000000), pro.endsAt)
        val s = pro.status!!
        assertEquals(187_600_000_000L, s.remainingFullSpeedBytes)
        assertEquals(300_000_000_000L, s.limitBytes)
        assertFalse(s.limited)
        assertFalse(s.stale)
        assertEquals(Instant.ofEpochSecond(1792592000), s.nextPackageAt)

        val limited = EntitlementParser.parse(
            """{"kind":"pro","ends_at":1800000000,"pro":{"used_bytes":300000000000,"limit_bytes":300000000000,"remaining_full_speed_bytes":0,"speed_mode":"throttled",""" +
                """"limited":true,"normal_speed_limit_bps":2000000,"next_package_at":null,"plan_end":1800000000,"stale":true}}""",
        ) as Entitlement.Pro
        val ls = limited.status!!
        assertTrue(ls.limited)
        assertEquals(2_000_000L, ls.limitedSpeedBps)
        assertNull(ls.nextPackageAt)
        assertTrue(ls.stale)

        // Inconsistent package views: "checking", never a guess.
        assertNull((EntitlementParser.parse("""{"kind":"pro","ends_at":1800000000}""") as Entitlement.Pro).status)
        assertNull((EntitlementParser.parse("""{"kind":"pro","ends_at":1,"pro":{"used_bytes":3,"limit_bytes":0,"limited":false,"plan_end":5}}""") as Entitlement.Pro).status)
        assertNull((EntitlementParser.parse("""{"kind":"pro","ends_at":1,"pro":{"used_bytes":3,"limit_bytes":9,"limited":true,"plan_end":5}}""") as Entitlement.Pro).status)
        assertNull((EntitlementParser.parse("""{"kind":"pro","ends_at":1,"pro":{"used_bytes":3,"limit_bytes":9,"limited":false}}""") as Entitlement.Pro).status)

        val legacy = EntitlementParser.parse("""{"kind":"unlimited","tier":"legacy_unlimited","ends_at":1800000000,"quota_bytes":null,"bonus":{"reserve_bytes":26843545600}}""")
        assertEquals(Entitlement.PaidNoLimit(Instant.ofEpochSecond(1800000000), 26_843_545_600L), legacy)
        val plain = EntitlementParser.parse("""{"kind":"unlimited","ends_at":1800000000,"quota_bytes":null}""")
        assertTrue(plain is Entitlement.PaidNoLimit)
        val verifying = EntitlementParser.parse("""{"kind":"subscription","tier":"verifying","ends_at":1800000000,"quota_bytes":null}""")
        assertEquals(Entitlement.PaidChecking(Instant.ofEpochSecond(1800000000), null), verifying)

        // Unchanged kinds.
        assertEquals(Entitlement.Basic(Instant.ofEpochSecond(10), 100 * Catalog.GIB, 5, null),
            EntitlementParser.parse("""{"kind":"basic","ends_at":10,"quota_bytes":107374182400,"used_bytes":5}"""))
        assertEquals(Entitlement.Trial(Instant.ofEpochSecond(10), 3 * Catalog.GIB, 0, null),
            EntitlementParser.parse("""{"kind":"trial","ends_at":10,"quota_bytes":3221225472,"used_bytes":null}"""))
        assertEquals(Entitlement.Other(Instant.ofEpochSecond(10), null, null, null),
            EntitlementParser.parse("""{"kind":"special","ends_at":10,"quota_bytes":null}"""))
        assertNotNull(EntitlementParser.parse("""{"kind":"basic","ends_at":10,"quota_bytes":null}""") as? Entitlement.Other)
    }
}
