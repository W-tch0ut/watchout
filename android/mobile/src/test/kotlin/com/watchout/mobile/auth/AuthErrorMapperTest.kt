package com.watchout.mobile.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthErrorMapperTest {

    @Test
    fun `틀린 비밀번호와 없는 계정은 같은 안내를 보여준다`() {
        // 어떤 이메일이 가입돼 있는지 남이 알아낼 수 없게 하려는 것
        for (code in listOf("ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND")) {
            assertEquals(AuthError.WRONG_CREDENTIALS, AuthErrorMapper.fromFirebaseCode(code), code)
        }
    }

    @Test
    fun `가입 관련 코드를 알맞은 오류로 바꾼다`() {
        assertEquals(AuthError.EMAIL_IN_USE, AuthErrorMapper.fromFirebaseCode("ERROR_EMAIL_ALREADY_IN_USE"))
        assertEquals(AuthError.WEAK_PASSWORD, AuthErrorMapper.fromFirebaseCode("ERROR_WEAK_PASSWORD"))
        assertEquals(AuthError.INVALID_EMAIL, AuthErrorMapper.fromFirebaseCode("ERROR_INVALID_EMAIL"))
        assertEquals(
            AuthError.OTHER_SIGN_IN_METHOD,
            AuthErrorMapper.fromFirebaseCode("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL"),
        )
    }

    @Test
    fun `모르는 코드나 null은 UNKNOWN`() {
        assertEquals(AuthError.UNKNOWN, AuthErrorMapper.fromFirebaseCode("ERROR_SOMETHING_NEW"))
        assertEquals(AuthError.UNKNOWN, AuthErrorMapper.fromFirebaseCode(null))
    }
}
