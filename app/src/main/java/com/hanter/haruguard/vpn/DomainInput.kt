package com.hanter.haruguard.vpn

import java.net.IDN
import java.util.Locale

/** 호스트만 받는다. URL/포트/IP를 자동 추측해서 더 넓은 차단 규칙으로 바꾸지 않는다. */
internal object DomainInput {
    const val MAX_DOMAINS = 512

    fun normalize(input: String): String {
        val text = input.trim().removeSuffix(".")
        require(text.isNotEmpty() && text.none { it.isWhitespace() || it.isISOControl() || it in "/\\:@?#*%,=" }) {
            "도메인만 입력하세요. http(s)://, /경로, 포트, IP, *는 넣지 마세요."
        }
        val ascii = try { IDN.toASCII(text, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("올바른 도메인을 입력하세요.") }
        val labels = ascii.split('.')
        require(ascii.length <= 253 && labels.size >= 2 && labels.all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        } && labels.last().any { it in 'a'..'z' }) { "올바른 사이트 도메인을 입력하세요. IP 주소는 지원하지 않습니다." }
        return ascii
    }
}

/** Mozilla Public Suffix List의 정확/와일드카드/예외 규칙으로 공용 접미사 입력을 거절한다.
 * https://publicsuffix.org/list/ (MPL-2.0, 원본과 라이선스를 assets에 포함)
 */
internal class PublicSuffixes(lines: Sequence<String>) {
    private val rules = lines.map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }.map {
        val prefix = if (it.startsWith("!")) "!" else if (it.startsWith("*.")) "*." else ""
        // 공식 목록의 최신 문자도 구형 JDK/Android에서 동일한 ASCII 규칙으로 읽는다.
        prefix + IDN.toASCII(it.removePrefix(prefix), IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    }.toSet()

    init { require("com" in rules && "co.kr" in rules) { "공용 접미사 목록을 읽지 못했습니다." } }

    fun validate(input: String): String {
        val domain = DomainInput.normalize(input)
        val labels = domain.split('.')
        var suffixSize = 1
        for (index in labels.indices) {
            val suffix = labels.drop(index).joinToString(".")
            if ("!$suffix" in rules) {
                suffixSize = labels.size - index - 1
                break
            }
            if (suffix in rules) suffixSize = maxOf(suffixSize, labels.size - index)
            if (index > 0 && "*.$suffix" in rules) suffixSize = maxOf(suffixSize, labels.size - index + 1)
        }
        require(labels.size > suffixSize) { "com, co.kr 같은 공용 접미사 대신 사이트 도메인을 입력하세요." }
        return domain
    }
}
