package com.watchout.mobile.profile

/**
 * 앱을 쓰는 사람의 역할(모드). 로그인 후 처음 한 번 고르고 Firestore `users/{uid}.role`에 저장한다.
 *
 * @property firestoreValue Firestore에 저장하는 글자. 보안 규칙(firebase/firestore.rules)도 이 두 값만 받는다.
 */
enum class UserRole(val firestoreValue: String, val label: String, val description: String) {
    PATIENT("patient", "본인(환자)", "응급 상황에 연락받을 보호자 연락처를 등록해요."),
    GUARDIAN("guardian", "보호자", "가족이 쓰러지면 알림을 받아요."),
    ;

    companion object {
        /** Firestore 값 → 역할. 없거나 모르는 값이면 null (= 아직 안 고름) */
        fun fromFirestore(value: String?): UserRole? = entries.firstOrNull { it.firestoreValue == value }
    }
}

/** 역할 화면에서 보여줄 오류 */
enum class ProfileError(val message: String) {
    LOAD_FAILED("모드를 불러오지 못했어요. 인터넷 연결을 확인해 주세요."),
    SAVE_FAILED("모드를 저장하지 못했어요. 잠시 후 다시 시도해 주세요."),
}
