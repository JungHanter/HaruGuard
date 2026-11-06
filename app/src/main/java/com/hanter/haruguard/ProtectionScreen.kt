package com.hanter.haruguard

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hanter.haruguard.vpn.DomainGroup
import com.hanter.haruguard.vpn.FilterService
import com.hanter.haruguard.vpn.FilterSettings

internal enum class ParentPage(val title: String) {
    MENU("부모 관리"), FILTERS("VPN 필터 설정"), SETUP("처음 시작: 설정 보호 옵션"),
    UPDATE("업데이트"), OTHER("기타"), KAKAO("카카오톡 숏폼"), NAVER("네이버 클립"), ADS("광고 차단"), P2P("P2P 차단"), CUSTOM("사용자 지정 차단 도메인");

    val group: DomainGroup? get() = when (this) {
        KAKAO -> DomainGroup.KAKAO
        NAVER -> DomainGroup.NAVER
        ADS -> DomainGroup.ADS
        P2P -> DomainGroup.P2P
        CUSTOM -> DomainGroup.CUSTOM
        else -> null
    }
    val parent: ParentPage? get() = when (this) {
        MENU -> null
        KAKAO, NAVER, ADS, P2P, CUSTOM -> FILTERS
        else -> MENU
    }
}

@Composable
internal fun ProtectionScreen(
    state: ParentUiState, vpn: VpnStatus, alwaysOn: Boolean?, lockdown: Boolean?,
    busy: Boolean, feedback: String?, recoveryCode: String?,
    onAuth: (ParentAuthAction, String, String, String) -> Unit,
    onVpn: (Boolean) -> Unit, onProtection: (Boolean) -> Unit,
    onSettings: (ParentSetting) -> Unit, onLock: () -> Unit,
    onRecoveryCode: () -> Unit, onRemoveProtection: () -> Unit,
    update: UpdateUiState, onUpdateDownload: (String) -> Unit,
    onUpdateCancel: () -> Unit, onUpdateInstall: () -> Unit,
    filters: FilterSettings?, filterError: String?, installedServices: Set<FilterService>,
    onRetryFilters: () -> Unit, onServiceFilter: (FilterService, Boolean) -> Unit,
    onGroupFilter: (DomainGroup, Boolean) -> Unit,
    onDomainEdit: (DomainGroup, String?, String?) -> String?,
) {
    Scaffold(Modifier.fillMaxSize()) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("HaruGuard", style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
            // 관리 화면은 인증 분기 안에서만 생성한다. 잠기면 경로·대화상자·입력 상태도 폐기한다.
            if (!state.authenticated) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusSummary(state, vpn)
                    feedback?.let { Text(it) }
                    ParentAuthForm(if (state.configured) ParentAuthAction.LOGIN else ParentAuthAction.SETUP,
                        busy, state.retryMs, onAuth)
                    Text("설정 변경은 부모 PIN이 필요합니다. 앱 화면을 닫아도 켜 둔 VPN은 계속 실행됩니다.",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else if (recoveryCode != null) {
                BackHandler(onBack = onLock)
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("부모용 복구 코드", style = MaterialTheme.typography.titleLarge)
                    Text(recoveryCode, style = MaterialTheme.typography.headlineSmall)
                    Text("PIN을 잊었을 때 사용합니다. 종이나 부모의 안전한 보관함에 기록하세요. 이 기기에 저장하거나 자녀에게 알려주지 마세요. 앱을 나가거나 인증이 만료되면 코드 표시가 사라집니다.")
                    Button(onClick = onLock) { Text("보관했어요 · 잠그기") }
                }
            } else {
                var page by remember { mutableStateOf(ParentPage.MENU) }
                val goBack = { page.parent?.let { page = it } ?: onLock() }
                BackHandler(onBack = goBack)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (page != ParentPage.MENU) TextButton(onClick = goBack) { Text("뒤로") }
                    Text(page.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onLock) { Text("잠그기") }
                }
                Text("인증 ${formatRemaining(state.authenticationMs)} 남음" +
                    if (state.maintenanceMs > 0) " · 설정 접근 허용 중" else "", style = MaterialTheme.typography.bodySmall)
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                key(page) {
                    val group = page.group
                    if (group != null && filters != null) {
                        DomainListScreen(group, filters, busy, onGroupFilter, onDomainEdit)
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            when (page) {
                                ParentPage.MENU -> {
                                    StatusSummary(state, vpn)
                                    ToggleCard("1. 설정 화면 보호", "HaruGuard 관리·권한과 VPN·핫스팟·테더링·초기화 설정을 보호합니다. 켜면 부모 인증이 종료됩니다.",
                                        state.protection, !busy, onProtection)
                                    ToggleCard("2. VPN 필터 켜기", "선택한 서비스와 도메인 목록을 적용합니다. 부모가 끄면 자동 복구도 중지합니다.",
                                        state.filteringRequested, !busy && vpn.phase != VpnPhase.STARTING && vpn.phase != VpnPhase.STOPPING, onVpn)
                                    if (state.filteringRequested && vpn.phase == VpnPhase.ERROR) {
                                        Button(onClick = { onVpn(true) }, enabled = !busy) { Text("VPN 승인·연결 다시 시도") }
                                    }
                                    MenuButton("3. VPN 필터 설정", "서비스별 차단 · 숏폼 · 광고 · P2P · 사용자 지정 도메인") { page = ParentPage.FILTERS }
                                    MenuButton("4. 처음 시작: 설정 보호 옵션", "관리자·접근성·VPN 등 6개 상태 확인") { page = ParentPage.SETUP }
                                    MenuButton("5. 업데이트", "APK 링크로 다운로드·설치") { page = ParentPage.UPDATE }
                                    MenuButton("6. 기타", "PIN 변경 · 복구 코드 · 보호 해제") { page = ParentPage.OTHER }
                                    Text("인증 성공부터 5분간 유효합니다. 일반 앱 이탈·화면 잠금 시 다시 PIN을 요구합니다. 설정 접근을 허용한 동안에는 남은 시간 안에서 돌아올 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                                }
                                ParentPage.FILTERS -> FilterControls(filters, filterError, installedServices, state.filteringRequested,
                                    busy, onRetryFilters, onServiceFilter, onGroupFilter, { page = it })
                                ParentPage.SETUP -> SetupControls(state, alwaysOn, lockdown, busy, onSettings)
                                ParentPage.UPDATE -> UpdateControls(update, busy, onUpdateDownload, onUpdateCancel, onUpdateInstall)
                                ParentPage.OTHER -> OtherControls(busy, onAuth, onRecoveryCode, onRemoveProtection)
                                else -> {
                                    Text(filterError ?: "차단 설정을 불러오지 못했습니다.")
                                    OutlinedButton(onClick = onRetryFilters, enabled = !busy) { Text("다시 읽기") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val seconds = (ms + 999) / 1_000
    return "${seconds / 60}분 ${seconds % 60}초"
}

@Composable
private fun StatusSummary(state: ParentUiState, vpn: VpnStatus) {
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
    vpn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun SetupControls(state: ParentUiState, alwaysOn: Boolean?, lockdown: Boolean?, busy: Boolean,
                          onSettings: (ParentSetting) -> Unit) {
    Text("각 항목을 설정한 뒤 메뉴에서 설정 화면 보호를 켜세요. 설정을 다녀와도 최초 인증의 5분 기한은 늘어나지 않습니다.")
    StatusButton("1. 일반 기기 관리자", if (state.admin) "활성화됨" else "설정 필요", state.admin, busy) { onSettings(ParentSetting.ADMIN) }
    StatusButton("2. 접근성 설정 보호", when {
        state.accessibilityConnected -> "연결됨"
        state.accessibilityEnabled -> "허용됨 · 서비스 연결 대기"
        else -> "설정 필요"
    }, state.accessibilityConnected, busy) { onSettings(ParentSetting.ACCESSIBILITY) }
    Text("접근성에서 ‘HaruGuard 설정 보호’를 켜고 단축키·접근성 버튼·음량키 바로가기는 끄세요. 제한된 설정 안내가 나오면 앱 정보의 ‘Allow restricted settings’를 확인하세요.", style = MaterialTheme.typography.bodySmall)
    StatusButton("3. 항상 켜진 VPN", when (alwaysOn) { true -> "켜짐"; false -> "꺼짐"; null -> "VPN 실행 후 확인" }, alwaysOn, busy) { onSettings(ParentSetting.VPN) }
    Text("VPN 설정에서 ‘Always-on VPN’을 켜고 ‘Block connections without VPN’은 끄세요.", style = MaterialTheme.typography.bodySmall)
    if (lockdown == true) Text("VPN 외 연결 차단이 켜져 있어 일반 사이트가 막힐 수 있습니다. 이 옵션을 꺼 주세요.", color = MaterialTheme.colorScheme.error)
    StatusButton("4. 배터리 최적화 예외", if (state.batteryExempt) "허용됨" else "설정 필요", state.batteryExempt, busy) { onSettings(ParentSetting.BATTERY) }
    StatusButton("5. 정확한 알람", if (Build.VERSION.SDK_INT < 31) "별도 허가 불필요" else if (state.exactAlarm) "허용됨" else "미허용 · 복구 지연 가능",
        state.exactAlarm, busy) { onSettings(ParentSetting.EXACT_ALARM) }
    StatusButton("6. 상태 알림 (선택)", if (state.notifications) "허용됨" else "꺼짐 · VPN 실행 가능", state.notifications, busy) { onSettings(ParentSetting.NOTIFICATIONS) }
    Text("삼성 절전 목록·접근성 서비스 종료에 따른 실제 유지 여부는 기기에서 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onSettings(ParentSetting.ALL) }, enabled = !busy) { Text("남은 시간 동안 설정 열기") }
}

@Composable
private fun OtherControls(busy: Boolean, onAuth: (ParentAuthAction, String, String, String) -> Unit,
                          onRecoveryCode: () -> Unit, onRemoveProtection: () -> Unit) {
    var changePin by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { changePin = !changePin }, enabled = !busy) { Text("부모 PIN 변경") }
    if (changePin) ParentAuthForm(ParentAuthAction.CHANGE_PIN, busy, 0, onAuth)
    OutlinedButton(onClick = { showRecovery = true }, enabled = !busy) { Text("복구 코드 다시 발급") }
    OutlinedButton(onClick = { showRemove = true }, enabled = !busy) { Text("보호 해제 및 앱 제거 준비") }
    ProtectionRuntime.lastBlock?.let { Text("마지막 차단: ${it.area.label} · ${it.scope.label}", style = MaterialTheme.typography.bodySmall) }
    Text("개발자옵션을 켜려면 설정 화면 보호를 먼저 끄세요. 임시 개방만으로는 활성화할 수 없습니다. 사용 후 개발자옵션을 끄고 보호를 다시 켜세요.", style = MaterialTheme.typography.bodySmall)
    Text("설정 보호는 HaruGuard 앱 관리·실행 권한과 관리자·접근성·VPN·핫스팟·테더링·초기화 화면을 대상으로 합니다. Wi-Fi·밝기·소리 등 일반 설정은 사용할 수 있습니다. 앱을 구분할 수 없는 상세 화면이나 목록에서 바로 권한을 바꾸는 경우에는 해당 화면·목록을 보호합니다.", style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.p0_security_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.vpn_test_note), style = MaterialTheme.typography.bodySmall)
    if (showRemove) AlertDialog(onDismissRequest = { showRemove = false },
        title = { Text("보호를 모두 해제할까요?") },
        text = { Text("VPN·자동 복구·설정 보호를 끄고 일반 관리자와 접근성을 해제합니다. 그 후 시스템 설정에서 앱을 삭제할 수 있습니다. 기존 ADB 삭제 금지는 별도 원복이 필요합니다. Family Link 설정은 바꾸지 않습니다.") },
        confirmButton = { TextButton(onClick = { showRemove = false; onRemoveProtection() }, enabled = !busy) { Text("해제") } },
        dismissButton = { TextButton(onClick = { showRemove = false }) { Text("취소") } })
    if (showRecovery) AlertDialog(onDismissRequest = { showRecovery = false },
        title = { Text("새 복구 코드를 발급할까요?") },
        text = { Text("기존 복구 코드는 사용할 수 없게 됩니다. 새 코드를 부모만 볼 수 있는 곳에 기록하세요.") },
        confirmButton = { TextButton(onClick = { showRecovery = false; onRecoveryCode() }, enabled = !busy) { Text("발급") } },
        dismissButton = { TextButton(onClick = { showRecovery = false }) { Text("취소") } })
}

@Composable
private fun StatusButton(title: String, status: String, ready: Boolean?, busy: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(when (ready) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.outline
                }, CircleShape))
                Text(status)
            }
            OutlinedButton(onClick = onClick, enabled = !busy) { Text("설정 열기") }
        }
    }
}

@Composable
internal fun ToggleCard(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
        }
    }
}

@Composable
internal fun MenuButton(title: String, description: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
