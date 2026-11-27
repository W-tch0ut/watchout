package com.watchout.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchout.mobile.alert.AlertFormat
import com.watchout.mobile.alert.AlertPhoto
import com.watchout.mobile.alert.AlertSource
import com.watchout.mobile.auth.AppUser
import com.watchout.mobile.family.FamilyAlert
import com.watchout.mobile.family.GuardianHomeUiState
import com.watchout.mobile.family.GuardianHomeViewModel
import com.watchout.mobile.link.InviteCode
import com.watchout.mobile.link.LinkedPatient

/**
 * 보호자 모드 첫 화면 (#50).
 * 위: 연결된 가족 + [초대 코드 입력]  /  아래: 받은 알림 (사진·움직임·119 시뮬레이션·[확인했어요])
 * 앱이 켜져 있는 동안 실시간으로 새 알림이 위에 붙는다. (앱이 꺼져 있을 때의 푸시는 다음 단계)
 */
@Composable
fun GuardianHomeScreen(vm: GuardianHomeViewModel, user: AppUser, onChangeMode: () -> Unit, onSignOut: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // 연결 끊기는 되돌릴 수 없어서 한 번 더 묻는다
    var leaving by remember { mutableStateOf<LinkedPatient?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeHeader(title = "보호자", user = user, onChangeMode = onChangeMode, onSignOut = onSignOut)

        (s.errorMessage ?: s.message)?.let { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = if (s.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                Text(text, color = color, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissMessage) { Text("닫기") }
            }
        }

        // 목록 전체를 한 번에 스크롤한다: 가족 카드 → 알림 제목 → 알림들
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FamilyCard(s, onAdd = vm::openCodeDialog, onLeave = { leaving = it }) }
            item {
                val badge = if (s.unacknowledgedCount > 0) "  (확인 안 함 ${s.unacknowledgedCount})" else ""
                Text("받은 알림$badge", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (!s.isLoading && s.alerts.isEmpty()) {
                item { Text("아직 받은 알림이 없어요. 응급 상황이 확정되면 여기에 사진과 상태가 표시돼요.") }
            }
            items(s.alerts, key = { "${it.alert.patientUid}/${it.alert.id}" }) { item ->
                AlertCard(item, isAcking = item.alert.id in s.acking, onAck = { vm.acknowledge(item) })
            }
        }
    }

    if (s.showCodeDialog) {
        CodeDialog(s, onChange = vm::onCodeChange, onSubmit = vm::submitCode, onCancel = vm::closeCodeDialog)
    }

    leaving?.let { p ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("연결을 끊을까요?") },
            text = { Text("${p.name}님의 응급 알림을 더 받지 않아요. 다시 연결하려면 새 초대 코드가 필요해요.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.leave(p)
                    leaving = null
                }) { Text("연결 끊기") }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun FamilyCard(s: GuardianHomeUiState, onAdd: () -> Unit, onLeave: (LinkedPatient) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("연결된 가족", style = MaterialTheme.typography.titleMedium)
            when {
                s.isLoading -> Text("불러오는 중…")
                s.patients.isEmpty() -> Text(
                    "아직 연결된 가족이 없어요. 가족(환자)이 보낸 문자 속 ${InviteCode.LENGTH}자리 코드를 입력해 주세요.\n" +
                        "환자 본인이라면 위 [모드 바꾸기]에서 본인(환자)을 고르면 연결된 보호자를 볼 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            s.patients.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        if (p.uid in s.disconnected) {
                            Text(
                                "가족이 연결을 끊었어요. 알림을 볼 수 없어요.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    TextButton(onClick = { onLeave(p) }) { Text("연결 끊기") }
                }
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("초대 코드 입력") }
        }
    }
}

@Composable
private fun AlertCard(item: FamilyAlert, isAcking: Boolean, onAck: () -> Unit) {
    val a = item.alert
    // 확인 안 한 알림은 눈에 띄게 빨간 계열 바탕
    val colors = if (a.isAcknowledged) {
        CardDefaults.cardColors()
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val test = if (a.source == AlertSource.TEST) " [테스트]" else ""
            Text("🚨 ${item.patientName}님 쓰러짐 확인$test", style = MaterialTheme.typography.titleMedium)
            Text("${AlertFormat.time(a.createdAtMillis)} · ${AlertFormat.moving(a.moving)}")
            a.photo?.let { AlertPhotoView(it) }
            Text("🚑 119 연락: 시뮬레이션 (실제로 전화하지 않았어요)", style = MaterialTheme.typography.bodySmall)
            if (a.isAcknowledged) {
                Text("✅ ${a.ackedByName}님이 확인했어요", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = onAck, enabled = !isAcking, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isAcking) "표시 중…" else "확인했어요")
                }
            }
        }
    }
}

/** Firestore에 담긴 JPEG을 그림으로 바꿔 보여준다. 깨진 데이터면 안내 글만 */
@Composable
private fun AlertPhotoView(photo: AlertPhoto) {
    val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size)?.asImageBitmap() }
    if (bitmap == null) {
        Text("사진을 열 수 없어요.", style = MaterialTheme.typography.bodySmall)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "응급 상황 사진",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
        )
    }
}

@Composable
private fun CodeDialog(s: GuardianHomeUiState, onChange: (String) -> Unit, onSubmit: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("초대 코드 입력") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("가족(환자)이 보낸 문자 속 숫자 ${InviteCode.LENGTH}자리를 입력해 주세요.")
                OutlinedTextField(
                    value = s.codeInput,
                    onValueChange = onChange,
                    label = { Text("코드") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                s.codeError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !s.isLinking) { Text(if (s.isLinking) "연결 중…" else "연결") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !s.isLinking) { Text("취소") } },
    )
}
