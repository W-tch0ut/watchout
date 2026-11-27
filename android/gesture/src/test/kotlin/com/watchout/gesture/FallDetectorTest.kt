package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallDetectorTest {

    private val g = 9.81f

    /** 가속도 크기만 정하는 가짜 샘플 (Z축에 몰아 넣는다) */
    private class Timeline {
        var t = 0L
        val samples = mutableListOf<SensorSample>()
        fun hold(ms: Long, accelG: Float, gyro: Float = 0f) {
            val end = t + ms
            while (t < end) {
                samples += SensorSample(t, 0f, 0f, accelG * 9.81f, gyro, 0f, 0f)
                t += 20
            }
        }
    }

    private fun detect(tl: Timeline): Float? {
        val d = FallDetector()
        return tl.samples.firstNotNullOfOrNull { d.onSample(it) }
    }

    @Test
    fun `자유낙하 → 충격 → 가만히 누워 있음은 낙상이다`() {
        val tl = Timeline().apply {
            hold(1000, 1f)
            hold(300, 0.2f) // 떨어지는 중
            hold(60, 3.5f) // 바닥에 부딪힘
            hold(3500, 1f) // 움직이지 않음
        }

        val impact = detect(tl)
        assertNotNull(impact)
        assertEquals(3.5f, impact!!, 0.01f)
    }

    @Test
    fun `손짓처럼 세게 휘두른 뒤 팔을 멈춰도 낙상이 아니다`() {
        // 실제 워치에서 "이리 와" 손짓이 낙상으로 잡힌 경우: 자유낙하 없이 5g 충격 → 가만히
        val tl = Timeline().apply {
            hold(1000, 1f)
            hold(80, 0.4f) // 손목을 휙 내릴 때 잠깐 가벼워짐 (짧음)
            hold(60, 5.0f)
            hold(4000, 1f)
        }

        assertNull(detect(tl))
    }

    @Test
    fun `쓰러진 뒤 2초 만에 움직이면 아직 낙상으로 보지 않는다`() {
        val tl = Timeline().apply {
            hold(1000, 1f); hold(300, 0.2f); hold(60, 3.5f)
            hold(2000, 1f) // 2초 가만히
            hold(6000, 1f, gyro = 2f) // 그 뒤 움직임
        }

        assertNull(detect(tl))
    }

    @Test
    fun `충격 뒤 바로 움직이면(일어남) 낙상이 아니다`() {
        val tl = Timeline().apply {
            hold(1000, 1f); hold(300, 0.2f); hold(60, 3.5f)
            hold(6000, 1f, gyro = 2f) // 계속 움직임
        }

        assertNull(detect(tl))
    }

    @Test
    fun `손을 세게 내려놓는 정도의 충격만으로는 낙상이 아니다`() {
        // 자유낙하 없음 + 큰 충격
        val tl = Timeline().apply { hold(1000, 1f); hold(60, 4.0f); hold(4000, 1f) }

        assertNull(detect(tl))
    }

    @Test
    fun `아주 짧은 무중력(손목 휙)은 자유낙하로 보지 않는다`() {
        val tl = Timeline().apply { hold(1000, 1f); hold(100, 0.2f); hold(60, 3.5f); hold(4000, 1f) }

        assertNull(detect(tl))
    }

    @Test
    fun `10초 안에 취소하면 알리지 않는다`() {
        val timer = FallAlertTimer()
        timer.start(0)

        assertEquals(10, timer.secondsLeft(0))
        assertEquals(FallAlertTimer.Phase.COUNTDOWN, timer.tick(9_999))
        assertTrue(timer.cancel())
        assertEquals(FallAlertTimer.Phase.CANCELLED, timer.tick(20_000))
    }

    @Test
    fun `10초가 지나면 한 번만 보내라고 한다`() {
        val timer = FallAlertTimer()
        timer.start(1_000)

        assertEquals(1, timer.secondsLeft(10_500))
        assertEquals(FallAlertTimer.Phase.SEND, timer.tick(11_000))
        // 보낸 뒤에는 취소할 수 없고, 다시 SEND로 바뀌지 않는다
        assertFalse(timer.cancel())
        assertEquals(FallAlertTimer.Phase.SEND, timer.tick(12_000))
        assertEquals(0, timer.secondsLeft(12_000))
    }

    @Test
    fun `카운트다운 중에 다시 시작해도 처음부터 세지 않는다`() {
        val timer = FallAlertTimer()
        timer.start(0)
        timer.start(5_000)

        assertEquals(FallAlertTimer.Phase.SEND, timer.tick(10_000))
    }
}
