package app.willbolt

import app.willbolt.vpn.DnsPacket
import app.willbolt.vpn.Packets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class PacketsTest {
    private val server = byteArrayOf(10, -1, -1, 2) // 10.255.255.2
    private val client = byteArrayOf(10, -1, -1, 1) // 10.255.255.1

    @Test
    fun checksumKnownVector() {
        // Example IPv4 header from RFC 1071 style walkthroughs: 4500 0073 0000 4000 4011 [b861] c0a8 0001 c0a8 00c7
        val hdr = byteArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00,
            0xc0.toByte(), 0xa8.toByte(), 0x00, 0x01, 0xc0.toByte(), 0xa8.toByte(), 0x00, 0xc7.toByte(),
        )
        assertEquals(0xB861, Packets.checksum(hdr, 0, 20, 0))
    }

    @Test
    fun udpReplyIsAValidIpv4UdpPacket() {
        val payload = ByteArray(33) { (it * 7).toByte() } // odd length exercises the padding rule
        val p = Packets.udpReply(server, client, 53, 40000, 0x1234, payload)

        assertEquals(0x45, p[0].toInt() and 0xFF)
        assertEquals(20 + 8 + 33, DnsPacket.u16(p, 2))
        assertEquals(0x1234, DnsPacket.u16(p, 4))
        assertEquals(17, p[9].toInt() and 0xFF)
        assertArrayEquals(server, p.copyOfRange(12, 16))
        assertArrayEquals(client, p.copyOfRange(16, 20))
        assertEquals("IP header must sum to zero", 0, Packets.checksum(p, 0, 20, 0))

        val udp = p.copyOfRange(20, p.size)
        assertEquals(53, DnsPacket.u16(udp, 0))
        assertEquals(40000, DnsPacket.u16(udp, 2))
        assertEquals(8 + 33, DnsPacket.u16(udp, 4))
        assertEquals(
            "UDP segment + pseudo-header must sum to zero", 0,
            Packets.checksum(udp, 0, udp.size, Packets.pseudoSum(server, client, 17, udp.size)),
        )
        assertArrayEquals(payload, udp.copyOfRange(8, udp.size))
    }

    @Test
    fun udpReplyWithEvenPayloadAlsoValid() {
        val p = Packets.udpReply(server, client, 53, 1234, 1, ByteArray(40) { it.toByte() })
        val udp = p.copyOfRange(20, p.size)
        assertEquals(0, Packets.checksum(p, 0, 20, 0))
        assertEquals(0, Packets.checksum(udp, 0, udp.size, Packets.pseudoSum(server, client, 17, udp.size)))
    }

    private fun syn(seq: Long, flags: Int): ByteArray {
        val tcp = ByteArray(20)
        DnsPacket.put16(tcp, 0, 50000)
        DnsPacket.put16(tcp, 2, 853)
        tcp[4] = (seq shr 24).toByte(); tcp[5] = (seq shr 16).toByte(); tcp[6] = (seq shr 8).toByte(); tcp[7] = seq.toByte()
        tcp[12] = (5 shl 4).toByte()
        tcp[13] = flags.toByte()
        return Packets.ipv4(client, server, 6, 9, tcp)
    }

    @Test
    fun tcpSynIsAnsweredWithValidReset() {
        val req = syn(1000, 0x02)
        val r = Packets.tcpResetFor(req, req.size, 20, 7)
        assertNotNull(r)
        r!!
        assertEquals(6, r[9].toInt() and 0xFF)
        assertArrayEquals(server, r.copyOfRange(12, 16))
        assertArrayEquals(client, r.copyOfRange(16, 20))
        assertEquals(0, Packets.checksum(r, 0, 20, 0))
        val tcp = r.copyOfRange(20, r.size)
        assertEquals(853, DnsPacket.u16(tcp, 0))
        assertEquals(50000, DnsPacket.u16(tcp, 2))
        assertEquals(0x14, tcp[13].toInt() and 0xFF) // RST + ACK
        assertEquals(1001, DnsPacket.u16(tcp, 10))   // ack = seq + 1 (low 16 bits)
        assertEquals(0, Packets.checksum(tcp, 0, tcp.size, Packets.pseudoSum(server, client, 6, tcp.size)))
    }

    @Test
    fun nonSynSegmentsAreIgnored() {
        val ack = syn(5, 0x10)
        assertNull(Packets.tcpResetFor(ack, ack.size, 20, 1))
        val synAck = syn(5, 0x12)
        assertNull(Packets.tcpResetFor(synAck, synAck.size, 20, 1))
        assertNull(Packets.tcpResetFor(ByteArray(10), 10, 20, 1))
    }

    @Test
    fun parseAddressesFollowsCnameAndCompression() {
        // Response for www.example.com: CNAME -> host.example.net, then A 93.184.216.34 and AAAA ::1
        val q = DnsPacket.query("www.example.com", 1, 0x4242)
        val out = ByteArrayOutputStream()
        out.write(q, 0, 2)                                                             // transaction id
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 3, 0, 0, 0, 0)) // QR+RD+RA, 1 question, 3 answers
        out.write(q, 12, q.size - 12)                                                  // question section
        // answer 1: CNAME (type 5) with pointer name, rdata = "host.example.net"
        val cname = byteArrayOf(4, 'h'.code.toByte(), 'o'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 7) +
            "example".toByteArray() + byteArrayOf(3) + "net".toByteArray() + byteArrayOf(0)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 5, 0, 1, 0, 0, 0, 60, 0, cname.size.toByte()))
        out.write(cname)
        // answer 2: A record, name pointer to offset 12
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184.toByte(), 216.toByte(), 34))
        // answer 3: AAAA ::1
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16))
        out.write(ByteArray(15)); out.write(1)

        val addrs = DnsPacket.parseAddresses(out.toByteArray())
        assertEquals(2, addrs.size)
        assertArrayEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), addrs[0])
        assertEquals(16, addrs[1].size)
        assertEquals(1, addrs[1][15].toInt())
    }

    @Test
    fun parseAddressesToleratesTruncatedAndEmptyInput() {
        assertEquals(0, DnsPacket.parseAddresses(ByteArray(3)).size)
        val q = DnsPacket.query("example.com", 1, 1)
        val claimsAnswer = q.copyOf().also { it[7] = 1 } // header says 1 answer but none follow
        assertEquals(0, DnsPacket.parseAddresses(claimsAnswer).size)
    }

    @Test
    fun builtQueryParsesBack() {
        val q = DnsPacket.parseQuestion(DnsPacket.query("Forcesafesearch.Google.com", 28, 7))!!
        assertEquals("forcesafesearch.google.com", q.name)
        assertEquals(28, q.type)
    }
}
