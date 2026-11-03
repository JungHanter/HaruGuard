package com.hanter.haruguard

import java.io.Closeable

enum class VpnPhase { STOPPED, STARTING, FILTERING, RECOVERING, STOPPING, ERROR }

data class VpnStatus(
    val phase: VpnPhase = VpnPhase.STOPPED,
    val blockedQueries: Int = 0,
    val failedQueries: Int = 0,
    val error: String? = null,
)

/** 메인 스레드에서 VPN 연결 수명을 관리한다. 서비스 바인딩 수명과는 구분한다. */
internal class VpnSession(
    private val publish: (VpnStatus) -> Unit,
    private val describeFailure: (Exception) -> String,
) {
    @Volatile private var connection: Closeable? = null
    val currentConnection: Closeable? get() = connection
    var status = VpnStatus()
        private set
    val isRunning: Boolean get() = connection != null

    fun starting() {
        check(!isRunning)
        update(VpnStatus(phase = VpnPhase.STARTING))
    }

    fun connected(connection: Closeable) {
        check(!isRunning)
        this.connection = connection
        update(VpnStatus(phase = VpnPhase.FILTERING))
    }

    fun isCurrent(connection: Closeable): Boolean = this.connection === connection

    fun recovering(message: String) {
        check(!isRunning)
        update(VpnStatus(phase = VpnPhase.RECOVERING, error = message))
    }

    fun recordBlocked(connection: Closeable) {
        if (isCurrent(connection)) update(status.copy(blockedQueries = status.blockedQueries + 1))
    }

    fun recordResolveError(connection: Closeable) {
        if (isCurrent(connection)) update(status.copy(failedQueries = status.failedQueries + 1))
    }

    fun stop(error: String? = null) {
        val previous = connection
        // 연결을 먼저 무효화해 취소 중 늦게 도착하는 응답·오류가 새 상태를 바꾸지 못하게 한다.
        connection = null
        if (previous != null) update(status.copy(phase = VpnPhase.STOPPING))
        var failure = error
        try {
            previous?.close()
        } catch (closeError: Exception) {
            if (failure == null) failure = describeFailure(closeError)
        }
        update(if (failure == null) VpnStatus() else VpnStatus(phase = VpnPhase.ERROR, error = failure))
    }

    private fun update(status: VpnStatus) {
        this.status = status
        publish(status)
    }
}
