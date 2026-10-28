package com.csqtt.client.ui.legacy

import java.io.IOException
import java.net.SocketTimeoutException
import net.schmizz.sshj.SSHClient

internal const val SSH_HANDSHAKE_TIMEOUT_MS = 15_000
internal const val SSH_SESSION_TIMEOUT_MS = 300_000

/** Retry only the transport setup, before authentication or any remote command. */
internal fun connectSshTransport(
    host: String,
    port: Int,
    clientFactory: () -> SSHClient,
    onRetry: () -> Unit = {},
): SSHClient {
    repeat(2) { attempt ->
        val ssh = clientFactory()
        try {
            ssh.connectTimeout = SSH_HANDSHAKE_TIMEOUT_MS
            ssh.timeout = SSH_HANDSHAKE_TIMEOUT_MS
            ssh.transport.timeoutMs = SSH_HANDSHAKE_TIMEOUT_MS
            ssh.connect(host, port)
            return ssh
        } catch (error: Exception) {
            runCatching { ssh.disconnect() }
            val timedOut = generateSequence<Throwable>(error) { it.cause }
                .any { it is SocketTimeoutException }
            if (!timedOut) throw error
            if (attempt == 1) {
                throw IOException("SSH handshake timeout at $host:$port after two connection attempts", error)
            }
            onRetry()
        }
    }
    error("SSH connection attempts exhausted")
}
