package com.csqtt.client

import android.net.LocalServerSocket
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbeTunTransportTest {
    @Test fun exchangesOnlyThroughTheDescriptorPassedToTheNativeSide() = runBlocking<Unit> {
        val name = "csqtt_probe_test_${System.nanoTime()}"
        val server = LocalServerSocket(name)
        val source = byteArrayOf(10, 66, 67, 2)
        val dns = byteArrayOf(1, 1, 1, 1)
        val probe = TunnelProbePacket(source, dns, 12345)
        try {
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    assertEquals(1, socket.inputStream.read())
                    val fd = requireNotNull(socket.ancillaryFileDescriptors).single()
                    try {
                        socket.outputStream.write(1)
                        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                        check(Os.poll(arrayOf(poll), 3000) > 0)
                        val bytes = ByteArray(2048)
                        val count = Os.read(fd, bytes, 0, bytes.size)
                        assertArrayEquals(probe.request(), bytes.copyOf(count))
                        val reply = bytes.copyOf(count)
                        dns.copyInto(reply, 12); source.copyInto(reply, 16)
                        val port = TunnelProbePacket.read16(reply, 20)
                        TunnelProbePacket.put16(reply, 20, 53)
                        TunnelProbePacket.put16(reply, 22, port)
                        reply[30] = 0x81.toByte()
                        Os.write(fd, reply, 0, reply.size)
                    } finally { Os.close(fd) }
                }
            }
            withContext(Dispatchers.IO) {
                ProbeTunTransport().use { tun ->
                    tun.attach(name)
                    assertNotNull(tun.exchange(probe, timeoutMs = 3000))
                }
            }
            peer.await()
        } finally { server.close() }
    }

    @Test fun silenceDoesNotProduceAFakeLatencyAndCancellationStopsWaiting() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            ProbeTunTransport().use { tun ->
                val packet = TunnelProbePacket(byteArrayOf(10, 66, 67, 2), byteArrayOf(1, 1, 1, 1), 1)
                assertNull(tun.exchange(packet, timeoutMs = 50))
                assertNull(withTimeoutOrNull(50) { tun.exchange(packet, timeoutMs = 4000) })
            }
        }
    }
}
