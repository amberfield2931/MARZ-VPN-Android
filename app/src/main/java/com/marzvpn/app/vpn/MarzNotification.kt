package com.marzvpn.app.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.marzvpn.app.MarzActivity
import com.marzvpn.app.model.RouteNames
import com.marzvpn.app.settings.MarzPrefs
import io.nekohasekai.sfa.R

/** MARZ VPN texts and actions for the engine's existing foreground notification (no second service). */
object MarzNotification {
    /** "Connected · <route the engine really uses>", from the live outbound tag and the cached route list. */
    fun connectedText(context: Context, activeTag: String?): String {
        val prefs = MarzPrefs(context)
        val routes = prefs.routes.orEmpty()
        val locale = context.resources.configuration.locales[0]
        val active = RouteMonitor.serverId(activeTag)?.let { id -> routes.firstOrNull { it.id == id } }
        val name = when {
            active != null -> RouteNames.full(context, active, routes, locale)
            else -> context.getString(R.string.marz_server_auto)
        }
        return context.getString(R.string.marz_notif_connected, name)
    }

    fun connectingText(context: Context): String = context.getString(R.string.marz_notif_connecting)

    fun openApp(context: Context, flags: Int): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MarzActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
        flags,
    )

    fun openServers(context: Context, flags: Int): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, MarzActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MarzActivity.EXTRA_OPEN, "servers"),
        flags,
    )
}
