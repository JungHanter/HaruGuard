package com.hanter.haruguard.vpn

/** IPv4 연결 구분과 TCP 순서 번호. 패킷 형식 참고: https://www.rfc-editor.org/rfc/rfc9293 */
internal data class ConnectionKey(val sourceIp: Int, val sourcePort: Int, val destIp: Int, val destPort: Int)

internal data class IpPacket(val headerLength: Int, val length: Int, val protocol: Int,
                             val sourceIp: Int, val destIp: Int) {
    companion object {
        fun parse(bytes: ByteArray, length: Int): IpPacket? {
            if (length !in 20..bytes.size || (bytes[0].toInt() and 0xf0) != 0x40) return null
            val header = (bytes[0].toInt() and 15) * 4
            val total = readU16(bytes, 2)
            if (header < 20 || header > total || total > length || readU16(bytes, 6) and 0x3fff != 0) return null
            return IpPacket(header, total, bytes[9].toInt() and 255, getInt(bytes, 12), getInt(bytes, 16))
        }
    }
}

internal data class TcpPacket(val sourcePort: Int, val destPort: Int, val sequence: Long,
    val acknowledgment: Long, val headerLength: Int, val flags: Int, val window: Int,
    val maximumSegmentSize: Int? = null) {
    val isSyn get() = flags and SYN != 0
    val isAck get() = flags and ACK != 0
    val isFin get() = flags and FIN != 0
    val isRst get() = flags and RST != 0

    companion object {
        const val FIN = 1
        const val SYN = 2
        const val RST = 4
        const val PSH = 8
        const val ACK = 16
        fun parse(bytes: ByteArray, offset: Int, length: Int): TcpPacket? {
            if (offset < 0 || length < 20 || offset > bytes.size - length) return null
            val header = ((bytes[offset + 12].toInt() and 255) ushr 4) * 4
            if (header !in 20..length) return null
            var mss: Int? = null
            var option = offset + 20
            while (option < offset + header) {
                val kind = bytes[option].toInt() and 255
                if (kind == 0) break
                if (kind == 1) { option++; continue }
                if (option + 1 >= offset + header) return null
                val size = bytes[option + 1].toInt() and 255
                if (size < 2 || option + size > offset + header) return null
                if (kind == 2 && size == 4) mss = readU16(bytes, option + 2).takeIf { it > 0 }
                option += size
            }
            return TcpPacket(readU16(bytes, offset), readU16(bytes, offset + 2),
                getInt(bytes, offset + 4).toLong() and 0xffffffffL,
                getInt(bytes, offset + 8).toLong() and 0xffffffffL, header,
                bytes[offset + 13].toInt() and 255, readU16(bytes, offset + 14), mss)
        }
    }
}

internal fun seqAdvance(sequence: Long, length: Int): Long = (sequence + length) and 0xffffffffL
internal fun seqLessThan(first: Long, second: Long): Boolean = (first - second).toInt() < 0
internal fun seqLessOrEqual(first: Long, second: Long): Boolean = (first - second).toInt() <= 0
internal fun getInt(bytes: ByteArray, offset: Int): Int =
    (readU16(bytes, offset) shl 16) or readU16(bytes, offset + 2)
internal fun putInt(bytes: ByteArray, offset: Int, value: Int) {
    writeU16(bytes, offset, value ushr 16)
    writeU16(bytes, offset + 2, value)
}
internal fun Int.addressBytes(): ByteArray = ByteArray(4).also { putInt(it, 0, this) }

/** TUN에 반환할 응답. 외부 네트워크로는 이 내부 IP 패킷을 전송하지 않는다. */
internal object ConnectionPackets {
    fun unreachable(original: ByteArray, ip: IpPacket): ByteArray {
        val quoted = original.copyOf(minOf(ip.length, ip.headerLength + 8))
        val result = header(ConnectionKey(ip.sourceIp, 0, ip.destIp, 0), 1, 8 + quoted.size)
        result[20] = 3; result[21] = 3
        quoted.copyInto(result, 28)
        writeU16(result, 22, internetChecksum(result, 20, result.size - 20))
        return result
    }

    fun tcp(key: ConnectionKey, sequence: Long, acknowledgment: Long, flags: Int, window: Int,
            payload: ByteArray = byteArrayOf(), mss: Int = 0): ByteArray {
        val options = if (mss > 0) 4 else 0
        val result = header(key, 6, 20 + options + payload.size)
        writeU16(result, 20, key.destPort)
        writeU16(result, 22, key.sourcePort)
        putInt(result, 24, sequence.toInt())
        putInt(result, 28, acknowledgment.toInt())
        result[32] = ((5 + options / 4) shl 4).toByte()
        result[33] = flags.toByte()
        writeU16(result, 34, window)
        if (mss > 0) {
            result[40] = 2; result[41] = 4
            writeU16(result, 42, mss)
        }
        payload.copyInto(result, 40 + options)
        writeU16(result, 36, transportChecksum(result, 6))
        return result
    }

    fun udp(key: ConnectionKey, payload: ByteArray): ByteArray {
        require(payload.size <= 65_507)
        val result = header(key, 17, 8 + payload.size)
        writeU16(result, 20, key.destPort)
        writeU16(result, 22, key.sourcePort)
        writeU16(result, 24, 8 + payload.size)
        payload.copyInto(result, 28)
        val checksum = transportChecksum(result, 17)
        writeU16(result, 26, if (checksum == 0) 0xffff else checksum)
        return result
    }

    private fun header(key: ConnectionKey, protocol: Int, length: Int): ByteArray = ByteArray(20 + length).also {
        it[0] = 0x45
        writeU16(it, 2, it.size)
        writeU16(it, 6, 0x4000)
        it[8] = 64; it[9] = protocol.toByte()
        putInt(it, 12, key.destIp); putInt(it, 16, key.sourceIp)
        writeU16(it, 10, internetChecksum(it, 0, 20))
    }

    private fun transportChecksum(packet: ByteArray, protocol: Int): Int {
        val pseudo = ByteArray(12 + packet.size - 20)
        packet.copyInto(pseudo, 0, 12, 20)
        pseudo[9] = protocol.toByte()
        writeU16(pseudo, 10, packet.size - 20)
        packet.copyInto(pseudo, 12, 20)
        return internetChecksum(pseudo, 0, pseudo.size)
    }
}
