package com.hanter.haruguard.vpn

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class ConnectionPacketsTest {
    private val key = ConnectionKey(0x0acb0001, 45000, YouTubeRouting.NETWORK + 1, 443)

    @Test fun tcpResponsesHaveCorrectEndpointsOptionsPayloadAndChecksums() {
        val payload = byteArrayOf(0, -1, 7, 1, 5)
        val bytes = ConnectionPackets.tcp(key, 0xffffffffL, 5, TcpPacket.SYN or TcpPacket.ACK,
            1234, payload, 1200)
        val ip = requireNotNull(IpPacket.parse(bytes, bytes.size))
        val tcp = requireNotNull(TcpPacket.parse(bytes, 20, bytes.size - 20))
        assertEquals(key.destIp, ip.sourceIp)
        assertEquals(key.sourceIp, ip.destIp)
        assertEquals(key.destPort, tcp.sourcePort)
        assertEquals(key.sourcePort, tcp.destPort)
        assertEquals(0xffffffffL, tcp.sequence)
        assertEquals(1200, tcp.maximumSegmentSize)
        assertArrayEquals(payload, bytes.copyOfRange(20 + tcp.headerLength, bytes.size))
        assertEquals(0, internetChecksum(bytes, 0, 20))
        assertEquals(0, transportChecksum(bytes))
    }

    @Test fun udpResponsesKeepBinaryPayloadAndValidChecksum() {
        val data = ByteArray(1231).also { Random(29).nextBytes(it) }
        val bytes = ConnectionPackets.udp(key, data)
        assertEquals(data.size + 8, readU16(bytes, 24))
        assertArrayEquals(data, bytes.copyOfRange(28, bytes.size))
        assertEquals(0, transportChecksum(bytes))
    }

    @Test fun blockedUdpReturnsAnIcmpRejectionQuotingTheOriginalPacket() {
        val bytes = ConnectionPackets.udp(key, byteArrayOf(1, 2, 3))
        val ip = requireNotNull(IpPacket.parse(bytes, bytes.size))
        val reply = ConnectionPackets.unreachable(bytes, ip)
        assertEquals(1, reply[9].toInt())
        assertEquals(3, reply[20].toInt())
        assertEquals(3, reply[21].toInt())
        assertArrayEquals(bytes.copyOf(28), reply.copyOfRange(28, reply.size))
        assertEquals(0, internetChecksum(reply, 0, 20))
        assertEquals(0, internetChecksum(reply, 20, reply.size - 20))
    }

    @Test fun malformedLengthsOptionsAndFragmentsAreRejectedWithoutOutOfBoundsAccess() {
        val random = Random(10)
        repeat(5000) {
            val bytes = ByteArray(random.nextInt(100)).also(random::nextBytes)
            IpPacket.parse(bytes, random.nextInt(150) - 10)
            TcpPacket.parse(bytes, random.nextInt(150) - 10, random.nextInt(150) - 10)
        }
        val bytes = ConnectionPackets.tcp(key, 1, 1, TcpPacket.ACK, 100)
        assertNull(IpPacket.parse(bytes.copyOf().apply { this[7] = 1 }, bytes.size))
        assertNull(TcpPacket.parse(bytes.copyOf().apply { this[32] = 0 }, 20, 20))
        val options = ConnectionPackets.tcp(key, 1, 1, TcpPacket.SYN, 100, mss = 1200)
        assertNull(TcpPacket.parse(options.copyOf().apply { this[41] = 0 }, 20, 24))
    }

    @Test fun tcpSequenceComparisonHandlesWraparound() {
        assertEquals(2L, seqAdvance(0xffffffffL, 3))
        assertTrue(seqLessThan(0xfffffffeL, 2))
        assertTrue(seqLessOrEqual(0xffffffffL, 0))
        assertFalse(seqLessThan(2, 0xfffffffeL))
    }

    private fun transportChecksum(bytes: ByteArray): Int {
        val pseudo = bytes.copyOfRange(12, 20) +
            byteArrayOf(0, bytes[9], ((bytes.size - 20) ushr 8).toByte(), (bytes.size - 20).toByte()) +
            bytes.copyOfRange(20, bytes.size)
        return internetChecksum(pseudo, 0, pseudo.size)
    }
}
