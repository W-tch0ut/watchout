package com.watchout.mobile.guardian

/** 검사 결과: 통과(정리된 값) 또는 실패(이유) */
sealed interface GuardianValidation {
    data class Valid(val guardian: Guardian) : GuardianValidation

    data class Invalid(val error: GuardianError) : GuardianValidation
}

/**
 * 보호자 연락처 입력값을 검사하고 저장하기 좋은 모양으로 정리한다.
 *
 * 같은 규칙이 서버 쪽 Firestore 보안 규칙(firebase/firestore.rules)에도 있다.
 * 앱은 사용자에게 바로 알려 주려고, 서버는 앱을 거치지 않은 요청까지 막으려고 검사한다.
 */
object GuardianValidator {
    const val MAX_NAME_LENGTH = 20
    const val MAX_RELATION_LENGTH = 10
    const val MAX_GUARDIANS = 5

    // 한국 휴대폰 번호: 010/011/016/017/018/019 + 7~8자리 (문자 알림을 받을 수 있는 번호)
    private val MOBILE_PATTERN = Regex("^01[016789][0-9]{7,8}$")

    /**
     * 사용자가 어떤 모양으로 입력해도 숫자만 남긴다.
     * "010-1234-5678", "010 1234 5678", "+82 10-1234-5678", "+82 010-1234-5678" → "01012345678"
     * 올바른 휴대폰 번호가 아니면 null.
     */
    fun normalizePhone(raw: String): String? {
        var digits = raw.filter { it.isDigit() }
        // 국가번호 82를 떼고 앞에 0을 하나만 붙인다 (+82 뒤에 0을 써도, 안 써도 같은 결과)
        if (raw.trim().startsWith("+82")) digits = "0" + digits.removePrefix("82").removePrefix("0")
        return digits.takeIf { MOBILE_PATTERN.matches(it) }
    }

    /** 화면에 보여줄 때: "01012345678" → "010-1234-5678" */
    fun formatPhone(digits: String): String = when (digits.length) {
        11 -> "${digits.take(3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
        10 -> "${digits.take(3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
        else -> digits
    }

    /**
     * @param existingCount 이미 등록된 보호자 수 (새로 추가할 때 최대 인원을 넘는지 본다)
     */
    fun validate(input: Guardian, existingCount: Int): GuardianValidation {
        val name = input.name.trim()
        val relation = input.relation.trim()
        val error = when {
            input.id == null && existingCount >= MAX_GUARDIANS -> GuardianError.TOO_MANY
            name.isEmpty() -> GuardianError.EMPTY_NAME
            name.length > MAX_NAME_LENGTH -> GuardianError.NAME_TOO_LONG
            relation.isEmpty() -> GuardianError.EMPTY_RELATION
            relation.length > MAX_RELATION_LENGTH -> GuardianError.RELATION_TOO_LONG
            else -> null
        }
        if (error != null) return GuardianValidation.Invalid(error)

        val phone = normalizePhone(input.phone) ?: return GuardianValidation.Invalid(GuardianError.INVALID_PHONE)
        return GuardianValidation.Valid(input.copy(name = name, relation = relation, phone = phone))
    }
}
