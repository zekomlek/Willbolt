package app.willbolt

import app.willbolt.vpn.DnsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsPacketTest {

    private fun query(name: String, type: Int = 1, id: Int = 0x1234, flags: Int = 0x0100): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                (id shr 8).toByte(), id.toByte(), (flags shr 8).toByte(), flags.toByte(),
                0, 1, 0, 0, 0, 0, 0, 0,
            ),
        )
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    @Test
    fun parsesNameTypeAndClass() {
        val q = DnsPacket.parseQuestion(query("WWW.Example.com", 28))
        assertNotNull(q)
        assertEquals("www.example.com", q!!.name)
        assertEquals(28, q.type)
        assertEquals(1, q.qclass)
    }

    @Test
    fun nxdomainKeepsIdAndQuestionAndSetsFlags() {
        val msg = query("blocked.example.com")
        val q = DnsPacket.parseQuestion(msg)!!
        val r = DnsPacket.nxdomain(msg, q)
        assertEquals(0x12, r[0].toInt() and 0xFF)
        assertEquals(0x34, r[1].toInt() and 0xFF)
        assertEquals(0x81, r[2].toInt() and 0xFF) // QR=1, RD copied
        assertEquals(0x83, r[3].toInt() and 0xFF) // RA=1, RCODE=3 (NXDOMAIN)
        assertEquals(1, DnsPacket.u16(r, 4))      // QDCOUNT
        assertEquals(0, DnsPacket.u16(r, 6))      // ANCOUNT
        assertEquals(q.end, r.size)
        assertEquals("blocked.example.com", DnsPacket.parseQuestion(r)!!.name)
    }

    @Test
    fun noDataIsNoErrorWithoutAnswers() {
        val msg = query("example.com", 65)
        val q = DnsPacket.parseQuestion(msg)!!
        val r = DnsPacket.noData(msg, q)
        assertEquals(0x80, r[3].toInt() and 0xFF)
        assertEquals(0, DnsPacket.u16(r, 6))
    }

    @Test
    fun answerAppendsARecordWithPointerToQuestionName() {
        val msg = query("www.google.com")
        val q = DnsPacket.parseQuestion(msg)!!
        val r = DnsPacket.answer(msg, q, 1, 300, listOf(byteArrayOf(1, 2, 3, 4)))
        assertEquals(1, DnsPacket.u16(r, 6))
        val rr = q.end
        assertEquals(0xC0, r[rr].toInt() and 0xFF)
        assertEquals(0x0C, r[rr + 1].toInt() and 0xFF)
        assertEquals(1, DnsPacket.u16(r, rr + 2))      // type A
        assertEquals(1, DnsPacket.u16(r, rr + 4))      // class IN
        assertEquals(300, DnsPacket.u16(r, rr + 8))    // TTL (low 16 bits)
        assertEquals(4, DnsPacket.u16(r, rr + 10))     // RDLENGTH
        assertEquals(listOf<Byte>(1, 2, 3, 4), r.slice(rr + 12 until rr + 16))
        assertEquals(rr + 16, r.size)
    }

    @Test
    fun truncatedSetsTcBit() {
        val msg = query("example.com")
        val q = DnsPacket.parseQuestion(msg)!!
        val r = DnsPacket.truncated(msg, q)
        assertEquals(0x02, r[2].toInt() and 0x02)
        assertEquals(0, DnsPacket.u16(r, 6))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(DnsPacket.parseQuestion(ByteArray(5)))
        val compressed = query("example.com").also { it[12] = 0xC0.toByte() }
        assertNull(DnsPacket.parseQuestion(compressed))
        val cut = query("example.com").copyOf(20)
        assertNull(DnsPacket.parseQuestion(cut))
    }
}
