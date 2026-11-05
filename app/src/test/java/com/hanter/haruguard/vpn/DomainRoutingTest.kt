package com.hanter.haruguard.vpn

import org.junit.Assert.*
import org.junit.Test

class DomainRoutingTest {
    @Test fun sameCachedAddressStillRequiresTheServiceUidForEveryApp() {
        val routing = DomainRouting()
        val host = routing.hostFor(requireNotNull(routing.addressFor("www.youtube.com")))
        val policy = FilterPolicy(FilterSettings())
        assertTrue(policy.mayConnect(host, { 10123 }, { 10123 }))
        assertFalse(policy.mayConnect(host, { 10456 }, { 10123 }))
        assertFalse(policy.mayConnect(host, { -1 }, { 10123 }))
        assertFalse(policy.mayConnect(host, { 10123 }, { null }))
        assertFalse(policy.mayConnect(null, { 10123 }, { 10123 }))
    }

    @Test fun differentServicesUseOnePoolWithoutConfusingHosts() {
        val routing = DomainRouting(2)
        val youtube = requireNotNull(routing.addressFor("youtube.com"))
        val tiktok = requireNotNull(routing.addressFor("tiktok.com"))
        assertNotEquals(youtube, tiktok)
        assertEquals("tiktok.com", routing.hostFor(tiktok))
        assertNull(routing.addressFor("https://youtube.com"))
        assertFalse(DomainRouting.contains(0x08080808))
        assertTrue(DomainRouting.contains(youtube))
        assertNull(routing.hostFor(DomainRouting.NETWORK + 500))
    }

    @Test fun stableHostMappingsNeverReuseAnAddressForAnotherHost() {
        val routing = DomainRouting(2)
        val first = routing.addressFor("YOUTUBE.COM.")
        assertEquals(first, routing.addressFor("youtube.com"))
        val second = routing.addressFor("video.googlevideo.com")
        assertNotEquals(first, second)
        assertNull(routing.addressFor("www.youtube.com"))
        assertEquals("youtube.com", routing.hostFor(requireNotNull(first)))
        assertEquals("video.googlevideo.com", routing.hostFor(requireNotNull(second)))
    }

    @Test fun aReplyIsInternalOnlyAndDoesNotMutateTheQuestion() {
        val routing = DomainRouting()
        val query = query("youtube.com", 1)
        val original = query.copyOf()
        val question = requireNotNull(DnsQuestion.parse(query))
        val reply = routing.reply(query, question)
        assertArrayEquals(original, query)
        assertEquals(0x1234, readU16(reply, 0))
        assertEquals(1, readU16(reply, 6))
        assertEquals(0, readU16(reply, 8))
        assertEquals(0, readU16(reply, 10))
        assertEquals(0, getInt(reply, question.endOffset + 6))
        val address = getInt(reply, question.endOffset + 12)
        assertTrue(DomainRouting.contains(address))
        assertEquals("youtube.com", routing.hostFor(address))
    }

    @Test fun aaaaAndHttpsRecordsCannotExposeAnUnfilteredRealAddress() {
        val routing = DomainRouting()
        listOf(28, 64, 65).forEach { type ->
            val query = query("youtube.com", type)
            val reply = routing.reply(query, requireNotNull(DnsQuestion.parse(query)))
            assertEquals(0, readU16(reply, 2) and 15)
            assertEquals(0, readU16(reply, 6))
            assertEquals(query.size, reply.size)
        }
    }

    @Test fun exhaustedInternalAddressesReturnFailureWithoutOverwritingTheOldMapping() {
        val routing = DomainRouting(1)
        val first = requireNotNull(routing.addressFor("youtube.com"))
        val query = query("www.youtube.com", 1)
        val reply = routing.reply(query, requireNotNull(DnsQuestion.parse(query)))
        assertEquals(DnsReply.SERVFAIL, readU16(reply, 2) and 15)
        assertEquals("youtube.com", routing.hostFor(first))
    }

    private fun query(host: String, type: Int): ByteArray {
        val labels = host.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }
        return byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            labels.toByteArray() + byteArrayOf(0, 0, type.toByte(), 0, 1)
    }
}
