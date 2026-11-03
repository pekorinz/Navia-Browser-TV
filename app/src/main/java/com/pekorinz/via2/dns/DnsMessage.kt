package com.pekorinz.via2.dns

/**
 * Mensajes DNS crudos (RFC 1035) escritos a mano.
 *
 * No se usa ninguna libreria externa: se arma el paquete byte a byte y se
 * parsea la respuesta saltando los punteros de compresion. Solo interesan los
 * registros A / AAAA / CNAME del primer bloque de respuestas.
 */
internal object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28

    private const val CLASS_IN = 1

    /** Arma una consulta recursiva con EDNS0 desactivado para que sea un paquete minimo. */
    fun buildQuery(host: String, qType: Int, qId: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(64)
        out.write((qId ushr 8) and 0xFF)
        out.write(qId and 0xFF)
        out.write(0x01) // RD = recursion desired
        out.write(0x00)
        out.write(0x00) // QDCOUNT = 1
        out.write(0x01)
        out.write(0x00) // ANCOUNT = 0
        out.write(0x00)
        out.write(0x00) // NSCOUNT = 0
        out.write(0x00)
        out.write(0x00) // ARCOUNT = 0

        for (label in host.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.size > 63) return ByteArray(0)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)

        out.write((qType ushr 8) and 0xFF)
        out.write(qType and 0xFF)
        out.write(0x00)
        out.write(CLASS_IN)

        return out.toByteArray()
    }

    class Answer(val addresses: List<String>, val cnames: List<String>, val rcode: Int)

    /**
     * Lee la respuesta. Devuelve las IPv4/IPv6 y los CNAME para poder seguir la
     * cadena sin pedir todo otra vez.
     */
    fun parseResponse(buf: ByteArray): Answer {
        if (buf.size < 12) return Answer(emptyList(), emptyList(), RCODE_FORMERR)
        val rcode = buf[3].toInt() and 0x0F
        val qdCount = readU16(buf, 4)
        val anCount = readU16(buf, 6)

        var pos = 12
        // Salta la seccion de preguntas.
        repeat(qdCount) {
            val skipped = skipName(buf, pos) ?: return Answer(emptyList(), emptyList(), rcode)
            pos = skipped + 4
        }

        val ips = ArrayList<String>(4)
        val cnames = ArrayList<String>(2)

        repeat(anCount) {
            if (pos >= buf.size) return@repeat
            val nameEnd = skipName(buf, pos) ?: return Answer(ips, cnames, rcode)
            pos = nameEnd
            if (pos + 10 > buf.size) return Answer(ips, cnames, rcode)
            val type = readU16(buf, pos)
            val rdLength = readU16(buf, pos + 8)
            pos += 10
            if (pos + rdLength > buf.size) return Answer(ips, cnames, rcode)

            when (type) {
                TYPE_A -> if (rdLength == 4) ips += formatIpv4(buf, pos) else Unit
                TYPE_AAAA -> if (rdLength == 16) ips += formatIpv6(buf, pos) else Unit
                TYPE_CNAME -> nameAt(buf, pos)?.let { cnames += it }
            }
            pos += rdLength
        }
        return Answer(ips, cnames, rcode)
    }

    const val RCODE_OK = 0
    const val RCODE_NXDOMAIN = 3
    const val RCODE_FORMERR = 1

    /** Recorre las etiquetas (con punteros) y devuelve la posicion tras el nombre. */
    private fun skipName(buf: ByteArray, start: Int): Int? {
        var pos = start
        var guard = 0
        while (pos < buf.size) {
            val len = buf[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return pos + 2
                len > 63 -> return null
                else -> pos += 1 + len
            }
            if (++guard > 128) return null
        }
        return null
    }

    private fun nameAt(buf: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var pos = start
        var guard = 0
        var jumps = 0
        while (pos < buf.size) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) return sb.toString()
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= buf.size || ++jumps > 8) return null
                pos = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                continue
            }
            if (len > 63 || pos + 1 + len > buf.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(buf, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
            if (++guard > 128) return null
        }
        return null
    }

    private fun readU16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun formatIpv4(buf: ByteArray, at: Int): String =
        "${buf[at].toInt() and 0xFF}.${buf[at + 1].toInt() and 0xFF}." +
            "${buf[at + 2].toInt() and 0xFF}.${buf[at + 3].toInt() and 0xFF}"

    /** IPv6 comprimido sin forma de agrupar ceros: Android acepta cualquier forma valida. */
    private fun formatIpv6(buf: ByteArray, at: Int): String {
        val groups = IntArray(8) {
            ((buf[at + it * 2].toInt() and 0xFF) shl 8) or (buf[at + it * 2 + 1].toInt() and 0xFF)
        }
        // Comprime la secuencia de ceros mas larga (>= 2 grupos).
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                val s = i
                while (i < 8 && groups[i] == 0) i++
                if (i - s > bestLen) {
                    bestLen = i - s
                    bestStart = s
                }
            } else i++
        }
        if (bestLen < 2) return groups.joinToString(":") { it.toString(16) }

        val head = groups.sliceArray(0 until bestStart).joinToString(":") { it.toString(16) }
        val tailStart = bestStart + bestLen
        val tail = if (tailStart < 8)
            groups.sliceArray(tailStart until 8).joinToString(":") { it.toString(16) }
        else ""
        return "$head::$tail"
    }
}
