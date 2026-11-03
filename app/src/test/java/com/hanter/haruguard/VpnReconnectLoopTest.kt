package com.hanter.haruguard

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnReconnectLoopTest {
    private var time = 0L
    private var requested = true
    private var connections = 0
    private var cancelled = 0
    private val pending = mutableListOf<() -> Unit>()
    private val loop = VpnReconnectLoop(
        now = { time },
        shouldRun = { requested },
        schedule = { _, action -> pending += action },
        cancelScheduled = { cancelled++ },
        connect = { connections++ },
    )

    @Test fun manualStopInvalidatesEvenAnAlreadyDispatchedRetry() {
        loop.retry()
        val lateCallback = pending.single()
        requested = false
        loop.stop()
        lateCallback()
        assertEquals(0, connections)
        assertEquals(2, cancelled)
    }

    @Test fun newerFailureDoesNotAllowOlderRetryToOpenAnotherTunnel() {
        loop.retry()
        loop.retry()
        pending.forEach { it() }
        assertEquals(1, connections)
    }

    @Test fun permissionWithdrawalPreventsPendingReconnect() {
        loop.retry()
        requested = false
        pending.single()()
        assertEquals(0, connections)
    }

    @Test fun repeatedFailuresAndShortLivedConnectionsBackOffWithoutOverflow() {
        val delays = (1..10).map {
            val delay = loop.retry()
            loop.connected()
            time += 100
            delay
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L,
            30_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test fun stableConnectionRestoresFastRecoveryForNextFailure() {
        repeat(5) { loop.retry() }
        loop.connected()
        time += 60_000
        assertEquals(1_000L, loop.retry())
    }

    @Test fun successfulConnectionInvalidatesPendingRecovery() {
        loop.retry()
        loop.connected()
        pending.single()()
        assertEquals(0, connections)
    }
}
