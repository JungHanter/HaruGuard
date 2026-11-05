package com.hanter.haruguard.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

/**
 * 로컬 커널과 TCP를 종단하고 암호화된 바이트를 그대로 실제 서버에 전달한다.
 * FreeProxy의 TCP 세션 구조를 참고·수정했다. 원격 프록시/HTTPS 복호화/접속 로그는 사용하지 않는다.
 * https://github.com/xVanTuring/free-proxy/blob/f13432fe12866fa6f66b150f25c2b1ec0549eb77/app/src/main/java/tech/xvanturing/freeproxy/vpn/TcpSession.kt
 * Apache-2.0 고지: assets/licenses/free-proxy-NOTICE.txt 및 free-proxy-LICENSE.txt
 */
internal class TcpRelay(
    val key: ConnectionKey,
    private val scope: CoroutineScope,
    private val connect: ((Socket) -> Boolean) -> Socket,
    private val write: (ByteArray) -> Unit,
    private val now: () -> Long,
    private val onFinished: (ConnectionKey) -> Unit,
    private val initialSequence: Long = Random.nextLong(0, 0x1_0000_0000L),
) {
    private val lock = Object()
    private val closed = AtomicBoolean()
    private val upstream = Channel<ByteArray>(64)
    private var socket: Socket? = null
    private var job: Job? = null
    private var connected = false
    private var handshake = false
    private var receiveNext = 0L
    private var sendNext = initialSequence
    private var sendUnacked = initialSequence
    private var peerWindow = 0
    private var mss = 536
    private var pendingBytes = 0
    private var queueFull = false
    private var receivedFin = false
    private var sentFin = false
    private var lastTransmit = now()
    private var retries = 0
    private val unacknowledged = ArrayDeque<Segment>()
    @Volatile var lastActivity = now()
        private set

    private data class Segment(val sequence: Long, val flags: Int, val payload: ByteArray) {
        val end get() = seqAdvance(sequence, payload.size + if (flags and (TcpPacket.SYN or TcpPacket.FIN) != 0) 1 else 0)
    }

    fun open(syn: TcpPacket) {
        synchronized(lock) {
            receiveNext = seqAdvance(syn.sequence, 1)
            peerWindow = syn.window
            mss = min(1460, syn.maximumSegmentSize ?: 536).coerceAtLeast(1)
        }
        job = scope.launch {
            try {
                val remote = connect { candidate ->
                    synchronized(lock) {
                        if (closed.get()) { candidate.close(); false }
                        else { socket = candidate; true }
                    }
                }
                synchronized(lock) {
                    if (closed.get()) { remote.close(); return@launch }
                    connected = true
                    transmit(TcpPacket.SYN or TcpPacket.ACK, byteArrayOf())
                }
                launch { pumpUpstream(remote) }
                pumpDownstream(remote)
            } catch (_: Exception) {
                reset()
                finish()
            }
        }
        // 실행기 포화 등으로 본문이 시작되기 전에 취소된 작업도 연결 목록에서 정리한다.
        job?.invokeOnCompletion { error -> if (error != null) finish() }
    }

    fun packet(header: TcpPacket, payload: ByteArray) {
        lastActivity = now()
        if (header.isRst) { finish(); return }
        synchronized(lock) {
            if (closed.get()) return
            if (header.isSyn) {
                if (connected && !handshake) resendFirst()
                return
            }
            if (!connected || !header.isAck) return
            // 전송하지 않은 순서 번호를 ACK로 받아 대기 버퍼를 버리지 않는다.
            if (seqLessThan(sendNext, header.acknowledgment) || seqLessThan(header.acknowledgment, sendUnacked)) return
            if (!handshake) {
                if (header.acknowledgment != sendNext || header.sequence != receiveNext) return
                handshake = true
            }
            peerWindow = header.window
            if (seqLessThan(sendUnacked, header.acknowledgment)) {
                sendUnacked = header.acknowledgment
                retries = 0
                while (unacknowledged.firstOrNull()?.let { seqLessOrEqual(it.end, sendUnacked) } == true) {
                    unacknowledged.removeFirst()
                }
                // 부분 ACK 뒤에는 이미 받은 바이트를 중복 전송하지 않는다.
                unacknowledged.firstOrNull()?.let { segment ->
                    if (segment.payload.isNotEmpty() && seqLessThan(segment.sequence, sendUnacked)) {
                        val consumed = (sendUnacked - segment.sequence).toInt()
                        unacknowledged.removeFirst()
                        unacknowledged.addFirst(segment.copy(sequence = sendUnacked,
                            payload = segment.payload.copyOfRange(consumed, segment.payload.size)))
                    }
                }
            }
            lock.notifyAll()
            if (payload.isNotEmpty() && !receivedFin) accept(header.sequence, payload)
            if (header.isFin) {
                val finAt = seqAdvance(header.sequence, payload.size)
                if (!receivedFin && finAt == receiveNext) {
                    receiveNext = seqAdvance(receiveNext, 1)
                    receivedFin = true
                    upstream.close()
                }
                ack()
            }
            if (receivedFin && sentFin && sendUnacked == sendNext) finish()
        }
    }

    private fun accept(sequence: Long, payload: ByteArray) {
        val skipped = if (seqLessThan(sequence, receiveNext)) (receiveNext - sequence).toInt() else 0
        if (seqLessThan(receiveNext, sequence) || skipped >= payload.size) { ack(); return }
        val bytes = payload.copyOfRange(skipped, payload.size)
        if (bytes.size > advertisedWindow()) { ack(); return }
        // 큐에서 꺼낸 쓰기 작업과 바이트 수 갱신이 경합하지 않도록 같은 잠금을 사용한다.
        pendingBytes += bytes.size
        if (!upstream.trySend(bytes).isSuccess) {
            pendingBytes -= bytes.size
            queueFull = true
        } else receiveNext = seqAdvance(receiveNext, bytes.size)
        ack()
    }

    private suspend fun pumpUpstream(remote: Socket) {
        try {
            val output = remote.getOutputStream()
            for (bytes in upstream) {
                output.write(bytes)
                synchronized(lock) {
                    val before = advertisedWindow()
                    pendingBytes -= bytes.size
                    queueFull = false
                    if (!closed.get() && before < mss && advertisedWindow() >= mss) ack()
                }
            }
            remote.shutdownOutput()
        } catch (_: Exception) { reset(); finish() }
    }

    private fun pumpDownstream(remote: Socket) {
        val input = remote.getInputStream()
        val bytes = ByteArray(mss)
        while (!closed.get()) {
            val length = input.read(bytes)
            if (length < 0) break
            var offset = 0
            while (offset < length) {
                synchronized(lock) {
                    while (!closed.get() && (!handshake || availableWindow() <= 0)) lock.wait(1_000)
                    if (closed.get()) return
                    val count = minOf(mss, length - offset, availableWindow())
                    transmit(TcpPacket.PSH or TcpPacket.ACK, bytes.copyOfRange(offset, offset + count))
                    offset += count
                }
            }
        }
        synchronized(lock) {
            while (!closed.get() && (!handshake || availableWindow() <= 0)) lock.wait(1_000)
            if (!closed.get()) {
                sentFin = true
                transmit(TcpPacket.FIN or TcpPacket.ACK, byteArrayOf())
            }
        }
    }

    private fun availableWindow(): Int =
        if (unacknowledged.size >= 128) 0
        else (min(peerWindow, 65_535) - (sendNext - sendUnacked).toInt()).coerceAtLeast(0)
    private fun advertisedWindow(): Int = if (queueFull) 0 else (65_535 - pendingBytes).coerceAtLeast(0)

    // 아래 전송/ACK 처리 메서드는 lock을 잡은 상태에서만 호출한다.
    private fun transmit(flags: Int, payload: ByteArray) {
        val segment = Segment(sendNext, flags, payload)
        sendNext = segment.end
        unacknowledged.addLast(segment)
        if (unacknowledged.size == 1) lastTransmit = now()
        emit(segment)
    }

    private fun emit(segment: Segment) {
        write(ConnectionPackets.tcp(key, segment.sequence, receiveNext, segment.flags,
            advertisedWindow(), segment.payload, if (segment.flags and TcpPacket.SYN != 0) mss else 0))
    }

    private fun ack() {
        write(ConnectionPackets.tcp(key, sendNext, receiveNext, TcpPacket.ACK, advertisedWindow()))
    }

    private fun resendFirst() {
        unacknowledged.firstOrNull()?.let(::emit)
        lastTransmit = now()
    }

    /** 마지막 데이터·SYN·FIN도 ACK가 없으면 재전송하고 기한 뒤 연결을 정리한다. */
    fun tick() {
        synchronized(lock) {
            if (closed.get()) return
            if (now() - lastActivity > 120_000) { reset(); finish(); return }
            if (unacknowledged.isNotEmpty() && now() - lastTransmit >= 1_000) {
                if (++retries > 10) { reset(); finish() } else resendFirst()
            } else if (handshake && peerWindow == 0 && now() - lastTransmit >= 2_000) {
                // 창 갱신 ACK가 유실됐을 때 데이터를 밀어 넣지 않고 현재 창 크기를 다시 확인한다.
                write(ConnectionPackets.tcp(key, seqAdvance(sendNext, -1), receiveNext, TcpPacket.ACK, advertisedWindow()))
                lastTransmit = now()
            }
        }
    }

    private fun reset() {
        synchronized(lock) {
            if (!closed.get()) write(ConnectionPackets.tcp(key, sendNext, receiveNext,
                TcpPacket.RST or TcpPacket.ACK, 0))
        }
    }

    fun finish() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { lock.notifyAll() }
        upstream.cancel()
        runCatching { socket?.close() }
        job?.cancel()
        onFinished(key)
    }
}
