package com.csqtt.client

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.FileDescriptor

/** Packet-preserving virtual TUN. Its only peer is the native client's received file descriptor. */
internal class ProbeTunTransport : Closeable {
    private val appFd = FileDescriptor()
    private val clientFd = FileDescriptor()
    init { Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, appFd, clientFd) }

    fun attach(uds: String) {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(uds, LocalSocketAddress.Namespace.ABSTRACT))
            socket.setFileDescriptorsForSend(arrayOf(clientFd))
            socket.soTimeout = 1000
            socket.outputStream.write(1)
            check(socket.inputStream.read() == 1)
        }
    }

    suspend fun exchange(probe: TunnelProbePacket, timeoutMs: Long = 4000, shouldAbort: () -> Boolean = { false }): Int? {
        val packet = probe.request()
        val received = ByteArray(65535)
        val poll = StructPollfd().apply { fd = appFd; events = OsConstants.POLLIN.toShort() }
        val start = SystemClock.elapsedRealtimeNanos()
        Os.write(appFd, packet, 0, packet.size)
        while (SystemClock.elapsedRealtimeNanos() - start < timeoutMs * 1_000_000) {
            currentCoroutineContext().ensureActive()
            check(!shouldAbort())
            if (Os.poll(arrayOf(poll), 200) > 0) {
                val length = Os.read(appFd, received, 0, received.size)
                if (probe.isReply(received.copyOf(length))) {
                    return ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).toInt().coerceAtLeast(1)
                }
            }
        }
        return null
    }

    override fun close() {
        runCatching { Os.close(appFd) }
        runCatching { Os.close(clientFd) }
    }
}
