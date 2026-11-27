package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TapCounterTest {

    @Test
    fun `7번 연달아 누르면 0을 돌려준다`() {
        val c = TapCounter(required = 7, gapMs = 1500)
        val left = (0 until 7).map { c.onTap(it * 300L) }

        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), left)
    }

    @Test
    fun `중간에 오래 쉬면 처음부터 다시 센다`() {
        val c = TapCounter(required = 3, gapMs = 1000)
        c.onTap(0)
        c.onTap(500)

        assertEquals(2, c.onTap(3000)) // 2.5초 쉼 → 첫 번째로 다시 센다
    }

    @Test
    fun `다 채운 뒤에는 다시 처음부터 센다`() {
        val c = TapCounter(required = 2, gapMs = 1000)
        c.onTap(0)
        assertEquals(0, c.onTap(100))

        assertEquals(1, c.onTap(200))
    }
}
