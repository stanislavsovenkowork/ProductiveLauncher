package dev.stanislav.focusdeck.data.network

/** Offline packet helpers; never route or send traffic. */
internal fun domainMatchesRule(host: String, rule: String): Boolean {
    val name = host.trimEnd('.').lowercase()
    val suffix = rule.trimEnd('.').lowercase()
    return suffix.isNotEmpty() && (name == suffix || name.endsWith(".$suffix"))
}

private fun checksum(bytes: ByteArray): Int {
    var sum = 0L
    var i = 0
    while (i < bytes.size) {
        sum += (bytes[i].toInt() and 255) shl 8
        if (i + 1 < bytes.size) sum += bytes[i + 1].toInt() and 255
        i += 2
    }
    while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
    return sum.inv().toInt() and 0xffff
}

/** Build an IPv4 UDP response containing only NXDOMAIN and the validated question. */
internal fun offlineNxdomain(packet: ByteArray): ByteArray? {
    if (inspectPlainDns(packet) !is DnsObservation.Query) return null
    fun u8(i: Int) = packet[i].toInt() and 255
    fun u16(i: Int) = u8(i) * 256 + u8(i + 1)
    val ihl = (u8(0) and 15) * 4
    if (checksum(packet.copyOfRange(0, ihl)) != 0) return null
    val udpLength = u16(ihl + 4)
    val udp = packet.copyOfRange(ihl, ihl + udpLength)
    val udpChecksum = u16(ihl + 6)
    if (udpChecksum != 0) {
        val pseudo = packet.copyOfRange(12, 20) + byteArrayOf(0, 17, (udpLength shr 8).toByte(), udpLength.toByte()) + udp
        if (checksum(pseudo) != 0) return null
    }
    val dns = ihl + 8
    var qEnd = dns + 12
    while (qEnd < packet.size && u8(qEnd) != 0) qEnd += 1 + u8(qEnd)
    qEnd += 5 // terminator and QTYPE/QCLASS; parser already validated these bounds
    val question = packet.copyOfRange(dns + 12, qEnd)
    val answer = ByteArray(12 + question.size)
    answer[0] = packet[dns]; answer[1] = packet[dns + 1]
    answer[2] = (0x80 or (u8(dns + 2) and 0x01)).toByte() // QR, preserve RD
    answer[3] = 0x83.toByte() // RA and NXDOMAIN
    answer[5] = 1
    question.copyInto(answer, 12)
    val reply = ByteArray(20 + 8 + answer.size)
    fun put16(offset: Int, value: Int) { reply[offset] = (value shr 8).toByte(); reply[offset + 1] = value.toByte() }
    reply[0] = 0x45; put16(2, reply.size); reply[8] = 64; reply[9] = 17
    packet.copyInto(reply, 12, 16, 20); packet.copyInto(reply, 16, 12, 16)
    put16(10, checksum(reply.copyOfRange(0, 20)))
    put16(20, u16(ihl + 2)); put16(22, u16(ihl)); put16(24, 8 + answer.size)
    answer.copyInto(reply, 28)
    val length = 8 + answer.size
    val pseudo = reply.copyOfRange(12, 20) + byteArrayOf(0, 17, (length shr 8).toByte(), length.toByte()) + reply.copyOfRange(20, reply.size)
    val sum = checksum(pseudo)
    put16(26, if (sum == 0) 0xffff else sum)
    return reply
}
