package com.hanter.haruguard.vpn

import java.util.Locale

internal enum class FilterService(val title: String, val packageName: String) {
    YOUTUBE("YouTube", "com.google.android.youtube"),
    TIKTOK("TikTok", "com.zhiliaoapp.musically"),
    INSTAGRAM("Instagram", "com.instagram.android"),
    FACEBOOK("Facebook", "com.facebook.katana"),
    X("X (Twitter)", "com.twitter.android"),
    VIMEO("Vimeo", "com.vimeo.android.videoapp"),
}

internal enum class DomainGroup(val title: String) {
    KAKAO("카카오톡 숏폼"), NAVER("네이버 클립"), ADS("광고"), P2P("P2P"), CUSTOM("사용자 지정 차단 도메인"),
}

/** 설정값은 불변 스냅샷으로 전달해 DNS와 실제 연결이 같은 정책을 읽게 한다. */
internal data class FilterSettings(
    val enabledServices: Set<FilterService> = FilterService.entries.toSet(),
    val kakaoEnabled: Boolean = true,
    val naverEnabled: Boolean = true,
    val kakaoDomains: Set<String> = FilterCatalog.kakaoDefaults,
    val naverDomains: Set<String> = FilterCatalog.naverDefaults,
    val customDomains: Set<String> = emptySet(),
    val p2pEnabled: Boolean = true,
    val p2pDomains: Set<String> = FilterCatalog.p2pDefaults,
    val adsEnabled: Boolean = true,
    val adDomains: Set<String> = FilterCatalog.adDefaults,
) {
    fun domains(group: DomainGroup): Set<String> = when (group) {
        DomainGroup.KAKAO -> kakaoDomains
        DomainGroup.NAVER -> naverDomains
        DomainGroup.ADS -> adDomains
        DomainGroup.P2P -> p2pDomains
        DomainGroup.CUSTOM -> customDomains
    }

    fun withDomains(group: DomainGroup, domains: Set<String>): FilterSettings = when (group) {
        DomainGroup.KAKAO -> copy(kakaoDomains = domains.toSet())
        DomainGroup.NAVER -> copy(naverDomains = domains.toSet())
        DomainGroup.ADS -> copy(adDomains = domains.toSet())
        DomainGroup.P2P -> copy(p2pDomains = domains.toSet())
        DomainGroup.CUSTOM -> copy(customDomains = domains.toSet())
    }

    fun enabled(group: DomainGroup): Boolean = when (group) {
        DomainGroup.KAKAO -> kakaoEnabled
        DomainGroup.NAVER -> naverEnabled
        DomainGroup.ADS -> adsEnabled
        DomainGroup.P2P -> p2pEnabled
        DomainGroup.CUSTOM -> true
    }
}

internal sealed interface DomainDecision {
    data object Direct : DomainDecision
    data object Block : DomainDecision
    data class OfficialApps(val packages: Set<String>) : DomainDecision
}

/** 전역 목록은 공식 앱 예외보다 먼저 판정한다. 설치 앱 전체를 순회하지 않는다. */
internal class FilterPolicy(val settings: FilterSettings) {
    private val globalDomains = settings.customDomains +
        (if (settings.kakaoEnabled) settings.kakaoDomains else emptySet()) +
        (if (settings.naverEnabled) settings.naverDomains else emptySet()) +
        (if (settings.adsEnabled) settings.adDomains else emptySet()) +
        (if (settings.p2pEnabled) settings.p2pDomains else emptySet())
    private val servicePackages = buildMap<String, Set<String>> {
        settings.enabledServices.forEach { service ->
            FilterCatalog.domains.getValue(service).forEach { domain ->
                put(domain, get(domain).orEmpty() + service.packageName)
            }
        }
    }

