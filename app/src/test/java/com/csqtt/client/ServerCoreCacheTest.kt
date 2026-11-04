package com.csqtt.client

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ServerCoreCacheTest {
    @Test fun verifiesCachesAndRepairsCorruption() {
        val dir = Files.createTempDirectory("server-cache").toFile()
        try {
            val bytes = "verified server".toByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val a = ServerCoreCache.Artifact("csqtt-linux-amd64", "https://example.com/core", bytes.size.toLong(), sha)
            val cache = ServerCoreCache(dir)
            val file = cache.obtain(a, open = { bytes.inputStream() })
            assertArrayEquals(bytes, file.readBytes())
            cache.obtain(a, open = { error("Cache must work offline") })
            file.writeText("broken")
            assertArrayEquals(bytes, cache.obtain(a, open = { bytes.inputStream() }).readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsWrongHashTruncationOversizeAndCleansPartialFiles() {
        for (body in listOf("abc", "ab", "abcd")) {
            val dir = Files.createTempDirectory("server-cache").toFile()
            try {
                val a = ServerCoreCache.Artifact("csqtt-linux-arm64", "https://example.com/core", 3, "0".repeat(64))
                assertThrows(java.io.IOException::class.java) { ServerCoreCache(dir).obtain(a, open = { body.byteInputStream() }) }
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }
}
