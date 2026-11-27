package com.watchout.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchout.mobile.auth.AppUser
import com.watchout.mobile.sms.SmsTemplate
import com.watchout.mobile.sms.SmsViewModel

/**
 * 응급 문자 설정 화면 (본인 모드).
 * 문자에 들어갈 이름·주소·참고를 고치고, 실제로 갈 문자를 미리 본다.
 * 지금은 폰 안에만 저장한다 (로봇카·서버가 읽게 하는 것은 다음 단계).
 */
@Composable
fun SmsScreen(vm: SmsViewModel, user: AppUser, onChangeMode: () -> Unit, onSignOut: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeHeader(title = "응급 문자", user = user, onChangeMode = onChangeMode, onSignOut = onSignOut)
        Text(
            "쓰러짐이 확인되면 보호자(와 119 시뮬레이션)에게 이 내용으로 문자가 가요.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = s.name,
            onValueChange = vm::onName,
            label = { Text("이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.address,
            onValueChange = vm::onAddress,
            label = { Text("주소 (구조하러 올 곳)") },
            supportingText = { Text("${s.address.length}/${SmsTemplate.MAX_ADDRESS}") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.note,
            onValueChange = vm::onNote,
            label = { Text("참고 (복용 약, 지병 등 · 선택)") },
            supportingText = { Text("${s.note.length}/${SmsTemplate.MAX_NOTE}") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = vm::save, enabled = state.dirty, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.saved && !state.dirty) "저장했어요" else "저장")
        }

        Text("미리보기", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Text(state.preview, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        Text(
            "※ 119에는 실제로 보내지 않아요 (데모는 시뮬레이션).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
