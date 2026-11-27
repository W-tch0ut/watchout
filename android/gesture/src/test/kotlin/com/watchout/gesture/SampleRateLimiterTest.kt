package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleRateLimiterTest {

    @Test
    fun `첫 값은 시각이 얼마든 받는다`() {
        // 실제 워치 시각은 부팅 후 경과 ms라서 크다. 예전 버그는 여기서 모든 값을 버렸다.
        for (t in listOf(0L, 18L, 123_456_789L, Long.MAX_VALUE / 2)) {
            assertTrue(SampleRateLimiter(18).accept(t), "t=$t")
        }
    }

    @Test
    fun `간격보다 가까운 값은 버리고 먼 값은 받는다`() {
        val limiter = SampleRateLimiter(18)
        val accepted = listOf(1000L, 1010L, 1018L, 1020L, 1036L, 1040L).filter { limiter.accept(it) }

        assertEquals(listOf(1000L, 1018L, 1036L), accepted)
    }

    @Test
    fun `100Hz로 들어오면 대략 50Hz로 줄인다`() {
        val limiter = SampleRateLimiter(18)
        val count = (0 until 100).count { limiter.accept(5_000_000L + it * 10L) }

        assertEquals(50, count)
    }

    @Test
    fun `reset 하면 다음 값을 바로 받는다`() {
        val limiter = SampleRateLimiter(18)
        limiter.accept(1000)
        limiter.reset()

        assertTrue(limiter.accept(1001))
    }
}
