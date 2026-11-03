package com.hanter.haruguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectionGuardTest {
    private val list = SettingsScreenPolicy.Screen(headings = listOf("All apps"), list = true)

    @Test fun onlyOurExactAppRowInAnObservedAppListIsPreempted() {
        val guard = AppSelectionGuard()
        guard.observe(7, list, 1_000)
        assertFalse(guard.clicked(7, listOf("Calculator"), 1_010))
        assertFalse(guard.clicked(7, listOf("HaruGuard helper"), 1_010))
        assertTrue(guard.clicked(7, listOf("HaruGuard"), 1_010))
    }

    @Test fun staleOrDifferentWindowListEvidenceCannotBlockAnotherClick() {
        val guard = AppSelectionGuard()
        guard.observe(7, list, 1_000)
        assertFalse(guard.clicked(8, listOf("HaruGuard"), 1_010))
        assertFalse(guard.clicked(7, listOf("HaruGuard"), 999))
        assertFalse(guard.clicked(7, listOf("HaruGuard"), 2_501))
    }

    @Test fun normalSettingsAndAnAppDetailCannotPrimeListProtection() {
        val guard = AppSelectionGuard()
        guard.observe(7, SettingsScreenPolicy.Screen(headings = listOf("Settings"), list = true), 1_000)
        assertFalse(guard.clicked(7, listOf("HaruGuard"), 1_010))
        guard.observe(7, list, 1_020)
        guard.observe(7, SettingsScreenPolicy.Screen(headings = listOf("App info"), appLabels = listOf("Calculator")), 1_030)
        assertFalse(guard.clicked(7, listOf("HaruGuard"), 1_040))
    }

    @Test fun preemptiveInputShieldExpiresEvenIfNoDetailPageAppears() {
        val guard = AppSelectionGuard()
        guard.observe(7, list, 1_000)
        assertTrue(guard.clicked(7, listOf("HaruGuard"), 1_010))
        assertTrue(guard.isPending(1_159))
        assertFalse(guard.isPending(1_160))
    }

    @Test fun parentOpeningOrScreenLockCanCancelPendingSelectionImmediately() {
        val guard = AppSelectionGuard()
        guard.observe(7, list, 1_000)
        guard.clicked(7, listOf("HaruGuard"), 1_010)
        guard.clear()
        assertFalse(guard.isPending(1_011))
        assertFalse(guard.clicked(7, listOf("HaruGuard"), 1_012))
    }
}
