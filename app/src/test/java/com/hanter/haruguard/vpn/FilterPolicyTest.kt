package com.hanter.haruguard.vpn

import org.junit.Assert.*
import org.junit.Test

class FilterPolicyTest {
    private val defaults = FilterSettings()

    @Test fun eachServiceAllowsOnlyItsOwnPackageAcrossEveryDomain() {
        val policy = FilterPolicy(defaults)
        FilterCatalog.domains.forEach { (service, domains) ->
            domains.forEach { domain ->
                assertTrue(domain, policy.mayConnect("test.$domain", { 42 }, { if (it == service.packageName) 42 else 43 }))
                assertFalse(domain, policy.mayConnect(domain, { 99 }, { 42 }))
                assertFalse(domain, policy.mayConnect(domain, { -1 }, { 42 }))
                assertFalse(domain, policy.mayConnect(domain, { 42 }, { null }))
                assertEquals(DomainDecision.Direct, policy.decision("not$domain.example.org"))
            }
        }
    }

    @Test fun youtubeUidDoesNotAllowTiktokOrOtherServices() {
        val policy = FilterPolicy(defaults)
        val packages = mapOf(FilterService.YOUTUBE.packageName to 42, FilterService.TIKTOK.packageName to 43)
        assertTrue(policy.mayConnect("youtube.com", { 42 }, packages::get))
        assertFalse(policy.mayConnect("tiktok.com", { 42 }, packages::get))
        assertTrue(policy.mayConnect("tiktok.com", { 43 }, packages::get))
        assertFalse(policy.mayConnect("youtube.com", { 43 }, packages::get))
    }

    @Test fun globalListsBlockOfficialAppsBeforeUidLookup() {
        DomainGroup.entries.forEach { group ->
            val policy = FilterPolicy(defaults.withDomains(group, setOf("youtube.com")))
            assertEquals(DomainDecision.Block, policy.decision("www.youtube.com"))
            assertFalse(policy.mayConnect("youtube.com", { error("전역 차단에는 UID 조회 불필요") }, { error("패키지 조회 불필요") }))
        }
    }

    @Test fun switchesAndOverlappingListsRemoveOnlyTheirOwnRule() {
        val both = defaults.copy(kakaoDomains = setOf("example.org"), naverDomains = setOf("example.org"))
        assertEquals(DomainDecision.Block, FilterPolicy(both.copy(kakaoEnabled = false)).decision("a.example.org"))
        assertEquals(DomainDecision.Direct, FilterPolicy(both.copy(kakaoEnabled = false, naverEnabled = false)).decision("a.example.org"))
        val custom = both.copy(kakaoEnabled = false, naverEnabled = false, customDomains = setOf("example.org"))
        assertEquals(DomainDecision.Block, FilterPolicy(custom).decision("example.org"))
        assertEquals(DomainDecision.Direct, FilterPolicy(custom.copy(customDomains = emptySet())).decision("example.org"))
    }

    @Test fun serviceOffKeepsGlobalBlockAndOtherServicesOn() {
        val off = defaults.copy(enabledServices = defaults.enabledServices - FilterService.YOUTUBE)
        assertEquals(DomainDecision.Direct, FilterPolicy(off).decision("youtube.com"))
        assertTrue(FilterPolicy(off).decision("tiktok.com") is DomainDecision.OfficialApps)
        assertEquals(DomainDecision.Block, FilterPolicy(off.copy(customDomains = setOf("youtube.com"))).decision("youtube.com"))
    }

    @Test fun hostnameBoundaryAndCaseDoNotOverblockOtherSites() {
        val policy = FilterPolicy(defaults.copy(customDomains = setOf("dcinside.com")))
        listOf("dcinside.com", "WWW.DCINSIDE.COM.", "a.b.dcinside.com").forEach {
            assertEquals(DomainDecision.Block, policy.decision(it))
        }
        listOf("notdcinside.com", "dcinside.com.example.org", "google.com", "accounts.google.com",
            "apis.naver.com", "map.naver.com", "talk-pilsner.kakao.com", "ik.kakaocdn.net").forEach {
            assertEquals(it, DomainDecision.Direct, policy.decision(it))
            assertTrue(policy.mayConnect(it, { error("일반 사이트에는 UID 조회 불필요") }, { null }))
        }
    }

    @Test fun cachedSyntheticAddressUsesNewPolicyWithoutReassignment() {
        val routing = DomainRouting()
        val ip = requireNotNull(routing.addressFor("youtube.com"))
        val blocked = FilterPolicy(defaults.copy(customDomains = setOf("youtube.com")))
        assertFalse(blocked.mayConnect(routing.hostFor(ip), { 42 }, { 42 }))
        val off = FilterPolicy(defaults.copy(enabledServices = emptySet()))
        assertTrue(off.mayConnect(routing.hostFor(ip), { error("토글 OFF에는 조회 불필요") }, { null }))
        assertEquals(ip, routing.addressFor("youtube.com"))
    }
}
