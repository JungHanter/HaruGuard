package com.hanter.haruguard.vpn

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FilterSettingsStoreTest {
    private var disk: String? = null
    private var failWrite = false
    private fun store() = FilterSettingsStore({ disk }, { if (failWrite) false else { disk = it; true } }, DomainInput::normalize)

    @Test fun defaultsAreCommittedOnlyOnFirstUse() {
        val first = store().load()
        assertEquals(FilterSettings(), first)
        assertNotNull(disk)
        failWrite = true
        assertEquals(first, store().load())
    }

    @Test fun editedAndEmptyDefaultsSurviveRecreation() {
        val changed = store().load().copy(kakaoEnabled = false, naverEnabled = false, p2pEnabled = false, p2pDomains = emptySet(),
            adsEnabled = false, adDomains = emptySet(),
            kakaoDomains = emptySet(), naverDomains = setOf("custom.example.org"), customDomains = setOf("dcinside.com"),
            enabledServices = setOf(FilterService.YOUTUBE, FilterService.VIMEO))
        store().save(changed)
        assertEquals(changed, store().load())
        assertEquals(changed, FilterSettingsCodec.decode(FilterSettingsCodec.encode(changed)))
    }

    @Test fun storageFailurePreservesPreviousPolicy() {
        val before = store().load()
        failWrite = true
        assertThrows(IOException::class.java) { store().save(before.copy(customDomains = setOf("example.org"))) }
        assertEquals(before, store().load())
    }

    @Test fun invalidOrOversizedListsNeverReplaceSavedPolicy() {
        val before = store().load()
        assertThrows(IllegalArgumentException::class.java) { store().save(before.copy(customDomains = setOf("https://example.org"))) }
        assertThrows(IllegalArgumentException::class.java) { store().save(before.copy(customDomains = (0..DomainInput.MAX_DOMAINS).map { "d$it.example.org" }.toSet())) }
        assertEquals(before, store().load())
    }

    @Test fun corruptOrUnknownVersionNeverSilentlyLoadsAnEmptyPolicy() {
        store().load()
        disk = disk!!.replace("version=3", "version=999")
        assertThrows(IllegalArgumentException::class.java) { store().load() }
        disk = "broken"
        assertThrows(IllegalArgumentException::class.java) { store().load() }
    }

    @Test fun failureToPersistInitialDefaultsIsReported() {
        failWrite = true
        assertThrows(IOException::class.java) { store().load() }
        assertNull(disk)
    }

    @Test fun versionOnePreservesAllExistingChoicesAndInitializesNewGroupsOnce() {
        disk = legacySettings()
        val upgraded = store().load()
        assertEquals(setOf(FilterService.YOUTUBE), upgraded.enabledServices)
        assertFalse(upgraded.kakaoEnabled)
        assertTrue(upgraded.kakaoDomains.isEmpty())
        assertEquals(setOf("naver.example.org"), upgraded.naverDomains)
        assertEquals(setOf("blocked.example.org"), upgraded.customDomains)
        assertTrue(upgraded.p2pEnabled)
        assertEquals(FilterCatalog.p2pDefaults, upgraded.p2pDomains)
        assertTrue(upgraded.adsEnabled)
        assertEquals(FilterCatalog.adDefaults, upgraded.adDomains)
        assertTrue(disk!!.startsWith("version=3\n"))
        val edited = upgraded.copy(p2pDomains = emptySet(), p2pEnabled = false, adDomains = emptySet(), adsEnabled = false)
        store().save(edited)
        assertEquals(edited, store().load())
    }

    @Test fun failedMigrationDoesNotOverwriteLegacyDataOrHideFailure() {
        val legacy = legacySettings()
        disk = legacy
        failWrite = true
        assertThrows(IOException::class.java) { store().load() }
        assertEquals(legacy, disk)
        failWrite = false
        assertTrue(store().load().p2pEnabled)
    }

    @Test fun currentVersionRejectsMissingP2pKeysAndDuplicateKeys() {
        val valid = FilterSettingsCodec.encode(FilterSettings())
        assertThrows(IllegalArgumentException::class.java) {
            FilterSettingsCodec.decode(valid.lineSequence().filterNot { it.startsWith("p2p=") }.joinToString("\n"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilterSettingsCodec.decode(valid.replace("p2pEnabled=true", "naverEnabled=true"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FilterSettingsCodec.decode(legacySettings().replace("custom=", "unexpected="))
        }
    }

    @Test fun versionTwoPreservesEditedP2pAndCustomChoicesAndSeedsAdsOnlyOnce() {
        val before = FilterSettings(enabledServices = emptySet(), kakaoEnabled = false,
            naverDomains = emptySet(), p2pEnabled = false, p2pDomains = setOf("custom-torrent.example.org"),
            customDomains = setOf("custom-block.example.org"))
        disk = versionTwo(before)
        val upgraded = store().load()
        assertEquals(before, upgraded)
        assertTrue(disk!!.startsWith("version=3\n"))
        val edited = upgraded.copy(adsEnabled = false, adDomains = setOf("parent-ad.example.org"))
        store().save(edited)
        failWrite = true
        assertEquals(edited, store().load())
        assertFalse("삭제한 광고 기본 목록을 복구하지 않음", "ad.naver.com" in store().load().adDomains)
    }

    @Test fun versionTwoMigrationFailurePreservesRawSettingsIncludingEmptyLists() {
        val legacy = versionTwo(FilterSettings(p2pEnabled = false, p2pDomains = emptySet(), customDomains = emptySet()))
        disk = legacy
        failWrite = true
        assertThrows(IOException::class.java) { store().load() }
        assertEquals(legacy, disk)
        failWrite = false
        val upgraded = store().load()
        assertFalse(upgraded.p2pEnabled)
        assertTrue(upgraded.p2pDomains.isEmpty())
        assertTrue(upgraded.customDomains.isEmpty())
        assertEquals(FilterCatalog.adDefaults, upgraded.adDomains)
    }

    @Test fun missingDuplicateUnknownOrInvalidAdvertisingFieldsNeverLoadAsDefaults() {
        val valid = FilterSettingsCodec.encode(FilterSettings())
        for (bad in listOf(
            valid.lineSequence().filterNot { it.startsWith("ads=") }.joinToString("\n"),
            valid.replace("adsEnabled=true", "ads=true"),
            valid.replace("adsEnabled=true", "unknown=true"),
            valid.replace("adsEnabled=true", "adsEnabled=maybe"),
            valid.replace("version=3", "version=2"),
        )) assertThrows(IllegalArgumentException::class.java) { FilterSettingsCodec.decode(bad) }
        val before = store().load()
        assertThrows(IllegalArgumentException::class.java) {
            store().save(before.copy(adDomains = setOf("https://ad.example.org/banner")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().save(before.copy(adDomains = (0..DomainInput.MAX_DOMAINS).map { "ad$it.example.org" }.toSet()))
        }
        assertEquals(before, store().load())
    }

    @Test fun fiveFullListsOfLongDomainsFitTheSerializedLimitAndSurviveReload() {
        var settings = FilterSettings()
        DomainGroup.entries.forEach { group ->
            settings = settings.withDomains(group, (0 until DomainInput.MAX_DOMAINS).map {
                "d$it.${"a".repeat(63)}.${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(40)}.${group.name.lowercase()}.org"
            }.toSet())
        }
        store().save(settings)
        assertTrue(disk!!.length > 600_000)
        assertEquals(settings, store().load())
    }

    private fun versionTwo(settings: FilterSettings) = FilterSettingsCodec.encode(settings).lineSequence()
        .filterNot { it.startsWith("adsEnabled=") || it.startsWith("ads=") }
        .joinToString("\n").replace("version=3", "version=2")

    private fun legacySettings() = """
        version=1
        services=YOUTUBE
        kakaoEnabled=false
        naverEnabled=true
        kakao=
        naver=naver.example.org
        custom=blocked.example.org
    """.trimIndent()
}
