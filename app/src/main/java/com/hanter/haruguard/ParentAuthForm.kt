package com.hanter.haruguard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun ParentAuthForm(initialAction: ParentAuthAction, busy: Boolean, retryMs: Long,
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

