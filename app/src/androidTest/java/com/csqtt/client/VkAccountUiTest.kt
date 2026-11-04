package com.csqtt.client

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.csqtt.client.ui.v2.design.CsqttV2Theme
import com.csqtt.client.ui.v2.dialogs.V2VkAccountsDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Emulator fixtures only. No real account, authentication request, or VPN connection. */
class VkAccountUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    @Test fun accountMenuHidesIdentifiersAndCredentials() {
        val store = SettingsStore(compose.activity)
        runBlocking {
            store.saveVkAccessToken("fixture-one", "2718281828")
            store.saveVkAccessToken("fixture-two", "3141592653")
        }
        val profile = runBlocking { store.activeProfile.first() }
        val connection = runBlocking { store.readProfileConnectionSettings(profile) }
        var choose = false
        compose.setContent {
            CsqttV2Theme(darkTheme = true, themePalette = UiDesignPresets.darkPalette(UiDesignPresets.DEFAULT)) {
                V2VkAccountsDialog(store, onAuthorize = { choose = it }, onDismiss = {})
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Аккаунт 1").fetchSemanticsNodes().isNotEmpty() }
        for (hidden in listOf("2718281828", "3141592653", "fixture-one", "fixture-two", "Копировать токен", "Удалить текущий токен")) {
            compose.onAllNodesWithText(hidden, substring = true).assertCountEquals(0)
        }
        compose.onNodeWithText("Аккаунт 1").performClick()
        compose.waitUntil(5000) { runBlocking { store.vkAccessTokenUserId.first() == "2718281828" } }
        compose.onNodeWithText("Добавить / сменить аккаунт").performClick()
        compose.runOnIdle { assertTrue(choose) }
        compose.onNodeWithContentDescription("Удалить аккаунт 1").performClick()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { runBlocking { "2718281828" !in store.savedVkAccountIds.first() } }
        assertTrue(runBlocking { "3141592653" in store.savedVkAccountIds.first() })
        assertTrue(runBlocking { store.vkAccessTokenUserId.first().isBlank() })
        assertEquals(connection, runBlocking { store.readProfileConnectionSettings(profile) })
        runBlocking { store.selectSavedVkAccount("3141592653") }
        assertFalse(runBlocking { "2718281828" in store.savedVkAccountIds.first() })
    }

    @Test fun newLoginWaitsForVkCookieRemovalAndKeepsSavedAccounts() {
        val store = SettingsStore(compose.activity)
        val accounts = runBlocking { store.savedVkAccountIds.first() }
        val ready = AtomicInteger()
        val requested = AtomicReference<String?>(null)
        val received = AtomicReference<VkAuthPayload?>(null)
        val acquisitions = AtomicInteger()
        lateinit var view: WebView
        lateinit var session: VkAuthSession
        compose.runOnIdle {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setCookie("https://vk.com/", "remixsid=fixture; Path=/; Secure") { ready.incrementAndGet() }
            cookies.setCookie("https://id.vk.ru/", "remixsid=fixture; Path=/; Secure") { ready.incrementAndGet() }
            cookies.setCookie("https://example.test/", "keep=fixture; Path=/; Secure") { ready.incrementAndGet() }
        }
        compose.waitUntil(5000) { ready.get() == 3 }
        compose.runOnIdle {
            view = object : WebView(compose.activity) {
                override fun loadUrl(url: String) { requested.set(url) }
                override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) { requested.set(url) }
            }
            session = VkAuthSession(onUiReady = {}, onToken = { received.set(it) }, onError = { fail(it) }, chooseAccount = true,
                acquireToken = { cookies, _ ->
                    assertTrue(cookies.contains("fresh-session"))
                    acquisitions.incrementAndGet()
                    Result.success(VkAuthPayload("fixture-new", "42", 0))
                })
            session.attach(view)
            assertNull(received.get())
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("intent://vk.com/#Intent;scheme=vk;end")))
            assertTrue(view.webViewClient.shouldOverrideUrlLoading(view, request("vk://vk.com")))
            assertFalse(view.webViewClient.shouldOverrideUrlLoading(view, request("https://m.vk.com/")))
            assertNull(requested.get())
        }
        try {
            compose.waitUntil(5000) { requested.get() == CsqttConstants.VkAutoHash.VK_LOGIN_URL }
            compose.runOnIdle {
                val cookies = CookieManager.getInstance()
                assertFalse(cookies.getCookie("https://vk.com/").orEmpty().contains("remixsid="))
                assertFalse(cookies.getCookie("https://id.vk.ru/").orEmpty().contains("remixsid="))
                assertTrue(cookies.getCookie("https://example.test/").orEmpty().contains("keep=fixture"))
                assertNull(received.get())
            }
            compose.runOnIdle {
                CookieManager.getInstance().setCookie("https://vk.ru/", "remixsid=fresh-session; Path=/; Secure") { ready.incrementAndGet() }
            }
            compose.waitUntil(5000) { ready.get() == 4 }
            compose.runOnIdle {
                view.webViewClient.onPageFinished(view, "https://m.vk.ru/feed")
                view.webViewClient.onPageFinished(view, "https://m.vk.ru/feed")
            }
            compose.waitUntil(5000) { received.get() != null }
            assertEquals("42", received.get()?.userId)
            assertEquals(1, acquisitions.get())
            assertEquals(accounts, runBlocking { store.savedVkAccountIds.first() })
        } finally {
            compose.runOnIdle { session.stop(); view.destroy() }
        }
    }
}
