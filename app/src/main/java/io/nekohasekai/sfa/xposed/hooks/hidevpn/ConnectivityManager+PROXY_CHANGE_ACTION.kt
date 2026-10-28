package io.nekohasekai.sfa.xposed.hooks.hidevpn

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Proxy
import android.net.ProxyInfo
import android.os.Binder
import android.os.UserHandle
import androidx.annotation.RequiresPermission
import de.robv.android.xposed.XposedHelpers
import io.nekohasekai.sfa.xposed.HookErrorStore
import io.nekohasekai.sfa.xposed.hooks.SafeMethodHook

class HookConnectivityManagerProxyChangeAction(private val helper: ConnectivityServiceHookHelper) {
    private companion object {
        private const val SOURCE = "HookProxyChangeAction"
    }

    fun install() {
        if (helper.sdkInt >= 29) {
            try {
                hookProxyBroadcastTracker()
                return
            } catch (e: Throwable) {
                HookErrorStore.w(SOURCE, "hookProxyBroadcastTracker failed: ${e.message}", e)
            }
        }

        try {
            hookLegacyProxyBroadcast()
        } catch (e: Throwable) {
            HookErrorStore.w(SOURCE, "hookLegacyProxyBroadcast failed: ${e.message}", e)
        }
    }

    private fun hookProxyBroadcastTracker() {
        val trackerClass = helper.resolveConnectivityModuleClass("ProxyTracker", "connectivity")
        XposedHelpers.findAndHookMethod(
            trackerClass,
            "sendProxyBroadcast",
            object : SafeMethodHook(SOURCE) {
                // This callback is installed in Android system_server, not the
                // MARZ app process. Retain runtime checks as well as its contract.
                @RequiresPermission(allOf = ["android.permission.INTERACT_ACROSS_USERS", Manifest.permission.BROADCAST_STICKY])
                override fun beforeHook(param: MethodHookParam) {
                    val tracker = param.thisObject ?: return
                    val context = XposedHelpers.getObjectField(tracker, "mContext") as Context
                    val proxyInfo = emptyProxyInfo()
                    val intent = Intent(Proxy.PROXY_CHANGE_ACTION)
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING)
                    intent.putExtra("android.intent.extra.PROXY_INFO", proxyInfo)
                    val ident = Binder.clearCallingIdentity()
                    try {
                        val userAll = try {
                            UserHandle::class.java.getField("ALL").get(null) as? UserHandle
                        } catch (_: Throwable) {
                            null
                        }
                        // The hook runs in the platform process. An unprivileged
                        // context must not attempt signature-protected broadcasts.
                        if (context.checkSelfPermission(Manifest.permission.BROADCAST_STICKY) != PackageManager.PERMISSION_GRANTED) return
                        if (userAll != null) {
                            if (context.checkSelfPermission("android.permission.INTERACT_ACROSS_USERS") != PackageManager.PERMISSION_GRANTED) return
                            context.sendStickyBroadcastAsUser(intent, userAll)
                        } else {
                            context.sendStickyBroadcast(intent)
                        }
                    } catch (_: SecurityException) {
                        // Preserve the original method when platform permissions changed.
                        return
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                    param.result = null
                }
            },
        )
    }

    private fun hookLegacyProxyBroadcast() {
        XposedHelpers.findAndHookMethod(
            helper.cls,
            "sendProxyBroadcast",
            ProxyInfo::class.java,
            object : SafeMethodHook(SOURCE) {
                override fun beforeHook(param: MethodHookParam) {
                    param.args[0] = emptyProxyInfo()
                }
            },
        )
    }

    private fun emptyProxyInfo(): ProxyInfo = ProxyInfo.buildDirectProxy("", 0)
}
