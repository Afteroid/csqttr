package com.csqtt.client

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeOutputReaderTest {
    @Test fun silentNativeProcessCanBeCancelledWithoutBlockingRead() = runTest {
        val input = object : InputStream() {
            override fun available() = 0
            override fun read(): Int = error("Must never block on a silent process")
        }
        val reader = launch { readProbeOutput(input, { true }) { fail("No output expected") } }
        runCurrent(); reader.cancelAndJoin()
        assertTrue(reader.isCompleted)
    }

    @Test fun parsesCompleteAndFinalUtf8Lines() = runTest {
        val lines = mutableListOf<String>()
        readProbeOutput(ByteArrayInputStream("TUNCONF:1\r\nготово".toByteArray()), { false }, lines::add)
        assertEquals(listOf("TUNCONF:1", "готово"), lines)
    }
}
