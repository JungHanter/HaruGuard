package com.hanter.haruguard.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class YouTubeUdpConnectionTest {
    @Test(timeout = 8000) fun connectedUdpSocketPreservesBinaryDatagramsInBothDirections() = Harness().use { h ->
        val request = byteArrayOf(0, -1, 7, 1)
        h.connection.start(request)
        val received = h.receive()
        assertArrayEquals(request, received.data.copyOf(received.length))
        h.server.send(DatagramPacket(request, request.size, received.socketAddress))
        assertArrayEquals(request, h.frames.poll(3, TimeUnit.SECONDS))
        h.connection.send(byteArrayOf(9, 8))
        assertEquals(2, h.receive().length)
    }

    @Test(timeout = 8000) fun ownershipRevocationDropsTheReplyAndClosesTheFlow() = Harness().use { h ->
        h.connection.start(byteArrayOf(1))
        val request = h.receive()
        h.allowed.set(false)
        h.server.send(DatagramPacket(byteArrayOf(2), 1, request.socketAddress))
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
        assertTrue(h.frames.isEmpty())
        assertEquals(0, h.errors.get())
    }

    @Test(timeout = 8000) fun closeStopsBlockedReceivesAndRunsCleanupOnlyOnce() = Harness().use { h ->
        h.connection.start(byteArrayOf(1))
        h.receive()
        h.connection.close()
        h.connection.close()
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
        assertEquals(1, h.closeCount.get())
        assertEquals(0, h.errors.get())
    }

    private class Harness : Closeable {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress()).apply { soTimeout = 2500 }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val allowed = AtomicBoolean(true)
        val errors = AtomicInteger()
        val frames = LinkedBlockingQueue<ByteArray>()
        val finished = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val connection = YouTubeUdpConnection(scope,
            connect = { register -> DatagramSocket().also {
                check(register(it))
                it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
            } }, mayReceive = allowed::get, write = { frames.add(it) }, now = System::currentTimeMillis,
            onError = { errors.incrementAndGet() }, onFinished = { closeCount.incrementAndGet(); finished.countDown() })

        fun receive(): DatagramPacket = DatagramPacket(ByteArray(4096), 4096).also(server::receive)

        override fun close() {
            connection.close()
            server.close()
            scope.cancel()
        }
    }
}
