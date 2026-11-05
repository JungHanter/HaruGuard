package com.hanter.haruguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ProtectionScreen(
    state: ParentUiState,
    vpn: VpnStatus,
    alwaysOn: Boolean?,
    lockdown: Boolean?,
    busy: Boolean,
    feedback: String?,
    recoveryCode: String?,
    onAuth: (ParentAuthAction, String, String, String) -> Unit,
    onVpn: (Boolean) -> Unit,
    onProtection: (Boolean) -> Unit,
    onSettings: (ParentSetting) -> Unit,
    onLock: () -> Unit,
    onRecoveryCode: () -> Unit,
    onRemoveProtection: () -> Unit,
    update: UpdateUiState,
    onUpdateDownload: (String) -> Unit,
    onUpdateCancel: () -> Unit,
    onUpdateInstall: () -> Unit,
) {
    Scaffold(Modifier.fillMaxSize()) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.p0_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(when (vpn.phase) {
                VpnPhase.STARTING -> R.string.vpn_starting
                VpnPhase.FILTERING -> R.string.vpn_filtering
                VpnPhase.RECOVERING -> R.string.vpn_recovering
                VpnPhase.STOPPING -> R.string.vpn_stopping
                VpnPhase.ERROR -> R.string.vpn_error
                VpnPhase.STOPPED -> R.string.vpn_stopped
            }))
            Text(if (state.protection) {
                if (state.accessibilityConnected && state.admin) "설정 보호 켜짐" else "설정 보호 요청됨 · 권한 확인 필요"
            } else "설정 보호 꺼짐")
            if (state.maintenanceMs > 0) {
                Text("부모 설정 개방: ${state.maintenanceMs / 1_000}초 남음", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onLock) { Text("지금 잠그기") }
            }
            vpn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            feedback?.let { Text(it) }
            if (!state.authenticated) {
                ParentAuthForm(if (state.configured) ParentAuthAction.LOGIN else ParentAuthAction.SETUP,
                    busy, state.retryMs, onAuth)
                Text("설정 변경은 부모 PIN이 필요합니다. 앱 화면을 닫아도 켜 둔 VPN은 계속 실행됩니다.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (recoveryCode != null) {
                Text("부모용 복구 코드", style = MaterialTheme.typography.titleLarge)
                Text(recoveryCode, style = MaterialTheme.typography.headlineSmall)
                Text("PIN을 잊었을 때 사용합니다. 종이나 부모의 안전한 보관함에 기록하세요. 이 기기에 저장하거나 자녀에게 알려주지 마세요. 앱을 나가면 코드 표시가 사라집니다.")
                Button(onClick = onLock) { Text("보관했어요 · 잠그기") }
            } else {
                ParentControls(state, vpn, alwaysOn, lockdown, busy, onAuth, onVpn, onProtection,
                    onSettings, onLock, onRecoveryCode, onRemoveProtection)
                UpdateControls(update, busy, onUpdateDownload, onUpdateCancel, onUpdateInstall)
            }
        }
    }
}

