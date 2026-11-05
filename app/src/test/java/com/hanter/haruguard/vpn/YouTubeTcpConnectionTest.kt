package com.hanter.haruguard.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class YouTubeTcpConnectionTest {
    @Test(timeout = 10000) fun streamLargerThanTheTcpWindowKeepsItsBytesAndOrder() = Harness().use { h ->
        h.open(); h.ack(window = 8192)
        val expected = ByteArray(256 * 1024).also { java.util.Random(29).nextBytes(it) }
        val outputDone = CountDownLatch(1)
        thread(isDaemon = true) {
            try { h.remote.getOutputStream().write(expected) } finally { outputDone.countDown() }
        }
        val actual = java.io.ByteArrayOutputStream()
        while (actual.size() < expected.size) {
            val frame = h.take { it.data.isNotEmpty() }
            actual.write(frame.data)
            h.ack(frame.end, window = 8192)
        }
        assertArrayEquals(expected, actual.toByteArray())
        assertTrue(outputDone.await(3, TimeUnit.SECONDS))
    }

    @Test(timeout = 8000) fun stoppingWhileConnectingClosesTheSocketAndCannotPublishALateHandshake() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val socket = Socket()
        val registered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val output = LinkedBlockingQueue<ByteArray>()
        val finished = AtomicInteger()
        val connection = YouTubeTcpConnection(
            ConnectionKey(1, 45000, YouTubeRouting.NETWORK + 1, 443), scope,
            connect = { register ->
                check(register(socket))
                registered.countDown()
                try { release.await(3, TimeUnit.SECONDS) } finally { completed.countDown() }
                socket
            }, write = { output.add(it) }, now = { 1000 }, onFinished = { finished.incrementAndGet() })
        try {
            connection.open(TcpPacket(45000, 443, 1, 0, 20, TcpPacket.SYN, 65535))
            assertTrue(registered.await(3, TimeUnit.SECONDS))
            connection.finish()
            assertTrue(socket.isClosed)
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertNull(output.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(1, finished.get())
        } finally { release.countDown(); connection.finish(); scope.cancel() }
    }

    @Test(timeout = 8000) fun realSocketTransfersBothDirectionsAndPreservesHalfClose() = Harness().use { h ->
        h.open()
        h.ack()
        h.clientData("request".toByteArray())
        assertArrayEquals("request".toByteArray(), h.remoteRead(7))
        h.remote.getOutputStream().write("response".toByteArray())
        val data = h.take { it.data.isNotEmpty() }
        assertArrayEquals("response".toByteArray(), data.data)
        h.ack(data.end)
        h.clientFin()
        assertEquals(-1, h.remote.getInputStream().read())
        h.remote.shutdownOutput()
        val fin = h.take { it.header.isFin }
        h.ack(fin.end)
        assertEquals(1, h.finished.get())
    }

    @Test(timeout = 8000) fun duplicateOverlapAndOutOfOrderDataDoNotReachTheRemoteTwice() = Harness().use { h ->
        h.open(); h.ack()
        val base = h.clientNext
        h.packet(base + 3, "xyz".toByteArray())
        assertEquals(base, h.take { it.header.flags == TcpPacket.ACK }.header.acknowledgment)
        h.packet(base, "abc".toByteArray())
        h.take { it.header.acknowledgment == base + 3 }
        h.packet(base, "abc".toByteArray())
        h.packet(base + 1, "bcde".toByteArray())
        h.take { it.header.acknowledgment == base + 5 }
        assertArrayEquals("abcde".toByteArray(), h.remoteRead(5))
        assertEquals(0, h.remote.getInputStream().available())
    }

    @Test(timeout = 8000) fun zeroAndSmallReceiveWindowsAreRespected() = Harness().use { h ->
        h.open(); h.ack(window = 0)
        h.remote.getOutputStream().write("hello".toByteArray())
        assertNull(h.frames.poll(150, TimeUnit.MILLISECONDS))
        h.ack(window = 2)
        val first = h.take { it.data.isNotEmpty() }
        assertArrayEquals("he".toByteArray(), first.data)
        assertNull(h.frames.poll(100, TimeUnit.MILLISECONDS))
        h.ack(first.end, window = 3)
        assertArrayEquals("llo".toByteArray(), h.take { it.data.isNotEmpty() }.data)
    }

    @Test(timeout = 8000) fun eofAlsoWaitsForTheClientsZeroWindowToReopen() = Harness().use { h ->
        h.open(); h.ack(window = 0)
        h.remote.shutdownOutput()
        assertNull(h.frames.poll(150, TimeUnit.MILLISECONDS))
        h.ack(window = 1)
        assertTrue(h.take { it.header.isFin }.header.isFin)
    }

    @Test(timeout = 8000) fun ackBeyondSentDataCannotReleaseTheWindow() = Harness().use { h ->
        h.open(); h.ack(window = 2)
        h.remote.getOutputStream().write("data".toByteArray())
        val first = h.take { it.data.isNotEmpty() }
        h.ack(seqAdvance(first.end, 100), window = 65000)
        assertNull(h.frames.poll(150, TimeUnit.MILLISECONDS))
        h.ack(first.end, window = 2)
        assertArrayEquals("ta".toByteArray(), h.take { it.data.isNotEmpty() }.data)
    }

    @Test(timeout = 8000) fun synDataPartialAckAndFinCanAllBeRetransmitted() = Harness().use { h ->
        val syn = h.open()
        h.time.addAndGet(1000); h.connection.tick()
        assertEquals(syn.header.sequence, h.take { it.header.isSyn }.header.sequence)
        h.ack()
        h.remote.getOutputStream().write("abc".toByteArray())
        val first = h.take { it.data.isNotEmpty() }
        h.time.addAndGet(1000); h.connection.tick()
        assertArrayEquals(first.data, h.take { it.data.isNotEmpty() }.data)
        h.ack(seqAdvance(first.header.sequence, 1))
        h.time.addAndGet(1000); h.connection.tick()
        val partial = h.take { it.data.isNotEmpty() }
        assertEquals(seqAdvance(first.header.sequence, 1), partial.header.sequence)
        assertArrayEquals("bc".toByteArray(), partial.data)
        h.ack(first.end)
        h.remote.shutdownOutput()
        val fin = h.take { it.header.isFin }
        h.time.addAndGet(1000); h.connection.tick()
        assertEquals(fin.header.sequence, h.take { it.header.isFin }.header.sequence)
    }

    @Test(timeout = 8000) fun unansweredHandshakeExpiresAndClosesTheActualSocketOnce() = Harness().use { h ->
        h.open()
        repeat(11) { h.time.addAndGet(1000); h.connection.tick() }
        assertEquals(1, h.finished.get())
        assertEquals(-1, h.remote.getInputStream().read())
        h.connection.finish()
        assertEquals(1, h.finished.get())
    }

    @Test(timeout = 8000) fun clientResetCancelsBlockedNetworkReads() = Harness().use { h ->
        h.open(); h.ack()
        h.connection.packet(TcpPacket(45000, 443, h.clientNext, h.serverNext, 20, TcpPacket.RST, 0), byteArrayOf())
        assertEquals(1, h.finished.get())
        assertEquals(-1, h.remote.getInputStream().read())
    }

    @Test(timeout = 8000) fun requestAndResponseSequencesCanWrapAround() =
        Harness(clientInitial = 0xfffffffeL, serverInitial = 0xfffffffeL).use { h ->
            h.open(); h.ack()
            h.clientData("abc".toByteArray())
            assertEquals(2L, h.take { it.header.flags == TcpPacket.ACK }.header.acknowledgment)
            assertArrayEquals("abc".toByteArray(), h.remoteRead(3))
            h.remote.getOutputStream().write("xyz".toByteArray())
            val data = h.take { it.data.isNotEmpty() }
            assertEquals(2L, data.end)
            h.ack(data.end)
        }

    @Test(timeout = 8000) fun remoteDataHonorsTheClientsMss() = Harness().use { h ->
        h.open(mss = 3); h.ack()
        h.remote.getOutputStream().write("abcdefgh".toByteArray())
        val result = ArrayList<Byte>()
        while (result.size < 8) {
            val data = h.take { it.data.isNotEmpty() }
            assertTrue(data.data.size <= 3)
            result.addAll(data.data.toList())
            h.ack(data.end)
        }
        assertArrayEquals("abcdefgh".toByteArray(), result.toByteArray())
    }

    @Test(timeout = 8000) fun payloadBeforeTheValidHandshakeIsNotForwarded() = Harness().use { h ->
        h.open()
        h.connection.packet(TcpPacket(45000, 443, h.clientNext, 0, 20, TcpPacket.ACK, 65535), "bad".toByteArray())
        h.ack()
        h.clientData("good".toByteArray())
        assertArrayEquals("good".toByteArray(), h.remoteRead(4))
    }

    private class Frame(bytes: ByteArray) {
        val header = requireNotNull(TcpPacket.parse(bytes, 20, bytes.size - 20))
        val data = bytes.copyOfRange(20 + header.headerLength, bytes.size)
        val end = seqAdvance(header.sequence, data.size + if (header.isSyn || header.isFin) 1 else 0)
    }

    private class Harness(val clientInitial: Long = 100, val serverInitial: Long = 500) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 2500 }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val frames = LinkedBlockingQueue<Frame>()
        val time = AtomicLong(1000)
        val finished = AtomicInteger()
        lateinit var remote: Socket
        var clientNext = seqAdvance(clientInitial, 1)
        var serverNext = seqAdvance(serverInitial, 1)
        val connection = YouTubeTcpConnection(
            ConnectionKey(0x0acb0001, 45000, YouTubeRouting.NETWORK + 1, 443), scope,
            connect = { register ->
                Socket().also {
                    check(register(it))
                    it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 2500)
                }
            }, write = { frames.add(Frame(it)) }, now = time::get,
            onFinished = { finished.incrementAndGet() }, initialSequence = serverInitial)

        fun open(mss: Int = 1460): Frame {
            connection.open(TcpPacket(45000, 443, clientInitial, 0, 20, TcpPacket.SYN, 65535, mss))
            remote = server.accept().apply { soTimeout = 2500 }
            return take { it.header.isSyn }
        }

        fun ack(number: Long = serverNext, window: Int = 65535) {
            connection.packet(TcpPacket(45000, 443, clientNext, number, 20, TcpPacket.ACK, window), byteArrayOf())
            serverNext = number
        }

        fun packet(sequence: Long, payload: ByteArray) {
            connection.packet(TcpPacket(45000, 443, sequence, serverNext, 20, TcpPacket.ACK, 65535), payload)
        }

        fun clientData(payload: ByteArray) {
            packet(clientNext, payload)
            clientNext = seqAdvance(clientNext, payload.size)
        }

        fun clientFin() {
            connection.packet(TcpPacket(45000, 443, clientNext, serverNext, 20, TcpPacket.FIN or TcpPacket.ACK, 65535), byteArrayOf())
            clientNext = seqAdvance(clientNext, 1)
        }

        fun take(predicate: (Frame) -> Boolean): Frame {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                val frame = frames.poll((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("기대한 TCP 응답이 없음")
                if (predicate(frame)) return frame
                if (System.nanoTime() >= deadline) throw AssertionError("기대한 TCP 응답이 없음")
            }
        }

        fun remoteRead(count: Int): ByteArray = ByteArray(count).also { DataInputStream(remote.getInputStream()).readFully(it) }

        override fun close() {
            connection.finish()
            if (::remote.isInitialized) remote.close()
            server.close()
            scope.cancel()
        }
    }
}
