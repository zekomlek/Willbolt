package app.willbolt.vpn

/** IPv4 / UDP / TCP packet building with correct checksums. Pure functions, unit-tested. */
object Packets {

    /** Internet checksum (RFC 1071) of data[off, off+len) added to [initial]; returns 0 when the data already contains a valid checksum. */
    fun checksum(data: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        var left = len
        while (left > 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            left -= 2
        }
        if (left > 0) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Sum of the IPv4 pseudo-header used by the UDP and TCP checksums. */
    fun pseudoSum(src: ByteArray, dst: ByteArray, proto: Int, len: Int): Long =
        (DnsPacket.u16(src, 0) + DnsPacket.u16(src, 2) + DnsPacket.u16(dst, 0) + DnsPacket.u16(dst, 2) + proto + len).toLong()

    /** Wraps [payload] in a 20-byte IPv4 header (no options, "don't fragment"). */
    fun ipv4(src: ByteArray, dst: ByteArray, proto: Int, ident: Int, payload: ByteArray): ByteArray {
        val total = 20 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        DnsPacket.put16(p, 2, total)
        DnsPacket.put16(p, 4, ident and 0xFFFF)
        DnsPacket.put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = proto.toByte()
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        DnsPacket.put16(p, 10, checksum(p, 0, 20, 0))
        System.arraycopy(payload, 0, p, 20, payload.size)
        return p
    }

    /** A UDP datagram [serverIp]:[serverPort] -> [clientIp]:[clientPort] carrying [payload], as a full IPv4 packet. */
    fun udpReply(
        serverIp: ByteArray,
        clientIp: ByteArray,
        serverPort: Int,
        clientPort: Int,
        ident: Int,
        payload: ByteArray,
    ): ByteArray {
        val udp = ByteArray(8 + payload.size)
        DnsPacket.put16(udp, 0, serverPort)
        DnsPacket.put16(udp, 2, clientPort)
        DnsPacket.put16(udp, 4, udp.size)
        System.arraycopy(payload, 0, udp, 8, payload.size)
        var c = checksum(udp, 0, udp.size, pseudoSum(serverIp, clientIp, 17, udp.size))
        if (c == 0) c = 0xFFFF
        DnsPacket.put16(udp, 6, c)
        return ipv4(serverIp, clientIp, 17, ident, udp)
    }

    /**
     * The RST+ACK that refuses a TCP connection attempt. [b] holds an IPv4 packet of [n] bytes with a [ihl]-byte
     * header. Returns null unless it is a plain SYN.
     */
    fun tcpResetFor(b: ByteArray, n: Int, ihl: Int, ident: Int): ByteArray? {
        if (n < ihl + 20) return null
        val flags = b[ihl + 13].toInt() and 0xFF
        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        if (!syn || ack) return null

        val clientIp = b.copyOfRange(12, 16)
        val serverIp = b.copyOfRange(16, 20)
        val seq = ((b[ihl + 4].toLong() and 0xFF) shl 24) or ((b[ihl + 5].toLong() and 0xFF) shl 16) or
            ((b[ihl + 6].toLong() and 0xFF) shl 8) or (b[ihl + 7].toLong() and 0xFF)
        val ackNum = (seq + 1) and 0xFFFFFFFFL

        val tcp = ByteArray(20)
        DnsPacket.put16(tcp, 0, DnsPacket.u16(b, ihl + 2)) // our source port = their destination port
        DnsPacket.put16(tcp, 2, DnsPacket.u16(b, ihl))     // our destination port = their source port
        tcp[8] = (ackNum shr 24).toByte()
        tcp[9] = (ackNum shr 16).toByte()
        tcp[10] = (ackNum shr 8).toByte()
        tcp[11] = ackNum.toByte()
        tcp[12] = (5 shl 4).toByte() // 20-byte header
        tcp[13] = 0x14               // RST + ACK
        DnsPacket.put16(tcp, 16, checksum(tcp, 0, tcp.size, pseudoSum(serverIp, clientIp, 6, tcp.size)))
        return ipv4(serverIp, clientIp, 6, ident, tcp)
    }
}
