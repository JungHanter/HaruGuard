package com.hanter.haruguard.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** DNS 전용 분리 터널로, 허용 질의는 VPN 외부 네트워크의 시스템 resolver에 전달한다. */
internal class DnsTunnel(
    context: Context,
    private val descriptor: ParcelFileDescriptor,
    private val onBlocked: () -> Unit,
    private val onResolveError: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION") // API 29를 지원한다. 새 생성자는 API 37부터 사용할 수 있다.
    private val resolver = DnsResolver.getInstance()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Any, CancellationSignal>()
    @Volatile private var reader: Thread? = null
    val isHealthy: Boolean get() = !closed.get() && reader?.isAlive == true
    private val callbacks = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "haruguard-dns-reply").apply { isDaemon = true }
    }
    private val dispatch = Executor { task ->
        if (!closed.get()) {
            try {
                callbacks.execute(task)
            } catch (error: RejectedExecutionException) {
                if (!closed.get()) throw error
            }
        }
    }

    fun start() {
        reader = Thread({ readPackets() }, "haruguard-dns-tun").apply { isDaemon = true }
        reader!!.start()
    }

    private fun readPackets() {
        val buffer = ByteArray(65_535)
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        try {
            while (!closed.get()) {
                if (Os.poll(arrayOf(poll), 1_000) == 0) continue
                if (closed.get()) break
                if (poll.revents.toInt() and OsConstants.POLLIN == 0) {
                    throw IOException("VPN interface closed")
                }
                val size = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                if (size <= 0) throw IOException("VPN interface EOF")
                val packet = DnsUdpPacket.parse(buffer, size) ?: continue
                val question = DnsQuestion.parse(packet.query) ?: continue
                if (YouTubeDnsFilter.isBlocked(question.host)) {
                    writeReply(packet, DnsReply.error(packet.query, question, DnsReply.NXDOMAIN))
                    onBlocked()
                } else {
                    forward(packet, question)
                }
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun physicalNetwork(): Network? {
        fun eligible(network: Network): Boolean {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        connectivity.activeNetwork?.takeIf(::eligible)?.let { return it }
        @Suppress("DEPRECATION")
        val candidates = connectivity.allNetworks.filter(::eligible)
        return candidates.firstOrNull {
            connectivity.getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    private fun forward(packet: DnsUdpPacket, question: DnsQuestion) {
        val network = physicalNetwork()
        if (network == null || pending.size >= 64) {
            writeReply(packet, DnsReply.error(packet.query, question, DnsReply.SERVFAIL))
            onResolveError()
            return
        }
        val token = Any()
        val cancellation = CancellationSignal()
        pending[token] = cancellation
        // 연결 종료와 질의 제출이 경합하면 작업을 만들기 전에 취소한다.
        if (closed.get()) {
            pending.remove(token)
            cancellation.cancel()
            return
        }
        try {
            resolver.rawQuery(
                network,
                packet.query,
                DnsResolver.FLAG_NO_CACHE_LOOKUP,
                dispatch,
                cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        pending.remove(token)
                        val reply = DnsReply.upstream(packet.query, answer)
                        if (reply == null) onResolveError()
                        writeReply(packet, reply ?: DnsReply.error(packet.query, question, DnsReply.SERVFAIL))
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        pending.remove(token)
                        if (!closed.get()) {
                            onResolveError()
                            writeReply(packet, DnsReply.error(packet.query, question, DnsReply.SERVFAIL))
                        }
                    }
                },
            )
        } catch (error: Exception) {
            pending.remove(token)
            if (!closed.get()) {
                onResolveError()
                writeReply(packet, DnsReply.error(packet.query, question, DnsReply.SERVFAIL))
            }
        }
    }

    private fun writeReply(packet: DnsUdpPacket, dnsMessage: ByteArray) {
        if (closed.get()) return
        try {
            val reply = packet.response(dnsMessage)
            synchronized(writeLock) {
                if (!closed.get()) {
                    val written = Os.write(descriptor.fileDescriptor, reply, 0, reply.size)
                    if (written != reply.size) throw IOException("Incomplete VPN packet write")
                }
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Exception) {
        if (closeOnce()) onFailure(error)
    }

    override fun close() {
        closeOnce()
    }

    private fun closeOnce(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        pending.values.forEach { it.cancel() }
        pending.clear()
        synchronized(writeLock) { descriptor.close() }
        // 이미 예약된 취소 콜백은 마저 실행하되 닫힌 터널에 쓸 수는 없다.
        callbacks.shutdown()
        return true
    }
}
