package com.csqtt.client

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.csqtt.client.ui.v2.components.V2Switch
import com.csqtt.client.ui.v2.design.CsqttV2Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SwitchRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun storedCheckedValueIsDrawnAtItsFinalPositionWithoutPlayingAnAnimation() {
        val checked = mutableStateOf(false)
        compose.setContent {
            CsqttV2Theme { V2Switch(checked.value, { checked.value = it }, Modifier.testTag("stored-switch")) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { checked.value = true }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val image = compose.onNodeWithTag("stored-switch").captureToImage().toPixelMap()
        // The checked thumb must already be white at the right end after two frames.
        val rightThumb = image[(image.width * 0.73f).toInt(), image.height / 2]
        assertTrue("Stored value is still animating instead of appearing immediately", rightThumb.red > .95f && rightThumb.green > .95f && rightThumb.blue > .95f)
        compose.mainClock.autoAdvance = true
    }
}
