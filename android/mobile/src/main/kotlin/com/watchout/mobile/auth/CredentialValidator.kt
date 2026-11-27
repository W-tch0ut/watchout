package com.watchout.mobile.auth

/**
 * 이메일·비밀번호를 서버에 보내기 전에 앱에서 먼저 검사한다.
 * (잘못된 값으로 서버에 여러 번 요청하지 않게, 사용자에게 바로 이유를 알려 주려고)
 */
object CredentialValidator {
    /** Firebase 최소 길이는 6자지만, 보호자 연락처를 지키는 계정이라 조금 더 엄격하게 한다. */
    const val MIN_PASSWORD_LENGTH = 8

    // 아이디@도메인.최상위도메인 형태만 확인하는 간단한 규칙 (정확한 확인은 Firebase가 한다)
    private val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /** 앞뒤 공백을 없앤 이메일. 입력할 때 실수로 들어간 공백 때문에 로그인이 실패하지 않게 한다. */
    fun normalizeEmail(email: String): String = email.trim()

    /** 문제가 없으면 null, 있으면 그 이유를 돌려준다. */
    fun validateEmail(email: String): AuthError? {
        val e = normalizeEmail(email)
        return when {
            e.isEmpty() -> AuthError.EMPTY_EMAIL
            !EMAIL_PATTERN.matches(e) -> AuthError.INVALID_EMAIL
            else -> null
        }
    }

    /**
     * 로그인할 때는 비어 있는지만 본다. (예전에 만든 짧은 비밀번호도 로그인은 되어야 하므로)
     * 회원가입할 때는 [isSignUp]=true로 길이와 영문·숫자 섞임까지 본다.
     */
    fun validatePassword(password: String, isSignUp: Boolean): AuthError? = when {
        password.isEmpty() -> AuthError.EMPTY_PASSWORD
        !isSignUp -> null
        password.length < MIN_PASSWORD_LENGTH -> AuthError.WEAK_PASSWORD
        password.none { it.isLetter() } || password.none { it.isDigit() } -> AuthError.WEAK_PASSWORD
        else -> null
    }
}
