package com.watchout.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchout.mobile.auth.AppUser
import com.watchout.mobile.guardian.Guardian
import com.watchout.mobile.guardian.GuardianEditor
import com.watchout.mobile.guardian.GuardianValidator
import com.watchout.mobile.guardian.GuardianViewModel
import com.watchout.mobile.patient.EmergencyResult
import com.watchout.mobile.patient.InviteResult
import com.watchout.mobile.patient.PatientViewModel

/**
 * 본인(환자) 모드의 첫 화면.
 * - 보호자 연락처 등록·수정·삭제 (#49)
 * - 연락처마다 [초대 문자]: 보호자 앱과 연결할 6자리 코드를 문자로 보낸다 (#50)
 * - [테스트 응급 알림]: 보호자 앱 알림 + 보호자 번호로 실제 문자 + 119 시뮬레이션 (#50)
 */
@Composable
fun GuardianScreen(
    vm: GuardianViewModel,
    patientVm: PatientViewModel,
    user: AppUser,
    onChangeMode: () -> Unit,
    onSignOut: () -> Unit,
) {
    val guardians by vm.guardians.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val listError by vm.listError.collectAsStateWithLifecycle()
    val patient by patientVm.state.collectAsStateWithLifecycle()
    // 삭제는 되돌릴 수 없어서 한 번 더 묻는다
    var deleting by remember { mutableStateOf<Guardian?>(null) }
    // 테스트 응급 알림도 실제 문자가 나가서 한 번 더 묻는다
    var confirmEmergency by remember { mutableStateOf(false) }

    // 문자 권한: 문자가 필요한 버튼을 누를 때 물어본다. 거절해도 문자만 빼고 진행한다.
    val withSmsPermission = rememberSmsPermission()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeHeader(title = "보호자 연락처", user = user, onChangeMode = onChangeMode, onSignOut = onSignOut)
        Text(
            "응급 상황이 확정되면 이 분들께 문자와 앱 알림이 가요. (최대 ${GuardianValidator.MAX_GUARDIANS}명)\n" +
                "[초대 문자]로 보호자 앱과 연결하면 사진과 상태도 볼 수 있어요.",
            style = MaterialTheme.typography.bodyMedium,
        )

        (listError?.message ?: patient.errorMessage)?.let { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    vm.dismissListError()
                    patientVm.dismissError()
                }) { Text("닫기") }
            }
        }

        if (guardians.isEmpty()) {
            Text("아직 등록된 보호자가 없어요.", modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(guardians, key = { it.id ?: it.phone }) { g ->
                GuardianRow(
                    g = g,
                    isLinked = patient.isLinked(g.id),
                    isInviting = patient.invitingId == g.id,
                    onInvite = { withSmsPermission { patientVm.sendInvite(g) } },
                    onUnlink = { g.id?.let(patientVm::unlink) },
                    onEdit = { vm.startEdit(g) },
                    onDelete = { deleting = g },
                )
            }
        }

        Button(onClick = vm::startAdd, modifier = Modifier.fillMaxWidth()) { Text("보호자 추가") }
        OutlinedButton(
            onClick = { confirmEmergency = true },
            enabled = guardians.isNotEmpty() && !patient.isSendingEmergency,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(if (patient.isSendingEmergency) "보내는 중…" else "테스트 응급 알림 보내기") }
    }

    editor?.let { e ->
        GuardianEditorDialog(
            editor = e,
            onName = vm::onNameChange,
            onRelation = vm::onRelationChange,
            onPhone = vm::onPhoneChange,
            onSave = vm::save,
            onCancel = vm::cancelEdit,
        )
    }

    deleting?.let { g ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("삭제할까요?") },
            text = { Text("${g.name}(${g.relation}) 님의 연락처를 삭제해요. 보호자 앱 연결도 함께 끊겨요. 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    g.id?.let {
                        patientVm.unlink(it) // 연락처를 지우면 그 보호자는 알림도 더 못 보게
                        vm.delete(it)
                    }
                    deleting = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }

    if (confirmEmergency) {
        EmergencyConfirmDialog(
            guardianCount = guardians.size,
            onSend = { moving ->
                confirmEmergency = false
                withSmsPermission { patientVm.sendTestEmergency(guardians, moving) }
            },
            onCancel = { confirmEmergency = false },
        )
    }

    patient.invite?.let { InviteResultDialog(it, onClose = patientVm::dismissInvite) }
    patient.emergency?.let { EmergencyResultDialog(it, guardians.size, onClose = patientVm::dismissEmergency) }
}

