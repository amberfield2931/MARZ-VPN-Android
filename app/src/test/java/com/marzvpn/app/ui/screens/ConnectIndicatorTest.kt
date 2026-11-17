package com.marzvpn.app.ui.screens

import com.marzvpn.app.TrafficState
import com.marzvpn.app.vpn.EngineState
import com.marzvpn.app.vpn.NetworkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectIndicatorTest {
    @Test
    fun staysGreenWhileTheVpnIsLeftOnWhateverTheNetworkDoes() {
        for (traffic in TrafficState.entries) {
            assertEquals(traffic.name, IndicatorColor.Connected, indicatorColor(true, EngineState.Connected, traffic))
        }
    }

    @Test
    fun anythingButConnectedIsNotGreen() {
        for (state in EngineState.entries.filter { it != EngineState.Connected }) {
            for (traffic in TrafficState.entries) assertEquals(IndicatorColor.Idle, indicatorColor(true, state, traffic))
        }
    }

    @Test
    fun anImsNetworkIsNotInternet() {
        // Mobile data off: the IMS network stays connected and validated, without the internet capability.
        assertFalse(NetworkIdentity.countsAsInternet(isVpn = false, hasInternet = false, validated = true))
    }

    @Test
    fun onlyAValidatedInternetNetworkOutsideTheVpnCounts() {
        assertTrue(NetworkIdentity.countsAsInternet(isVpn = false, hasInternet = true, validated = true))
        assertFalse(NetworkIdentity.countsAsInternet(isVpn = true, hasInternet = true, validated = true))
        assertFalse(NetworkIdentity.countsAsInternet(isVpn = false, hasInternet = true, validated = false))
    }
}
