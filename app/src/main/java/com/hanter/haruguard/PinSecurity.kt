package com.hanter.haruguard

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN·복구 코드는 평문으로 보관하지 않고 서로 다른 salt로 검증값만 만든다. */
internal object PinSecurity {
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private val random = SecureRandom()

    fun isValidPin(pin: String): Boolean = pin.length in 6..12 && pin.all { it in '0'..'9' }

    fun newRecoveryCode(): String = ByteArray(16).also(random::nextBytes)
        .toHex().uppercase(Locale.ROOT).chunked(4).joinToString("-")

    fun normalizeRecoveryCode(code: String): String? {
        val normalized = code.filterNot { it == '-' || it.isWhitespace() }.uppercase(Locale.ROOT)
        return normalized.takeIf { it.length == 32 && it.all { char -> char in '0'..'9' || char in 'A'..'F' } }
    }

    fun createVerifier(secret: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(secret, salt)
        return try {
            "1:$ITERATIONS:${salt.toHex()}:${hash.toHex()}"
        } finally {
            hash.fill(0)
            salt.fill(0)
        }
    }

    fun verify(secret: String, verifier: String): Boolean {
        val parts = verifier.split(':')
        if (parts.size != 4 || parts[0] != "1" || parts[1] != ITERATIONS.toString()) return false
        val salt = parts[2].fromHex(SALT_BYTES) ?: return false
        val expected = parts[3].fromHex(HASH_BYTES) ?: return false
        val actual = derive(secret, salt)
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
            expected.fill(0)
            salt.fill(0)
        }
    }

    private fun derive(secret: String, salt: ByteArray): ByteArray {
        val chars = secret.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, HASH_BYTES * 8)
        chars.fill('\u0000')
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.fromHex(expectedBytes: Int): ByteArray? {
        if (length != expectedBytes * 2 || any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(expectedBytes) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}

internal data class PinThrottleState(
    val failures: Int = 0,
    val bootCount: Int = -1,
    val lastElapsed: Long = 0,
    val blockedUntilElapsed: Long = 0,
    val delayMillis: Long = 0,
)

/** 벽시계를 사용하지 않는다. 재부팅으로 단조 시계가 바뀌면 기존 지연을 다시 부여한다. */
internal object PinThrottle {
    const val MAX_DELAY_MILLIS = 30 * 60_000L

    fun current(state: PinThrottleState, elapsed: Long, bootCount: Int): PinThrottleState {
        if (state.delayMillis <= 0) return state.copy(bootCount = bootCount, lastElapsed = elapsed)
        val changedBoot = state.bootCount != bootCount || elapsed < state.lastElapsed
        val delay = state.delayMillis.coerceAtMost(MAX_DELAY_MILLIS)
        if (changedBoot) {
            return state.copy(bootCount = bootCount, lastElapsed = elapsed,
                blockedUntilElapsed = elapsed + delay, delayMillis = delay)
        }
        return if (elapsed >= state.blockedUntilElapsed) {
            state.copy(lastElapsed = elapsed, blockedUntilElapsed = 0, delayMillis = 0)
        } else {
            state.copy(lastElapsed = elapsed)
        }
    }

    fun remainingMillis(state: PinThrottleState, elapsed: Long): Long =
        if (state.delayMillis > 0) (state.blockedUntilElapsed - elapsed).coerceIn(0, MAX_DELAY_MILLIS) else 0

    fun failedAttempt(state: PinThrottleState, elapsed: Long, bootCount: Int): PinThrottleState {
        val failures = state.failures.coerceIn(0, 30) + 1
        val delay = if (failures < 5) 0 else (30_000L shl (failures - 5).coerceAtMost(6))
            .coerceAtMost(MAX_DELAY_MILLIS)
        return PinThrottleState(failures, bootCount, elapsed,
            if (delay > 0) elapsed + delay else 0, delay)
    }
}

/** 인증과 유지보수 권한은 디스크에 저장하지 않는다. 프로세스 재생성 시 항상 잠긴다. */
internal class ParentSession(private val elapsedRealtime: () -> Long) {
    private var authenticatedUntil = 0L
    private var maintenanceUntil = 0L
    private var generation = 0L

    @Synchronized fun generation(): Long = generation

    @Synchronized fun authenticate(expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration) return false
        authenticatedUntil = elapsedRealtime() + AUTH_DURATION_MILLIS
        return true
    }

    @Synchronized fun isAuthenticated(): Boolean {
        val now = elapsedRealtime()
        // 부모가 연 5분 동안은 설정 앱을 다녀와도 앱 내부 설정을 계속 사용할 수 있다.
        return authenticatedUntil > now || maintenanceUntil > now
    }

    @Synchronized fun isAuthenticatedGeneration(expectedGeneration: Long): Boolean =
        generation == expectedGeneration && isAuthenticated()

    @Synchronized fun openMaintenance(): Boolean {
        val now = elapsedRealtime()
        if (authenticatedUntil <= now && maintenanceUntil <= now) return false
        // 설정 버튼을 다시 눌러도 이미 시작한 개방 시간을 연장하지 않는다.
        if (maintenanceUntil <= now) maintenanceUntil = now + MAINTENANCE_DURATION_MILLIS
        authenticatedUntil = maintenanceUntil
        return true
    }

    @Synchronized fun maintenanceRemainingMillis(): Long =
        (maintenanceUntil - elapsedRealtime()).coerceAtLeast(0)

    @Synchronized fun lockAuthenticationOnly() {
        authenticatedUntil = 0
        generation++
    }

    @Synchronized fun lock() {
        lockAuthenticationOnly()
        maintenanceUntil = 0
    }

    companion object {
        const val AUTH_DURATION_MILLIS = 2 * 60_000L
        const val MAINTENANCE_DURATION_MILLIS = 5 * 60_000L
    }
}
