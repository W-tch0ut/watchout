package com.watchout.mobile.auth

/** 로그인한 사용자. 화면에 필요한 최소 정보만 담는다. */
data class AppUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
)

/** 로그인 중에 생길 수 있는 문제. 화면에는 [message]로 한국어 안내를 보여준다. */
enum class AuthError(val message: String) {
    EMPTY_EMAIL("이메일을 입력해 주세요."),
    INVALID_EMAIL("이메일 형식이 올바르지 않아요."),
    EMPTY_PASSWORD("비밀번호를 입력해 주세요."),
    WEAK_PASSWORD("비밀번호는 ${CredentialValidator.MIN_PASSWORD_LENGTH}자 이상, 영문과 숫자를 섞어 주세요."),
    WRONG_CREDENTIALS("이메일 또는 비밀번호가 맞지 않아요."),
    EMAIL_IN_USE("이미 가입된 이메일이에요. 로그인해 주세요."),
    OTHER_SIGN_IN_METHOD("이 이메일은 다른 방법(구글 로그인 등)으로 가입되어 있어요."),
    TOO_MANY_REQUESTS("시도가 너무 많아요. 잠시 후 다시 해 주세요."),
    NETWORK("인터넷 연결을 확인해 주세요."),
    CANCELLED("로그인을 취소했어요."),
    NO_GOOGLE_ACCOUNT("이 기기에 구글 계정이 없어요. 설정에서 계정을 추가해 주세요."),
    NOT_CONFIGURED("Firebase 설정 파일(google-services.json)이 없어요."),
    UNKNOWN("알 수 없는 문제가 생겼어요. 다시 시도해 주세요."),
}

/** [AuthError]를 담아 던지는 예외. 저장소(Repository)가 실패를 알릴 때 쓴다. */
class AuthException(val error: AuthError, cause: Throwable? = null) : Exception(error.name, cause)

/**
 * Firebase가 돌려주는 오류 코드를 우리 [AuthError]로 바꾼다.
 *
 * Firebase 클래스에 의존하지 않는 순수 함수라서 PC에서 바로 테스트할 수 있다.
 * 코드 목록: FirebaseAuthException.errorCode
 */
object AuthErrorMapper {
    fun fromFirebaseCode(code: String?): AuthError = when (code) {
        "ERROR_INVALID_EMAIL" -> AuthError.INVALID_EMAIL
        "ERROR_WEAK_PASSWORD" -> AuthError.WEAK_PASSWORD
        "ERROR_EMAIL_ALREADY_IN_USE" -> AuthError.EMAIL_IN_USE
        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> AuthError.OTHER_SIGN_IN_METHOD
        // 새 Firebase 프로젝트는 "이메일 열거 보호"가 켜져 있어서
        // 없는 계정·틀린 비밀번호를 구분하지 않고 INVALID_CREDENTIAL로 알려준다.
        "ERROR_INVALID_CREDENTIAL",
        "ERROR_WRONG_PASSWORD",
        "ERROR_USER_NOT_FOUND",
        "ERROR_USER_DISABLED",
        -> AuthError.WRONG_CREDENTIALS
        "ERROR_TOO_MANY_REQUESTS" -> AuthError.TOO_MANY_REQUESTS
        "ERROR_NETWORK_REQUEST_FAILED" -> AuthError.NETWORK
        else -> AuthError.UNKNOWN
    }
}
