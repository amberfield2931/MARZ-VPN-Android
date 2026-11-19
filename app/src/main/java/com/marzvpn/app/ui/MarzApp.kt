package com.marzvpn.app.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.marzvpn.app.Links
import com.marzvpn.app.model.AccountState
import androidx.compose.ui.platform.LocalContext
import com.marzvpn.app.vpn.EngineState
import io.nekohasekai.sfa.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.marzvpn.app.MarzViewModel
import com.marzvpn.app.CheckoutUi
import com.marzvpn.app.api.CheckoutAction
import com.marzvpn.app.api.PaymentChannel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.marzvpn.app.ui.screens.DiagnosticsScreen
import com.marzvpn.app.ui.screens.EmailLoginScreen
import com.marzvpn.app.ui.screens.HomeScreen
import com.marzvpn.app.ui.screens.LoginChooserScreen
import com.marzvpn.app.ui.screens.PlansScreen
import com.marzvpn.app.ui.screens.ServersScreen
import com.marzvpn.app.ui.screens.SettingsScreen
import com.marzvpn.app.ui.screens.TelegramLoginScreen
import com.marzvpn.app.ui.screens.WelcomeScreen

private object Route {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val PLANS = "plans?action={action}"
    fun plans(action: CheckoutAction) = "plans?action=${action.name}"
    const val LOGIN = "login"
    const val LOGIN_TELEGRAM = "login/telegram"
    const val LOGIN_EMAIL = "login/email"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
fun MarzApp(vm: MarzViewModel, onToggleConnection: () -> Unit, onEngineTools: () -> Unit) {
    val nav = rememberNavController()
    val account by vm.account.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var updateLater by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val theme by vm.theme.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val engineError by vm.engineError.collectAsStateWithLifecycle()
    val configSource by vm.configSource.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val serverChoice by vm.serverChoice.collectAsStateWithLifecycle()
    val sample by vm.sample.collectAsStateWithLifecycle()
    val developerUnlocked by vm.developerUnlocked.collectAsStateWithLifecycle()
    val telegram by vm.telegram.collectAsStateWithLifecycle()
    val email by vm.email.collectAsStateWithLifecycle()
    val emailLink by vm.emailLink.state.collectAsStateWithLifecycle()
    val syncError by vm.syncError.collectAsStateWithLifecycle()
    val activeServerId by vm.activeServerId.collectAsStateWithLifecycle()
    val routeMissing by vm.routeMissing.collectAsStateWithLifecycle()
    val latency by vm.latency.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val subscription by vm.subscription.collectAsStateWithLifecycle()
    val checkout by vm.checkout.collectAsStateWithLifecycle()
    val checkoutLinkReady by vm.checkoutLinkReady.collectAsStateWithLifecycle()
    val canRetryCheckout by vm.canRetryCheckout.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val liveMs by vm.liveMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = currentLocale()

    // Signed in (real account, not a sample): leave the login screens for Home.
    val signedIn = account is AccountState.SignedIn && !(account as AccountState.SignedIn).isSample
    // Also after signing in again while already signed in (session to confirm): that error then clears.
    LaunchedEffect(signedIn, syncError) {
        if (signedIn && syncError != "reauth_required") {
            val route = nav.currentDestination?.route
            if (route in setOf(Route.WELCOME, Route.LOGIN, Route.LOGIN_TELEGRAM, Route.LOGIN_EMAIL)) {
                vm.markWelcomeSeen()
                nav.navigate(Route.HOME) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    // "Change server" from the VPN notification (or the quick-settings long press) opens the route list.
    LaunchedEffect(Unit) {
        vm.openRequests.collect { screen ->
            vm.consumeOpenRequest()
            if (screen == "servers" && nav.currentDestination?.route != Route.SERVERS) {
                nav.navigate(Route.SERVERS) { launchSingleTop = true }
            }
        }
    }

    // A payment link is opened exactly once, right away, from whatever screen is shown (never stored).
    LaunchedEffect(checkoutLinkReady) {
        if (checkoutLinkReady) vm.takeCheckoutLink()?.let { link ->
            if (link.channel == PaymentChannel.TelegramBot) Links.open(context, link.url) else Links.openInBrowserTab(context, link.url)
        }
    }

    fun openPlans(action: CheckoutAction) {
        // A finished or failed attempt is not shown again; an open payment keeps being watched.
        if (vm.checkout.value !is CheckoutUi.Opened) vm.resetCheckout()
        nav.navigate(Route.plans(action))
    }

    // Decided once per activity: a changing start destination would rebuild the graph and reset navigation.
    val start = rememberSaveable { if (vm.welcomeSeen) Route.HOME else Route.WELCOME }

    NavHost(navController = nav, startDestination = start) {
        composable(Route.WELCOME) {
            WelcomeScreen(
                onTelegram = { nav.navigate(Route.LOGIN_TELEGRAM) },
                onEmail = { nav.navigate(Route.LOGIN_EMAIL) },
                onPlans = { openPlans(CheckoutAction.Buy) },
                trial = catalog.trial,
                onContinue = {
                    vm.markWelcomeSeen()
                    nav.navigate(Route.HOME) { popUpTo(Route.WELCOME) { inclusive = true } }
                },
            )
        }
        composable(Route.HOME) {
            val found = (update as? com.marzvpn.app.update.UpdateUi.Available)?.version
            HomeScreen(
                updateVersion = found?.takeIf { it != updateLater },
                onUpdateDownload = { Links.open(context, Links.androidPageUrl(locale)) },
                onUpdateLater = { updateLater = found },
                account = account,
                engineState = engineState,
                engineError = engineError,
                configSource = configSource,
                servers = servers,
                serverChoice = serverChoice,
                onToggle = onToggleConnection,
                syncError = syncError,
                activeServerId = activeServerId,
                routeMissing = routeMissing,
                traffic = traffic,
                liveMs = liveMs,
                onServers = { nav.navigate(Route.SERVERS) },
                onPlans = ::openPlans,
                onLogin = { nav.navigate(Route.LOGIN) },
                onSettings = { nav.navigate(Route.SETTINGS) },
            )
        }
        composable(Route.PLANS, arguments = listOf(navArgument("action") { type = NavType.StringType; defaultValue = CheckoutAction.Buy.name })) { entry ->
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.cancelPendingCheckout() } }
            // The current catalog each time the plans open (the cached or built-in one meanwhile).
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshCatalog() }
            val action = runCatching { CheckoutAction.valueOf(entry.arguments?.getString("action").orEmpty()) }.getOrDefault(CheckoutAction.Buy)
            PlansScreen(
                signedIn = signedIn,
                action = action,
                subscription = subscription,
                sources = (account as? AccountState.SignedIn)?.account?.vpnSources.orEmpty(),
                checkout = checkout,
                catalog = catalog,
                onPay = { plan, planAction, profileId, channel -> vm.startCheckout(plan, planAction, profileId, locale.language, channel) },
                onCheckNow = vm::refreshPayment,
                canRetry = canRetryCheckout,
                onRetry = { vm.retryCheckout(locale.language) },
                onChooseAnother = vm::chooseAnotherPlan,
                onDone = {
                    vm.resetCheckout()
                    nav.back()
                },
                onBack = nav::back,
            )
        }
        composable(Route.LOGIN) {
            LoginChooserScreen(
                onTelegram = { nav.navigate(Route.LOGIN_TELEGRAM) },
                onEmail = { nav.navigate(Route.LOGIN_EMAIL) },
                onBack = nav::back,
            )
        }
        composable(Route.LOGIN_TELEGRAM) {
            TelegramLoginScreen(
                available = vm.api.isAvailable,
                state = telegram,
                onStart = { vm.startTelegramLogin(com.marzvpn.app.api.CheckoutRules.apiLocale(locale.language)) },
                onResume = vm::resumeTelegramLogin,
                onOpenTelegram = { url -> Links.open(context, url) },
                onEnterCode = vm::enterTelegramCode,
                onEmail = {
                    vm.cancelTelegramLogin()
                    nav.navigate(Route.LOGIN_EMAIL)
                },
                onCancel = vm::cancelTelegramLogin,
                onBack = nav::back,
            )
        }
        composable(Route.LOGIN_EMAIL) {
            EmailLoginScreen(
                available = vm.api.isAvailable,
                state = email,
                onSend = { vm.sendEmailCode(it, locale.language) },
                onVerify = { code -> vm.verifyEmailCode(code) {} },
                onResend = { vm.resendEmailCode(locale.language) },
                onChangeEmail = vm::resetEmail,
                onBack = {
                    vm.resetEmail()
                    nav.back()
                },
            )
        }
        composable(Route.SERVERS) {
            // One cold check of every route each time the list is opened; it stops when the list closes.
            androidx.compose.runtime.DisposableEffect(Unit) {
                vm.startRouteCheck()
                onDispose { vm.stopRouteCheck() }
            }
            ServersScreen(
                availability = availability,
                servers = servers,
                fromAccount = vm.serversFromAccount,
                choice = serverChoice,
                connected = engineState == EngineState.Connected,
                activeId = activeServerId,
                latency = latency,
                onRefresh = vm::startRouteCheck,
                onSelect = {
                    vm.selectServer(it)
                    nav.back()
                },
                onBack = nav::back,
            )
        }
        composable(Route.SETTINGS) {
            LaunchedEffect(Unit) { vm.refreshEmailLink() }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.emailLink.reset() } }
            SettingsScreen(
                update = update,
                onCheckUpdates = if (vm.updateCheckAvailable) vm::checkUpdates else null,
                onUpdateDownload = { Links.open(context, Links.androidPageUrl(locale)) },
                account = account,
                theme = theme,
                onTheme = vm::setTheme,
                onLogin = { nav.navigate(Route.LOGIN) },
                onLogout = vm::logout,
                emailLink = emailLink,
                onEmailLinkSend = { vm.sendEmailLink(it, locale.language) },
                onEmailLinkConfirm = vm::confirmEmailLink,
                onEmailLinkReset = vm.emailLink::reset,
                onEmailLinkRefresh = vm::refreshEmailLink,
                onEmailUnlink = vm::unlinkEmail,
                onDeveloper = if (vm.developerToolsAvailable) ({ nav.navigate(Route.DIAGNOSTICS) }) else null,
                developerUnlocked = developerUnlocked,
                onVersionTap = {
                    if (vm.onVersionTap()) Toast.makeText(context, R.string.marz_developer_unlocked, Toast.LENGTH_SHORT).show()
                },
                onBack = nav::back,
            )
        }
        // Registered only in debug builds: release builds have no route to the developer screen.
        if (vm.developerToolsAvailable) composable(Route.DIAGNOSTICS) {
            DiagnosticsScreen(
                engineState = engineState,
                engineError = engineError,
                configSource = configSource,
                coreVersion = vm.engine.coreVersion,
                sample = sample,
                onSample = vm::showSample,
                onEngineTools = onEngineTools,
                apiUrl = vm.apiUrl,
                onApiUrl = vm::setApiUrlOverride,
                onBack = nav::back,
            )
        }
    }
}

/** Back that never pops the last screen (avoids a blank NavHost on double taps). */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
}
