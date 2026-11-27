package com.watchout.mobile.guardian

/**
 * 보호자 연락처 한 명.
 *
 * Firestore 위치: users/{로그인한 사람 uid}/guardians/{id}
 * 필드: name, relation, phone(숫자만, 예 "01012345678"), updatedAt
 *
 * @property id Firestore 문서 ID. 새로 만들 때는 null.
 */
data class Guardian(
    val id: String?,
    val name: String,
    val relation: String,
    val phone: String,
)

/** 보호자 연락처를 다룰 때 생길 수 있는 문제와 한국어 안내 */
enum class GuardianError(val message: String) {
    EMPTY_NAME("이름을 입력해 주세요."),
    NAME_TOO_LONG("이름은 ${GuardianValidator.MAX_NAME_LENGTH}자까지 쓸 수 있어요."),
    EMPTY_RELATION("관계를 입력해 주세요. (예: 딸, 아들)"),
    RELATION_TOO_LONG("관계는 ${GuardianValidator.MAX_RELATION_LENGTH}자까지 쓸 수 있어요."),
    INVALID_PHONE("휴대폰 번호 형식이 올바르지 않아요. (예: 010-1234-5678)"),
    TOO_MANY("보호자는 ${GuardianValidator.MAX_GUARDIANS}명까지 등록할 수 있어요."),
    LOAD_FAILED("연락처를 불러오지 못했어요. 인터넷 연결을 확인해 주세요."),
    SAVE_FAILED("저장하지 못했어요. 다시 시도해 주세요."),
    DELETE_FAILED("삭제하지 못했어요. 다시 시도해 주세요."),
}
