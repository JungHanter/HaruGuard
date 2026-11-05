package com.hanter.haruguard.vpn

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketsTest {
    @Test fun blocksYouTubePagesVideoCdnAndApi() {
        listOf("youtube.com", "m.youtube.com", "youtu.be", "www.youtube-nocookie.com",
            "rr1---sn.example.googlevideo.com", "i.ytimg.com", "youtubei.googleapis.com",
            "YOUTUBE.COM.").forEach { assertTrue(it, (FilterPolicy(FilterSettings()).decision(it) is DomainDecision.OfficialApps)) }
    }

    @Test fun keepsUnrelatedGoogleServicesAndDeceptiveSuffixesWorking() {
        listOf("google.com", "accounts.google.com", "googleapis.com", "photos.googleapis.com",
            "notyoutube.com", "youtube.com.example.org", "googlevideo.com.evil.test")
            .forEach { assertFalse(it, (FilterPolicy(FilterSettings()).decision(it) is DomainDecision.OfficialApps)) }
    }

    @Test fun parsesAAndAaaaQuestions() {
        assertEquals("www.youtube.com", DnsQuestion.parse(query("www.youtube.com", 1))?.host)
        assertEquals("example.org", DnsQuestion.parse(query("example.org", 28))?.host)
    }

    @Test fun rejectsTruncatedLabelsCompressedNamesAndMultipleQuestions() {
        val valid = query("youtube.com")
        assertNull(DnsQuestion.parse(valid.copyOf(valid.size - 1)))
        assertNull(DnsQuestion.parse(valid.copyOf().apply { this[12] = 63 }))
        assertNull(DnsQuestion.parse(valid.copyOf().apply { this[12] = 0xc0.toByte() }))
        assertNull(DnsQuestion.parse(valid.copyOf().apply { this[5] = 2 }))
        assertNull(DnsQuestion.parse(valid.copyOf().apply { this[2] = 0x80.toByte() }))
    }

    @Test fun nxdomainPreservesQuestionAndIdWithoutStaleAdditionalData() {
        val original = query("youtube.com").apply { this[11] = 1 }
        val extended = original + ByteArray(11)
        val question = requireNotNull(DnsQuestion.parse(extended))
        val reply = DnsReply.error(extended, question, DnsReply.NXDOMAIN)
        assertEquals(original.size, reply.size)
        assertEquals(0x1234, word(reply, 0))
        assertEquals(0x8183, word(reply, 2))
        assertEquals(1, word(reply, 4))
        assertEquals(0, word(reply, 6))
        assertEquals(0, word(reply, 8))
        assertEquals(0, word(reply, 10))
        assertArrayEquals(original.copyOfRange(12, original.size), reply.copyOfRange(12, reply.size))
    }

    @Test fun upstreamReplyRestoresClientTransactionIdAndRejectsNonResponses() {
        val request = query("example.org")
        val upstream = request.copyOf().apply { this[0] = 0x55; this[2] = 0x81.toByte() }
        val reply = requireNotNull(DnsReply.upstream(request, upstream))
        assertEquals(0x1234, word(reply, 0))
        assertEquals(0x5534, word(upstream, 0)) // Do not mutate the resolver buffer.
        assertNull(DnsReply.upstream(request, request))
        assertNull(DnsReply.upstream(request, ByteArray(11)))
    }

    @Test fun parsesOnlyUnfragmentedUdpToVirtualDns() {
        val bytes = packet(query("example.org"))
        assertNotNull(DnsUdpPacket.parse(bytes, bytes.size))
        assertNull(DnsUdpPacket.parse(bytes.copyOf().apply { this[9] = 6 }, bytes.size))
        assertNull(DnsUdpPacket.parse(bytes.copyOf().apply { this[7] = 1 }, bytes.size))
        assertNull(DnsUdpPacket.parse(bytes.copyOf().apply { this[19] = 3 }, bytes.size))
        assertNull(DnsUdpPacket.parse(bytes.copyOf().apply { this[23] = 54 }, bytes.size))
        assertNull(DnsUdpPacket.parse(bytes, bytes.size - 1))
    }

    @Test fun udpReplyHasReversedEndpointsLengthAndValidChecksums() {
        val questionBytes = query("www.youtube.com")
        val incoming = packet(questionBytes)
        val parsed = requireNotNull(DnsUdpPacket.parse(incoming, incoming.size))
        val reply = parsed.response(DnsReply.error(questionBytes, requireNotNull(DnsQuestion.parse(questionBytes)), 3))
        assertEquals(reply.size, word(reply, 2))
        assertArrayEquals(incoming.copyOfRange(16, 20), reply.copyOfRange(12, 16))
        assertArrayEquals(incoming.copyOfRange(12, 16), reply.copyOfRange(16, 20))
        assertEquals(53, word(reply, 20))
        assertEquals(50_000, word(reply, 22))
        assertEquals(reply.size - 20, word(reply, 24))
        assertEquals(0xffff, foldedSum(reply.copyOfRange(0, 20)))
        val pseudo = reply.copyOfRange(12, 20) + byteArrayOf(0, 17, reply[24], reply[25]) + reply.copyOfRange(20, reply.size)
        assertEquals(0xffff, foldedSum(pseudo))
    }

    @Test fun malformedUntrustedPacketsNeverReadOutsideTheirBuffer() {
        val random = Random(29)
        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(256)).also(random::nextBytes)
            DnsQuestion.parse(bytes)
            DnsUdpPacket.parse(bytes, bytes.size)
        }
    }

    private fun query(host: String, type: Int = 1): ByteArray {
        val name = host.split('.').flatMap { label -> listOf(label.length.toByte()) + label.toByteArray().toList() }
        return byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            name.toByteArray() + byteArrayOf(0, 0, type.toByte(), 0, 1)
    }

    private fun packet(dns: ByteArray): ByteArray = ByteArray(28 + dns.size).apply {
        this[0] = 0x45
        this[2] = (size ushr 8).toByte(); this[3] = size.toByte()
        this[8] = 64; this[9] = 17
        byteArrayOf(10, 203.toByte(), 0, 1, 10, 203.toByte(), 0, 2).copyInto(this, 12)
        this[20] = 0xc3.toByte(); this[21] = 0x50 // Client port 50000.
        this[23] = 53
        this[24] = ((dns.size + 8) ushr 8).toByte(); this[25] = (dns.size + 8).toByte()
        dns.copyInto(this, 28)
    }

    private fun word(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toUByte().toInt() * 256) + bytes[offset + 1].toUByte().toInt()

    private fun foldedSum(bytes: ByteArray): Int {
        val padded = if (bytes.size % 2 == 0) bytes else bytes + byteArrayOf(0)
        var sum = padded.indices.step(2).sumOf { word(padded, it) }
        while (sum > 0xffff) sum = (sum and 0xffff) + (sum ushr 16)
        return sum
    }
}
