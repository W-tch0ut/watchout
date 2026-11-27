package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionCsvTest {

    @Test
    fun `센서 한 줄은 실제 시각, 센서 이름, 값 순서다`() {
        assertEquals(
            "1795742707790,acc,0.12,-0.3,9.81",
            SessionCsv.line(1_795_742_707_790, SessionCsv.ACC, floatArrayOf(0.12f, -0.3f, 9.81f)),
        )
        assertEquals("5,ppg,1.0", SessionCsv.line(5, SessionCsv.PPG, floatArrayOf(1f)))
    }

    @Test
    fun `appendLine은 line과 같은 내용을 이어 붙인다`() {
        val sb = StringBuilder("x")
        SessionCsv.appendLine(sb, 7, SessionCsv.GYRO, floatArrayOf(1f, 2f))

        assertEquals("x" + SessionCsv.line(7, SessionCsv.GYRO, floatArrayOf(1f, 2f)), sb.toString())
    }

    @Test
    fun `아주 작은 값은 지수 표기로 나와도 숫자로 다시 읽힌다`() {
        val line = SessionCsv.line(1, SessionCsv.GYRO, floatArrayOf(1e-5f, 0f, -0f))

        assertEquals(listOf(1e-5f, 0f, -0f), line.split(',').drop(2).map { it.toFloat() })
    }

    @Test
    fun `정보 줄은 쉼표로 구분하고 값 안의 쉼표는 공백으로 바꾼다`() {
        val line = SessionCsv.infoLine(linkedMapOf("hand" to "LEFT", "ppg" to "HR Raw, v1"))

        assertEquals("# watchout session v1,hand=LEFT,ppg=HR Raw  v1", line)
    }

    @Test
    fun `센서 시각을 시작 순간의 두 시계 차이로 실제 시각으로 바꾼다`() {
        // 시작: 실제 시각 1_000_000ms, 부팅 후 5초(5e9 ns)
        val clock = ClockConverter(wallNowMs = 1_000_000, elapsedNowNs = 5_000_000_000)

        assertEquals(1_000_000, clock.toEpochMs(5_000_000_000))
        assertEquals(1_000_020, clock.toEpochMs(5_020_000_000)) // 20ms 뒤
        assertEquals(999_990, clock.toEpochMs(4_990_000_000)) // 시작 직전에 측정된 값
    }
}
