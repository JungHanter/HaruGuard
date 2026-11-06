package com.hanter.haruguard.vpn

import java.io.IOException

/** 최초 1회만 기본 목록을 확정하고, 이후에는 빈 목록/사용자 삭제도 그대로 복원한다. */
internal class FilterSettingsStore(
    private val read: () -> String?,
    private val write: (String) -> Boolean,
    private val validateDomain: (String) -> String,
) {
    fun load(): FilterSettings {
        val serialized = read() ?: return FilterSettings().also(::save)
        val settings = validated(FilterSettingsCodec.decode(serialized))
        // 기존 목록은 보존하고 이전 형식에 없던 그룹의 기본값만 최초 1회 확정한다.
        if (FilterSettingsCodec.needsUpgrade(serialized)) save(settings)
        return settings
    }

    fun save(settings: FilterSettings) {
        val checked = validated(settings)
        if (!write(FilterSettingsCodec.encode(checked))) throw IOException("차단 설정을 저장하지 못했습니다.")
    }

    private fun validated(settings: FilterSettings): FilterSettings {
        DomainGroup.entries.forEach { group ->
            val domains = settings.domains(group)
            require(domains.size <= DomainInput.MAX_DOMAINS) { "목록마다 최대 ${DomainInput.MAX_DOMAINS}개까지 저장할 수 있습니다." }
            require(domains.all { validateDomain(it) == it }) { "차단 도메인 형식 오류" }
        }
        return settings
    }
}
