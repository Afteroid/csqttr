package com.csqtt.client.ui.legacy

import android.content.Context
import java.io.File

internal fun normalizeDeployScript(script: String): String =
    script.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

internal fun writeDeployScript(context: Context, target: File) {
    val script = context.assets.open("deploy.sh").bufferedReader(Charsets.UTF_8).use { it.readText() }
    target.writeText(normalizeDeployScript(script), Charsets.UTF_8)
}
