package com.csqtt.client
import org.junit.Assert.*
import org.junit.Test
class AppRoutingPresetsTest {
 @Test fun explicitPresetAddsMissingAppsWithoutRemovingCustomSelectionsOrReorderingThem() {
  val existing = linkedSetOf("custom.app", "com.avito.android", "not.installed.anymore")
  val installed = setOf("com.avito.android", "com.vkontakte.android", "org.telegram.messenger")
  val merged = AppRoutingPresets.merge(existing, installed, false)
  assertEquals(existing.toList() + "com.vkontakte.android", merged.toList())
  assertEquals(merged, AppRoutingPresets.merge(merged, installed, false))
  assertEquals(existing.toList() + "org.telegram.messenger", AppRoutingPresets.merge(existing, installed, true).toList())
 }
 @Test fun installedRussianAppsAreRecognizedByTheirActualPackageIds() {
  val installed = setOf("com.vkontakte.android", "ru.ozon.app.android", "ru.ozon.fintech.finance",
      "com.wildberries.ru", "ru.yandex.metro", "com.yandex.iot", "ru.sberbankmobile")
  assertEquals(installed - "ru.sberbankmobile", AppRoutingPresets.initial(emptySet(), false, installed, false))
  assertTrue(AppRoutingPresets.initial(emptySet(), false, installed, true).isEmpty())
 }
 @Test fun selectedUseAdditionOrderAndUncheckedReturnToAlphabeticalPosition() {
  val apps = listOf("a", "b", "c", "d")
  val selected = linkedSetOf("d", "b", "a")
  assertEquals(listOf("d", "b", "a", "c"), AppRoutingPresets.orderedPackages(apps, selected))
  val removed = selected - "b"
  assertEquals(listOf("d", "a", "b", "c"), AppRoutingPresets.orderedPackages(apps, removed))
  val addedBack = removed + "b"
  assertEquals(listOf("d", "a", "b", "c"), AppRoutingPresets.orderedPackages(apps, addedBack))
  val restored = addedBack.joinToString(",").split(',').toSet()
  assertEquals(addedBack.toList(), restored.toList())
  assertEquals(apps, AppRoutingPresets.orderedPackages(apps, emptySet()))
 }
 @Test fun preservesNonemptyListsAndDeliberateEmptyLists() {
  val installed = AppRoutingPresets.direct + AppRoutingPresets.vpn
  for (white in listOf(false, true)) {
   assertEquals(setOf("custom.app"), AppRoutingPresets.initial(setOf("custom.app"), false, installed, white))
   assertTrue(AppRoutingPresets.initial(emptySet(), true, installed, white).isEmpty())
  }
 }
 @Test fun seedsOnlyInstalledAppsInTheChosenMode() {
  val installed = setOf("com.avito.android", "org.telegram.messenger", "unknown.app")
  assertEquals(setOf("com.avito.android"), AppRoutingPresets.initial(emptySet(), false, installed, false))
  assertEquals(setOf("org.telegram.messenger"), AppRoutingPresets.initial(emptySet(), false, installed, true))
 }
 @Test fun noBrowsersOrSberAndNoOverlap() {
  assertTrue(AppRoutingPresets.direct.intersect(AppRoutingPresets.vpn).isEmpty())
  assertFalse("ru.sberbankmobile" in AppRoutingPresets.direct)
  for (pkg in listOf("com.android.chrome", "com.yandex.browser", "org.mozilla.firefox", "com.microsoft.emmx")) { assertFalse(pkg in AppRoutingPresets.direct); assertTrue(pkg in AppRoutingPresets.vpn) }
  assertTrue(AppRoutingPresets.direct.all { it.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) })
 }
}
