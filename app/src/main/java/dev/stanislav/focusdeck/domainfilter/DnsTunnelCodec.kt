package dev.stanislav.focusdeck.domainfilter

import dev.stanislav.focusdeck.data.network.DnsObservation
import dev.stanislav.focusdeck.data.network.inspectPlainDns
import dev.stanislav.focusdeck.data.network.offlineNxdomain

internal data class TunnelQuestion(val domain: String, val packet: ByteArray, val raw: ByteArray)

internal object DnsTunnelCodec {
    val SERVER = byteArrayOf(10,111,0,2)
    fun question(packet: ByteArray): TunnelQuestion? {
        val query = inspectPlainDns(packet) as? DnsObservation.Query ?: return null
        if (!packet.copyOfRange(16,20).contentEquals(SERVER) || offlineNxdomain(packet) == null) return null
        val offset = (packet[0].toInt() and 15)*4
        val length = u16(packet,offset+4)-8
        if (length !in 12..1232) return null
        return TunnelQuestion(query.domain,packet.copyOf(),packet.copyOfRange(offset+8,offset+8+length))
    }
    fun blocked(query: TunnelQuestion): ByteArray = requireNotNull(offlineNxdomain(query.packet))
    fun response(query: TunnelQuestion, answer: ByteArray): ByteArray? {
        if (answer.size !in 12..1232 || answer[0] != query.raw[0] || answer[1] != query.raw[1] || answer[2].toInt() and 0x80 == 0) return null
        // Echoed question must match; never send an unrelated resolver answer to the client.
        val end = questionEnd(query.raw) ?: return null
        if (answer.size < end || !answer.copyOfRange(12,end).contentEquals(query.raw.copyOfRange(12,end))) return null
        val original = query.packet
        val ihl = (original[0].toInt() and 15)*4
        val reply = ByteArray(28+answer.size)
        reply[0]=0x45; put16(reply,2,reply.size); reply[8]=64; reply[9]=17
        original.copyInto(reply,12,16,20); original.copyInto(reply,16,12,16)
        put16(reply,10,checksum(reply.copyOfRange(0,20)))
        put16(reply,20,u16(original,ihl+2)); put16(reply,22,u16(original,ihl)); put16(reply,24,8+answer.size)
        answer.copyInto(reply,28)
        val length=8+answer.size
        val pseudo=reply.copyOfRange(12,20)+byteArrayOf(0,17,(length shr 8).toByte(),length.toByte())+reply.copyOfRange(20,reply.size)
        val sum=checksum(pseudo); put16(reply,26,if(sum==0) 65535 else sum)
        return reply
    }
    private fun questionEnd(raw: ByteArray): Int? {
        var offset=12
        while(offset<raw.size) {
            val length=raw[offset++].toInt() and 255
            if(length==0)return (offset+4).takeIf { it<=raw.size }
            if(length>63 || offset+length>raw.size)return null
            offset+=length
        }
        return null
    }
    private fun u16(data:ByteArray,index:Int)=(data[index].toInt() and 255)*256+(data[index+1].toInt() and 255)
    private fun put16(data:ByteArray,index:Int,value:Int){data[index]=(value shr 8).toByte();data[index+1]=value.toByte()}
    private fun checksum(bytes:ByteArray):Int {
        var sum=0L
        var index=0
        while(index<bytes.size){sum+=(bytes[index].toInt() and 255) shl 8;if(index+1<bytes.size)sum+=bytes[index+1].toInt() and 255;index+=2}
        while(sum shr 16!=0L)sum=(sum and 65535)+(sum shr 16)
        return sum.inv().toInt() and 65535
    }
}
