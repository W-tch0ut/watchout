package com.watchout.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.watchout.mobile.auth.AppUser
import com.watchout.mobile.profile.ProfileError
import com.watchout.mobile.profile.UserRole

/** 서버에서 모드를 받아오는 동안 보여주는 빙글빙글 */
@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * 모드 선택 화면. 처음 로그인했을 때와 "모드 바꾸기"를 눌렀을 때 나온다.
 * 카드 두 장(본인 / 보호자) 중 하나를 누르면 저장한다.
 */
@Composable
fun RoleSelectScreen(
    current: UserRole?,
    isSaving: Boolean,
    error: ProfileError?,
    canCancel: Boolean,
    onChoose: (UserRole) -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("누가 쓰시나요?", style = MaterialTheme.typography.headlineSmall)
        Text("나중에 화면 위 \"모드 바꾸기\"로 언제든 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium)

        UserRole.entries.forEach { role ->
            Card(
                onClick = { onChoose(role) },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val mark = if (role == current) "  (지금 모드)" else ""
                    Text(role.label + mark, style = MaterialTheme.typography.titleMedium)
                    Text(role.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (isSaving) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }

        Row {
            if (canCancel) TextButton(onClick = onCancel, enabled = !isSaving) { Text("취소") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSignOut, enabled = !isSaving) { Text("로그아웃") }
        }
    }
}

/** 두 모드 화면 위쪽 공통 줄: 제목 + 계정 + [모드 바꾸기] [로그아웃] */
@Composable
fun ModeHeader(title: String, user: AppUser, onChangeMode: () -> Unit, onSignOut: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(user.email ?: user.displayName ?: "", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onChangeMode) { Text("모드 바꾸기") }
        TextButton(onClick = onSignOut) { Text("로그아웃") }
    }
}
