package com.csqtt.client

import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Do not park a child coroutine in readLine while the native probe is silent. */
internal suspend fun readProbeOutput(input: InputStream, alive: () -> Boolean, onLine: (String) -> Unit) {
    val pending = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val available = input.available()
        if (available == 0) {
            if (!alive()) break
            delay(25)
            continue
        }
        val count = input.read(buffer, 0, minOf(available, buffer.size))
        if (count < 0) break
        for (i in 0 until count) {
            if (buffer[i] == '\n'.code.toByte()) {
                onLine(pending.toString("UTF-8").trimEnd('\r'))
                pending.reset()
            } else {
                check(pending.size() < 65536) { "Diagnostic line too long" }
                pending.write(buffer[i].toInt())
            }
        }
    }
    if (pending.size() > 0) onLine(pending.toString("UTF-8"))
}
