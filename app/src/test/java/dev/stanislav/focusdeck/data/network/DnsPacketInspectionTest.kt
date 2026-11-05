package dev.stanislav.focusdeck.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsPacketInspectionTest {
    private fun packet(): ByteArray {
        val dns = byteArrayOf(0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 121, 111, 117, 116, 117, 98, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45; p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte(); p[9] = 17
        p[22] = 0; p[23] = 53; p[24] = ((dns.size + 8) shr 8).toByte(); p[25] = (dns.size + 8).toByte()
        dns.copyInto(p, 28)
        return p
    }
    @Test fun classifiesOnlyValidPlainUdpDns() {
        assertEquals(DnsObservation.Query("youtube.com"), inspectPlainDns(packet()))
        assertEquals(DnsObservation.Unknown, inspectPlainDns(packet().copyOf(29)))
        assertEquals(DnsObservation.Unknown, inspectPlainDns(packet().apply { this[20 + 3] = 0x50 })) // not port 53
        assertEquals(DnsObservation.Unknown, inspectPlainDns(packet().apply { this[6] = 0x20 })) // fragmented
        assertEquals(DnsObservation.Unknown, inspectPlainDns(packet().apply { this[40] = 0xc0.toByte() })) // compression
        assertEquals(DnsObservation.Unknown, inspectPlainDns(packet().apply { this[0] = 0x60 })) // IPv6
        assertEquals(DnsObservation.Unknown, inspectPlainDns(packet().apply { this[30] = 0x09 })) // non-query opcode
    }
}