@Composable
private fun UpdateControls(state: UpdateUiState, busy: Boolean, onDownload: (String) -> Unit,
                           onCancel: () -> Unit, onInstall: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    Text("앱 업데이트", style = MaterialTheme.typography.titleLarge)
    Text("OneDrive·Dropbox의 APK 파일 공유 링크 또는 직접 다운로드 HTTPS 링크를 입력하세요. 로그인 없이 다운로드할 수 있어야 합니다.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = link, onValueChange = { link = it.take(8_192) },
        label = { Text("업데이트 APK 링크") }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        enabled = !busy && !state.busy, minLines = 2, maxLines = 4)
    Button(onClick = { onDownload(link) }, enabled = !busy && !state.busy && link.isNotBlank()) {
        Text("다운로드")
    }
    if (state.downloading) {
        val amount = "${state.received / 1_024} KB"
        Text(if (state.total > 0) "다운로드 중 ${(state.received * 100 / state.total).coerceIn(0, 100)}% · $amount" else "다운로드 중 · $amount")
        OutlinedButton(onClick = onCancel, enabled = !busy) { Text("다운로드 취소") }
    } else if (state.checking) Text("APK 확인 중…")
    state.message?.let { Text(it) }
    state.readyVersion?.let { version ->
        Text("설치할 버전: $version")
        Button(onClick = onInstall, enabled = !busy && !state.busy) { Text("설치하기") }
    }
    Text("같은 서명 키의 새 HaruGuard APK만 설치합니다. 설치 권한·업데이트 확인은 Android 화면에서 부모가 승인하세요. 설치 화면을 열면 기존 5분 설정 개방을 사용합니다.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ParentControls(
    state: ParentUiState, vpn: VpnStatus, alwaysOn: Boolean?, lockdown: Boolean?, busy: Boolean,
    onAuth: (ParentAuthAction, String, String, String) -> Unit,
    onVpn: (Boolean) -> Unit, onProtection: (Boolean) -> Unit,
    onSettings: (ParentSetting) -> Unit, onLock: () -> Unit,
    onRecoveryCode: () -> Unit, onRemoveProtection: () -> Unit,
) {
    var showRemove by remember { mutableStateOf(false) }
    var changePin by remember { mutableStateOf(false) }
    var showRecoveryWarning by remember { mutableStateOf(false) }
    Text("부모 관리", style = MaterialTheme.typography.titleLarge)
    Text("일반 인증은 2분간 유효하며 앱을 나가면 다시 PIN을 요구합니다. 부모 설정 개방 5분 동안은 앱을 다녀와도 PIN을 묻지 않습니다. ‘지금 잠그기’·5분 만료·화면 잠금 시 다시 잠깁니다.", style = MaterialTheme.typography.bodySmall)
    ToggleCard("VPN 필터 켜기", "켜 두면 종료·오류 후 복구를 시도합니다. 기존 YouTube 시험 필터를 사용합니다.",
        state.filteringRequested, !busy && vpn.phase != VpnPhase.STARTING && vpn.phase != VpnPhase.STOPPING, onVpn)
    if (state.filteringRequested && vpn.phase == VpnPhase.ERROR) {
        Button(onClick = { onVpn(true) }, enabled = !busy) { Text("VPN 승인·연결 다시 시도") }
    }
    ToggleCard("설정 화면 보호", "HaruGuard의 앱 관리·실행 권한과 관리자·접근성·VPN·초기화 화면을 보호합니다. 개발자옵션이 꺼져 있으면 활성화 경로도 보호합니다. Wi-Fi·밝기·소리 등 일반 설정은 사용할 수 있습니다.",
        state.protection, !busy, onProtection)
    Text("개발자옵션을 켜려면 부모 PIN으로 설정 화면 보호를 먼저 끄세요. 5분 개방만으로는 활성화할 수 없습니다. 사용 후 개발자옵션을 끄고 설정 화면 보호를 다시 켜세요.", style = MaterialTheme.typography.bodySmall)
    Text("앱을 구분할 수 없는 상세 화면이나 목록에서 바로 권한을 바꾸는 경우에는 해당 화면·목록을 보호합니다.", style = MaterialTheme.typography.bodySmall)
    ProtectionRuntime.lastBlock?.let {
        Text("마지막 차단: ${it.area.label} · ${it.scope.label}", style = MaterialTheme.typography.bodySmall)
    }
    Text("처음에는 아래 항목을 설정한 뒤 보호를 켜세요. 설정 버튼으로 5분간 설정 접근을 허용합니다. 개방 중 다른 버튼을 눌러도 남은 시간은 늘어나지 않습니다.")
    StatusButton("1. 일반 기기 관리자", if (state.admin) "활성화됨" else "설정 필요", busy) { onSettings(ParentSetting.ADMIN) }
    StatusButton("2. 접근성 설정 보호", when {
        state.accessibilityConnected -> "연결됨"
        state.accessibilityEnabled -> "허용됨 · 서비스 연결 대기"
        else -> "설정 필요"
    }, busy) { onSettings(ParentSetting.ACCESSIBILITY) }
    Text("접근성은 ‘HaruGuard 설정 보호’를 켜세요. 단축키·접근성 버튼·음량키 바로가기는 끄세요. 제한된 설정 안내가 나오면 부모가 앱 정보의 ‘Allow restricted settings’를 확인해야 합니다. 시스템에서 허용하지 않으면 이 보호는 사용할 수 없습니다.",
        style = MaterialTheme.typography.bodySmall)
    StatusButton("3. 항상 켜진 VPN", when (alwaysOn) { true -> "켜짐"; false -> "꺼짐"; null -> "VPN 실행 후 확인" }, busy) { onSettings(ParentSetting.VPN) }
    Text("VPN 설정에서 HaruGuard의 ‘Always-on VPN’을 켜세요. 현재 시험 필터에서는 ‘Block connections without VPN’은 끄세요.",
        style = MaterialTheme.typography.bodySmall)
    if (lockdown == true) Text("VPN 외 연결 차단이 켜져 있습니다. 제외한 YouTube 앱 통신도 막힐 수 있습니다.", color = MaterialTheme.colorScheme.error)
    StatusButton("4. 배터리 최적화 예외", if (state.batteryExempt) "허용됨" else "설정 필요", busy) { onSettings(ParentSetting.BATTERY) }
    StatusButton("5. 정확한 알람", if (state.exactAlarm) "허용됨" else "미허용 · 복구가 늦어질 수 있음", busy) { onSettings(ParentSetting.EXACT_ALARM) }
    StatusButton("6. 상태 알림 (선택)", if (state.notifications) "허용됨" else "꺼짐 · VPN 실행 가능", busy) { onSettings(ParentSetting.NOTIFICATIONS) }
    Text("삼성 절전 목록·접근성 서비스 종료·OS 제한에 따른 실제 유지 여부는 기기에서 시험해야 합니다.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onSettings(ParentSetting.ALL) }, enabled = !busy) { Text("설정을 5분간 열기") }
    OutlinedButton(onClick = onLock) { Text("지금 잠그기") }
    OutlinedButton(onClick = { changePin = !changePin }, enabled = !busy) { Text("부모 PIN 변경") }
    if (changePin) ParentAuthForm(ParentAuthAction.CHANGE_PIN, busy, 0, onAuth)
    OutlinedButton(onClick = { showRecoveryWarning = true }, enabled = !busy) { Text("복구 코드 다시 발급") }
    OutlinedButton(onClick = { showRemove = true }, enabled = !busy) { Text("보호 해제 및 앱 제거 준비") }
    Text(stringResource(R.string.p0_security_note), style = MaterialTheme.typography.bodySmall)
    if (showRemove) AlertDialog(onDismissRequest = { showRemove = false },
        title = { Text("보호를 모두 해제할까요?") },
        text = { Text("VPN·자동 복구·설정 보호를 끄고 일반 관리자와 접근성을 해제합니다. 그 후 시스템 설정에서 앱을 삭제할 수 있습니다. Family Link 설정은 바꾸지 않습니다.") },
        confirmButton = { TextButton(onClick = { showRemove = false; onRemoveProtection() }) { Text("해제") } },
        dismissButton = { TextButton(onClick = { showRemove = false }) { Text("취소") } })
    if (showRecoveryWarning) AlertDialog(onDismissRequest = { showRecoveryWarning = false },
        title = { Text("새 복구 코드를 발급할까요?") },
        text = { Text("기존 복구 코드는 사용할 수 없게 됩니다. 새 코드를 부모만 볼 수 있는 곳에 기록하세요.") },
        confirmButton = { TextButton(onClick = { showRecoveryWarning = false; onRecoveryCode() }) { Text("발급") } },
        dismissButton = { TextButton(onClick = { showRecoveryWarning = false }) { Text("취소") } })
}

@Composable
private fun ParentAuthForm(initialAction: ParentAuthAction, busy: Boolean, retryMs: Long,
                           onAuth: (ParentAuthAction, String, String, String) -> Unit) {
    var action by remember(initialAction) { mutableStateOf(initialAction) }
    var pin by remember(action) { mutableStateOf("") }
    var confirmation by remember(action) { mutableStateOf("") }
    var recovery by remember(action) { mutableStateOf("") }
    Text(when (action) {
        ParentAuthAction.SETUP -> "부모 PIN 만들기"
        ParentAuthAction.LOGIN -> "부모 PIN 입력"
        ParentAuthAction.RECOVER -> "복구 코드로 PIN 재설정"
        ParentAuthAction.CHANGE_PIN -> "새 부모 PIN"
    }, style = MaterialTheme.typography.titleLarge)
    if (action == ParentAuthAction.SETUP) Text("부모가 처음 설정하세요. 숫자 6~12자리를 사용하며 자녀의 화면 잠금 PIN과 다르게 만드세요.")
    if (action == ParentAuthAction.RECOVER) {
        OutlinedTextField(value = recovery, onValueChange = { recovery = it.take(64) }, label = { Text("부모용 복구 코드") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
    OutlinedTextField(value = pin, onValueChange = { pin = it.filter { c -> c in '0'..'9' }.take(12) },
        label = { Text(if (action == ParentAuthAction.LOGIN) "PIN" else "새 PIN (6~12자리)") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    if (action != ParentAuthAction.LOGIN) OutlinedTextField(value = confirmation,
        onValueChange = { confirmation = it.filter { c -> c in '0'..'9' }.take(12) }, label = { Text("새 PIN 다시 입력") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    if (retryMs > 0) Text("다시 시도하기까지 ${(retryMs + 999) / 1_000}초")
    Button(enabled = !busy && retryMs == 0L && pin.length >= 6,
        onClick = {
            onAuth(action, pin, confirmation, recovery)
            pin = ""; confirmation = ""; recovery = ""
        }) { Text(if (busy) "확인 중…" else if (action == ParentAuthAction.LOGIN) "인증" else "저장") }
    if (initialAction == ParentAuthAction.LOGIN) TextButton(enabled = !busy,
        onClick = { action = if (action == ParentAuthAction.RECOVER) ParentAuthAction.LOGIN else ParentAuthAction.RECOVER }) {
        Text(if (action == ParentAuthAction.RECOVER) "PIN으로 돌아가기" else "PIN을 잊었어요")
    }
}

@Composable
private fun StatusButton(title: String, status: String, busy: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(status)
            OutlinedButton(onClick = onClick, enabled = !busy) { Text("설정 열기") }
        }
    }
}

@Composable
private fun ToggleCard(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked, onChange, enabled = enabled)
        }
    }
}
