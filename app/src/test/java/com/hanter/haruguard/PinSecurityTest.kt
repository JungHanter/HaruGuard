package com.hanter.haruguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSecurityTest {
    @Test fun pinRequiresSixToTwelveAsciiDigits() {
        assertTrue(PinSecurity.isValidPin("012345"))
        assertTrue(PinSecurity.isValidPin("012345678901"))
        listOf("12345", "1234567890123", "１２３４５６", "12345 ", "12345a").forEach {
            assertFalse(PinSecurity.isValidPin(it))
        }
    }

    @Test fun saltedVerifierAcceptsOnlyMatchingPin() {
        val first = PinSecurity.createVerifier("271828")
        val second = PinSecurity.createVerifier("271828")
        assertNotEquals(first, second)
        assertTrue(PinSecurity.verify("271828", first))
        assertFalse(PinSecurity.verify("271829", first))
        assertFalse(PinSecurity.verify("271828", first.dropLast(1)))
        assertFalse(PinSecurity.verify("271828", "invalid"))
    }

    @Test fun recoveryCodeNormalizesFormattingWithoutAcceptingInvalidSymbols() {
        val code = PinSecurity.newRecoveryCode()
        assertEquals(39, code.length)
        val normalized = PinSecurity.normalizeRecoveryCode(code)
        assertNotNull(normalized)
        assertEquals(32, normalized!!.length)
        assertEquals(normalized, PinSecurity.normalizeRecoveryCode(" ${code.lowercase()} "))
        assertNull(PinSecurity.normalizeRecoveryCode("G".repeat(32)))
        assertNull(PinSecurity.normalizeRecoveryCode(code.dropLast(1)))
        assertNotEquals(code, PinSecurity.newRecoveryCode())
        val verifier = PinSecurity.createVerifier(normalized)
        assertTrue(PinSecurity.verify(normalized, verifier))
        assertFalse(PinSecurity.verify("0".repeat(32), verifier))
    }

    @Test fun fiveFailuresStartThirtySecondDelayThenIncrease() {
        var state = PinThrottleState()
        repeat(4) {
            state = PinThrottle.failedAttempt(state, 100L, 7)
            assertEquals(0L, PinThrottle.remainingMillis(state, 100))
        }
        state = PinThrottle.failedAttempt(state, 100L, 7)
        assertEquals(30_000L, PinThrottle.remainingMillis(state, 100))
        assertEquals(1L, PinThrottle.remainingMillis(state, 30_099))
        state = PinThrottle.current(state, 30_100, 7)
        assertEquals(0L, PinThrottle.remainingMillis(state, 30_100))
        state = PinThrottle.failedAttempt(state, 30_100, 7)
        assertEquals(60_000L, PinThrottle.remainingMillis(state, 30_100))
    }

    @Test fun processRestartRetainsLockoutAndFailureCount() {
        val persisted = PinThrottleState(5, 7, 1_000, 31_000, 30_000)
        val restored = PinThrottle.current(persisted.copy(), 20_000, 7)
        assertEquals(11_000L, PinThrottle.remainingMillis(restored, 20_000))
        assertEquals(5, restored.failures)
    }

    @Test fun rebootRearmsDelayEvenWhenNewUptimeExceedsOldDeadline() {
        val persisted = PinThrottleState(5, 7, 1_000, 31_000, 30_000)
        val restored = PinThrottle.current(persisted, 90_000, 8)
        assertEquals(30_000L, PinThrottle.remainingMillis(restored, 90_000))
        assertEquals(0L, PinThrottle.remainingMillis(PinThrottle.current(restored, 120_000, 8), 120_000))
    }

    @Test fun elapsedClockRollbackCannotClearDelay() {
        val persisted = PinThrottleState(7, 7, 90_000, 210_000, 120_000)
        val restored = PinThrottle.current(persisted, 1_000, 7)
        assertEquals(120_000L, PinThrottle.remainingMillis(restored, 1_000))
    }

    @Test fun delayIsCappedAndDoesNotOverflowAfterManyFailures() {
        var state = PinThrottleState()
        repeat(100) { state = PinThrottle.failedAttempt(state, 1_000, 7) }
        assertEquals(PinThrottle.MAX_DELAY_MILLIS, state.delayMillis)
        assertEquals(PinThrottle.MAX_DELAY_MILLIS, PinThrottle.remainingMillis(state, 1_000))
        assertTrue(state.failures > 5)
    }

    @Test fun expiredDelayDoesNotReappearOnLaterReboot() {
        val blocked = PinThrottleState(5, 7, 1_000, 31_000, 30_000)
        val elapsed = PinThrottle.current(blocked, 31_000, 7)
        val rebooted = PinThrottle.current(elapsed, 1_000, 8)
        assertEquals(0L, PinThrottle.remainingMillis(rebooted, 1_000))
        assertEquals(5, rebooted.failures)
    }
}
