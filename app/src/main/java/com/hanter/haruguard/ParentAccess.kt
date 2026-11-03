package com.hanter.haruguard

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat

internal enum class AuthError {
    INVALID_PIN, INVALID_RECOVERY, NOT_CONFIGURED, ALREADY_CONFIGURED,
    LOCKED_OUT, DEVICE_LOCKED, STORAGE_ERROR, NOT_AUTHENTICATED,
}

internal data class AuthResult(
    val success: Boolean,
    val error: AuthError? = null,
    val remainingDelayMillis: Long = 0,
)

internal data class SetupResult(
    val success: Boolean,
    val recoveryCode: String? = null,
    val error: AuthError? = null,
)

/** 부모 인증은 앱 내부 권한이다. 일반 앱에 Device Owner 권한을 부여하지 않는다. */
internal object ParentAccess {
    private const val SECRETS_NAME = "parent_access_secrets"
    private const val STATE_NAME = "parent_access_state"
    private const val PIN_VERIFIER = "pin_verifier"
    private const val RECOVERY_VERIFIER = "recovery_verifier"
    private val credentialLock = Any()
    private val session = ParentSession(SystemClock::elapsedRealtime)
    @Volatile private var applicationContext: Context? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) lock()
        }
    }

    @Synchronized fun initialize(context: Context) {
        if (applicationContext != null) return
        val app = context.applicationContext
        ContextCompat.registerReceiver(app, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        applicationContext = app
    }

    fun isCredentialStorageUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java).isUserUnlocked

    fun hasPin(context: Context): Boolean {
        initialize(context)
        if (!isCredentialStorageUnlocked(context)) return false
        return try {
            // 손상된 검증값도 초기 설정으로 덮어쓰지 않는다.
            secrets(context).contains(PIN_VERIFIER)
        } catch (_: RuntimeException) {
            true
        }
    }

    fun isProtectionEnabled(context: Context): Boolean {
        initialize(context)
        return try {
            state(context).getBoolean("protection_enabled", false)
        } catch (_: RuntimeException) {
            // 보호 상태를 읽지 못했다고 설정 화면 보호를 임의로 해제하지 않는다.
            true
        }
    }

    fun isAuthenticated(): Boolean = isScreenAvailable() && session.isAuthenticated()

    fun isMaintenanceOpen(): Boolean = maintenanceRemainingMillis() > 0

    fun maintenanceRemainingMillis(): Long =
        if (isScreenAvailable()) session.maintenanceRemainingMillis() else 0

    fun lock() = session.lock()

    fun lockAuthenticationOnly() = session.lockAuthenticationOnly()

    fun openMaintenance(): Boolean = isScreenAvailable() && session.openMaintenance()

    fun setProtectionEnabled(context: Context, enabled: Boolean): Boolean {
        initialize(context)
        if (!isAuthenticated() || !hasPin(context)) return false
        return try {
            state(context).edit().putBoolean("protection_enabled", enabled).commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    /** PBKDF2가 UI를 막지 않도록 작업 스레드에서 호출한다. 복구 코드는 이 결과로 한 번만 전달한다. */
    fun setup(context: Context, pin: String): SetupResult = synchronized(credentialLock) {
        initialize(context)
        if (!isCredentialStorageUnlocked(context)) return@synchronized SetupResult(false, error = AuthError.DEVICE_LOCKED)
        if (!PinSecurity.isValidPin(pin)) return@synchronized SetupResult(false, error = AuthError.INVALID_PIN)
        val generation = session.generation()
        try {
            val preferences = secrets(context)
            if (preferences.contains(PIN_VERIFIER)) return@synchronized SetupResult(false, error = AuthError.ALREADY_CONFIGURED)
            val recoveryCode = PinSecurity.newRecoveryCode()
            val pinVerifier = PinSecurity.createVerifier(pin)
            val recoveryVerifier = PinSecurity.createVerifier(requireNotNull(PinSecurity.normalizeRecoveryCode(recoveryCode)))
            val saved = clearThrottle(preferences.edit()).putString(PIN_VERIFIER, pinVerifier)
                .putString(RECOVERY_VERIFIER, recoveryVerifier).commit()
            if (!saved) return@synchronized SetupResult(false, error = AuthError.STORAGE_ERROR)
            if (isScreenAvailable()) session.authenticate(generation)
            SetupResult(true, recoveryCode)
        } catch (_: Exception) {
            SetupResult(false, error = AuthError.STORAGE_ERROR)
        }
    }

    /** 실패 시도를 해시 검증 전에 저장해 프로세스 종료로 시도 제한을 회피하지 못하게 한다. */
    fun authenticate(context: Context, pin: String): AuthResult = synchronized(credentialLock) {
        initialize(context)
        if (!isCredentialStorageUnlocked(context) || !isScreenAvailable()) {
            return@synchronized AuthResult(false, AuthError.DEVICE_LOCKED)
        }
        val generation = session.generation()
        verifyCredential(context, pin, PIN_VERIFIER, PinSecurity.isValidPin(pin), AuthError.INVALID_PIN) { preferences ->
            if (!clearThrottle(preferences.edit()).commit()) return@verifyCredential AuthResult(false, AuthError.STORAGE_ERROR)
            if (!isScreenAvailable() || !session.authenticate(generation)) {
                return@verifyCredential AuthResult(false, AuthError.DEVICE_LOCKED)
            }
            AuthResult(true)
        }
    }

    /** 복구 코드 원본은 저장하지 않으며 PIN 재설정 후에도 최초 코드를 계속 사용한다. */
    fun resetPin(context: Context, recoveryCode: String, newPin: String): AuthResult = synchronized(credentialLock) {
        initialize(context)
        if (!isCredentialStorageUnlocked(context) || !isScreenAvailable()) {
            return@synchronized AuthResult(false, AuthError.DEVICE_LOCKED)
        }
        if (!PinSecurity.isValidPin(newPin)) return@synchronized AuthResult(false, AuthError.INVALID_PIN)
        val generation = session.generation()
        val normalized = PinSecurity.normalizeRecoveryCode(recoveryCode)
        verifyCredential(context, normalized.orEmpty(), RECOVERY_VERIFIER, normalized != null, AuthError.INVALID_RECOVERY) { preferences ->
            val verifier = PinSecurity.createVerifier(newPin)
            if (!clearThrottle(preferences.edit()).putString(PIN_VERIFIER, verifier).commit()) {
                return@verifyCredential AuthResult(false, AuthError.STORAGE_ERROR)
            }
            if (isScreenAvailable()) session.authenticate(generation)
            AuthResult(true)
        }
    }

    /** 기존 PIN을 아는 부모는 복구 코드를 다시 발급해 잃어버린 기존 코드를 무효화할 수 있다. */
    fun rotateRecoveryCode(context: Context): SetupResult = synchronized(credentialLock) {
        initialize(context)
        if (!isCredentialStorageUnlocked(context) || !isScreenAvailable()) {
            return@synchronized SetupResult(false, error = AuthError.DEVICE_LOCKED)
        }
        val generation = session.generation()
        if (!session.isAuthenticatedGeneration(generation)) {
            return@synchronized SetupResult(false, error = AuthError.NOT_AUTHENTICATED)
        }
        try {
            val preferences = secrets(context)
            if (!preferences.contains(PIN_VERIFIER)) return@synchronized SetupResult(false, error = AuthError.NOT_CONFIGURED)
            val code = PinSecurity.newRecoveryCode()
            val verifier = PinSecurity.createVerifier(requireNotNull(PinSecurity.normalizeRecoveryCode(code)))
            // 계산 도중 화면 잠금·앱 이탈·인증 만료가 발생했으면 기존 복구 코드를 유지한다.
            if (!isScreenAvailable() || !session.isAuthenticatedGeneration(generation)) {
                return@synchronized SetupResult(false, error = AuthError.NOT_AUTHENTICATED)
            }
            if (!preferences.edit().putString(RECOVERY_VERIFIER, verifier).commit()) {
                return@synchronized SetupResult(false, error = AuthError.STORAGE_ERROR)
            }
            SetupResult(true, code)
        } catch (_: Exception) {
            SetupResult(false, error = AuthError.STORAGE_ERROR)
        }
    }

    /** 부모 인증 세션에서 PIN만 변경한다. 복구 코드는 계속 사용할 수 있다. */
    fun changePin(context: Context, newPin: String): AuthResult = synchronized(credentialLock) {
        initialize(context)
        if (!isCredentialStorageUnlocked(context) || !isScreenAvailable()) {
            return@synchronized AuthResult(false, AuthError.DEVICE_LOCKED)
        }
        val generation = session.generation()
        if (!session.isAuthenticatedGeneration(generation)) {
            return@synchronized AuthResult(false, AuthError.NOT_AUTHENTICATED)
        }
        if (!PinSecurity.isValidPin(newPin)) return@synchronized AuthResult(false, AuthError.INVALID_PIN)
        try {
            val preferences = secrets(context)
            if (!preferences.contains(PIN_VERIFIER)) return@synchronized AuthResult(false, AuthError.NOT_CONFIGURED)
            val verifier = PinSecurity.createVerifier(newPin)
            if (!isScreenAvailable() || !session.isAuthenticatedGeneration(generation)) {
                return@synchronized AuthResult(false, AuthError.NOT_AUTHENTICATED)
            }
            if (!clearThrottle(preferences.edit()).putString(PIN_VERIFIER, verifier).commit()) {
                return@synchronized AuthResult(false, AuthError.STORAGE_ERROR)
            }
            AuthResult(true)
        } catch (_: Exception) {
            AuthResult(false, AuthError.STORAGE_ERROR)
        }
    }

    fun retryDelayMillis(context: Context): Long = synchronized(credentialLock) {
        if (!isCredentialStorageUnlocked(context)) return@synchronized 0
        try {
            val preferences = secrets(context)
            val elapsed = SystemClock.elapsedRealtime()
            val original = readThrottle(preferences)
            val current = PinThrottle.current(original, elapsed, bootCount(context))
            // 1초 UI 조회마다 쓰지 않고 재부팅/잠금 만료 등 의미 있는 변화만 확정한다.
            if (current.bootCount != original.bootCount || current.delayMillis != original.delayMillis ||
                current.blockedUntilElapsed != original.blockedUntilElapsed) {
                if (!writeThrottle(preferences.edit(), current).commit()) return@synchronized PinThrottle.MAX_DELAY_MILLIS
            }
            PinThrottle.remainingMillis(current, elapsed)
        } catch (_: RuntimeException) {
            PinThrottle.MAX_DELAY_MILLIS
        }
    }

    private fun verifyCredential(
        context: Context,
        candidate: String,
        key: String,
        validFormat: Boolean,
        invalidError: AuthError,
        onSuccess: (SharedPreferences) -> AuthResult,
    ): AuthResult {
        return try {
            val preferences = secrets(context)
            val verifier = preferences.getString(key, null) ?: return AuthResult(false, AuthError.NOT_CONFIGURED)
            val elapsed = SystemClock.elapsedRealtime()
            val boot = bootCount(context)
            val current = PinThrottle.current(readThrottle(preferences), elapsed, boot)
            val remaining = PinThrottle.remainingMillis(current, elapsed)
            if (remaining > 0) {
                if (!writeThrottle(preferences.edit(), current).commit()) return AuthResult(false, AuthError.STORAGE_ERROR)
                return AuthResult(false, AuthError.LOCKED_OUT, remaining)
            }
            val attempted = PinThrottle.failedAttempt(current, elapsed, boot)
            if (!writeThrottle(preferences.edit(), attempted).commit()) return AuthResult(false, AuthError.STORAGE_ERROR)
            if (validFormat && PinSecurity.verify(candidate, verifier)) {
                onSuccess(preferences)
            } else {
                AuthResult(false, invalidError, PinThrottle.remainingMillis(attempted, SystemClock.elapsedRealtime()))
            }
        } catch (_: Exception) {
            AuthResult(false, AuthError.STORAGE_ERROR)
        }
    }

    private fun isScreenAvailable(): Boolean {
        val context = applicationContext ?: return false
        val available = context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!available) session.lock()
        return available
    }

    private fun secrets(context: Context): SharedPreferences {
        // Application의 기본 저장소는 CE다. DP context로 받은 호출도 기본 context로 되돌린다.
        // https://developer.android.com/privacy-and-security/direct-boot
        val credentialContext = context.applicationContext
        check(!credentialContext.isDeviceProtectedStorage)
        return credentialContext.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE)
    }

    private fun state(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(STATE_NAME, Context.MODE_PRIVATE)

    private fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        // 부팅 횟수를 제공하지 않는 기기는 프로세스 재시작마다 잠금 시간을 보수적으로 다시 부여한다.
        unknownBootIdentity
    }

    private val unknownBootIdentity: Int = java.security.SecureRandom().nextInt()

    private fun readThrottle(preferences: SharedPreferences): PinThrottleState = PinThrottleState(
        failures = preferences.getInt("failures", 0),
        bootCount = preferences.getInt("boot_count", -1),
        lastElapsed = preferences.getLong("last_elapsed", 0),
        blockedUntilElapsed = preferences.getLong("blocked_until_elapsed", 0),
        delayMillis = preferences.getLong("delay_millis", 0),
    )

    private fun writeThrottle(editor: SharedPreferences.Editor, throttle: PinThrottleState): SharedPreferences.Editor =
        editor.putInt("failures", throttle.failures).putInt("boot_count", throttle.bootCount)
            .putLong("last_elapsed", throttle.lastElapsed).putLong("blocked_until_elapsed", throttle.blockedUntilElapsed)
            .putLong("delay_millis", throttle.delayMillis)

    private fun clearThrottle(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        writeThrottle(editor, PinThrottleState())
}
