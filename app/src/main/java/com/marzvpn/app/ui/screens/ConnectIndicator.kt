package com.marzvpn.app.ui.screens

import com.marzvpn.app.TrafficState
import com.marzvpn.app.vpn.EngineState

enum class IndicatorColor { Unavailable, Connected, Idle }

/**
 * The big button follows the service only. While the user leaves the VPN on it stays on, with or without a
 * network: the tunnel comes back by itself, and the text below the button says what is happening.
 */
@Suppress("UNUSED_PARAMETER") // traffic is taken on purpose, so that the test can prove that it changes nothing
fun indicatorColor(canConnect: Boolean, state: EngineState, traffic: TrafficState): IndicatorColor = when {
    !canConnect -> IndicatorColor.Unavailable
    state == EngineState.Connected -> IndicatorColor.Connected
    else -> IndicatorColor.Idle
}
