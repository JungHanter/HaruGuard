package com.hanter.haruguard

/** 반복 감시가 이미 예약된 복구 시점을 계속 뒤로 미루지 않도록 한다. */
internal class RecoveryAlarmDeadline {
    private var scheduledAt: Long? = null

    @Synchronized
    fun reserve(now: Long, delayMs: Long): Long? {
        val candidate = now + delayMs.coerceAtLeast(1_000)
        // Doze 등으로 만료 시각보다 늦게 전달되어도 실제 수신 전에는 예약을 보존한다.
        if (scheduledAt?.let { it <= candidate } == true) return null
        scheduledAt = candidate
        return candidate
    }

    @Synchronized
    fun clear() {
        scheduledAt = null
    }
}
