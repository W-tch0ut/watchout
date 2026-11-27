package com.watchout.mobile.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class InviteCodeTest {

    @Test
    fun `코드는 항상 숫자 6자리다 (작은 수는 앞에 0을 채운다)`() {
        val alwaysSeven = object : Random() {
            override fun nextInt(bound: Int): Int = 7
        }
        assertEquals("000007", InviteCode.generate(alwaysSeven))

        val random = Random(42)
        repeat(200) {
            val code = InviteCode.generate(random)
            assertTrue(code.length == 6 && code.all(Char::isDigit), code)
        }
    }

    @Test
    fun `입력할 때 띄어쓰기나 하이픈을 넣어도 숫자 6자리로 정리한다`() {
        assertEquals("123456", InviteCode.normalize("123 456"))
        assertEquals("123456", InviteCode.normalize("123-456"))
        assertEquals("000001", InviteCode.normalize("000001"))
    }

    @Test
    fun `6자리가 아니면 null`() {
        assertNull(InviteCode.normalize(""))
        assertNull(InviteCode.normalize("12345"))
        assertNull(InviteCode.normalize("1234567"))
        assertNull(InviteCode.normalize("abcdef"))
    }

    @Test
    fun `10분이 지나면 만료, 만든 시각을 모르면 만료로 본다`() {
        val created = 1_000_000L
        assertFalse(InviteCode.isExpired(created, created))
        assertFalse(InviteCode.isExpired(created, created + InviteCode.VALID_MILLIS - 1))
        assertTrue(InviteCode.isExpired(created, created + InviteCode.VALID_MILLIS))
        assertTrue(InviteCode.isExpired(null, created))
    }
}
