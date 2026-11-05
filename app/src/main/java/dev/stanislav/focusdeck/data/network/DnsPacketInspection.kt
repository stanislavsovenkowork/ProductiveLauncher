package dev.stanislav.focusdeck.data.network

/** Offline-only classification. An Unknown is not an allow/deny decision. */
internal sealed interface DnsObservation {
    data class Query(val domain: String) : DnsObservation
    data object Unknown : DnsObservation
}

/** Minimal IPv4/UDP/port-53 DNS question parser; no network service, interception or logging. */
internal fun inspectPlainDns(packet: ByteArray): DnsObservation {
    fun u8(i: Int) = packet[i].toInt() and 0xff
    fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    if (packet.size < 40 || u8(0) shr 4 != 4) return DnsObservation.Unknown
    val ipLength = (u8(0) and 15) * 4
    if (ipLength < 20 || ipLength + 20 > packet.size || u8(9) != 17) return DnsObservation.Unknown
    val total = u16(2)
    if (total > packet.size || total < ipLength + 20 || u16(6) and 0x3fff != 0) return DnsObservation.Unknown
    if (u16(ipLength + 2) != 53) return DnsObservation.Unknown
    val udpLength = u16(ipLength + 4)
    if (udpLength < 20 || ipLength + udpLength > total) return DnsObservation.Unknown
    val dns = ipLength + 8
    if (u16(dns + 2) and 0xf800 != 0 || u16(dns + 4) != 1) return DnsObservation.Unknown
    val end = ipLength + udpLength
    var offset = dns + 12
    val labels = mutableListOf<String>()
    while (offset < end) {
        val size = u8(offset++)
        if (size == 0) {
            if (labels.isEmpty() || offset + 4 > end) return DnsObservation.Unknown
            return DnsObservation.Query(labels.joinToString(".").lowercase())
        }
        // Reject compression pointers, invalid labels and overlong names rather than guess.
        if (size > 63 || offset + size > end) return DnsObservation.Unknown
        val label = packet.copyOfRange(offset, offset + size).toString(Charsets.US_ASCII)
        if (!label.matches(Regex("[A-Za-z0-9_-]+"))) return DnsObservation.Unknown
        labels += label
        if (labels.sumOf { it.length + 1 } > 253) return DnsObservation.Unknown
        offset += size
    }
    return DnsObservation.Unknown
}
