package dev.stanislav.focusdeck.domainfilter

import org.junit.Assert.*
import org.junit.Test

class DnsTunnelCodecTest {
    private fun checksum(bytes:ByteArray):Int {
        var sum=0L;var i=0
        while(i<bytes.size){sum+=(bytes[i].toInt() and 255) shl 8;if(i+1<bytes.size)sum+=bytes[i+1].toInt() and 255;i+=2}
        while(sum shr 16!=0L)sum=(sum and 65535)+(sum shr 16)
        return sum.inv().toInt() and 65535
    }
    private fun packet():ByteArray {
        val dns=byteArrayOf(0x12,0x34,1,0,0,1,0,0,0,0,0,0,7,98,108,111,99,107,101,100,7,101,120,97,109,112,108,101,0,0,1,0,1)
        val p=ByteArray(28+dns.size)
        p[0]=0x45;p[2]=(p.size shr 8).toByte();p[3]=p.size.toByte();p[8]=64;p[9]=17
        p[12]=10;p[13]=111;p[15]=1;DnsTunnelCodec.SERVER.copyInto(p,16)
        p[20]=0x30;p[21]=0x39;p[23]=53;p[24]=((dns.size+8) shr 8).toByte();p[25]=(dns.size+8).toByte()
        dns.copyInto(p,28)
        val sum=checksum(p.copyOfRange(0,20));p[10]=(sum shr 8).toByte();p[11]=sum.toByte()
        return p
    }
    @Test fun blockedReplyAndForwardedReplyKeepValidatedEndpointsAndChecksums() {
        val query=requireNotNull(DnsTunnelCodec.question(packet()))
        assertEquals("blocked.example",query.domain)
        val blocked=DnsTunnelCodec.blocked(query)
        assertEquals(3,blocked[31].toInt() and 15)
        assertEquals(0,checksum(blocked.copyOfRange(0,20)))
        val raw=blocked.copyOfRange(28,blocked.size)
        val forwarded=requireNotNull(DnsTunnelCodec.response(query,raw))
        assertArrayEquals(blocked,forwarded)
        assertNull(DnsTunnelCodec.response(query,raw.copyOf().apply{this[0]=99}))
        assertNull(DnsTunnelCodec.response(query,raw.copyOf().apply{this[12]=99}))
    }
    @Test fun syntheticTunPacketRespectsWindowAndRelief() {
        val id = "d6457bfa-352c-4da6-a271-f1f9c862c055"
        val clock = dev.stanislav.focusdeck.ui.restrictions.ProtectionClock(1_000_000, 10_000, 1)
        val window = dev.stanislav.focusdeck.ui.restrictions.ProtectionWindow(id, "study", emptySet(),
            startWall = clock.wall, endWall = clock.wall + 600_000, startElapsed = clock.elapsed,
            endElapsed = clock.elapsed + 600_000, boot = 1, policyVersion = 2)
        val policy = DomainPolicy(id, setOf("blocked.example"), allowedPackages = setOf("qa.browser"), consentVpnReplacementRisk = true)
        val parsed = requireNotNull(DnsTunnelCodec.question(packet()))
        fun fakeTunAction(w: dev.stanislav.focusdeck.ui.restrictions.ProtectionWindow?): String =
            if (!domainWindowActive(w, id, policy, clock)) "stop" else if (domainWindowBlocks(w, id, policy, clock, parsed.domain)) "block" else "forward"
        assertEquals("block", fakeTunAction(window))
        assertEquals("forward", fakeTunAction(requireNotNull(dev.stanislav.focusdeck.ui.restrictions.grantRelief(window, clock))))
        assertEquals("stop", fakeTunAction(null))
    }
    @Test fun noGuessForOtherDestinationMalformedEncryptedOrFragmentedTraffic() {
        assertNull(DnsTunnelCodec.question(packet().apply{this[16]=1}))
        assertNull(DnsTunnelCodec.question(packet().apply{this[12]=99}))
        assertNull(DnsTunnelCodec.question(packet().apply{this[6]=0x20}))
        assertNull(DnsTunnelCodec.question(packet().apply{this[0]=0x60}))
        assertNull(DnsTunnelCodec.question(byteArrayOf(0x16,3,3)))
        assertNull(DnsTunnelCodec.question(packet().apply{this[23]=0xbb.toByte()}))
    }
}
