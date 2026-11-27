package com.watchout.mobile.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchout.mobile.auth.AppUser
import com.watchout.mobile.guardian.Guardian
import com.watchout.mobile.guardian.GuardianEditor
import com.watchout.mobile.guardian.GuardianValidator
import com.watchout.mobile.guardian.GuardianViewModel

/**
 * 보호자 연락처 화면 (이슈 #49 완료 조건: 로그인 후 등록·수정).
 * 목록 + [추가] 버튼, 항목마다 [수정] [삭제], 추가·수정은 작은 창(다이얼로그)에서 한다.
 */
@Composable
fun GuardianScreen(vm: GuardianViewModel, user: AppUser, onSignOut: () -> Unit) {
    val guardians by vm.guardians.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val listError by vm.listError.collectAsStateWithLifecycle()
    // 삭제는 되돌릴 수 없어서 한 번 더 묻는다
    var deleting by remember { mutableStateOf<Guardian?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("보호자 연락처", style = MaterialTheme.typography.headlineSmall)
                Text(user.email ?: user.displayName ?: "", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSignOut) { Text("로그아웃") }
        }
        Text(
            "응급 상황이 확정되면 이 분들께 알림이 가요. (최대 ${GuardianValidator.MAX_GUARDIANS}명)",
            style = MaterialTheme.typography.bodyMedium,
        )

        listError?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissListError) { Text("닫기") }
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
                GuardianRow(g, onEdit = { vm.startEdit(g) }, onDelete = { deleting = g })
            }
        }

        Button(onClick = vm::startAdd, modifier = Modifier.fillMaxWidth()) { Text("보호자 추가") }
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
            text = { Text("${g.name}(${g.relation}) 님의 연락처를 삭제해요. 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    g.id?.let(vm::delete)
                    deleting = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun GuardianRow(g: Guardian, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${g.name} (${g.relation})", style = MaterialTheme.typography.titleMedium)
                Text(GuardianValidator.formatPhone(g.phone), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onEdit) { Text("수정") }
            TextButton(onClick = onDelete) { Text("삭제") }
        }
    }
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