/**
 * 문자 권한이 있으면 바로 [action]을 하고, 없으면 먼저 물어본 뒤 한다.
 * 거절해도 [action]은 한다: ViewModel이 권한을 다시 확인해서 문자만 빼고 진행한다.
 */
@Composable
private fun rememberSmsPermission(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.SEND_SMS)
        }
    }
}

@Composable
private fun GuardianRow(
    g: Guardian,
    isLinked: Boolean,
    isInviting: Boolean,
    onInvite: () -> Unit,
    onUnlink: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${g.name} (${g.relation})", style = MaterialTheme.typography.titleMedium)
            Text(GuardianValidator.formatPhone(g.phone), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (isLinked) "✅ 보호자 앱 연결됨" else "보호자 앱 연결 안 됨 (문자만 받아요)",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                if (isLinked) {
                    TextButton(onClick = onUnlink) { Text("연결 끊기") }
                } else {
                    TextButton(onClick = onInvite, enabled = !isInviting) {
                        Text(if (isInviting) "보내는 중…" else "초대 문자")
                    }
                }
                TextButton(onClick = onEdit) { Text("수정") }
                TextButton(onClick = onDelete) { Text("삭제") }
            }
        }
    }
}

@Composable
private fun EmergencyConfirmDialog(guardianCount: Int, onSend: (moving: Boolean) -> Unit, onCancel: () -> Unit) {
    var moving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("테스트 응급 알림") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "로봇카가 쓰러짐을 확인한 상황을 흉내 내요.\n" +
                        "• 보호자 ${guardianCount}명에게 실제 문자가 가요 ([WatchOut 테스트] 표시)\n" +
                        "• 연결된 보호자 앱에 알림과 테스트 사진이 떠요\n" +
                        "• 119는 실제로 연락하지 않아요 (시뮬레이션)",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = moving, onCheckedChange = { moving = it })
                    Text("움직임 있음으로 보내기")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(moving) }) { Text("보내기") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}

@Composable
private fun InviteResultDialog(r: InviteResult, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("초대 코드: ${r.code}") },
        text = {
            Text(
                if (r.smsSent) {
                    "${r.guardianName}님께 코드를 문자로 보냈어요. 10분 안에 보호자 모드에서 입력하면 연결돼요."
                } else {
                    "문자를 보내지 못했어요(권한 없음 또는 유심 없음). 위 코드를 ${r.guardianName}님께 직접 알려 주세요. 10분 동안 쓸 수 있어요."
                },
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("확인") } },
    )
}

@Composable
private fun EmergencyResultDialog(r: EmergencyResult, guardianCount: Int, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("응급 알림을 보냈어요 (테스트)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (r.alertSaved) "📱 보호자 앱 알림: 보냄" else "📱 보호자 앱 알림: 실패 (인터넷 확인)")
                Text("✉️ 문자: ${r.smsSent}/${guardianCount}명에게 보냄")
                if (r.noSmsPermission) Text("문자 권한이 없어 문자를 보내지 못했어요.", color = MaterialTheme.colorScheme.error)
                Text(
                    "🚑 119에 연락했어요 — 시뮬레이션이에요. 실제로 전화·문자하지 않았어요.",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("확인") } },
    )
}

@Composable
private fun GuardianEditorDialog(
    editor: GuardianEditor,
    onName: (String) -> Unit,
    onRelation: (String) -> Unit,
    onPhone: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (editor.isNew) "보호자 추가" else "보호자 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(editor.name, onName, label = { Text("이름") }, singleLine = true)
                OutlinedTextField(editor.relation, onRelation, label = { Text("관계 (예: 딸)") }, singleLine = true)
                OutlinedTextField(
                    editor.phone,
                    onPhone,
                    label = { Text("휴대폰 번호") },
                    placeholder = { Text("010-1234-5678") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                editor.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !editor.isSaving) { Text(if (editor.isSaving) "저장 중…" else "저장") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}
