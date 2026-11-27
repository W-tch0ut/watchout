package com.watchout.mobile.alert

import com.watchout.mobile.auth.AppUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EmergencyAlertTest {

    @Test
    fun `시각은 월·일 시·분으로, 모르면 방금으로 보여준다`() {
        val seoul = ZoneId.of("Asia/Seoul")
        val millis = ZonedDateTime.of(2026, 11, 27, 14, 3, 0, 0, seoul).toInstant().toEpochMilli()
        assertEquals("11/27 14:03", AlertFormat.time(millis, seoul))
        assertEquals("방금", AlertFormat.time(null, seoul))
    }

    @Test
    fun `알림 출처 값을 읽고, 모르는 값은 로봇카로 본다`() {
        assertEquals(AlertSource.TEST, AlertSource.fromFirestore("test"))
        assertEquals(AlertSource.ROBOT, AlertSource.fromFirestore("robot"))
        assertEquals(AlertSource.ROBOT, AlertSource.fromFirestore(null))
    }

    @Test
    fun `사진은 내용이 같으면 같은 사진이다`() {
        assertEquals(AlertPhoto(byteArrayOf(1, 2, 3)), AlertPhoto(byteArrayOf(1, 2, 3)))
        assertNotEquals(AlertPhoto(byteArrayOf(1, 2, 3)), AlertPhoto(byteArrayOf(1, 2)))
    }

    @Test
    fun `보여줄 이름은 계정 이름, 없으면 이메일 앞부분`() {
        assertEquals("홍길동", AppUser("u", "hong@example.com", " 홍길동 ").shownName)
        assertEquals("hong", AppUser("u", "hong@example.com", null).shownName)
        assertEquals("가족", AppUser("u", null, "").shownName)
    }
}
