package com.hanter.haruguard

import android.app.Activity
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.ClipData
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hanter.haruguard.ui.theme.HaruGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ParentUiState(
    val configured: Boolean = false,
    val authenticated: Boolean = false,
    val maintenanceMs: Long = 0,
    val retryMs: Long = 0,
    val filteringRequested: Boolean = false,
    val protection: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val admin: Boolean = false,
    val batteryExempt: Boolean = false,
    val exactAlarm: Boolean = false,
    val notifications: Boolean = false,
)

internal enum class ParentAuthAction { SETUP, LOGIN, RECOVER, CHANGE_PIN }
internal enum class ParentSetting { ADMIN, ACCESSIBILITY, VPN, BATTERY, EXACT_ALARM, NOTIFICATIONS, ALL }

class MainActivity : ComponentActivity() {
    private var ui by mutableStateOf(ParentUiState())
    private var feedback by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var awaitingConsent by mutableStateOf(false)
    private var recoveryCode by mutableStateOf<String?>(null)
    private var consentRequestedByParent = false
    private var consentOpenedMaintenance = false
    private val updater by lazy { AppUpdater.get(this) }

    private val updateSourcePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // 설정 화면의 결과 코드 대신 실제 허용 상태를 읽는다. 복귀만으로 설치를 자동 실행하지 않는다.
        feedback = if (runCatching { packageManager.canRequestPackageInstalls() }.getOrDefault(false))
            "설치 출처가 허용되었습니다. 아래 ‘설치하기’를 다시 눌러 주세요."
        else "설치 출처가 허용되지 않았습니다. 부모가 Family Link·기기 정책과 ‘이 출처 허용’을 확인해 주세요."
        updater.showInstallMessage(feedback.orEmpty())
        refreshUi()
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingConsent = false
        val authorized = consentRequestedByParent && ParentAccess.isMaintenanceOpen()
        consentRequestedByParent = false
        if (result.resultCode == Activity.RESULT_OK && authorized) startFiltering()
        else feedback = if (authorized) getString(R.string.vpn_permission_cancelled) else "부모 인증 후 VPN을 다시 켜 주세요."
        // VPN 동의 때문에 새로 연 개방만 닫고, 부모가 이미 열어 둔 5분은 유지한다.
        if (consentOpenedMaintenance) ParentAccess.lock()
        consentOpenedMaintenance = false
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ParentAccess.initialize(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        refreshUi()
        setContent {
            HaruGuardTheme {
                ProtectionScreen(ui, VpnRuntime.status, VpnRuntime.isAlwaysOn, VpnRuntime.isLockdownEnabled,
                    busy || awaitingConsent, feedback, recoveryCode,
                    onAuth = ::authenticate,
                    onVpn = { if (requireParent()) { if (it) requestFiltering() else stopFiltering() } },
                    onProtection = ::setProtection,
                    onSettings = ::openParentSettings,
                    onLock = { ParentAccess.lock(); recoveryCode = null; refreshUi() },
                    onRecoveryCode = ::rotateRecoveryCode,
                    onRemoveProtection = ::prepareRemoval,
                    update = updater.state,
                    onUpdateDownload = { if (requireParent()) updater.download(it) },
                    onUpdateCancel = { if (requireParent()) updater.cancel() },
                    onUpdateInstall = ::installUpdate)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val retry = if (busy) ui.retryMs else withContext(Dispatchers.IO) { ParentAccess.retryDelayMillis(this@MainActivity) }
                    refreshUi(retry)
                    delay(1_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        if (ui.filteringRequested) VpnRecovery.request(this)
    }

    override fun onStop() {
        // 일반 인증은 앱 이탈 시 끝내되, 부모가 연 5분 동안은 재입력 없이 돌아온다.
        ParentAccess.lockAuthenticationOnly()
        recoveryCode = null
        refreshUi()
        super.onStop()
    }

    private fun refreshUi(retry: Long = ui.retryMs) {
        runCatching {
            ui = ParentUiState(
                configured = ParentAccess.hasPin(this), authenticated = ParentAccess.isAuthenticated(),
                maintenanceMs = ParentAccess.maintenanceRemainingMillis(), retryMs = retry,
                filteringRequested = VpnPreferences(this).filteringRequested,
                protection = ParentAccess.isProtectionEnabled(this),
                accessibilityEnabled = ProtectionAccessibilityService.isEnabled(this),
                accessibilityConnected = ProtectionAccessibilityService.connected,
                admin = HaruGuardAdminReceiver.isActive(this),
                batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
                exactAlarm = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
                notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
            )
        }.onFailure { feedback = "상태 확인 실패: ${it.javaClass.simpleName}" }
    }

    private fun requireParent(): Boolean {
        if (ParentAccess.isAuthenticated()) return true
        feedback = "부모 PIN으로 먼저 인증해 주세요."
        refreshUi()
        return false
    }

    private fun authenticate(action: ParentAuthAction, pin: String, confirmation: String, recovery: String) {
        if (busy) return
        if (action != ParentAuthAction.LOGIN && pin != confirmation) {
            feedback = "입력한 PIN 두 개가 다릅니다."
            return
        }
        busy = true
        feedback = null
        lifecycleScope.launch {
            try {
                if (action == ParentAuthAction.SETUP) {
                    val result = withContext(Dispatchers.Default) { ParentAccess.setup(this@MainActivity, pin) }
                    if (result.success) {
                        recoveryCode = result.recoveryCode
                        feedback = "복구 코드를 부모만 볼 수 있는 곳에 기록하세요. 다시 표시하지 않습니다."
                    } else feedback = authError(result.error)
                } else {
                    val result = withContext(Dispatchers.Default) {
                        when (action) {
                            ParentAuthAction.LOGIN -> ParentAccess.authenticate(this@MainActivity, pin)
                            ParentAuthAction.RECOVER -> ParentAccess.resetPin(this@MainActivity, recovery, pin)
                            ParentAuthAction.CHANGE_PIN -> ParentAccess.changePin(this@MainActivity, pin)
                            else -> error("지원하지 않는 인증 작업")
                        }
                    }
                    feedback = if (result.success) "부모 인증 완료" else authError(result.error)
                }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    ParentAccess.lockAuthenticationOnly()
                    recoveryCode = null
                }
            } finally {
                busy = false
                refreshUi(withContext(Dispatchers.IO) { ParentAccess.retryDelayMillis(this@MainActivity) })
            }
        }
    }

    private fun rotateRecoveryCode() {
        if (!requireParent() || busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { ParentAccess.rotateRecoveryCode(this@MainActivity) }
                if (result.success && ParentAccess.isAuthenticated()) {
                    recoveryCode = result.recoveryCode
                    feedback = "이전 복구 코드는 무효입니다. 새 코드를 부모만 보관하세요."
                } else feedback = authError(result.error)
            } finally { busy = false; refreshUi() }
        }
    }

    private fun setProtection(enabled: Boolean) {
        if (!requireParent()) return
        if (enabled && (!ProtectionAccessibilityService.connected || !HaruGuardAdminReceiver.isActive(this))) {
            feedback = "일반 기기 관리자와 접근성 설정 보호를 먼저 활성화해 주세요."
            return
        }
        if (ParentAccess.setProtectionEnabled(this, enabled)) {
            if (enabled) ParentAccess.lock()
            feedback = if (enabled) "보호를 켰습니다. 설정 변경은 부모 인증이 필요합니다." else "설정 화면 보호를 껐습니다. 일반 관리자와 VPN은 유지됩니다."
        } else feedback = "보호 설정을 저장하지 못했습니다. 다시 인증해 주세요."
        refreshUi()
    }

    private fun requestFiltering() {
        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val anotherVpn = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                VpnRuntime.status.phase != VpnPhase.FILTERING && VpnRuntime.status.phase != VpnPhase.RECOVERING
            if (anotherVpn) {
                feedback = getString(R.string.other_vpn_active)
                return
            }
            val consent = VpnService.prepare(this)
            if (consent != null) {
                consentOpenedMaintenance = !ParentAccess.isMaintenanceOpen()
                if (!ParentAccess.openMaintenance()) return
                consentRequestedByParent = true
                awaitingConsent = true
                vpnConsent.launch(consent)
            } else startFiltering()
        } catch (error: Exception) {
            if (consentOpenedMaintenance) ParentAccess.lock()
            consentOpenedMaintenance = false
            consentRequestedByParent = false
            awaitingConsent = false
            feedback = getString(R.string.vpn_failed, error.javaClass.simpleName)
        }
    }

