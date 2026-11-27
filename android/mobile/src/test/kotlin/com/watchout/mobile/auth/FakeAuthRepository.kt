package com.watchout.mobile.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Firebase 대신 쓰는 가짜 로그인 저장소.
 * [failWith]에 오류를 넣어 두면 다음 호출이 그 오류로 실패한다.
 * [calls]에 어떤 함수가 어떤 값으로 불렸는지 기록한다.
 */
class FakeAuthRepository : AuthRepository {
    private val _currentUser = MutableStateFlow<AppUser?>(null)
    override val currentUser: StateFlow<AppUser?> = _currentUser

    var failWith: AuthError? = null
    val calls = mutableListOf<String>()

    private fun login(email: String?): Result<AppUser> {
        failWith?.let { return Result.failure(AuthException(it)) }
        val user = AppUser(uid = "test-uid", email = email, displayName = null)
        _currentUser.value = user
        return Result.success(user)
    }

    override suspend fun signInWithGoogle(idToken: String): Result<AppUser> {
        calls += "google:$idToken"
        return login("google@test.example")
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<AppUser> {
        calls += "signIn:$email"
        return login(email)
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<AppUser> {
        calls += "signUp:$email"
        return login(email)
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        calls += "reset:$email"
        return failWith?.let { Result.failure(AuthException(it)) } ?: Result.success(Unit)
    }

    override fun signOut() {
        calls += "signOut"
        _currentUser.value = null
    }
}
