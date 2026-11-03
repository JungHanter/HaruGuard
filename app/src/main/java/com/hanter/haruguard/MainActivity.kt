package com.hanter.haruguard

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hanter.haruguard.ui.theme.HaruGuardTheme

class MainActivity : ComponentActivity() {
    private var feedback by mutableStateOf<String?>(null)
    private var awaitingConsent by mutableStateOf(false)

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
                    vpn = VpnRuntime.status,
                    awaitingConsent = awaitingConsent,
                    feedback = feedback,
                    onYouTubeAccess = { allowed -> if (allowed) stopFiltering() else requestFiltering() },
                    onSettings = ::openVpnSettings,
                )
            }
        }
    }

    private fun requestFiltering() {
        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            if (connectivity.getNetworkCapabilities(connectivity.activeNetwork)
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
            // 관리자가 아니어도 시스템 VPN 연결 승인을 받으면 필터를 시작할 수 있다.
            VpnPreferences(this).filteringRequested = true
            VpnRecovery.schedule(this)
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
            VpnPreferences(this).filteringRequested = false
            VpnRecovery.cancel(this)
            // 시스템에 바인딩된 VPN은 stopService()만으로 onDestroy()가 호출되지 않을 수 있다.
            // 명시적 중지 명령에서 VPN 연결을 먼저 닫도록 요청한다.
            startService(Intent(this, YouTubeVpnService::class.java).setAction(YouTubeVpnService.ACTION_STOP))
        } catch (error: Exception) {
            feedback = getString(R.string.vpn_stop_failed, error.javaClass.simpleName)
        }
    }

    private fun openVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

@Composable
private fun P0Screen(
    vpn: VpnStatus,
    awaitingConsent: Boolean,
    feedback: String?,
    onYouTubeAccess: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val busy = awaitingConsent || vpn.phase == VpnPhase.STARTING || vpn.phase == VpnPhase.STOPPING
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.p0_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.p0_description))
            ToggleCard(
                title = stringResource(R.string.youtube_access_title),
                description = stringResource(R.string.youtube_access_description),
                checked = vpn.phase != VpnPhase.FILTERING && vpn.phase != VpnPhase.STOPPING &&
                    vpn.phase != VpnPhase.RECOVERING,
                enabled = !busy,
                onCheckedChange = onYouTubeAccess,
            )
            Text(stringResource(when (vpn.phase) {
                VpnPhase.STARTING -> R.string.vpn_starting
                VpnPhase.FILTERING -> R.string.vpn_filtering
                VpnPhase.RECOVERING -> R.string.vpn_recovering
                VpnPhase.STOPPING -> R.string.vpn_stopping
                VpnPhase.ERROR -> R.string.vpn_error
                VpnPhase.STOPPED -> R.string.vpn_stopped
            }))
            if (vpn.phase == VpnPhase.FILTERING) {
                Text(stringResource(R.string.vpn_query_counts, vpn.blockedQueries, vpn.failedQueries))
            }
            Text(stringResource(R.string.vpn_test_note), style = MaterialTheme.typography.bodySmall)
            feedback?.let { Text(it) }
            vpn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSettings) { Text(stringResource(R.string.open_vpn_settings)) }
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
