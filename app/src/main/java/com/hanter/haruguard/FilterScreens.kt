package com.hanter.haruguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hanter.haruguard.vpn.DomainGroup
import com.hanter.haruguard.vpn.DomainInput
import com.hanter.haruguard.vpn.FilterService
import com.hanter.haruguard.vpn.FilterSettings

@Composable
internal fun FilterControls(
    settings: FilterSettings?, error: String?, installed: Set<FilterService>, vpnRequested: Boolean,
    busy: Boolean, onRetry: () -> Unit, onService: (FilterService, Boolean) -> Unit,
    onGroup: (DomainGroup, Boolean) -> Unit, onNavigate: (ParentPage) -> Unit,
) {
    if (settings == null) {
        Text(error ?: "차단 설정을 불러오지 못했습니다.", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry, enabled = !busy) { Text("다시 읽기") }
        return
    }
    if (!vpnRequested) Text("VPN이 꺼져 있습니다. 목록은 저장되며 VPN을 켜면 적용됩니다.")
    Text("A. 서비스별 웹 차단 / 공식 앱 허용", style = MaterialTheme.typography.titleMedium)
    Text("켜면 해당 서비스의 웹·다른 앱 내 접속을 차단하고 지원 공식 앱에서만 허용합니다. 다른 차단 목록에 등록한 도메인은 공식 앱에서도 차단합니다.", style = MaterialTheme.typography.bodySmall)
    FilterService.entries.forEach { service ->
        ToggleCard(service.title, if (service in installed) "지원 공식 앱 설치됨 · 웹 차단" else "지원 공식 앱 미설치 · 웹 차단 유지",
            service in settings.enabledServices, !busy) { onService(service, it) }
    }
    Text("B. 카카오톡 숏폼", style = MaterialTheme.typography.titleMedium)
    ToggleCard("카카오톡 숏폼 차단", "모든 앱에서 목록의 도메인 차단 · ${settings.kakaoDomains.size}개",
        settings.kakaoEnabled, !busy) { onGroup(DomainGroup.KAKAO, it) }
    MenuButton("카카오톡 숏폼 도메인 편집", "기본 후보 주소 추가·수정·삭제") { onNavigate(ParentPage.KAKAO) }
    Text("C. 네이버 클립", style = MaterialTheme.typography.titleMedium)
    ToggleCard("네이버 클립 차단", "네이버 앱을 포함한 모든 앱에서 목록의 도메인 차단 · ${settings.naverDomains.size}개",
        settings.naverEnabled, !busy) { onGroup(DomainGroup.NAVER, it) }
    MenuButton("네이버 클립 도메인 편집", "기본 후보 주소 추가·수정·삭제") { onNavigate(ParentPage.NAVER) }
    Text("D. 사용자 지정 차단 도메인", style = MaterialTheme.typography.titleMedium)
    MenuButton("사용자 지정 도메인 편집", "${settings.customDomains.size}개 · 공식 앱을 포함한 모든 앱에서 차단") { onNavigate(ParentPage.CUSTOM) }
    Text("숏폼 기본 목록은 공개 자료를 바탕으로 선정한 후보입니다. 모든 숏폼이 차단되거나 일반 기능에 영향이 없다고 확인된 목록은 아닙니다. 문제가 생기면 해당 토글을 끄거나 주소를 편집하세요.", style = MaterialTheme.typography.bodySmall)
}

private data class DomainEdit(val original: String?)

/** B/C/D는 동일한 편집 화면을 사용한다. 입력/대화상자는 화면 이동·잠금 시 저장하지 않는다. */
@Composable
internal fun DomainListScreen(
    group: DomainGroup, settings: FilterSettings, busy: Boolean,
    onGroup: (DomainGroup, Boolean) -> Unit,
    onEdit: (DomainGroup, String?, String?) -> String?,
) {
    var editing by remember { mutableStateOf<DomainEdit?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val domains = settings.domains(group).sorted()
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (group != DomainGroup.CUSTOM) {
                    ToggleCard("${group.title} 차단", "목록의 도메인을 모든 앱에서 차단합니다.", settings.enabled(group), !busy) { onGroup(group, it) }
                    Text("기본 주소는 공개 자료에서 찾은 후보입니다. 직접 추가·수정·삭제할 수 있고, 삭제한 기본 주소는 자동으로 다시 넣지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    if (group == DomainGroup.NAVER) Text("일부 클립은 일반 네이버 서비스와 주소를 공유해 남을 수 있습니다. 클립 관련 댓글·프로필 기능에도 영향이 있을 수 있으니 문제가 있으면 주소를 제거하세요.", style = MaterialTheme.typography.bodySmall)
                }
                Text("도메인만 입력하세요. 예: dcinside.com\nhttp://, https://, /경로는 넣지 마세요. 루트와 모든 하위 도메인을 HTTP·HTTPS 구분 없이 차단합니다.", style = MaterialTheme.typography.bodySmall)
                Text("공식 앱에도 예외 없이 적용됩니다. VPN이 켜져 있어야 하며 자체 DNS·이미 열린 연결·저장된 영상은 차단되지 않을 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { error = null; editing = DomainEdit(null) }, enabled = !busy && domains.size < DomainInput.MAX_DOMAINS) { Text("도메인 추가") }
                Text("${domains.size} / ${DomainInput.MAX_DOMAINS}개" + if (!settings.enabled(group)) " · 현재 목록 차단 꺼짐" else "")
                if (domains.isEmpty()) Text("등록된 도메인이 없습니다. 이 목록에서 차단하는 주소가 없습니다.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(domains, key = { it }) { domain ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(domain, style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = { error = null; editing = DomainEdit(domain) }, enabled = !busy) { Text("수정") }
                        TextButton(onClick = { error = null; deleting = domain }, enabled = !busy) { Text("삭제") }
                    }
                }
            }
        }
    }
    editing?.let { edit ->
        var input by remember(edit) { mutableStateOf(edit.original.orEmpty()) }
        var inputError by remember(edit) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(if (edit.original == null) "도메인 추가" else "도메인 수정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it.take(512); inputError = null },
                        label = { Text("도메인 (예: dcinside.com)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = inputError != null)
                    inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !busy && input.isNotBlank(), onClick = {
                inputError = onEdit(group, edit.original, input)
                if (inputError == null) editing = null
            }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } })
    }
    deleting?.let { domain ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("이 도메인을 삭제할까요?") },
            text = { Text("$domain 및 하위 도메인을 이 목록에서 제거합니다. 다른 차단 목록에 있다면 차단은 유지됩니다.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                error = onEdit(group, domain, null)
                deleting = null
            }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } })
    }
}
