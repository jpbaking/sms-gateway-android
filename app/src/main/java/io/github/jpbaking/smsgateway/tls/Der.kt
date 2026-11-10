package io.github.jpbaking.smsgateway.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Just enough ASN.1 DER to build a self-signed certificate and to rewrap PKCS#1 / SEC1 private
 * keys as PKCS#8. Android doesn't expose a certificate builder, and pulling in BouncyCastle for
 * this would add megabytes to the APK.
 */
internal object Der {
    const val INTEGER = 0x02
    const val BIT_STRING = 0x03
    const val OCTET_STRING = 0x04
    const val NULL = 0x05
    const val OID = 0x06
    const val UTF8_STRING = 0x0c
    const val UTC_TIME = 0x17
    const val GENERALIZED_TIME = 0x18
    const val SEQUENCE = 0x30
    const val SET = 0x31

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg items: ByteArray): ByteArray = tlv(SEQUENCE, concat(items.toList()))
    fun sequence(items: List<ByteArray>): ByteArray = tlv(SEQUENCE, concat(items))
    fun set(vararg items: ByteArray): ByteArray = tlv(SET, concat(items.toList()))
    fun integer(value: BigInteger): ByteArray = tlv(INTEGER, value.toByteArray())
    fun integer(value: Int): ByteArray = integer(BigInteger.valueOf(value.toLong()))
    fun octetString(bytes: ByteArray): ByteArray = tlv(OCTET_STRING, bytes)
    fun bitString(bytes: ByteArray): ByteArray = tlv(BIT_STRING, byteArrayOf(0) + bytes)
    fun nullValue(): ByteArray = byteArrayOf(NULL.toByte(), 0)
    fun utf8(text: String): ByteArray = tlv(UTF8_STRING, text.toByteArray(Charsets.UTF_8))
    fun boolean(value: Boolean): ByteArray = byteArrayOf(0x01, 0x01, if (value) 0xff.toByte() else 0)

    /** Context-specific constructed tag, e.g. `[0] EXPLICIT`. */
    fun explicit(tagNumber: Int, content: ByteArray): ByteArray = tlv(0xa0 or tagNumber, content)

    /** Context-specific primitive tag, e.g. `[2] IMPLICIT` for a dNSName. */
    fun implicit(tagNumber: Int, content: ByteArray): ByteArray = tlv(0x80 or tagNumber, content)

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            var v = part
            val stack = ArrayDeque<Int>()
            stack.addFirst((v and 0x7f).toInt())
            v = v shr 7
            while (v > 0) {
                stack.addFirst(((v and 0x7f) or 0x80).toInt())
                v = v shr 7
            }
            stack.forEach(out::write)
        }
        return tlv(OID, out.toByteArray())
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
            return
        }
        val bytes = BigInteger.valueOf(length.toLong()).toByteArray().dropWhile { it == 0.toByte() }
        out.write(0x80 or bytes.size)
        bytes.forEach { out.write(it.toInt()) }
    }

    private fun concat(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach(out::write)
        return out.toByteArray()
    }

    /** One parsed element. [content] is the value bytes, [encoded] the whole TLV. */
    class Element(val tag: Int, val content: ByteArray, val encoded: ByteArray) {
        /** The children of a constructed element (SEQUENCE, SET, explicit tags). */
        fun children(): List<Element> {
            val result = mutableListOf<Element>()
            var offset = 0
            while (offset < content.size) {
                val (element, next) = read(content, offset)
                result += element
                offset = next
            }
            return result
        }
    }

    fun parse(bytes: ByteArray): Element = read(bytes, 0).first

    private fun read(bytes: ByteArray, start: Int): Pair<Element, Int> {
        require(start + 2 <= bytes.size) { "Truncated DER" }
        val tag = bytes[start].toInt() and 0xff
        var offset = start + 1
        var length = bytes[offset++].toInt() and 0xff
        if (length and 0x80 != 0) {
            val count = length and 0x7f
            require(count in 1..4 && offset + count <= bytes.size) { "Bad DER length" }
            length = 0
            repeat(count) { length = (length shl 8) or (bytes[offset++].toInt() and 0xff) }
        }
        require(length >= 0 && offset + length <= bytes.size) { "Truncated DER" }
        val content = bytes.copyOfRange(offset, offset + length)
        return Element(tag, content, bytes.copyOfRange(start, offset + length)) to offset + length
    }
}
