package com.hanter.haruguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryAlarmDeadlineTest {
    @Test
    fun frequentHealthChecksDoNotPostponeRecoveryAlarm() {
        val deadline = RecoveryAlarmDeadline()
        assertEquals(30_000L, deadline.reserve(now = 0, delayMs = 30_000))
        for (now in 5_000L..25_000L step 5_000L) {
            assertNull(deadline.reserve(now, delayMs = 30_000))
        }
    }

    @Test
    fun earlierRecoveryReplacesLaterHeartbeat() {
        val deadline = RecoveryAlarmDeadline()
        deadline.reserve(now = 0, delayMs = 30_000)
        assertEquals(11_000L, deadline.reserve(now = 10_000, delayMs = 1_000))
        assertNull(deadline.reserve(now = 10_000, delayMs = 30_000))
    }

    @Test
    fun delayedIdleAlarmIsNotPushedBackBeforeDelivery() {
        val deadline = RecoveryAlarmDeadline()
        deadline.reserve(now = 0, delayMs = 30_000)
        assertNull(deadline.reserve(now = 90_000, delayMs = 30_000))
        deadline.clear()
        assertEquals(120_000L, deadline.reserve(now = 90_000, delayMs = 30_000))
    }

    @Test
    fun cancelOrDeliveryAllowsNewReservation() {
        val deadline = RecoveryAlarmDeadline()
        deadline.reserve(now = 0, delayMs = 30_000)
        deadline.clear()
        assertEquals(40_000L, deadline.reserve(now = 10_000, delayMs = 30_000))
    }

    @Test
    fun invalidImmediateDelayStillHasBoundedMinimum() {
        val deadline = RecoveryAlarmDeadline()
        assertEquals(2_000L, deadline.reserve(now = 1_000, delayMs = -1_000))
    }
}
