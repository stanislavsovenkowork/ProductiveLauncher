package dev.stanislav.focusdeck.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsReplyTest {
    private fun checksum(bytes: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < bytes.size) {
            sum += (bytes[i].toInt() and 255) shl 8
            if (i + 1 < bytes.size) sum += bytes[i + 1].toInt() and 255
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 65535) + (sum shr 16)
        return sum.inv().toInt() and 65535
    }
    private fun query(): ByteArray {
        val dns = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 121, 111, 117, 116, 117, 98, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45; p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte(); p[8] = 64; p[9] = 17
        p[12] = 10; p[15] = 1; p[16] = 10; p[19] = 2
        p[20] = 0x30; p[21] = 0x39; p[23] = 53
        p[24] = ((dns.size + 8) shr 8).toByte(); p[25] = (dns.size + 8).toByte()
        dns.copyInto(p, 28)
        val sum = checksum(p.copyOfRange(0, 20)); p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
        return p
    }
    @Test fun exactDomainAndSubdomainsMatchWithoutSuffixConfusion() {
        assertTrue(domainMatchesRule("youtube.com", "youtube.com"))
        assertTrue(domainMatchesRule("m.youtube.com.", "youtube.com"))
        assertFalse(domainMatchesRule("notyoutube.com", "youtube.com"))
        assertFalse(domainMatchesRule("youtube.com.example", "youtube.com"))
    }
    @Test fun buildsChecksummedDnsNxdomainWithReversedEndpoints() {
        val original = query()
        val response = offlineNxdomain(original)
        assertNotNull(response)
        val p = response!!
        assertEquals(0, checksum(p.copyOfRange(0, 20)))
        assertEquals(original.slice(16..19), p.slice(12..15))
        assertEquals(original.slice(12..15), p.slice(16..19))
        assertEquals(0x12, p[28].toInt() and 255)
        assertEquals(0x34, p[29].toInt() and 255)
        assertEquals(3, p[31].toInt() and 15)
        val length = p.size - 20
        val pseudo = p.copyOfRange(12, 20) + byteArrayOf(0, 17, (length shr 8).toByte(), length.toByte()) + p.copyOfRange(20, p.size)
        assertEquals(0, checksum(pseudo))
    }
    @Test fun malformedPacketOrChecksumProducesNoReply() {
        assertNull(offlineNxdomain(query().apply { this[12] = 99 }))
        assertNull(offlineNxdomain(query().apply { this[26] = 1 }))
        assertNull(offlineNxdomain(query().copyOf(29)))
        assertNull(offlineNxdomain(query().apply { this[0] = 0x60 }))
    }
}
