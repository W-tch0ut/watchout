package com.watchout.mobile.link

import java.security.SecureRandom
import java.util.Random

/**
 * 환자 ↔ 보호자 연결에 쓰는 6자리 초대 코드 (#50).
 *
 * 흐름: 환자가 보호자 번호로 코드를 문자로 보낸다 → 보호자가 보호자 모드에서 코드를 입력한다 → 연결.
 * 문자를 받은 사람만 코드를 알기 때문에 "이 번호의 주인이 맞다"는 확인도 된다.
 *
 * 같은 규칙이 Firestore 보안 규칙(firebase/firestore.rules)에도 있다.
 */
object InviteCode {
    const val LENGTH = 6
    const val VALID_MINUTES = 10L
    const val VALID_MILLIS = VALID_MINUTES * 60 * 1000

    private val PATTERN = Regex("^[0-9]{$LENGTH}$")

    /** 000000~999999 중 하나. 맞히기 어렵도록 예측할 수 없는 난수(SecureRandom)를 쓴다. */
    fun generate(random: Random = SecureRandom()): String =
        random.nextInt(1_000_000).toString().padStart(LENGTH, '0')

    /** "123 456", "123-456"처럼 입력해도 숫자만 남긴다. 6자리가 아니면 null. */
    fun normalize(raw: String): String? = raw.filter { it.isDigit() }.takeIf { PATTERN.matches(it) }

    /**
     * 코드가 만료됐나? 만든 시각은 서버 시각(createdAt)을 쓴다.
     * @param createdAtMillis 서버가 기록한 만든 시각. 없으면(null) 만료로 본다.
     */
    fun isExpired(createdAtMillis: Long?, nowMillis: Long): Boolean =
        createdAtMillis == null || nowMillis - createdAtMillis >= VALID_MILLIS
}

/** 보호자가 연결한 환자 한 명. Firestore: users/{보호자 uid}/patients/{환자 uid} */
data class LinkedPatient(
    val uid: String,
    val name: String,
)

/**
 * 환자 쪽에서 본 "연결된 보호자 계정" 한 명. Firestore: users/{환자 uid}/links/{보호자 uid}
 *
 * @property guardianId 이 계정이 어느 보호자 연락처(users/{환자}/guardians/{id})로 초대받았는지
 */
data class LinkedGuardian(
    val guardianUid: String,
    val guardianId: String,
    val guardianName: String,
)

/** 연결하면서 생길 수 있는 문제와 한국어 안내 */
enum class LinkError(val message: String) {
    INVALID_CODE("초대 코드는 숫자 ${InviteCode.LENGTH}자리예요."),
    NOT_FOUND("코드가 없거나 이미 사용됐어요. 새 코드를 받아 주세요."),
    EXPIRED("코드가 만료됐어요(${InviteCode.VALID_MINUTES}분). 새 코드를 받아 주세요."),
    SELF("본인이 만든 코드는 입력할 수 없어요."),
    INVITE_FAILED("초대 코드를 만들지 못했어요. 인터넷 연결을 확인해 주세요."),
    LINK_FAILED("연결하지 못했어요. 다시 시도해 주세요."),
    UNLINK_FAILED("연결을 끊지 못했어요. 다시 시도해 주세요."),
    LOAD_FAILED("연결 정보를 불러오지 못했어요."),
}

/** [LinkError]를 담아 던지는 예외 */
class LinkException(val error: LinkError, cause: Throwable? = null) : Exception(error.name, cause)
