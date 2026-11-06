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
        disk = disk!!.replace("version=2", "version=999")
        assertThrows(IllegalArgumentException::class.java) { store().load() }
        disk = "broken"
        assertThrows(IllegalArgumentException::class.java) { store().load() }
    }

    @Test fun failureToPersistInitialDefaultsIsReported() {
        failWrite = true
        assertThrows(IOException::class.java) { store().load() }
        assertNull(disk)
    }

    @Test fun legacySettingsPreserveAllExistingChoicesAndInitializeP2pOnce() {
        disk = legacySettings()
        val upgraded = store().load()
        assertEquals(setOf(FilterService.YOUTUBE), upgraded.enabledServices)
        assertFalse(upgraded.kakaoEnabled)
        assertTrue(upgraded.kakaoDomains.isEmpty())
        assertEquals(setOf("naver.example.org"), upgraded.naverDomains)
        assertEquals(setOf("blocked.example.org"), upgraded.customDomains)
        assertTrue(upgraded.p2pEnabled)
        assertEquals(FilterCatalog.p2pDefaults, upgraded.p2pDomains)
        assertTrue(disk!!.startsWith("version=2\n"))
        val edited = upgraded.copy(p2pDomains = emptySet(), p2pEnabled = false)
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
