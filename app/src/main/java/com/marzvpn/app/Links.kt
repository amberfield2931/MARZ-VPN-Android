package com.marzvpn.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.marzvpn.app.model.PlanCode
import io.nekohasekai.sfa.BuildConfig
import java.util.Locale

/** Distribution channel, fixed at build time. Store builds never contain the web checkout path. */
enum class Channel { Web, Play }

object Links {
    private const val SITE = "https://marzvpn.com"
    const val BOT = "https://t.me/MarzReality_bot"
    const val SUPPORT_EMAIL = "support@marzvpn.com"
    const val LICENSE = "https://www.gnu.org/licenses/gpl-3.0.html"

    val channel: Channel = if (BuildConfig.MARZ_CHANNEL == "play") Channel.Play else Channel.Web

    /** The web checkout is allowed only in the APK distributed from marzvpn.com. */
    val webCheckoutAllowed: Boolean get() = channel == Channel.Web

    private fun sitePrefix(locale: Locale): String = when (locale.language) {
        "ru" -> "/ru"
        "fa" -> "/fa"
        "zh" -> "/zh-cn"
        else -> ""
    }

    /**
     * Existing public checkout page; no tokens, UUIDs or account data in the URL. Account binding
     * will be added with server-side checkout intents (docs/ARCHITECTURE.md §4).
     */
    fun checkoutUrl(plan: PlanCode, locale: Locale): String {
        val query = if (plan == PlanCode.Unlimited) "?plan=unlimited" else "?plan=basic"
        return "$SITE${sitePrefix(locale)}/checkout$query"
    }

    fun siteUrl(locale: Locale) = "$SITE${sitePrefix(locale)}/"
    fun termsUrl(locale: Locale) = "$SITE${sitePrefix(locale)}/terms"

    fun openInBrowserTab(context: Context, url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            open(context, url)
        }
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun email(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL?subject=MARZ%20VPN%20App"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
        }
    }
}
