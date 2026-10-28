package com.csqtt.client

import org.junit.Assert.*
import org.junit.Test

class TunnelProbePacketTest {
    private val source = byteArrayOf(10, 66, 67, 2)
    private val dns = byteArrayOf(1, 1, 1, 1)
    private val probe = TunnelProbePacket(source, dns, 12345)
    private fun answer(): ByteArray = probe.request().apply {
        dns.copyInto(this, 12); source.copyInto(this, 16)
        val port = TunnelProbePacket.read16(this, 20)
        TunnelProbePacket.put16(this, 20, 53); TunnelProbePacket.put16(this, 22, port)
        this[30] = 0x81.toByte()
    }

    @Test fun makesValidIpv4UdpDnsRequestAndRecognizesItsAnswer() {
        val packet = probe.request()
        assertEquals(packet.size, TunnelProbePacket.read16(packet, 2))
        assertEquals(packet.size - 20, TunnelProbePacket.read16(packet, 24))
        var sum = (0 until 20 step 2).sumOf { TunnelProbePacket.read16(packet, it) }
        while(sum > 65535) sum = (sum and 65535) + (sum ushr 16)
        assertEquals(65535, sum)
        assertFalse(probe.isReply(packet))
        assertTrue(probe.isReply(answer()))
    }
    @Test fun rejectsWrongAddressesPortsTransactionsQuestionsAndFragments() {
        for (offset in listOf(12, 16, 20, 22, 28, 40)) {
            assertFalse("offset=$offset", probe.isReply(answer().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }))
        }
        assertFalse(probe.isReply(answer().apply { this[6] = 0x20 }))
        assertFalse(probe.isReply(answer().apply { this[30] = 0x09 }))
        assertFalse(TunnelProbePacket(source, dns, 12346).isReply(answer()))
    }
    @Test fun rejectsTruncatedOrMalformedPacketsWithoutCrashing() {
        val answer = answer()
        for (size in 0 until answer.size) assertFalse(probe.isReply(answer.copyOf(size)))
        assertFalse(probe.isReply(answer.copyOf().apply { this[0] = 0x4f }))
        assertFalse(probe.isReply(answer.copyOf().apply { this[24] = 0x7f }))
    }
}
