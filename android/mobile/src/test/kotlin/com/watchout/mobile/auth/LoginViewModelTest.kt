package com.watchout.mobile.auth

import com.watchout.mobile.MainDispatcherExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherExtension::class)
class LoginViewModelTest {

    private val repo = FakeAuthRepository()
    // lazy: 테스트 시작 뒤(가짜 메인 디스패처를 끼운 뒤)에 만든다
    private val vm by lazy { LoginViewModel(repo) }

    private fun type(email: String, password: String) {
        vm.onEmailChange(email)
        vm.onPasswordChange(password)
    }

    @Test
    fun `이메일 로그인에 성공하면 사용자가 바뀌고 비밀번호는 지워진다`() {
        type(" family@test.example ", "abcd1234")
        vm.submitEmail()

        assertEquals(listOf("signIn:family@test.example"), repo.calls) // 공백 제거 후 요청
        assertEquals("family@test.example", repo.currentUser.value?.email)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isLoading)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun `입력값이 틀리면 서버에 요청하지 않는다`() {
        type("not-an-email", "abcd1234")
        vm.submitEmail()

        assertEquals(AuthError.INVALID_EMAIL, vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `회원가입 모드에서는 약한 비밀번호를 막고, 통과하면 signUp을 부른다`() {
        vm.toggleMode()
        assertEquals(LoginMode.SIGN_UP, vm.state.value.mode)

        type("family@test.example", "abc")
        vm.submitEmail()
        assertEquals(AuthError.WEAK_PASSWORD, vm.state.value.error)

        vm.onPasswordChange("abcd1234")
        vm.submitEmail()
        assertEquals(listOf("signUp:family@test.example"), repo.calls)
    }

    @Test
    fun `서버가 거절하면 그 이유를 보여준다`() {
        repo.failWith = AuthError.WRONG_CREDENTIALS
        type("family@test.example", "abcd1234")
        vm.submitEmail()

        assertEquals(AuthError.WRONG_CREDENTIALS, vm.state.value.error)
        assertNull(repo.currentUser.value)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `구글 로그인은 받은 토큰으로 저장소를 부른다`() {
        vm.signInWithGoogle { "fake-id-token" }

        assertEquals(listOf("google:fake-id-token"), repo.calls)
        assertNotNull(repo.currentUser.value)
    }

    @Test
    fun `구글 계정 선택을 취소하면 CANCELLED를 보여주고 저장소는 부르지 않는다`() {
        vm.signInWithGoogle { throw AuthException(AuthError.CANCELLED) }

        assertEquals(AuthError.CANCELLED, vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `예상하지 못한 예외도 앱을 끄지 않고 UNKNOWN으로 보여준다`() {
        vm.signInWithGoogle { throw IllegalStateException("boom") }

        assertEquals(AuthError.UNKNOWN, vm.state.value.error)
    }

    @Test
    fun `비밀번호 재설정 메일을 보내면 안내를 띄운다`() {
        vm.onEmailChange("family@test.example")
        vm.sendPasswordReset()

        assertEquals(listOf("reset:family@test.example"), repo.calls)
        assertTrue(vm.state.value.resetEmailSent)
    }

    @Test
    fun `이메일 없이 재설정을 누르면 요청하지 않는다`() {
        vm.sendPasswordReset()

        assertEquals(AuthError.EMPTY_EMAIL, vm.state.value.error)
        assertTrue(repo.calls.isEmpty())
    }
}
