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
        val changed = store().load().copy(kakaoEnabled = false, naverEnabled = false,
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
        disk = disk!!.replace("version=1", "version=999")
        assertThrows(IllegalArgumentException::class.java) { store().load() }
        disk = "broken"
        assertThrows(IllegalArgumentException::class.java) { store().load() }
    }

    @Test fun failureToPersistInitialDefaultsIsReported() {
        failWrite = true
        assertThrows(IOException::class.java) { store().load() }
        assertNull(disk)
    }
}
