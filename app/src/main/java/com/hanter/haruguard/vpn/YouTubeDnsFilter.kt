package com.hanter.haruguard.vpn

import java.util.Locale

/** P0 DNS filter. It deliberately does not inspect HTTPS or collect browsing history. */
object YouTubeDnsFilter {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val VPN_ADDRESS = "10.203.0.1"
    const val DNS_ADDRESS = "10.203.0.2"

    private val blockedDomains = setOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com",
        "youtubei.googleapis.com", "youtube.googleapis.com", "youtubeembeddedplayer.googleapis.com",
    )

    fun isBlocked(host: String): Boolean {
        val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
        return blockedDomains.any { normalized == it || normalized.endsWith(".$it") }
    }
}

/** A single, uncompressed standard DNS question. Malformed/multi-question queries are rejected. */
data class DnsQuestion(val host: String, val endOffset: Int) {
    companion object {
        fun parse(message: ByteArray): DnsQuestion? {
            if (message.size < 12 || readU16(message, 4) != 1) return null
            val flags = readU16(message, 2)
            if (flags and 0xf800 != 0) return null // Responses and non-standard opcodes.
            var offset = 12
            var nameLength = 0
            val labels = mutableListOf<String>()
            while (offset < message.size) {
                val length = message[offset++].toInt() and 0xff
                if (length == 0) {
                    if (offset + 4 > message.size || labels.isEmpty()) return null
                    return DnsQuestion(labels.joinToString("."), offset + 4)
                }
                // Compressed query names are not emitted by normal system DNS clients.
                if (length > 63 || offset + length > message.size) return null
                nameLength += length + 1
                if (nameLength > 254) return null
                val label = message.copyOfRange(offset, offset + length)
                if (label.any { (it.toInt() and 0xff) !in 33..126 || it.toInt() == 46 }) return null
                labels += label.toString(Charsets.US_ASCII)
                offset += length
            }
            return null
        }
    }
}

object DnsReply {
    const val SERVFAIL = 2
    const val NXDOMAIN = 3

    fun error(query: ByteArray, question: DnsQuestion, rcode: Int): ByteArray =
        query.copyOf(question.endOffset).also { reply ->
            // Preserve RD/CD, set QR/RA, and clear answer, authority and additional counts.
            writeU16(reply, 2, 0x8080 or (readU16(query, 2) and 0x0110) or rcode)
            for (offset in 6..10 step 2) writeU16(reply, offset, 0)
        }

    fun upstream(query: ByteArray, answer: ByteArray): ByteArray? {
        if (answer.size < 12 || answer.size > 65_507 || readU16(answer, 2) and 0x8000 == 0) return null
        // System resolvers may rewrite transaction IDs; clients require the original ID.
        return answer.copyOf().also {
            it[0] = query[0]
            it[1] = query[1]
        }
    }
}

/** Only the virtual DNS /32 is routed into the TUN; ordinary traffic uses the physical network. */
data class DnsUdpPacket(
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val query: ByteArray,
) {
    fun response(dnsMessage: ByteArray): ByteArray {
        require(dnsMessage.size <= 65_507)
        val packet = ByteArray(28 + dnsMessage.size)
        packet[0] = 0x45
        writeU16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 17
        destinationAddress.copyInto(packet, 12)
        sourceAddress.copyInto(packet, 16)
        writeU16(packet, 10, internetChecksum(packet, 0, 20))
        writeU16(packet, 20, 53)
        writeU16(packet, 22, sourcePort)
        writeU16(packet, 24, 8 + dnsMessage.size)
        dnsMessage.copyInto(packet, 28)
        val pseudoHeader = ByteArray(12 + 8 + dnsMessage.size)
        packet.copyInto(pseudoHeader, 0, 12, 20)
        pseudoHeader[9] = 17
        writeU16(pseudoHeader, 10, 8 + dnsMessage.size)
        packet.copyInto(pseudoHeader, 12, 20)
        val udpChecksum = internetChecksum(pseudoHeader, 0, pseudoHeader.size)
        writeU16(packet, 26, if (udpChecksum == 0) 0xffff else udpChecksum)
        return packet
    }

    companion object {
        private val virtualDns = byteArrayOf(10, 203.toByte(), 0, 2)

        fun parse(packet: ByteArray, length: Int): DnsUdpPacket? {
            if (length !in 28..packet.size || packet[0].toInt() ushr 4 != 4) return null
            val headerSize = (packet[0].toInt() and 0x0f) * 4
            if (headerSize < 20 || headerSize + 8 > length || packet[9].toInt() != 17) return null
            val totalSize = readU16(packet, 2)
            if (totalSize < headerSize + 8 || totalSize > length) return null
            if (readU16(packet, 6) and 0x3fff != 0) return null // No IP fragment reassembly in P0.
            if (!packet.copyOfRange(16, 20).contentEquals(virtualDns)) return null
            val udpSize = readU16(packet, headerSize + 4)
            if (udpSize < 20 || headerSize + udpSize != totalSize) return null
            if (readU16(packet, headerSize + 2) != 53) return null
            return DnsUdpPacket(
                packet.copyOfRange(12, 16),
                packet.copyOfRange(16, 20),
                readU16(packet, headerSize),
                packet.copyOfRange(headerSize + 8, totalSize),
            )
        }
    }
}

internal fun readU16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

internal fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

internal fun internetChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    var cursor = offset
    val end = offset + length
    while (cursor + 1 < end) {
        sum += readU16(bytes, cursor)
        cursor += 2
    }
    if (cursor < end) sum += (bytes[cursor].toInt() and 0xff) shl 8
    while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
    return sum.inv() and 0xffff
}
