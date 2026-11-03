package com.hanter.haruguard

/** 뒤로 이동이 실제 화면을 바꾸지 못했을 때만 홈으로 복귀한다. */
internal class BlockNavigation {
    enum class Action { BACK, WAIT, HOME }
    data class Target(val windowId: Int, val area: SettingsScreenPolicy.Area? = null, val pageIdentity: Int = 0)
    private var attempted = false
    private var attemptedAt = 0L
    private var attemptedTarget: Target? = null

    fun blocked(now: Long, target: Target = Target(0)): Action {
        // 빠른 탭으로 다른 보호 페이지까지 넘어갔으면 이전 페이지의 대기 시간을 적용하지 않는다.
        if (!attempted || target != attemptedTarget) {
            attempted = true
            attemptedAt = now
            attemptedTarget = target
            return Action.BACK
        }
        if (now - attemptedAt < 180) return Action.WAIT
        attemptedAt = now
        return Action.HOME
    }

    fun clear() { attempted = false; attemptedTarget = null }
}
