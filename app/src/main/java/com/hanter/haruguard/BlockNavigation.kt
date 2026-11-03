package com.hanter.haruguard

/** 뒤로 이동이 실제 화면을 바꾸지 못했을 때만 홈으로 복귀한다. */
internal class BlockNavigation {
    enum class Action { BACK, WAIT, HOME }
    private var attempted = false
    private var attemptedAt = 0L

    fun blocked(now: Long): Action {
        if (!attempted) {
            attempted = true
            attemptedAt = now
            return Action.BACK
        }
        if (now - attemptedAt < 500) return Action.WAIT
        attemptedAt = now
        return Action.HOME
    }

    fun clear() { attempted = false }
}
