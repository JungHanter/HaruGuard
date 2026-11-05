package com.hanter.haruguard.vpn

import org.junit.Assert.*
import org.junit.Test

class YouTubeRoutingTest {
    @Test fun sameCachedAddressStillRequiresTheYoutubeUidForEveryApp() {
        val routing = YouTubeRouting()
        val address = requireNotNull(routing.addressFor("www.youtube.com"))
        val host = routing.hostFor(address)
        assertTrue(YouTubeRouting.mayConnect(host, 10123, 10123))
        assertFalse(YouTubeRouting.mayConnect(host, 10456, 10123))
        assertFalse(YouTubeRouting.mayConnect(host, -1, 10123))
        assertFalse(YouTubeRouting.mayConnect(host, 10123, null))
        assertFalse(YouTubeRouting.mayConnect(null, 10123, 10123))
        assertFalse(YouTubeRouting.mayConnect("youtube.com.example.org", 10123, 10123))
    }

    @Test fun unrelatedDomainsStayOutsideTheInternalRoute() {
        val routing = YouTubeRouting(1)
        listOf("google.com", "accounts.google.com", "notyoutube.com", "youtube.com.example.org").forEach {
            assertNull(routing.addressFor(it))
        }
        assertNotNull(routing.addressFor("youtube.com"))
        assertFalse(YouTubeRouting.contains(0x08080808))
        assertTrue(YouTubeRouting.contains(requireNotNull(routing.addressFor("youtube.com"))))
        assertNull(routing.hostFor(YouTubeRouting.NETWORK + 500))
    }

    @Test fun stableHostMappingsNeverReuseAnAddressForAnotherHost() {
        val routing = YouTubeRouting(2)
        val first = routing.addressFor("YOUTUBE.COM.")
        assertEquals(first, routing.addressFor("youtube.com"))
        val second = routing.addressFor("video.googlevideo.com")
        assertNotEquals(first, second)
        assertNull(routing.addressFor("www.youtube.com"))
        assertEquals("youtube.com", routing.hostFor(requireNotNull(first)))
        assertEquals("video.googlevideo.com", routing.hostFor(requireNotNull(second)))
    }

    @Test fun aReplyIsInternalOnlyAndDoesNotMutateTheQuestion() {
        val routing = YouTubeRouting()
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
        assertTrue(YouTubeRouting.contains(address))
        assertEquals("youtube.com", routing.hostFor(address))
    }

    @Test fun aaaaAndHttpsRecordsCannotExposeAnUnfilteredRealAddress() {
        val routing = YouTubeRouting()
        listOf(28, 64, 65).forEach { type ->
            val query = query("youtube.com", type)
            val reply = routing.reply(query, requireNotNull(DnsQuestion.parse(query)))
            assertEquals(0, readU16(reply, 2) and 15)
            assertEquals(0, readU16(reply, 6))
            assertEquals(query.size, reply.size)
        }
    }

    @Test fun exhaustedInternalAddressesReturnFailureWithoutOverwritingTheOldMapping() {
        val routing = YouTubeRouting(1)
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
