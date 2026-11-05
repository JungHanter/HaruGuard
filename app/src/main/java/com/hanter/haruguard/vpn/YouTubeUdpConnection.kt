package com.hanter.haruguard.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

/** QUIC 등 암호화된 UDP 바이트를 그대로 전달하며 소유권을 잃은 흐름의 응답은 폐기한다. */
internal class YouTubeUdpConnection(
    private val scope: CoroutineScope,
    private val connect: ((DatagramSocket) -> Boolean) -> DatagramSocket,
    private val mayReceive: () -> Boolean,
    private val write: (ByteArray) -> Unit,
    private val now: () -> Long,
    private val onError: () -> Unit,
    private val onFinished: () -> Unit,
) : Closeable {
    private val stopped = AtomicBoolean()
    private val lock = Any()
    private val queue = Channel<ByteArray>(64)
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    @Volatile var lastActivity = now()
        private set

    fun start(first: ByteArray) {
        queue.trySend(first)
        job = scope.launch {
            try {
                val remote = connect { candidate ->
                    synchronized(lock) {
                        if (stopped.get()) { candidate.close(); false }
                        else { socket = candidate; true }
                    }
                }
                if (stopped.get()) { remote.close(); return@launch }
                launch {
                    try {
                        for (payload in queue) remote.send(DatagramPacket(payload, payload.size))
                    } catch (_: Exception) {
                        if (!stopped.get()) onError()
                        close()
                    }
                }
                val buffer = ByteArray(65_507)
                val response = DatagramPacket(buffer, buffer.size)
                while (!stopped.get()) {
                    response.length = buffer.size
                    remote.receive(response)
                    if (stopped.get() || !mayReceive()) return@launch
                    lastActivity = now()
                    write(buffer.copyOf(response.length))
                }
            } catch (_: Exception) {
                if (!stopped.get()) onError()
            } finally { close() }
        }
        job?.invokeOnCompletion { error -> if (error != null) close() }
    }

    fun send(payload: ByteArray) {
        lastActivity = now()
        queue.trySend(payload)
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        queue.cancel()
        synchronized(lock) { socket?.close() }
        job?.cancel()
        onFinished()
    }
}
