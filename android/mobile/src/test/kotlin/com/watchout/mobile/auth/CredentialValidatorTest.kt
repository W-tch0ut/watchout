package com.watchout.mobile.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CredentialValidatorTest {

    @Test
    fun `올바른 이메일은 통과한다`() {
        assertNull(CredentialValidator.validateEmail("family@test.example"))
    }

    @Test
    fun `앞뒤 공백은 지우고 검사한다`() {
        assertEquals("family@test.example", CredentialValidator.normalizeEmail("  family@test.example "))
        assertNull(CredentialValidator.validateEmail("  family@test.example "))
    }

    @Test
    fun `빈 이메일과 형식이 틀린 이메일을 구분한다`() {
        assertEquals(AuthError.EMPTY_EMAIL, CredentialValidator.validateEmail("   "))
        assertEquals(AuthError.INVALID_EMAIL, CredentialValidator.validateEmail("family"))
        assertEquals(AuthError.INVALID_EMAIL, CredentialValidator.validateEmail("family@test"))
        assertEquals(AuthError.INVALID_EMAIL, CredentialValidator.validateEmail("a b@test.example"))
    }

    @Test
    fun `로그인할 때는 비밀번호가 비어 있는지만 본다`() {
        assertEquals(AuthError.EMPTY_PASSWORD, CredentialValidator.validatePassword("", isSignUp = false))
        assertNull(CredentialValidator.validatePassword("short", isSignUp = false))
    }

    @Test
    fun `회원가입 비밀번호는 8자 이상에 영문과 숫자를 섞어야 한다`() {
        assertEquals(AuthError.WEAK_PASSWORD, CredentialValidator.validatePassword("abc123", isSignUp = true))
        assertEquals(AuthError.WEAK_PASSWORD, CredentialValidator.validatePassword("abcdefgh", isSignUp = true))
        assertEquals(AuthError.WEAK_PASSWORD, CredentialValidator.validatePassword("12345678", isSignUp = true))
        assertNull(CredentialValidator.validatePassword("abcd1234", isSignUp = true))
    }
}