    private fun startFiltering() {
        try {
            VpnPreferences(this).filteringRequested = true
            VpnRecovery.schedule(this)
            VpnRuntime.status = VpnStatus(phase = VpnPhase.STARTING)
            startForegroundService(Intent(this, YouTubeVpnService::class.java).setAction(YouTubeVpnService.ACTION_FILTER))
            feedback = null
        } catch (error: Exception) {
            feedback = getString(R.string.vpn_failed, error.javaClass.simpleName)
            VpnRuntime.status = VpnStatus(phase = VpnPhase.ERROR, error = feedback)
        }
        refreshUi()
    }

    private fun stopFiltering(): Boolean {
        var stopped = false
        try {
            VpnPreferences(this).filteringRequested = false
            VpnRecovery.cancel(this)
            startService(Intent(this, YouTubeVpnService::class.java).setAction(YouTubeVpnService.ACTION_STOP))
            stopped = true
            feedback = "부모가 VPN을 껐습니다. 자동 복구도 중지했습니다."
        } catch (error: Exception) { feedback = getString(R.string.vpn_stop_failed, error.javaClass.simpleName) }
        refreshUi()
        return stopped
    }

    private fun openParentSettings(setting: ParentSetting) {
        if (!requireParent() || !ParentAccess.openMaintenance()) return
        val intent = when (setting) {
            ParentSetting.ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, HaruGuardAdminReceiver.component(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_description))
            ParentSetting.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            ParentSetting.VPN -> Intent(Settings.ACTION_VPN_SETTINGS)
            ParentSetting.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            ParentSetting.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) else Intent(Settings.ACTION_SETTINGS)
            ParentSetting.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            ParentSetting.ALL -> Intent(Settings.ACTION_SETTINGS)
        }
        try {
            startActivity(intent)
            feedback = "부모 설정 개방 중입니다. 끝나면 앱의 ‘지금 잠그기’를 누르세요. 남은 시간은 위에서 확인할 수 있습니다."
        } catch (error: Exception) {
            ParentAccess.lock()
            feedback = "설정을 열지 못했습니다. 보호를 다시 잠갔습니다: ${error.javaClass.simpleName}"
        }
        refreshUi()
    }

    private fun prepareRemoval() {
        if (!requireParent()) return
        if (!ParentAccess.setProtectionEnabled(this, false)) {
            feedback = "보호 해제 저장에 실패했습니다."
            return
        }
        ParentAccess.openMaintenance()
        val failures = mutableListOf<String>()
        if (!stopFiltering()) failures += "VPN 중지"
        runCatching {
            getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(HaruGuardAdminReceiver.component(this))
        }.onFailure { failures += "관리자 해제 (${it.javaClass.simpleName})" }
        runCatching {
            if (ProtectionAccessibilityService.isEnabled(this) && !ProtectionAccessibilityService.disableForParent()) {
                failures += "접근성 해제"
            }
        }.onFailure { failures += "접근성 해제 (${it.javaClass.simpleName})" }
        feedback = if (failures.isEmpty())
            "설정 보호를 끄고 VPN·관리자·접근성 해제를 요청했습니다. 아래 상태가 꺼짐으로 바뀌었는지 확인하세요. 기존 ADB 삭제 금지가 남아 있으면 별도 원복이 필요합니다."
        else "일부 해제를 완료하지 못했습니다: ${failures.joinToString()}. 부모 설정 개방 중 직접 확인해 주세요."
        refreshUi()
    }

    private fun installUpdate() {
        if (!requireParent() || busy) return
        busy = true
        feedback = null
        lifecycleScope.launch {
            try {
                val file = updater.fileForInstall() ?: return@launch
                // 파일 검사 중 잠겼거나 앱을 떠났다면 외부 화면을 새로 띄우지 않는다.
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !requireParent()) return@launch
                if (!ParentAccess.openMaintenance()) return@launch
                if (!packageManager.canRequestPackageInstalls()) {
                    updateSourcePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:$packageName".toUri()))
                    feedback = "HaruGuard의 ‘이 출처 허용’을 켠 뒤 돌아와 ‘설치하기’를 다시 누르세요. 부모 설정은 최대 5분간 개방됩니다."
                } else {
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.updates", file)
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        clipData = ClipData.newRawUri("HaruGuard update", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    feedback = "설치 화면에서 업데이트를 승인해 주세요. 취소했다면 ‘설치하기’로 다시 열 수 있습니다. 끝나면 ‘지금 잠그기’를 누르세요."
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                feedback = "설치 화면을 열지 못했습니다. Family Link·기기 설치 제한을 확인해 주세요. (${error.javaClass.simpleName})"
            } finally {
                busy = false
                feedback?.let(updater::showInstallMessage)
                refreshUi()
            }
        }
    }

    private fun authError(error: AuthError?): String = when (error) {
        AuthError.INVALID_PIN -> "PIN을 확인하세요. PIN은 숫자 6~12자리입니다."
        AuthError.INVALID_RECOVERY -> "복구 코드가 일치하지 않습니다."
        AuthError.LOCKED_OUT -> "인증 시도가 너무 많습니다. 표시된 시간이 지난 뒤 다시 시도하세요."
        AuthError.DEVICE_LOCKED -> "화면 잠금을 해제하고 다시 인증해 주세요."
        AuthError.NOT_AUTHENTICATED -> "부모 인증이 만료되었습니다. 다시 인증해 주세요."
        AuthError.ALREADY_CONFIGURED -> "이미 PIN이 설정되어 있습니다."
        AuthError.NOT_CONFIGURED -> "부모 PIN 설정이 필요합니다."
        else -> "인증 정보를 저장하거나 확인하지 못했습니다."
    }
}
