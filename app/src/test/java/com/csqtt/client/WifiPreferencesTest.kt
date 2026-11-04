package com.csqtt.client

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class WifiPreferencesTest {
    @Test fun rememberSelectsNetworkForSelectedRule() {
        val prefs = mutablePreferencesOf(WifiPreferenceStore.modeKey to "blacklist", WifiPreferenceStore.selectedKey to "Home")
        WifiPreferenceStore.remember(prefs, "Cafe")
        val result = WifiPreferenceStore.read(prefs)
        assertEquals(WifiNetworkListMode.BLACKLIST, result.mode)
        assertEquals(setOf("Home", "Cafe"), result.selected)
        assertEquals(setOf("Home", "Cafe"), result.saved)
        assertTrue(shouldPauseForWifiNetwork(true, true, "Cafe", result.mode, result.selected))
    }

    @Test fun rememberSelectsExceptionWithoutChangingModeOrEnablingFeature() {
        val prefs = mutablePreferencesOf(WifiPreferenceStore.modeKey to "whitelist")
        assertTrue(WifiPreferenceStore.remember(prefs, "Cafe"))
        val result = WifiPreferenceStore.read(prefs)
        assertEquals(WifiNetworkListMode.WHITELIST, result.mode)
        assertEquals(setOf("Cafe"), result.selected)
        assertEquals(setOf("Cafe"), result.saved)
        assertFalse(result.enabled)
        assertFalse(shouldPauseForWifiNetwork(true, true, "Cafe", result.mode, result.selected))
        assertTrue(shouldPauseForWifiNetwork(true, true, "Other", result.mode, result.selected))
    }

    @Test fun rememberOnAnyWifiPreservesOldSelectionsWithoutSelectingNewNetwork() {
        val prefs = mutablePreferencesOf(WifiPreferenceStore.modeKey to "all", WifiPreferenceStore.selectedKey to "Home")
        WifiPreferenceStore.remember(prefs, "Cafe")
        val result = WifiPreferenceStore.read(prefs)
        assertEquals(setOf("Home"), result.selected)
        assertEquals(setOf("Home", "Cafe"), result.saved)
        assertEquals(WifiNetworkListMode.ALL, result.mode)
    }

    @Test fun staleNotificationCannotApplySelectionUnderAnotherRule() {
        val prefs = mutablePreferencesOf(WifiPreferenceStore.modeKey to "whitelist", WifiPreferenceStore.enabledKey to true)
        assertFalse(WifiPreferenceStore.remember(prefs, "Cafe", WifiNetworkListMode.BLACKLIST, true))
        assertTrue(WifiPreferenceStore.read(prefs).saved.isEmpty())
        prefs[WifiPreferenceStore.enabledKey] = false
        assertFalse(WifiPreferenceStore.remember(prefs, "Cafe", WifiNetworkListMode.WHITELIST, true))
        assertTrue(WifiPreferenceStore.read(prefs).selected.isEmpty())
        prefs[WifiPreferenceStore.enabledKey] = true
        assertTrue(WifiPreferenceStore.remember(prefs, "Cafe", WifiNetworkListMode.WHITELIST, true))
        assertEquals(setOf("Cafe"), WifiPreferenceStore.read(prefs).selected)
    }

    @Test fun oldWhitelistRemainsExceptSelectedEvenWhenEmpty() {
        val prefs = mutablePreferencesOf(WifiPreferenceStore.modeKey to "whitelist", WifiPreferenceStore.selectedKey to "Home")
        val result = WifiPreferenceStore.read(prefs)
        assertEquals(WifiNetworkListMode.WHITELIST, result.mode)
        assertTrue("Old selections must still be visible", "Home" in result.saved)
        assertFalse(shouldPauseForWifiNetwork(true, true, "Home", result.mode, result.selected))
        WifiPreferenceStore.select(prefs, "Home", false)
        assertTrue("Unchecking does not forget a saved network", "Home" in WifiPreferenceStore.read(prefs).saved)
        assertTrue(shouldPauseForWifiNetwork(true, true, "Home", result.mode, emptySet()))
    }

    @Test fun freshInstallPreservesPauseOnAnyWifiWithoutTurningItOn() {
        val value = WifiPreferenceStore.read(mutablePreferencesOf())
        assertFalse(value.enabled)
        assertEquals(WifiNetworkListMode.ALL, value.mode)
        assertTrue(shouldPauseForWifiNetwork(true, true, null, value.mode, emptySet()))
        assertFalse(shouldPauseForWifiNetwork(true, false, null, value.mode, emptySet()))
    }

    @Test fun implicitLegacyWhitelistKeepsItsExceptions() {
        val prefs = mutablePreferencesOf(WifiPreferenceStore.selectedKey to "Home")
        val value = WifiPreferenceStore.read(prefs)
        assertEquals(WifiNetworkListMode.WHITELIST, value.mode)
        assertFalse(shouldPauseForWifiNetwork(true, true, "Home", value.mode, value.selected))
        WifiPreferenceStore.select(prefs, "Home", false)
        assertEquals(WifiNetworkListMode.WHITELIST, WifiPreferenceStore.read(prefs).mode)
    }

    @Test fun sequentialActionsKeepOtherNetworksAndDeselectOnlyRequestedNetwork() {
        val prefs = mutablePreferencesOf()
        WifiPreferenceStore.select(prefs, "Home", true)
        WifiPreferenceStore.select(prefs, "Office", true)
        WifiPreferenceStore.select(prefs, "Home", false)
        assertEquals(setOf("Office"), WifiPreferenceStore.read(prefs).selected)
        assertEquals(setOf("Home", "Office"), WifiPreferenceStore.read(prefs).saved)
        WifiPreferenceStore.forget(prefs, "Office")
        assertTrue(WifiPreferenceStore.read(prefs).selected.isEmpty())
        assertEquals(setOf("Home"), WifiPreferenceStore.read(prefs).saved)
    }

    @Test fun unavailableAndMalformedSsidsAreNeverSavedOrProposed() {
        val prefs = mutablePreferencesOf()
        listOf("", "<unknown ssid>", "a\nb", "a\rb", "a\u0000b").forEach {
            WifiPreferenceStore.remember(prefs, it)
            assertNull(wifiDiscoveryCandidate(true, it, emptySet(), emptySet()))
        }
        assertTrue(WifiPreferenceStore.read(prefs).saved.isEmpty())
        assertNull(wifiDiscoveryCandidate(true, null, emptySet(), emptySet()))
    }

    @Test fun discoveryDoesNotRepeatForSavedDismissedOrDisabledFeature() {
        assertEquals("Cafe", wifiDiscoveryCandidate(true, "\"Cafe\"", setOf("Home"), emptySet()))
        assertNull(wifiDiscoveryCandidate(false, "Cafe", emptySet(), emptySet()))
        assertNull(wifiDiscoveryCandidate(true, "Cafe", setOf("Cafe"), emptySet()))
        assertNull(wifiDiscoveryCandidate(true, "Cafe", emptySet(), setOf("Cafe")))
    }

    @Test fun duplicateRememberActionsAreIdempotentAndCaseIsPreserved() {
        val prefs = mutablePreferencesOf()
        WifiPreferenceStore.remember(prefs, "Cafe")
        WifiPreferenceStore.remember(prefs, "Cafe")
        WifiPreferenceStore.remember(prefs, "cafe")
        assertEquals(setOf("Cafe", "cafe"), WifiPreferenceStore.read(prefs).saved)
        assertTrue(WifiPreferenceStore.read(prefs).selected.isEmpty())
    }

    @Test fun emptySelectionMatchesAllThreeRules() {
        assertTrue(shouldPauseForWifiNetwork(true, true, "Home", WifiNetworkListMode.ALL, emptySet()))
        assertFalse(shouldPauseForWifiNetwork(true, true, "Home", WifiNetworkListMode.BLACKLIST, emptySet()))
        assertTrue(shouldPauseForWifiNetwork(true, true, "Home", WifiNetworkListMode.WHITELIST, emptySet()))
    }
}
