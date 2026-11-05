package com.hanter.haruguard.vpn

import java.util.Locale

/**
 * DNS 응답에는 앱별 허용 여부를 넣지 않는다. Android의 공유 DNS 캐시 때문에 다른 앱도
 * 같은 응답을 사용할 수 있으므로, 도메인에 대응하는 내부 주소로 연결을 유도한 뒤 UID를 검사한다.
 * https://source.android.com/docs/core/ota/modular-system/dns-resolver
 */
internal class YouTubeRouting(private val capacity: Int = 16_384) {
    private val addresses = mutableMapOf<String, Int>()
    private val hosts = mutableMapOf<Int, String>()

    @Synchronized fun addressFor(host: String): Int? {
        if (!YouTubeDnsFilter.isBlocked(host)) return null
        val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
        addresses[normalized]?.let { return it }
        if (addresses.size >= capacity.coerceAtMost(0x1fffe)) return null
        val address = NETWORK + addresses.size + 1
        addresses[normalized] = address
        hosts[address] = normalized
        return address
    }

    @Synchronized fun hostFor(address: Int): String? = hosts[address]

    fun reply(query: ByteArray, question: DnsQuestion): ByteArray {
        val type = readU16(query, question.endOffset - 4)
        val dnsClass = readU16(query, question.endOffset - 2)
        // AAAA/HTTPS/SVCB에는 실제 주소나 IP 힌트를 내주지 않고 A 조회로 유도한다.
        // 일반 IPv6 통신은 유지하며, YouTube 연결의 내부 구간만 IPv4를 사용한다.
        if (type != 1 || dnsClass != 1) return DnsReply.error(query, question, 0)
        val address = addressFor(question.host)
            ?: return DnsReply.error(query, question, DnsReply.SERVFAIL)
        return DnsReply.error(query, question, 0).copyOf(question.endOffset + 16).also { reply ->
            writeU16(reply, 6, 1)
            val offset = question.endOffset
            writeU16(reply, offset, 0xc00c)
            writeU16(reply, offset + 2, 1)
            writeU16(reply, offset + 4, 1)
            // TTL 0: 터널 재생성 후 오래된 내부 주소를 재사용하는 시간을 줄인다.
            putInt(reply, offset + 6, 0)
            writeU16(reply, offset + 10, 4)
            putInt(reply, offset + 12, address)
        }
    }

    companion object {
        // 벤치마크용 예약 대역. 실제 YouTube IP를 공유 IP와 혼동하지 않도록 별도로 사용한다.
        const val ROUTE = "198.18.0.0"
        const val PREFIX = 15
        const val NETWORK: Int = -971898880 // 198.18.0.0
        fun contains(address: Int): Boolean = address and -0x20000 == NETWORK

        fun mayConnect(host: String?, ownerUid: Int, youtubeUid: Int?): Boolean =
            host != null && YouTubeDnsFilter.isBlocked(host) && ownerUid >= 0 &&
                youtubeUid != null && ownerUid == youtubeUid
    }
}
