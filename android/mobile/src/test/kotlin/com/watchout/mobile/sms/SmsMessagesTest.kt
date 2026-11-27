package com.watchout.mobile.sms

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsMessagesTest {

    @Test
    fun `휴대폰 번호만 문자 대상이고 119 같은 긴급 번호는 막는다`() {
        assertTrue(SmsMessages.isMobileNumber("01000000001"))
        assertTrue(SmsMessages.isMobileNumber("0110000001"))
        assertFalse(SmsMessages.isMobileNumber("119"))
        assertFalse(SmsMessages.isMobileNumber("112"))
        assertFalse(SmsMessages.isMobileNumber("010-0000-0001")) // 숫자만 받는다
        assertFalse(SmsMessages.isMobileNumber("0212345678")) // 집 전화
    }

    @Test
    fun `초대 문자에는 환자 이름, 코드, 유효 시간이 들어간다`() {
        val text = SmsMessages.invite("홍길동", "012345")
        assertTrue(text.contains("홍길동"))
        assertTrue(text.contains("012345"))
        assertTrue(text.contains("10분"))
        assertTrue(text.contains("보호자 모드"))
    }

    @Test
    fun `응급 문자는 쓰러짐과 움직임만 알리고, 테스트면 표시한다`() {
        val test = SmsMessages.emergency("홍길동", moving = false, timeMillis = null, isTest = true)
        assertTrue(test.startsWith("[WatchOut 테스트]"))
        assertTrue(test.contains("홍길동님 쓰러짐 확인"))
        assertTrue(test.contains("움직임 없음"))
        assertTrue(test.contains("방금"))

        val real = SmsMessages.emergency("홍길동", moving = true, timeMillis = null, isTest = false)
        assertTrue(real.startsWith("[WatchOut 응급]"))
        assertTrue(real.contains("움직임 있음"))
    }
}
