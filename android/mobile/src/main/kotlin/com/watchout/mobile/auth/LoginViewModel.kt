package com.watchout.mobile.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginMode { SIGN_IN, SIGN_UP }

/** 로그인 화면이 그릴 내용 전부. 화면은 이 값을 그리기만 한다. */
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val mode: LoginMode = LoginMode.SIGN_IN,
    val isLoading: Boolean = false,
    val error: AuthError? = null,
    val resetEmailSent: Boolean = false,
)

/**
 * 로그인 화면의 "두뇌".
 *
 * 입력값 검사 → 저장소(Firebase) 호출 → 결과를 [state]에 반영한다.
 * 로그인에 성공하면 [AuthRepository.currentUser]가 바뀌고, MainActivity가 그걸 보고
 * 보호자 연락처 화면으로 넘어간다. 그래서 여기서는 화면 이동을 신경 쓰지 않는다.
 */
class LoginViewModel(private val repo: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) = _state.update { it.copy(email = email, error = null, resetEmailSent = false) }

    fun onPasswordChange(password: String) = _state.update { it.copy(password = password, error = null) }

    /** 로그인 ↔ 회원가입 전환 */
    fun toggleMode() = _state.update {
        val next = if (it.mode == LoginMode.SIGN_IN) LoginMode.SIGN_UP else LoginMode.SIGN_IN
        it.copy(mode = next, error = null, resetEmailSent = false)
    }

    /** 이메일·비밀번호로 로그인 또는 회원가입 */
    fun submitEmail() {
        val s = _state.value
        if (s.isLoading) return // 버튼을 여러 번 눌러도 한 번만 요청
        val isSignUp = s.mode == LoginMode.SIGN_UP
        val email = CredentialValidator.normalizeEmail(s.email)
        val invalid = CredentialValidator.validateEmail(email)
            ?: CredentialValidator.validatePassword(s.password, isSignUp)
        if (invalid != null) {
            _state.update { it.copy(error = invalid) }
            return
        }
        launchAuth {
            if (isSignUp) repo.signUpWithEmail(email, s.password) else repo.signInWithEmail(email, s.password)
        }
    }

    /**
     * 구글 로그인.
     * @param getIdToken 구글 계정 선택 창을 띄워 ID 토큰을 받아 오는 함수.
     *   창은 화면(Activity)이 있어야 띄울 수 있어서, 화면 쪽에서 [GoogleSignInHelper]로 만들어 넘긴다.
     */
    fun signInWithGoogle(getIdToken: suspend () -> String) {
        if (_state.value.isLoading) return
        launchAuth { repo.signInWithGoogle(getIdToken()) }
    }

    /** 비밀번호 재설정 메일 보내기. 입력한 이메일로 보낸다. */
    fun sendPasswordReset() {
        val email = CredentialValidator.normalizeEmail(_state.value.email)
        val invalid = CredentialValidator.validateEmail(email)
        if (invalid != null) {
            _state.update { it.copy(error = invalid) }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = repo.sendPasswordReset(email)
            _state.update {
                it.copy(isLoading = false, resetEmailSent = result.isSuccess, error = result.authErrorOrNull())
            }
        }
    }

    private fun launchAuth(call: suspend () -> Result<AppUser>) {
        _state.update { it.copy(isLoading = true, error = null, resetEmailSent = false) }
        viewModelScope.launch {
            val result = try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 구글 계정 선택 창에서 취소한 경우 등. 앱이 꺼지지 않게 오류로 바꿔 보여준다.
                Result.failure(e as? AuthException ?: AuthException(AuthError.UNKNOWN, e))
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    error = result.authErrorOrNull(),
                    // 성공하든 실패하든 비밀번호는 화면에 남겨 두지 않는다
                    password = "",
                )
            }
        }
    }

    private fun Result<*>.authErrorOrNull(): AuthError? =
        exceptionOrNull()?.let { (it as? AuthException)?.error ?: AuthError.UNKNOWN }

    companion object {
        fun factory(repo: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(repo) }
        }
    }
}
