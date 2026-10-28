package com.marzvpn.app.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Several servers in one country must be distinguishable: country name + short server letter. */
class RouteVariantTest {
    private val ru = Locale.forLanguageTag("ru")
    private val en = Locale.ENGLISH

    private fun se(id: String, label: String, apiVariant: String?) =
        Server(id = id, countryCode = "SE", kind = RouteKind.Direct, path = listOf("SE"), sourceLabel = label,
            variant = routeVariant(apiVariant, label))

    @Test fun twoSwedishServersHaveDifferentTitles() {
        val a = se("7c88149153", "Sweden A", "A")
        val l = se("f63ad0ba70", "Sweden L", "L")
        assertEquals("Швеция A", RouteNames.title(a, ru))
        assertEquals("Швеция L", RouteNames.title(l, ru))
        assertEquals("Sweden A", RouteNames.title(a, en))
        assertEquals("Sweden L", RouteNames.title(l, en))
    }

    @Test fun olderAppApiWithoutVariantFallsBackToTheServerName() {
        assertEquals("A", routeVariant(null, "Sweden A"))
        assertEquals("Швеция A", RouteNames.title(se("x", "Sweden A", null), ru))
    }

    @Test fun singleServerCountriesAndRouteWordsGetNoLetter() {
        assertNull(routeVariant(null, "USA"))
        assertNull(routeVariant(null, "USA · Alternative"))
        assertNull(routeVariant(null, "Sweden ABC"))
        assertNull(routeVariant("abc", null))
        assertEquals("США", RouteNames.title(Server(id = "u", countryCode = "US", sourceLabel = "USA"), ru))
    }

    @Test fun idIsNotChanged() {
        assertEquals("f63ad0ba70", se("f63ad0ba70", "Sweden L", "L").id)
    }

    @Test fun serverLettersRemainDistinctInAllSupportedLocales() {
        for (tag in listOf("en", "ru", "fa", "zh-CN")) {
            val locale = Locale.forLanguageTag(tag)
            val a = se("same-stable-a", "Sweden A", null)
            val l = se("same-stable-l", "Sweden L", null)
            assertEquals(a.countryName(locale) + " A", RouteNames.title(a, locale))
            assertEquals(l.countryName(locale) + " L", RouteNames.title(l, locale))
        }
    }
}
