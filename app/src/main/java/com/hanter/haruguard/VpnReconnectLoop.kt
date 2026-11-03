package com.hanter.haruguard

/** 종료된 예약은 무효화하며, 연결이 반복 실패하면 재시도 간격을 늘린다. */
internal class VpnReconnectLoop(
    private val now: () -> Long,
    private val shouldRun: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val connect: () -> Unit,
) {
    private var generation = 0L
    private var failures = 0
    private var connectedAt: Long? = null

    fun connected() {
        invalidate()
        connectedAt = now()
    }

    fun retry(): Long {
        connectedAt?.let { if (now() - it >= STABLE_CONNECTION_MS) failures = 0 }
        connectedAt = null
        invalidate()
        val delay = (1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000L)
        failures = (failures + 1).coerceAtMost(6)
        val ticket = generation
        schedule(delay) {
            if (ticket == generation && shouldRun()) connect()
        }
        return delay
    }

    fun stop() {
        invalidate()
        failures = 0
        connectedAt = null
    }

    private fun invalidate() {
        generation++
        cancelScheduled()
    }

    companion object {
        private const val STABLE_CONNECTION_MS = 60_000L
    }
}
