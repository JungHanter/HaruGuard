package com.hanter.haruguard

import androidx.test.platform.app.InstrumentationRegistry
import com.hanter.haruguard.vpn.FilterPolicy
import com.hanter.haruguard.vpn.DomainDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterPreferencesInstrumentedTest {
    @Test fun bundledSuffixesAndEditedListsSurviveRepositoryRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = FilterPreferences(context)
        val previous = preferences.load()
        try {
            assertEquals("xn--bcher-kva.de", preferences.validateDomain("Bücher.DE."))
            assertTrue(runCatching { preferences.validateDomain("co.kr") }.isFailure)
            val changed = previous.copy(kakaoDomains = emptySet(), naverEnabled = false, adsEnabled = false, adDomains = emptySet(),
                customDomains = setOf(preferences.validateDomain("YouTube.com")))
            preferences.save(changed)
            val reloaded = FilterPreferences(context).load()
            assertEquals(changed, reloaded)
            assertEquals(DomainDecision.Block, FilterPolicy(reloaded).decision("www.youtube.com"))
        } finally {
            preferences.save(previous)
        }
    }
}
