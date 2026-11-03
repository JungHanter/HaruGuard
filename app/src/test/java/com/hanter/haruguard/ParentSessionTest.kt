package com.hanter.haruguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentSessionTest {
    private var elapsed = 1_000L
    private val session = ParentSession { elapsed }

    @Test fun authenticationExpiresAfterTwoMinutes() {
        assertFalse(session.isAuthenticated())
        assertFalse(session.openMaintenance())
        assertTrue(session.authenticate(session.generation()))
        elapsed += ParentSession.AUTH_DURATION_MILLIS - 1
        assertTrue(session.isAuthenticated())
        elapsed++
        assertFalse(session.isAuthenticated())
        assertFalse(session.openMaintenance())
    }

    @Test fun maintenanceCanOutliveAuthenticationForSettingsVisit() {
        session.authenticate(session.generation())
        assertTrue(session.openMaintenance())
        session.lockAuthenticationOnly()
        assertFalse(session.isAuthenticated())
        assertEquals(ParentSession.MAINTENANCE_DURATION_MILLIS, session.maintenanceRemainingMillis())
        elapsed += ParentSession.MAINTENANCE_DURATION_MILLIS
        assertEquals(0L, session.maintenanceRemainingMillis())
    }

    @Test fun screenLockRevokesAuthenticationAndMaintenance() {
        session.authenticate(session.generation())
        session.openMaintenance()
        session.lock()
        assertFalse(session.isAuthenticated())
        assertEquals(0L, session.maintenanceRemainingMillis())
    }

    @Test fun inFlightAuthenticationCannotUnlockAfterScreenOff() {
        val startedGeneration = session.generation()
        session.lock()
        assertFalse(session.authenticate(startedGeneration))
        assertFalse(session.isAuthenticated())
        assertTrue(session.authenticate(session.generation()))
    }

    @Test fun credentialMutationRequiresSameStillAuthenticatedGeneration() {
        val original = session.generation()
        assertFalse(session.isAuthenticatedGeneration(original))
        session.authenticate(original)
        assertTrue(session.isAuthenticatedGeneration(original))
        session.lockAuthenticationOnly()
        session.authenticate(session.generation())
        assertFalse(session.isAuthenticatedGeneration(original))
        assertTrue(session.isAuthenticatedGeneration(session.generation()))
        elapsed += ParentSession.AUTH_DURATION_MILLIS
        assertFalse(session.isAuthenticatedGeneration(session.generation()))
    }

    @Test fun processRecreationNeverRestoresSessions() {
        session.authenticate(session.generation())
        session.openMaintenance()
        val recreated = ParentSession { elapsed }
        assertFalse(recreated.isAuthenticated())
        assertEquals(0L, recreated.maintenanceRemainingMillis())
    }
}
