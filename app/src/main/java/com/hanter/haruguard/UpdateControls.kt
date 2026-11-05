package com.hanter.haruguard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun UpdateControls(state: UpdateUiState, busy: Boolean, onDownload: (String) -> Unit,
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
    Text("같은 서명 키의 새 HaruGuard APK만 설치합니다. 설치 권한·업데이트 확인은 Android 화면에서 부모가 승인하세요. 설치 화면 접근은 현재 부모 인증의 남은 시간 안에서 허용됩니다.",
        style = MaterialTheme.typography.bodySmall)
}

