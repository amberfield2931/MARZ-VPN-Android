package com.marzvpn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.marzvpn.app.ui.MarzApp
import com.marzvpn.app.ui.theme.MarzTheme
import com.marzvpn.app.ui.theme.isDark
import com.marzvpn.app.vpn.EngineState
import io.nekohasekai.sfa.compose.MainActivity
import kotlinx.coroutines.launch

/**
 * MARZ VPN entry point. AppCompatActivity so per-app language switching works on all supported
 * Android versions. The upstream sing-box UI (MainActivity) is reachable only from Diagnostics.
 */
class MarzActivity : AppCompatActivity() {
    companion object {
        /** Screen to open, e.g. "servers" from the VPN notification's "Change server" action. */
        const val EXTRA_OPEN = "com.marzvpn.app.extra.OPEN"
    }

    private val vm: MarzViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) toggleConnection()
    }

    // Android 13+: without this permission the VPN's status notification is hidden. Asked once, on the
    // first connect; the VPN connects whatever the answer is.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        toggleConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleOpenIntent(intent)
        lifecycleScope.launch {
            // A new config arrived while connected (server change, plan change): reconnect with it.
            vm.restartRequests.collect { vm.consumeRestart(); vm.engine.restartIfRunning(this@MarzActivity, "new config installed") }
        }
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val dark = isDark(theme)
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.3f).toArgb())
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MarzTheme(theme) {
                MarzApp(vm = vm, onToggleConnection = ::connectRequested, onEngineTools = ::openEngineTools)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A profile may have been changed in the engineer tools; the plan may have changed (e.g. after paying).
        vm.refreshConfigSource()
        if (vm.api.isSignedIn()) vm.sync()
        // Back from Telegram with the code (or after the process was killed): keep following the
        // pending code sign-in. Nothing is opened and no new request is created here.
        else vm.resumeTelegramCodeLogin()
        vm.onForeground(true)
    }

    override fun onPause() {
        vm.onForeground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN)?.let { vm.requestOpen(it) }
        intent?.removeExtra(EXTRA_OPEN)
    }

    private fun connectRequested() {
        if (Build.VERSION.SDK_INT >= 33 && vm.engineState.value == EngineState.Disconnected &&
            !vm.notificationPermissionAsked() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            vm.markNotificationPermissionAsked()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        toggleConnection()
    }

    private fun toggleConnection() {
        lifecycleScope.launch {
            vm.toggleConnection(this@MarzActivity)?.let { vpnPermission.launch(it) }
        }
    }

    private fun openEngineTools() {
        if (!io.nekohasekai.sfa.BuildConfig.DEBUG) return
        startActivity(Intent(this, MainActivity::class.java))
    }
}
