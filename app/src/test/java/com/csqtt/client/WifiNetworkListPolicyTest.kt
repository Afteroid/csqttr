package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiNetworkListPolicyTest {
    @Test
    fun normalizeWifiSsid_removesAndroidQuotesAndUnknownValue() {
        assertEquals("Home", normalizeWifiSsid("\"Home\""))
        assertEquals("Home", normalizeWifiSsid(" Home "))
        assertNull(normalizeWifiSsid("<unknown ssid>"))
        assertNull(normalizeWifiSsid(""))
    }

    @Test
    fun whitelist_onlyListedWifiKeepsVpnRunning() {
        val configured = setOf("Home", "Office")

        assertFalse(
            shouldPauseForWifiNetwork(true, true, "Home", WifiNetworkListMode.WHITELIST, configured),
        )
        assertTrue(
            shouldPauseForWifiNetwork(true, true, "Cafe", WifiNetworkListMode.WHITELIST, configured),
        )
    }

    @Test
    fun blacklist_onlyListedWifiPausesVpn() {
        val configured = setOf("Cafe")

        assertTrue(
            shouldPauseForWifiNetwork(true, true, "Cafe", WifiNetworkListMode.BLACKLIST, configured),
        )
        assertFalse(
            shouldPauseForWifiNetwork(true, true, "Home", WifiNetworkListMode.BLACKLIST, configured),
        )
    }

    @Test
    fun missingSsid_isSafeForChosenMode() {
        assertTrue(
            shouldPauseForWifiNetwork(true, true, null, WifiNetworkListMode.WHITELIST, setOf("Home")),
        )
        assertFalse(
            shouldPauseForWifiNetwork(true, true, null, WifiNetworkListMode.BLACKLIST, setOf("Cafe")),
        )
    }

    @Test
    fun masterSwitchAndMobileNetworkDisableListGate() {
        assertFalse(
            shouldPauseForWifiNetwork(false, true, "Cafe", WifiNetworkListMode.BLACKLIST, setOf("Cafe")),
        )
        assertFalse(
            shouldPauseForWifiNetwork(true, false, "Cafe", WifiNetworkListMode.BLACKLIST, setOf("Cafe")),
        )
    }

    @Test
    fun emptyDefaultWhitelist_preservesLegacyPauseAllWifiBehavior() {
        assertTrue(
            shouldPauseForWifiNetwork(true, true, "AnyWifi", WifiNetworkListMode.WHITELIST, emptySet()),
        )
    }
}
