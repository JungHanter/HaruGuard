package com.hanter.haruguard

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.hanter.haruguard.ui.theme.HaruGuardTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MainActivity : ComponentActivity() {
    private val controller by lazy { DpcController(this) }
    private var deviceState by mutableStateOf<DpcState?>(null)
    private var feedback by mutableStateOf<String?>(null)
    private var awaitingConsent by mutableStateOf(false)
    private var changingVpnLock by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingConsent = false
        if (result.resultCode == Activity.RESULT_OK) startFiltering()
        else feedback = getString(R.string.vpn_permission_cancelled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HaruGuardTheme {
                P0Screen(
                    state = deviceState,
                    vpn = VpnRuntime.status,
                    awaitingConsent = awaitingConsent,
                    changingVpnLock = changingVpnLock,
                    feedback = feedback,
                    onDnsLock = ::setDnsLock,
                    onVpnLock = ::setVpnLock,
                    onYouTubeAccess = { allowed -> if (allowed) stopFiltering() else requestFiltering() },
                    onSettings = ::openDnsSettings,
                    onRefresh = ::refresh,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        try {
            deviceState = controller.readState()
        } catch (error: Exception) {
            deviceState = null
            feedback = getString(R.string.policy_failed, error.javaClass.simpleName)
        }
    }

    private fun setDnsLock(locked: Boolean) {
        try {
            deviceState = controller.setDnsLocked(locked)
            feedback = getString(if (locked) R.string.dns_locked else R.string.dns_unlocked)
        } catch (error: Exception) {
            feedback = getString(R.string.policy_failed, error.javaClass.simpleName)
            refresh()
        }
    }

    private fun setVpnLock(locked: Boolean) {
        if (changingVpnLock) return
        changingVpnLock = true
        lifecycleScope.launch {
            try {
                val before = controller.readState()
                val resumeFilter = !locked && before.managedVpnPackage == packageName &&
                    VpnRuntime.status.phase == VpnPhase.FILTERING
                deviceState = controller.setVpnLocked(locked)
                feedback = getString(if (locked) R.string.vpn_settings_locked else R.string.vpn_settings_unlocked)
                if (resumeFilter) {
                    // 관리자 always-on 해제 시 VPN 연결 승인이 회수될 수 있다.
                    // 기존 연결을 정리한 뒤 다시 승인받아 실행 중이던 필터를 복구한다.
                    stopFiltering()
                    withTimeout(5_000) {
                        snapshotFlow { VpnRuntime.status.phase }.first {
                            it == VpnPhase.STOPPED || it == VpnPhase.ERROR
                        }
                    }
                    requestFiltering(replacingOwnVpn = true)
                }
            } catch (_: TimeoutCancellationException) {
                feedback = getString(R.string.vpn_settings_restart_pending)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                feedback = getString(R.string.vpn_settings_failed, error.localizedMessage ?: error.javaClass.simpleName)
                refresh()
            } finally {
                changingVpnLock = false
            }
        }
    }

    private fun requestFiltering(replacingOwnVpn: Boolean = false) {
        try {
            val actual = controller.readState()
            deviceState = actual
            if (!actual.isDeviceOwner) {
                feedback = getString(R.string.do_required)
                return
            }
            val connectivity = getSystemService(ConnectivityManager::class.java)
            if (!replacingOwnVpn && connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                feedback = getString(R.string.other_vpn_active)
                return
            }
            val consent = VpnService.prepare(this)
            if (consent != null) {
                awaitingConsent = true
                vpnConsent.launch(consent)
            } else {
                startFiltering()
            }
        } catch (error: Exception) {
            awaitingConsent = false
            feedback = getString(R.string.vpn_failed, error.javaClass.simpleName)
        }
    }

    private fun startFiltering() {
        try {
            // 연결 승인 후 DO를 다시 확인한다. Private DNS 모드는 시작 조건으로 삼지 않는다.
            val actual = controller.readState()
            deviceState = actual
            if (!actual.isDeviceOwner) {
                feedback = getString(R.string.do_required)
                return
            }
            feedback = null
            VpnRuntime.status = VpnStatus(phase = VpnPhase.STARTING)
            startForegroundService(Intent(this, YouTubeVpnService::class.java).setAction(YouTubeVpnService.ACTION_FILTER))
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        } catch (error: Exception) {
            feedback = getString(R.string.vpn_failed, error.javaClass.simpleName)
            VpnRuntime.status = VpnStatus(phase = VpnPhase.ERROR, error = feedback)
        }
    }

    private fun stopFiltering() {
        feedback = null
        try {
            // 시스템에 바인딩된 VPN은 stopService()만으로 onDestroy()가 호출되지 않을 수 있다.
            // 명시적 중지 명령에서 VPN 연결을 먼저 닫도록 요청한다.
            startService(Intent(this, YouTubeVpnService::class.java).setAction(YouTubeVpnService.ACTION_STOP))
        } catch (error: Exception) {
            feedback = getString(R.string.vpn_stop_failed, error.javaClass.simpleName)
        }
    }

    private fun openDnsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

@Composable
private fun P0Screen(
    state: DpcState?,
    vpn: VpnStatus,
    awaitingConsent: Boolean,
    changingVpnLock: Boolean,
    feedback: String?,
    onDnsLock: (Boolean) -> Unit,
    onVpnLock: (Boolean) -> Unit,
    onYouTubeAccess: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val owner = state?.isDeviceOwner == true
    val busy = changingVpnLock || awaitingConsent || vpn.phase == VpnPhase.STARTING || vpn.phase == VpnPhase.STOPPING
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.p0_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.p0_description))
            Text(stringResource(if (owner) R.string.do_registered else R.string.do_required))
            if (!owner) {
                SelectionContainer {
                    Text(
                        "adb shell dpm set-device-owner ${DpcController.ADMIN_COMPONENT}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            ToggleCard(
                title = stringResource(R.string.dns_lock_title),
                description = stringResource(R.string.dns_lock_description),
                checked = state?.ownDnsLock == true,
                enabled = owner,
                onCheckedChange = onDnsLock,
            )
            if (state?.effectiveDnsLock == true && !state.ownDnsLock) {
                Text(stringResource(R.string.other_dns_lock), color = MaterialTheme.colorScheme.error)
            }
            ToggleCard(
                title = stringResource(R.string.vpn_settings_lock_title),
                description = stringResource(R.string.vpn_settings_lock_description),
                checked = state?.ownVpnLock == true,
                enabled = owner && !busy,
                onCheckedChange = onVpnLock,
            )
            if (state?.effectiveVpnLock == true && !state.ownVpnLock) {
                Text(stringResource(R.string.other_vpn_lock), color = MaterialTheme.colorScheme.error)
            }
            if (state?.ownVpnLock == true && state.managedVpnPackage != "com.hanter.haruguard") {
                Text(stringResource(R.string.vpn_lock_incomplete), color = MaterialTheme.colorScheme.error)
            }
            ToggleCard(
                title = stringResource(R.string.youtube_access_title),
                description = stringResource(R.string.youtube_access_description),
                checked = vpn.phase != VpnPhase.FILTERING && vpn.phase != VpnPhase.STOPPING,
                enabled = !busy && (owner || vpn.phase == VpnPhase.FILTERING),
                onCheckedChange = onYouTubeAccess,
            )
            Text(stringResource(when (vpn.phase) {
                VpnPhase.STARTING -> R.string.vpn_starting
                VpnPhase.FILTERING -> R.string.vpn_filtering
                VpnPhase.STOPPING -> R.string.vpn_stopping
                VpnPhase.ERROR -> R.string.vpn_error
                VpnPhase.STOPPED -> R.string.vpn_stopped
            }))
            if (vpn.phase == VpnPhase.FILTERING) {
                Text(stringResource(R.string.vpn_query_counts, vpn.blockedQueries, vpn.failedQueries))
            }
            Text(stringResource(R.string.vpn_test_note), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.private_dns_state, stringResource(when (state?.privateDnsMode) {
                    DevicePolicyManager.PRIVATE_DNS_MODE_OFF -> R.string.dns_mode_off
                    DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC -> R.string.dns_mode_auto
                    DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME -> R.string.dns_mode_host
                    else -> R.string.dns_mode_unknown
                })),
            )
            feedback?.let { Text(it) }
            vpn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSettings) { Text(stringResource(R.string.open_dns_settings)) }
                OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
            }
            Text(stringResource(R.string.p0_security_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }
}
