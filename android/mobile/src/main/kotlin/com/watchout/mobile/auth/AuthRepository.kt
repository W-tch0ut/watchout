package com.watchout.mobile.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * 로그인 기능의 "약속(인터페이스)".
 *
 * 화면과 ViewModel은 이 약속만 알고, 실제로 Firebase를 쓰는지는 모른다.
 * 그래서 테스트에서는 가짜 저장소(FakeAuthRepository)를 끼워 Firebase 없이 검사할 수 있다.
 *
 * 실패하면 [AuthException]을 담은 Result.failure를 돌려준다.
 */
interface AuthRepository {
    /** 지금 로그인한 사용자. 로그아웃 상태면 null. 값이 바뀌면 화면이 자동으로 바뀐다. */
    val currentUser: StateFlow<AppUser?>

    /** 구글 로그인으로 받은 ID 토큰으로 Firebase에 로그인한다. */
    suspend fun signInWithGoogle(idToken: String): Result<AppUser>

    suspend fun signInWithEmail(email: String, password: String): Result<AppUser>

    /** 회원가입. 성공하면 바로 로그인 상태가 된다. */
    suspend fun signUpWithEmail(email: String, password: String): Result<AppUser>

    /** 비밀번호 재설정 메일을 보낸다. */
    suspend fun sendPasswordReset(email: String): Result<Unit>

    fun signOut()
}
