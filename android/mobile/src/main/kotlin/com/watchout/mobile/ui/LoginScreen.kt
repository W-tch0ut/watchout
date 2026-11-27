package com.watchout.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchout.mobile.auth.CredentialValidator
import com.watchout.mobile.auth.LoginMode
import com.watchout.mobile.auth.LoginViewModel

/**
 * 로그인 화면.
 * 위: 구글 로그인 버튼 (정문) / 아래: 이메일·비밀번호 (옆문)
 * 화면은 vm.state를 그리기만 하고, 버튼을 누르면 vm 함수를 부른다.
 */
@Composable
fun LoginScreen(vm: LoginViewModel, onGoogleClick: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isSignUp = state.mode == LoginMode.SIGN_UP

    // 비밀번호 칸은 자기 글자 상자(TextFieldState)를 가진다. 두 방향으로 맞춰 준다.
    val passwordField = rememberTextFieldState()
    // ① 사용자가 입력하면 → ViewModel에 알린다
    LaunchedEffect(passwordField) {
        snapshotFlow { passwordField.text.toString() }.collect(vm::onPasswordChange)
    }
    // ② ViewModel이 비밀번호를 지우면(로그인 시도 후) → 입력칸도 비운다
    LaunchedEffect(state.password) {
        if (state.password.isEmpty() && passwordField.text.isNotEmpty()) passwordField.clearText()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("WatchOut", style = MaterialTheme.typography.headlineLarge)
        Text("보호자 연락처를 관리하려면 로그인해 주세요.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onGoogleClick,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("구글 계정으로 로그인") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(if (isSignUp) "이메일로 회원가입" else "이메일로 로그인", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = state.email,
            onValueChange = vm::onEmailChange,
            label = { Text("이메일") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        // 비밀번호 전용 입력칸: 입력한 글자를 ●●● 로 가리고, 복사·붙여넣기 메뉴에서 "복사"를 막는다.
        OutlinedSecureTextField(
            state = passwordField,
            label = { Text("비밀번호") },
            supportingText = {
                if (isSignUp) Text("${CredentialValidator.MIN_PASSWORD_LENGTH}자 이상, 영문과 숫자를 섞어 주세요")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        if (state.resetEmailSent) {
            Text("비밀번호 재설정 메일을 보냈어요. 메일함을 확인해 주세요.", color = MaterialTheme.colorScheme.primary)
        }

        Button(
            onClick = vm::submitEmail,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text(if (isSignUp) "회원가입" else "로그인")
            }
        }

        TextButton(onClick = vm::toggleMode, enabled = !state.isLoading) {
            Text(if (isSignUp) "이미 계정이 있어요 → 로그인" else "계정이 없어요 → 회원가입")
        }
        if (!isSignUp) {
            TextButton(onClick = vm::sendPasswordReset, enabled = !state.isLoading) {
                Text("비밀번호를 잊었어요")
            }
        }
    }
}
