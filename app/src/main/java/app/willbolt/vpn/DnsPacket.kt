package app.willbolt.vpn

import java.io.ByteArrayOutputStream

/** Just enough DNS wire format to read a question and build NXDOMAIN / A / AAAA / empty answers. */
object DnsPacket {
    class Question(val name: String, val type: Int, val qclass: Int, /** offset just after the question section */ val end: Int)

    fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun parseQuestion(msg: ByteArray): Question? {
        if (msg.size < 17) return null
        if (u16(msg, 4) < 1) return null // QDCOUNT
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= msg.size) return null
            val len = msg[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if ((len and 0xC0) != 0) return null // compression is not used in questions
            if (pos + 1 + len > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(msg, pos + 1, len, Charsets.ISO_8859_1))
            pos += 1 + len
        }
        if (pos + 4 > msg.size) return null
        return Question(sb.toString().lowercase(), u16(msg, pos), u16(msg, pos + 2), pos + 4)
    }

    /** A standard recursive query for [name] of record [type] (1 = A, 28 = AAAA). */
    fun query(name: String, type: Int, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.ISO_8859_1)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    /** The raw A (4-byte) and AAAA (16-byte) record data in the answer section of [msg], following any CNAMEs. */
    fun parseAddresses(msg: ByteArray): List<ByteArray> {
        if (msg.size < 12) return emptyList()
        val questions = u16(msg, 4)
        val answers = u16(msg, 6)
        var pos = 12
        repeat(questions) {
            pos = skipName(msg, pos) ?: return emptyList()
            pos += 4
        }
        val out = ArrayList<ByteArray>()
        repeat(answers) {
            pos = skipName(msg, pos) ?: return out
            if (pos + 10 > msg.size) return out
            val type = u16(msg, pos)
            val rdLen = u16(msg, pos + 8)
            val rdStart = pos + 10
            if (rdStart + rdLen > msg.size) return out
            if ((type == 1 && rdLen == 4) || (type == 28 && rdLen == 16)) {
                out.add(msg.copyOfRange(rdStart, rdStart + rdLen))
            }
            pos = rdStart + rdLen
        }
        return out
    }

    /** Offset just past the (possibly compressed) name that starts at [start], or null if malformed. */
    private fun skipName(msg: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= msg.size) return null
            val len = msg[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                (len and 0xC0) == 0xC0 -> return if (pos + 2 <= msg.size) pos + 2 else null
                (len and 0xC0) != 0 -> return null
                else -> pos += 1 + len
            }
        }
    }

    private fun header(query: ByteArray, q: Question, rcode: Int, answers: Int): ByteArray {
        val h = ByteArray(q.end)
        System.arraycopy(query, 0, h, 0, q.end)
        val rd = query[2].toInt() and 0x01
        h[2] = (0x80 or rd).toByte()      // QR=1, opcode 0, RD copied
        h[3] = (0x80 or rcode).toByte()   // RA=1, RCODE
        put16(h, 4, 1)                    // QDCOUNT
        put16(h, 6, answers)              // ANCOUNT
        put16(h, 8, 0)                    // NSCOUNT
        put16(h, 10, 0)                   // ARCOUNT
        return h
    }

    /** "That name does not exist." */
    fun nxdomain(query: ByteArray, q: Question): ByteArray = header(query, q, 3, 0)

    /** "The name exists but has no records of this type." */
    fun noData(query: ByteArray, q: Question): ByteArray = header(query, q, 0, 0)

    /** Answers [q] with [rdata] records of [type] (1 = A, 28 = AAAA). */
    fun answer(query: ByteArray, q: Question, type: Int, ttl: Int, rdata: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(query, q, 0, rdata.size))
        for (r in rdata) {
            val rr = ByteArray(12 + r.size)
            rr[0] = 0xC0.toByte() // pointer to the name in the question
            rr[1] = 0x0C
            put16(rr, 2, type)
            put16(rr, 4, 1) // class IN
            rr[6] = (ttl shr 24).toByte()
            rr[7] = (ttl shr 16).toByte()
            rr[8] = (ttl shr 8).toByte()
            rr[9] = ttl.toByte()
            put16(rr, 10, r.size)
            System.arraycopy(r, 0, rr, 12, r.size)
            out.write(rr)
        }
        return out.toByteArray()
    }

    /** Header + question with the TC bit set, for responses too big for the tunnel. */
    fun truncated(query: ByteArray, q: Question): ByteArray {
        val h = header(query, q, 0, 0)
        h[2] = (h[2].toInt() or 0x02).toByte()
        return h
    }
}
