package com.csqtt.client

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class SavedVkAccountsTest {
    private val archive = SavedVkAccounts({ "sealed:${it.reversed()}" }, { it.takeIf { it.startsWith("sealed:") }?.removePrefix("sealed:")?.reversed() })

    @Test fun legacyMigrationNeverReplacesARefreshedAccountToken() {
        val prefs = mutablePreferencesOf()
        archive.remember(prefs, "11", "fresh")
        archive.rememberLegacy(prefs, "11", "stale-in-another-profile")
        archive.rememberLegacy(prefs, "22", "previously-unarchived")
        assertEquals("fresh", archive.token(prefs, "11"))
        assertEquals("previously-unarchived", archive.token(prefs, "22"))
    }

    @Test fun remembersBothLoginsWithoutStoringPlaintextOrLosingPreviousAccount() {
        val prefs = mutablePreferencesOf()
        archive.remember(prefs, "11", "first-token")
        archive.remember(prefs, "22", "second-token")
        assertEquals(listOf("11", "22"), archive.available(prefs))
        assertEquals("first-token", archive.token(prefs, "11"))
        assertEquals("second-token", archive.token(prefs, "22"))
        assertFalse(prefs.asMap().values.any { it == "first-token" || it == "second-token" })
    }

    @Test fun repeatedLoginUpdatesOnlyThatAccount() {
        val prefs = mutablePreferencesOf()
        archive.remember(prefs, "11", "old")
        archive.remember(prefs, "22", "other")
        archive.remember(prefs, "11", "fresh")
        assertEquals(listOf("11", "22"), archive.available(prefs))
        assertEquals("fresh", archive.token(prefs, "11"))
        assertEquals("other", archive.token(prefs, "22"))
    }

    @Test fun removalLeavesOtherAccountsAndProfileSecretsIntact() {
        val profile = stringPreferencesKey("connection_password_encrypted_2")
        val prefs = mutablePreferencesOf(profile to "untouched")
        archive.remember(prefs, "11", "first")
        archive.remember(prefs, "22", "other")
        archive.forget(prefs, "11")
        assertNull(archive.token(prefs, "11"))
        assertEquals(listOf("22"), archive.available(prefs))
        assertEquals("untouched", prefs[profile])
    }

    @Test fun tokenWithoutPersonalUserIdCannotEnterAccountList() {
        val prefs = mutablePreferencesOf()
        archive.remember(prefs, "", "link-token")
        archive.remember(prefs, "../other", "invalid")
        assertTrue(prefs.asMap().isEmpty())
        assertNull(archive.token(prefs, "../other"))
    }

    @Test fun unreadableCiphertextDoesNotOfferAnAccountThatCannotBeSelected() {
        val prefs = mutablePreferencesOf()
        archive.remember(prefs, "11", "first")
        prefs[stringPreferencesKey("saved_vk_account_11_encrypted")] = "unreadable"
        assertNull(archive.token(prefs, "11"))
        assertTrue(archive.available(prefs).isEmpty())
    }
}
