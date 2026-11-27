package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class RuleBasedGestureRecognizerTest {

    private fun recognizeOne(motion: FakeMotion, hand: WearingHand = WearingHand.LEFT): List<GestureResult> =
        RuleBasedGestureRecognizer(hand).runAll(motion.samples)

    @ParameterizedTest(name = "{0}")
    @MethodSource("flicks")
    fun `갔다 오는 동작 하나를 맞게 인식한다`(expected: Gesture, axis: Int, amplitude: Float) {
        val results = recognizeOne(FakeMotion().rest(500).flick(axis, amplitude).rest(600))

        assertEquals(1, results.size, "결과는 하나여야 한다: $results")
        assertEquals(expected, results[0].gesture)
        assertTrue(results[0].confidence >= 0.5f, "확신이 낮다: ${results[0].confidence}")
    }

    @Test
    fun `손목 회전은 갔다 돌아와도 처음 돌린 방향으로 인식한다`() {
        // 0.6초 동안 시계방향으로 돌렸다가 돌아오기. 첫 봉우리 회전량 ≈ 1.15 rad
        val results = recognizeOne(FakeMotion().rest(500).flick(3, 6f, ms = 600).rest(600))

        assertEquals(listOf(Gesture.ROTATE_CW), results.map { it.gesture })
        assertTrue(results[0].features.rotation.x > 1f)
    }

    @Test
    fun `휘두르기에 따라오는 Z축 회전은 손목 회전으로 착각하지 않는다`() {
        // 팔을 옆으로 휘두르면 워치가 수평으로 돌아서 Z축 자이로도 같이 나온다.
        val motion = FakeMotion().rest(500).flickMany(1 to 8f, 5 to 3f).rest(600)

        assertEquals(listOf(Gesture.SWING_LEFT), recognizeOne(motion).map { it.gesture })
    }

    @Test
    fun `가만히 있거나 살짝 떨리면 아무것도 인식하지 않는다`() {
        val motion = FakeMotion(noise = 0.3f).rest(3000)

        assertTrue(recognizeOne(motion).isEmpty())
    }

    @Test
    fun `계속 흔드는 긴 움직임은 제스처로 보지 않는다`() {
        val motion = FakeMotion().rest(500).shake(3000).rest(600)

        assertTrue(recognizeOne(motion).isEmpty())
    }

    @Test
    fun `여러 축이 비슷하게 움직이면 확신이 낮아서 일상 동작으로 답한다`() {
        val motion = FakeMotion().rest(500).flickMany(0 to 7f, 1 to 7f, 2 to 7f).rest(600)

        assertEquals(listOf(Gesture.IDLE), recognizeOne(motion).map { it.gesture })
    }

    @Test
    fun `동작 직후 쉬는 시간 안의 움직임은 무시한다`() {
        // 첫 동작이 끝나고(조용함 200ms) 곧바로 다시 움직이면 쉬는 시간(500ms) 안이라 무시된다.
        val motion = FakeMotion().rest(500).flick(0, 8f).rest(240).flick(0, -8f).rest(800)

        assertEquals(listOf(Gesture.PUSH), recognizeOne(motion).map { it.gesture })
    }

    @Test
    fun `쉬는 시간이 지나면 다음 동작을 다시 인식한다`() {
        val motion = FakeMotion().rest(500).flick(0, 8f).rest(1000).flick(1, -8f).rest(600)

        assertEquals(listOf(Gesture.PUSH, Gesture.SWING_RIGHT), recognizeOne(motion).map { it.gesture })
    }

    @Test
    fun `오른손에 차면 X와 Y 부호를 뒤집어서 같은 동작으로 인식한다`() {
        // 오른손(기본 방향)에서는 앞으로 뻗을 때 X축이 음수로 나온다.
        val motion = FakeMotion().rest(500).flick(0, -8f).rest(600)

        assertEquals(listOf(Gesture.PUSH), recognizeOne(motion, WearingHand.RIGHT).map { it.gesture })
        assertEquals(listOf(Gesture.PULL), recognizeOne(motion, WearingHand.LEFT).map { it.gesture })
    }

    @Test
    fun `동작하면서 손목 각도가 바뀌어도 동작이 끝나고 다음 동작도 인식한다`() {
        // 실제 워치에서 "반응 없음"이 났던 상황: 동작 후 손목이 25도 기울어진 채로 멈춤
        val motion = FakeMotion().rest(500).flickWithTilt(0, 8f, tiltDeg = 25.0).rest(1000)
            .flick(1, -8f).rest(600)

        assertEquals(listOf(Gesture.PUSH, Gesture.SWING_RIGHT), recognizeOne(motion).map { it.gesture })
    }

    @Test
    fun `손목을 천천히 기울이기만 하면 아무것도 인식하지 않는다`() {
        val motion = FakeMotion().rest(500).flickWithTilt(0, 0f, tiltDeg = 40.0, ms = 2000).rest(1000)

        assertTrue(recognizeOne(motion).isEmpty())
    }

    @Test
    fun `첫 봉우리 적분은 부호가 바뀌기 전까지만 더한다`() {
        val times = listOf(0L, 100, 200, 300, 400)
        val values = listOf(0f, 2f, 2f, -2f, -2f)

        // 0→100ms: 평균 1 × 0.1s, 100→200ms: 2 × 0.1s  → 0.3
        assertEquals(0.3f, RuleBasedGestureRecognizer.firstLobeIntegral(times, values, 0.5f), 1e-4f)
    }

    companion object {
        @JvmStatic
        fun flicks() = listOf(
            Arguments.of(Gesture.PUSH, 0, 8f),
            Arguments.of(Gesture.PULL, 0, -8f),
            Arguments.of(Gesture.SWING_LEFT, 1, 8f),
            Arguments.of(Gesture.SWING_RIGHT, 1, -8f),
            Arguments.of(Gesture.PRESS_DOWN, 2, -8f),
            Arguments.of(Gesture.ROTATE_CW, 3, 8f),
            Arguments.of(Gesture.ROTATE_CCW, 3, -8f),
        )
    }
}
