package com.csqtt.client

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.csqtt.client.ui.v2.screens.routing.V2AppsRoutingDialog
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** No SettingsStore, VPN or network: exercises the sheet with package loading in progress. */
class RoutingModeInteractionTest {
 @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
 @Test fun allVpnModePreservesBothLists() {
  var saved: Set<String>? = null
  var allMode = false
  compose.setContent {
   V2AppsRoutingDialog(emptyList(), false, false, setOf("custom.app"), setOf("vpn.app"), setOf("black", "white"),
    onClose = { black, white, _, _, all -> saved = black; allMode = all; assertEquals(setOf("vpn.app"), white) })
  }
  compose.onNodeWithText("Все с VPN").performClick()
  compose.onNodeWithContentDescription("Закрыть").performClick()
  compose.waitUntil(5000) { saved != null }
  assertEquals(setOf("custom.app"), saved)
  assertTrue(allMode)
 }
 @Test fun selectedSystemAppsRemainVisibleAndAllModeHasNoStaleCount() {
  val system = mutableStateOf(false)
  val apps = listOf(
   com.csqtt.client.ui.legacy.AppItem("A selected system", "test.system.selected", null, true),
   com.csqtt.client.ui.legacy.AppItem("B hidden system", "test.system.hidden", null, true),
   com.csqtt.client.ui.legacy.AppItem("C user", "test.user", null, false))
  compose.setContent {
   V2AppsRoutingDialog(apps, false, false, setOf("test.system.selected"), emptySet(), setOf("black", "white"),
    showSystemApps = system.value, onShowSystemAppsChange = { system.value = it },
    onClose = { _, _, _, _, _ -> })
  }
  compose.onNodeWithText("A selected system").assertIsDisplayed()
  compose.onNodeWithText("B hidden system").assertDoesNotExist()
  compose.onNodeWithText("C user").assertIsDisplayed()
  compose.onNode(isToggleable()).performClick()
  compose.onNodeWithText("B hidden system").assertExists()
  compose.onNode(isToggleable()).performClick()
  compose.onNodeWithText("A selected system").assertIsDisplayed()
  compose.onNodeWithText("B hidden system").assertDoesNotExist()
  compose.onNodeWithText("Все с VPN").performClick()
  compose.onNodeWithText("Выбрано:", substring = true).assertDoesNotExist()
  compose.onNodeWithText("Все приложения используют VPN").assertIsDisplayed()
 }
 @Test fun presetAndClearAffectOnlyTheSelectedMode() {
  val apps = listOf(
   com.csqtt.client.ui.legacy.AppItem("Avito", "com.avito.android", null, false),
   com.csqtt.client.ui.legacy.AppItem("Telegram", "org.telegram.messenger", null, false))
  var result: Pair<Set<String>, Set<String>>? = null
  compose.setContent {
   V2AppsRoutingDialog(apps, false, false, setOf("com.avito.android"), setOf("org.telegram.messenger"), setOf("black", "white"),
    onClose = { black, white, _, _, _ -> result = black to white })
  }
  compose.onNodeWithText("Очистить список").performClick()
  compose.onNodeWithText("Выбрано: 0 (исключено)").assertExists()
  compose.onNodeWithText("Готовый список").performClick()
  compose.onNodeWithText("Выбрано: 1 (исключено)").assertExists()
  compose.onNodeWithText("С VPN").performClick()
  compose.onNodeWithText("Выбрано: 1 (в туннеле)").assertExists()
  compose.onNodeWithText("Очистить список").performClick()
  compose.onNodeWithText("Выбрано: 0 (в туннеле)").assertExists()
  compose.onNodeWithContentDescription("Закрыть").performClick()
  compose.waitUntil(5000) { result != null }
  assertEquals(setOf("com.avito.android"), result!!.first)
  assertTrue(result!!.second.isEmpty())
 }
 @Test fun modesRespondWhileLoadingWithoutATunnel() {
  val loading = mutableStateOf(true)
  var result: Boolean? = null
  compose.setContent {
   V2AppsRoutingDialog(emptyList(), loading.value, false, emptySet(), emptySet(), emptySet(),
    onClose = { _, _, mode, _, _ -> result = mode })
  }
  compose.onNodeWithText("Без VPN").assertIsEnabled().performClick()
  compose.onNodeWithText("Выбрано: 0 (исключено)").assertExists()
  compose.onNodeWithText("С VPN").assertIsEnabled().performClick()
  compose.onNodeWithText("Выбрано: 0 (в туннеле)").assertExists()
  compose.runOnIdle { loading.value = false }
  compose.onNodeWithText("Очистить список").assertIsEnabled().performClick()
  compose.onNodeWithContentDescription("Закрыть").performClick()
  compose.waitUntil(5000) { result != null }
  assertEquals(true, result)
 }
}
