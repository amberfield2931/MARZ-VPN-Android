package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.withContext

class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false

    // MARZ VPN: the route the engine really uses, shown as "Connected · <route>" and refreshed on change.
    private var activeTag: String? = null
    private var started = false
    private val routeHandler = object : CommandClient.Handler {
        override fun updateGroups(newGroups: MutableList<io.nekohasekai.libbox.OutboundGroup>) {
            val selector = newGroups.firstOrNull { it.tag == com.marzvpn.app.vpn.RouteMonitor.SELECTOR_TAG }?.selected
            val auto = newGroups.firstOrNull { it.tag == com.marzvpn.app.vpn.RouteMonitor.AUTO_TAG }?.selected
            val tag = if (selector == com.marzvpn.app.vpn.RouteMonitor.AUTO_TAG) auto else selector
            if (tag != activeTag) {
                activeTag = tag
                if (started) notifyText()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val routeClient = CommandClient(GlobalScope, CommandClient.ConnectionType.Groups, routeHandler, localOnly = true)

    private fun notifyText() {
        if (!checkPermission()) return
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentText(com.marzvpn.app.vpn.MarzNotification.connectedText(service, activeTag)).build(),
        )
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle(service.getString(R.string.app_name)).setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(com.marzvpn.app.vpn.MarzNotification.openApp(service, flags))
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.marz_notif_disconnect),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.marz_notif_change_server),
                        com.marzvpn.app.vpn.MarzNotification.openServers(service, flags),
                    ).build(),
                )
            }
    }

    fun show(lastProfileName: String, @StringRes contentTextId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Service Notifications",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        started = contentTextId == R.string.status_started
        val text = if (started) {
            com.marzvpn.app.vpn.MarzNotification.connectedText(service, activeTag)
        } else {
            com.marzvpn.app.vpn.MarzNotification.connectingText(service)
        }
        service.startForeground(
            notificationId,
            notificationBuilder
                .setContentTitle(service.getString(R.string.app_name))
                .setContentText(text).build(),
        )
    }

    suspend fun start() {
        routeClient.connect()
        if (Settings.dynamicNotification && checkPermission()) {
            commandClient.connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    private fun registerReceiver() {
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    override fun updateStatus(status: StatusMessage) {
        // MARZ VPN: the speed goes to the sub-text; the content line keeps "Connected · <route>".
        val content =
            Libbox.formatBytes(status.uplink) + "/s ↑  " + Libbox.formatBytes(status.downlink) + "/s ↓"
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.setSubText(content).build(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                commandClient.connect()
            }

            Intent.ACTION_SCREEN_OFF -> {
                commandClient.disconnect()
            }
        }
    }

    fun close() {
        started = false
        routeClient.disconnect()
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