    fun decision(host: String): DomainDecision {
        var suffix = host.trimEnd('.').lowercase(Locale.ROOT)
        var packages: MutableSet<String>? = null
        // 목록 전체를 순회하지 않고 호스트와 상위 도메인을 해시 조회한다.
        // 항목 수가 늘어도 DNS 판정 횟수는 호스트의 라벨 수에 비례한다.
        while (suffix.isNotEmpty()) {
            if (suffix in globalDomains) return DomainDecision.Block
            servicePackages[suffix]?.let { matches ->
                val allowed = packages ?: mutableSetOf<String>().also { packages = it }
                allowed.addAll(matches)
            }
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }
        return packages?.let { DomainDecision.OfficialApps(it.toSet()) } ?: DomainDecision.Direct
    }

    fun mayConnect(host: String?, ownerUid: () -> Int, packageUid: (String) -> Int?): Boolean {
        if (host == null) return false
        return when (val rule = decision(host)) {
            DomainDecision.Block -> false
            DomainDecision.Direct -> true // 토글 OFF 전 발급된 내부 주소도 원래 호스트로 전달한다.
            is DomainDecision.OfficialApps -> {
                val uid = ownerUid()
                uid >= 0 && rule.packages.any { packageUid(it) == uid }
            }
        }
    }

    companion object {
        fun matches(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")
    }
}

/** 목록의 빈 값도 저장한다. 부모가 지운 기본 주소를 다음 실행/업데이트에서 복원하지 않는다. */
internal object FilterSettingsCodec {
    fun encode(settings: FilterSettings): String = listOf(
        "version=3",
        "services=${settings.enabledServices.map { it.name }.sorted().joinToString(",")}",
        "kakaoEnabled=${settings.kakaoEnabled}",
        "naverEnabled=${settings.naverEnabled}",
        "kakao=${settings.kakaoDomains.sorted().joinToString(",")}",
        "naver=${settings.naverDomains.sorted().joinToString(",")}",
        "custom=${settings.customDomains.sorted().joinToString(",")}",
        "p2pEnabled=${settings.p2pEnabled}",
        "p2p=${settings.p2pDomains.sorted().joinToString(",")}",
        "adsEnabled=${settings.adsEnabled}",
        "ads=${settings.adDomains.sorted().joinToString(",")}",
    ).joinToString("\n")

    fun decode(text: String): FilterSettings {
        require(text.length <= 1_000_000) { "차단 설정이 너무 큽니다." }
        val lines = text.split('\n')
        require(lines.size in setOf(7, 9, 11) && lines.all { it.contains('=') }) { "차단 설정 형식 오류" }
        val values = lines.associate { it.substringBefore('=') to it.substringAfter('=') }
        val version = values["version"]
        val keys = setOf("version", "services", "kakaoEnabled", "naverEnabled", "kakao", "naver", "custom") +
            (if (version == "1") emptySet() else setOf("p2pEnabled", "p2p")) +
            (if (version == "3") setOf("adsEnabled", "ads") else emptySet())
        require(version in setOf("1", "2", "3") && values.size == lines.size && values.keys == keys) {
            "지원하지 않는 차단 설정"
        }
        fun values(key: String): Set<String> = values.getValue(key).let {
            if (it.isEmpty()) emptySet() else it.split(',').toSet()
        }
        return FilterSettings(
            values("services").map(FilterService::valueOf).toSet(),
            values.getValue("kakaoEnabled").toBooleanStrict(),
            values.getValue("naverEnabled").toBooleanStrict(),
            values("kakao"), values("naver"), values("custom"),
            if (version == "1") true else values.getValue("p2pEnabled").toBooleanStrict(),
            if (version == "1") FilterCatalog.p2pDefaults else values("p2p"),
            if (version == "3") values.getValue("adsEnabled").toBooleanStrict() else true,
            if (version == "3") values("ads") else FilterCatalog.adDefaults,
        )
    }

    fun needsUpgrade(text: String): Boolean = text.lineSequence().any { it == "version=1" || it == "version=2" }
}
