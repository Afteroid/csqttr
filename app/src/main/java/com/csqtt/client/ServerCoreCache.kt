package com.csqtt.client

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Only APK-pinned sizes and digests are trusted; never a downloaded manifest. */
internal class ServerCoreCache(private val directory: File) {
    data class Artifact(val name: String, val url: String, val size: Long, val sha256: String)

    @Synchronized
    fun obtain(artifact: Artifact, checkCancelled: () -> Unit = {},
               open: (String) -> InputStream = ::openHttps): File {
        require(artifact.name.matches(Regex("csqtt-linux-(amd64|arm64|armv7)")))
        require(artifact.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(artifact.size in 1..100_000_000)
        require(artifact.url.startsWith("https://"))
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "${artifact.name}-${artifact.sha256.lowercase()}")
        checkCancelled()
        if (valid(target, artifact)) return target
        val partial = File.createTempFile("download-", ".part", directory)
        try {
            open(artifact.url).use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > artifact.size) throw IOException("Размер серверного ядра не совпадает")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            checkCancelled()
            if (!valid(partial, artifact)) throw IOException("Проверка SHA-256 серверного ядра не пройдена")
            if (target.exists() && !target.delete()) throw IOException("Не удалось обновить кэш ядра")
            if (!partial.renameTo(target)) throw IOException("Не удалось сохранить ядро в кэш")
            return target
        } finally { partial.delete() }
    }

    private fun valid(file: File, artifact: Artifact): Boolean {
        if (!file.isFile || file.length() != artifact.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(artifact.sha256, true)
    }

    companion object {
        private fun openHttps(address: String): InputStream {
            var url = URL(address)
            repeat(6) {
                if (url.protocol != "https") throw IOException("Загрузка ядра разрешена только по HTTPS")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                try {
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Пустой адрес перенаправления")
                        url = URL(url, location)
                        connection.disconnect()
                    } else {
                        if (status != 200) throw IOException("Не удалось скачать ядро: HTTP $status")
                        return object : java.io.FilterInputStream(connection.inputStream) {
                            override fun close() { try { super.close() } finally { connection.disconnect() } }
                        }
                    }
                } catch (e: Exception) { connection.disconnect(); throw e }
            }
            throw IOException("Слишком много перенаправлений при загрузке ядра")
        }
    }
}
