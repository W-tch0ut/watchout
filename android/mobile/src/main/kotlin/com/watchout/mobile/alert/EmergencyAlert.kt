package com.watchout.mobile.alert

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 알림을 누가 만들었나 */
enum class AlertSource(val firestoreValue: String) {
    /** 환자 모드의 [테스트 응급 알림] 버튼 (로봇카 없이 데모) */
    TEST("test"),

    /** 로봇카가 워치 충격 + 카메라 확인으로 쓰러짐을 확정 (다음 단계에서 연결) */
    ROBOT("robot"),
    ;

    companion object {
        fun fromFirestore(value: String?): AlertSource = entries.firstOrNull { it.firestoreValue == value } ?: ROBOT
    }
}

/**
 * 응급 사진 (작게 줄인 JPEG).
 * ByteArray는 ==로 내용을 비교하지 않아서, 내용으로 비교하도록 감싼다.
 */
class AlertPhoto(val jpeg: ByteArray) {
    override fun equals(other: Any?): Boolean = other is AlertPhoto && jpeg.contentEquals(other.jpeg)

    override fun hashCode(): Int = jpeg.contentHashCode()

    companion object {
        /** Firestore 문서 하나는 1MB까지라서 사진은 500KB까지만 넣는다 (보안 규칙도 같은 값) */
        const val MAX_BYTES = 500 * 1024
    }
}

/**
 * 응급 알림 한 건 (#50). 쓰러짐이 **확정**된 경우에만 만든다.
 *
 * Firestore 위치: users/{환자 uid}/alerts/{id}
 * 필드: kind("fall"), source("test"/"robot"), moving, call119("simulated"), photo(선택), createdAt,
 *       ackedBy·ackedByName·ackedAt (보호자가 [확인했어요]를 누르면)
 *
 * 영상·센서 원본은 넣지 않는다. 응급 확정 때 찍은 사진 한 장과 "움직임 있음/없음"만 담는다.
 *
 * @property createdAtMillis 서버 시각. 방금 만들어 아직 서버 시각을 모르면 추정값.
 * @property ackedByName 처음 확인한 보호자 이름. 아직 아무도 확인 안 했으면 null.
 */
data class EmergencyAlert(
    val id: String,
    val patientUid: String,
    val createdAtMillis: Long?,
    val moving: Boolean,
    val source: AlertSource,
    val photo: AlertPhoto?,
    val ackedByName: String?,
) {
    val isAcknowledged: Boolean get() = ackedByName != null
}

/** 새 알림을 만들 때 넣는 값 (id·시각은 서버가 정한다) */
data class NewAlert(
    val moving: Boolean,
    val source: AlertSource,
    val photo: AlertPhoto?,
)

/** 알림을 다룰 때 생길 수 있는 문제 */
enum class AlertError(val message: String) {
    SEND_FAILED("응급 알림을 저장하지 못했어요. 인터넷 연결을 확인해 주세요."),
    ACK_FAILED("확인 표시를 하지 못했어요. 다시 시도해 주세요."),
    LOAD_FAILED("알림을 불러오지 못했어요."),
    PHOTO_TOO_BIG("사진이 너무 커요."),
}

/** 화면에 보여줄 글자 만들기. 안드로이드에 기대지 않아서 PC에서 테스트할 수 있다. */
object AlertFormat {
    private val TIME = DateTimeFormatter.ofPattern("M/d HH:mm")

    /** 1764223380000 → "11/27 14:03" (시각을 모르면 "방금") */
    fun time(millis: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        millis?.let { TIME.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "방금"

    fun moving(moving: Boolean): String = if (moving) "움직임 있음" else "움직임 없음"
}
