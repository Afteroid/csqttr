package com.csqtt.client

/** A DNS exchange injected into the isolated client's TUN FD, with no direct network fallback. */
internal class TunnelProbePacket(private val source: ByteArray, private val dns: ByteArray, private val id: Int) {
    init { require(source.size == 4 && dns.size == 4) }
    private val port = 49152 + (id and 0x3fff)
    private val question = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)

    fun request(): ByteArray {
        val size = 40 + question.size
        val bytes = ByteArray(size)
        bytes[0] = 0x45
        put16(bytes, 2, size)
        put16(bytes, 4, id)
        bytes[8] = 64
        bytes[9] = 17
        source.copyInto(bytes, 12)
        dns.copyInto(bytes, 16)
        put16(bytes, 10, checksum(bytes, 20))
        put16(bytes, 20, port)
        put16(bytes, 22, 53)
        put16(bytes, 24, size - 20)
        put16(bytes, 28, id)
        bytes[30] = 1 // Recursion desired.
        bytes[33] = 1 // One question.
        question.copyInto(bytes, 40)
        return bytes
    }

    fun isReply(bytes: ByteArray): Boolean {
        if (bytes.size < 40 || bytes[0].toInt() ushr 4 != 4 || bytes[9] != 17.toByte()) return false
        val header = (bytes[0].toInt() and 15) * 4
        if (header < 20 || bytes.size < header + 20 || read16(bytes, 2) > bytes.size) return false
        if (read16(bytes, 6) and 0x3fff != 0) return false
        if (!bytes.copyOfRange(12, 16).contentEquals(dns) || !bytes.copyOfRange(16, 20).contentEquals(source)) return false
        if (read16(bytes, header) != 53 || read16(bytes, header + 2) != port) return false
        val length = read16(bytes, header + 4)
        if (length < 20 || header + length != read16(bytes, 2)) return false
        val payload = header + 8
        // A matching DNS answer proves return traffic; NXDOMAIN/SERVFAIL are still network replies.
        return read16(bytes, payload) == (id and 0xffff) &&
            bytes[payload + 2].toInt() and 0xf8 == 0x80 &&
            read16(bytes, payload + 4) == 1 &&
            bytes.size >= payload + 12 + question.size &&
            bytes.copyOfRange(payload + 12, payload + 12 + question.size).contentEquals(question)
    }

    companion object {
        internal fun read16(b: ByteArray, at: Int) = ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)
        internal fun put16(b: ByteArray, at: Int, n: Int) { b[at] = (n ushr 8).toByte(); b[at + 1] = n.toByte() }
        private fun checksum(b: ByteArray, size: Int): Int {
            var sum = (0 until size step 2).sumOf { read16(b, it) }
            while (sum > 65535) sum = (sum and 65535) + (sum ushr 16)
            return sum.inv() and 65535
        }
    }
}
