package com.csqtt.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.csqtt.client.ui.legacy.writeDeployScript
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeployScriptAssetTest {
    @Test
    fun packagedAndExtractedInstallerUseUnixLineEndings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packaged = context.assets.open("deploy.sh").bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(packaged.startsWith("#!/bin/bash\n"))
        assertFalse("APK contains a Windows CRLF shell script", packaged.contains('\r'))
        val target = File.createTempFile("deploy-test-", ".sh", context.cacheDir)
        try {
            writeDeployScript(context, target)
            val extracted = target.readText()
            assertTrue(extracted.startsWith("#!/bin/bash\n"))
            assertFalse(extracted.contains('\r'))
        } finally {
            target.delete()
        }
    }
}
