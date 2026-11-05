package com.hanter.haruguard.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 내부 주소로 들어온 실제 TCP/UDP 연결만 앱 UID와 도메인을 함께 검사한다.
 * Android 10의 DNS 소켓은 AID_DNS 소유일 수 있으므로 DNS 요청의 UID로 예외를 주지 않는다.
 * https://developer.android.com/reference/android/net/ConnectivityManager#getConnectionOwnerUid(int,java.net.InetSocketAddress,java.net.InetSocketAddress)
 */
internal class DomainConnections(
    private val service: VpnService,
    private val routing: DomainRouting,
    initialPolicy: FilterPolicy,
    private val write: (ByteArray) -> Unit,
    private val onBlocked: () -> Unit,
    private val onError: () -> Unit,
) : Closeable {
    @Volatile var policy = initialPolicy
        private set
    private val connectivity = service.getSystemService(ConnectivityManager::class.java)
    // 읽기와 쓰기가 서로 대기해도 진행하도록 전용 IO 실행기를 사용한다. 연결 수는 아래에서 제한한다.
    private val dispatcher = ThreadPoolExecutor(0, MAX_CONNECTIONS * 3, 30, TimeUnit.SECONDS,
        SynchronousQueue(), { task -> Thread(task, "haruguard-filter-io").apply { isDaemon = true } }
    ).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean()
    private val tcp = ConcurrentHashMap<ConnectionKey, TcpRelay>()
    private val udp = ConcurrentHashMap<ConnectionKey, UdpRelay>()

    @Synchronized fun accept(bytes: ByteArray, length: Int) {
        if (closed.get()) return
        val ip = IpPacket.parse(bytes, length) ?: return
        if (!DomainRouting.contains(ip.destIp)) return
        when (ip.protocol) {
            6 -> {
                val packet = TcpPacket.parse(bytes, ip.headerLength, ip.length - ip.headerLength) ?: return
                val key = ConnectionKey(ip.sourceIp, packet.sourcePort, ip.destIp, packet.destPort)
                val payload = bytes.copyOfRange(ip.headerLength + packet.headerLength, ip.length)
                tcp[key]?.let { it.packet(packet, payload); return }
                if (packet.isRst) return
                if (!packet.isSyn || packet.isAck) { reset(key, packet, payload.size); return }
                val host = authorizedHost(key, 6)
                if (host == null || tcp.size + udp.size >= MAX_CONNECTIONS) {
                    if (host == null) onBlocked() else onError()
                    reset(key, packet, payload.size); return
                }
                lateinit var connection: TcpRelay
                connection = TcpRelay(key, scope,
                    connect = { register -> connectTcp(host, key.destPort, register) },
                    write = write, now = SystemClock::elapsedRealtime,
                    onFinished = { tcp.remove(it, connection) })
                tcp[key] = connection
                connection.open(packet)
            }
            17 -> {
                val offset = ip.headerLength
                if (ip.length < offset + 8) return
                val size = readU16(bytes, offset + 4)
                if (size < 8 || offset + size != ip.length) return
                val key = ConnectionKey(ip.sourceIp, readU16(bytes, offset), ip.destIp, readU16(bytes, offset + 2))
                val payload = bytes.copyOfRange(offset + 8, ip.length)
                // UDP는 포트가 다른 앱에 즉시 재사용될 수 있다. 과거 흐름의 허용값을 재사용하지 않는다.
                val host = authorizedHost(key, 17)
                if (host == null) {
                    udp[key]?.close()
                    onBlocked(); write(ConnectionPackets.unreachable(bytes, ip)); return
                }
                udp[key]?.let { it.send(payload); return }
                if (tcp.size + udp.size >= MAX_CONNECTIONS) {
                    onError(); write(ConnectionPackets.unreachable(bytes, ip)); return
                }
                lateinit var connection: UdpRelay
                connection = UdpRelay(scope,
                    connect = { register -> connectUdp(host, key.destPort, register) },
                    mayReceive = { authorizedHost(key, 17) == host },
                    write = { write(ConnectionPackets.udp(key, it)) }, now = SystemClock::elapsedRealtime,
                    onError = onError, onFinished = { udp.remove(key, connection) })
                udp[key] = connection
                connection.start(payload)
            }
        }
    }

    private fun authorizedHost(key: ConnectionKey, protocol: Int): String? {
        val host = routing.hostFor(key.destIp) ?: return null
        return host.takeIf { policy.mayConnect(host, ownerUid = { runCatching {
            connectivity.getConnectionOwnerUid(protocol,
                InetSocketAddress(InetAddress.getByAddress(key.sourceIp.addressBytes()), key.sourcePort),
                InetSocketAddress(InetAddress.getByAddress(key.destIp.addressBytes()), key.destPort))
        }.getOrDefault(-1) }, packageUid = { packageName ->
            // 대상 패키지만 조회하고 설치/삭제 뒤 오래된 UID를 재사용하지 않는다.
            @Suppress("DEPRECATION")
            runCatching { service.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
        }) }
    }

    @Synchronized fun applyPolicy(next: FilterPolicy) {
        if (closed.get() || policy.settings == next.settings) return
        policy = next
        // 정책 갱신 시 관리 중인 흐름은 닫아 이전 허용값이 계속 사용되지 않게 한다.
        // 내부 주소↔호스트 대응은 보존해 앱의 캐시가 다른 호스트로 바뀌지 않게 한다.
        tcp.values.toList().forEach { it.finish() }
        udp.values.toList().forEach { it.close() }
        tcp.clear()
        udp.clear()
    }

    private fun reset(key: ConnectionKey, packet: TcpPacket, payloadLength: Int) {
        if (packet.isAck) write(ConnectionPackets.tcp(key, packet.acknowledgment, 0, TcpPacket.RST, 0))
        else write(ConnectionPackets.tcp(key, 0, seqAdvance(packet.sequence,
            payloadLength + (if (packet.isSyn) 1 else 0) + (if (packet.isFin) 1 else 0)),
            TcpPacket.RST or TcpPacket.ACK, 0))
    }

    private fun connectTcp(host: String, port: Int, register: (Socket) -> Boolean): Socket {
        val network = physicalNetwork() ?: throw IOException("물리 네트워크 없음")
        var failure: Exception? = null
        for (address in network.getAllByName(host)) {
            val socket = Socket()
            try {
                if (!register(socket)) throw IOException("연결 종료됨")
                if (!service.protect(socket)) throw IOException("소켓 보호 실패")
                network.bindSocket(socket)
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), 10_000)
                return socket
            } catch (error: Exception) {
                socket.close()
                failure = error
                if (closed.get()) break
            }
        }
        onError()
        throw IOException("필터 대상 서버 연결 실패", failure)
    }

    private fun physicalNetwork(): Network? {
        fun usable(network: Network): Boolean = connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        connectivity.activeNetwork?.takeIf(::usable)?.let { return it }
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.firstOrNull(::usable)
    }

    private fun connectUdp(host: String, port: Int, register: (DatagramSocket) -> Boolean): DatagramSocket {
        val network = physicalNetwork() ?: throw IOException("물리 네트워크 없음")
        val address = network.getAllByName(host).firstOrNull() ?: throw IOException("주소 없음")
        val socket = DatagramSocket(null)
        try {
            if (!register(socket)) throw IOException("연결 종료됨")
            if (!service.protect(socket)) throw IOException("소켓 보호 실패")
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port))
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    fun tick() {
        tcp.values.forEach { it.tick() }
        val now = SystemClock.elapsedRealtime()
        udp.values.filter { now - it.lastActivity > 30_000 }.forEach { it.close() }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tcp.values.toList().forEach { it.finish() }
        udp.values.toList().forEach { it.close() }
        tcp.clear(); udp.clear()
        scope.cancel()
        dispatcher.close()
    }

    private companion object { const val MAX_CONNECTIONS = 24 }
}
