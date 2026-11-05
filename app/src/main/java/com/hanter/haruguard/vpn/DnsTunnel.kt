package com.hanter.haruguard.vpn

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.system.StructPollfd
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 일반 DNS와 서비스 내부 주소만 처리하고, 나머지 통신은 물리 네트워크 경로를 유지한다. */
internal class DnsTunnel(
    private val context: VpnService,
    private val descriptor: ParcelFileDescriptor,
    initialPolicy: FilterPolicy,
    private val onBlocked: () -> Unit,
    private val onResolveError: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION") // API 29를 지원한다. 새 생성자는 API 37부터 사용할 수 있다.
    private val resolver = DnsResolver.getInstance()
    private val closed = AtomicBoolean(false)
    private val faulted = AtomicBoolean(false)
    private val writes = ArrayBlockingQueue<ByteArray>(256)
    private val routing = DomainRouting()
    private val connections = DomainConnections(context, routing, initialPolicy, ::writePacket, onBlocked, onResolveError)
    private val pending = ConcurrentHashMap<Any, CancellationSignal>()
    @Volatile private var reader: Thread? = null
    @Volatile private var writer: Thread? = null
    val isHealthy: Boolean get() = !closed.get() && !faulted.get() && reader?.isAlive == true && writer?.isAlive == true
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
        writer = Thread(::writePackets, "haruguard-tun-write").apply { isDaemon = true }
        reader = Thread({ readPackets() }, "haruguard-dns-tun").apply { isDaemon = true }
        writer!!.start()
        reader!!.start()
    }

    fun applyPolicy(policy: FilterPolicy) {
        if (connections.policy.settings == policy.settings) return
        connections.applyPolicy(policy)
        writes.clear()
    }

    /** 지연된 물리 DNS 응답에도 최신 정책을 다시 적용해 규칙 저장 직후의 우회를 막는다. */
    private fun replyForPolicy(packet: DnsUdpPacket, question: DnsQuestion): Boolean =
        when (connections.policy.decision(question.host)) {
            DomainDecision.Block -> {
                onBlocked()
                writeReply(packet, DnsReply.error(packet.query, question, DnsReply.NXDOMAIN))
                true
            }
            is DomainDecision.OfficialApps -> {
                writeReply(packet, routing.reply(packet.query, question))
                true
            }
            DomainDecision.Direct -> false
        }

    private fun readPackets() {
        val buffer = ByteArray(65_535)
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        try {
            var nextCheck = 0L
            while (!closed.get() && !faulted.get()) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= nextCheck) {
                    connections.tick()
                    nextCheck = now + 1_000
                }
                if (Os.poll(arrayOf(poll), 1_000) == 0) continue
                if (closed.get()) break
                if (poll.revents.toInt() and OsConstants.POLLIN == 0) {
                    throw IOException("VPN interface closed")
                }
                val size = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                if (size <= 0) throw IOException("VPN interface EOF")
                val packet = DnsUdpPacket.parse(buffer, size)
                if (packet == null) {
                    connections.accept(buffer, size)
                    continue
                }
                val question = DnsQuestion.parse(packet.query) ?: continue
                if (!replyForPolicy(packet, question)) forward(packet, question)
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
                        if (replyForPolicy(packet, question)) return
                        val reply = DnsReply.upstream(packet.query, answer)
                        if (reply == null) onResolveError()
                        writeReply(packet, reply ?: DnsReply.error(packet.query, question, DnsReply.SERVFAIL))
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        pending.remove(token)
                        if (!closed.get()) {
                            if (replyForPolicy(packet, question)) return
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
        writePacket(packet.response(dnsMessage))
    }

    private fun writePacket(packet: ByteArray) {
        if (closed.get() || faulted.get()) return
        // TUN 출력이 밀려도 연결 잠금/종료를 막지 않는다. TCP는 미확인 패킷을 재전송한다.
        if (!writes.offer(packet)) onResolveError()
    }

    private fun writePackets() {
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLOUT.toShort()
        }
        try {
            while (!closed.get() && !faulted.get()) {
                val packet = writes.poll(1, TimeUnit.SECONDS) ?: continue
                while (!closed.get() && !faulted.get()) {
                    if (Os.poll(arrayOf(poll), 1_000) == 0) continue
                    if (poll.revents.toInt() and OsConstants.POLLOUT == 0) throw IOException("VPN 출력 종료")
                    try {
                        val written = Os.write(descriptor.fileDescriptor, packet, 0, packet.size)
                        if (written != packet.size) throw IOException("Incomplete VPN packet write")
                        break
                    } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                    }
                }
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Exception) {
        if (closed.get() || !faulted.compareAndSet(false, true)) return
        // 연결의 내부 잠금을 보유한 작업 스레드에서 다른 연결을 동시에 닫지 않는다.
        context.mainExecutor.execute { if (closeOnce()) onFailure(error) }
    }

    override fun close() {
        closeOnce()
    }

    private fun closeOnce(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        pending.values.forEach { it.cancel() }
        pending.clear()
        connections.close()
        writes.clear()
        writer?.interrupt()
        descriptor.close()
        // 이미 예약된 취소 콜백은 마저 실행하되 닫힌 터널에 쓸 수는 없다.
        callbacks.shutdown()
        return true
    }
}
