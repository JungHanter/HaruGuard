package com.hanter.haruguard

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSessionTest {
    private val published = mutableListOf<VpnStatus>()
    private val session = VpnSession(published::add) { it.javaClass.simpleName }

    @Test fun stopClosesVpnAndFinishesWithoutWaitingForServiceDestruction() {
        var closed = false
        lateinit var tunnel: Closeable
        tunnel = Closeable {
            assertEquals(VpnPhase.STOPPING, session.status.phase)
            assertFalse(session.isCurrent(tunnel))
            session.recordResolveError(tunnel) // A cancellation callback during teardown.
            closed = true
        }
        session.starting()
        session.connected(tunnel)

        session.stop()

        assertTrue(closed)
        assertFalse(session.isRunning)
        assertEquals(VpnStatus(), session.status)
        assertEquals(listOf(VpnPhase.STARTING, VpnPhase.FILTERING, VpnPhase.STOPPING, VpnPhase.STOPPED),
            published.map { it.phase })
    }

    @Test fun sameBoundServiceCanStartAndStopRepeatedly() {
        var closedCount = 0
        repeat(5) {
            session.starting()
            session.connected(Closeable { closedCount++ })
            assertTrue(session.isRunning)
            session.stop()
            assertEquals(VpnPhase.STOPPED, session.status.phase)
        }
        assertEquals(5, closedCount)
    }

    @Test fun subsequentServiceDestructionDoesNotCloseConnectionTwice() {
        var closedCount = 0
        session.connected(Closeable { closedCount++ })
        session.stop()
        session.stop(session.status.error)
        assertEquals(1, closedCount)
        assertEquals(VpnStatus(), session.status)
    }

    @Test fun closeFailureExitsStoppingStateAndAllowsRetry() {
        session.connected(Closeable { throw IOException("close failed") })
        session.stop()
        assertEquals(VpnPhase.ERROR, session.status.phase)
        assertEquals("IOException", session.status.error)
        assertFalse(session.isRunning)

        session.starting()
        session.connected(Closeable {})
        assertEquals(VpnPhase.FILTERING, session.status.phase)
    }

    @Test fun tunnelFailureClosesImmediatelyAndRemainsVisibleAfterDestruction() {
        var closed = false
        session.connected(Closeable { closed = true })
        session.stop("network failure")
        assertTrue(closed)
        assertEquals(VpnPhase.ERROR, session.status.phase)
        session.stop(session.status.error)
        assertEquals("network failure", session.status.error)
    }

    @Test fun oldDnsCallbacksCannotChangeRestartedSession() {
        val old = Closeable {}
        session.connected(old)
        session.recordBlocked(old)
        assertEquals(1, session.status.blockedQueries)
        session.stop()
        val current = Closeable {}
        session.starting()
        session.connected(current)

        session.recordBlocked(old)
        session.recordResolveError(old)
        assertFalse(session.isCurrent(old))
        assertEquals(VpnStatus(phase = VpnPhase.FILTERING), session.status)
        session.recordBlocked(current)
        session.recordResolveError(current)
        assertEquals(1, session.status.blockedQueries)
        assertEquals(1, session.status.failedQueries)
    }

    @Test fun stopWithoutTunnelClearsStartingOrErrorState() {
        session.starting()
        session.stop()
        assertEquals(VpnStatus(), session.status)
        session.stop("start failed")
        session.stop()
        assertEquals(VpnStatus(), session.status)
    }
}
