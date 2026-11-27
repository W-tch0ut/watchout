package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DtwMatcherTest {

    /** 손목 까딱 [flaps]번 (Y축 자이로가 왔다 갔다) — "부르기" 같은 동작 */
    private fun beckon(flaps: Int, ms: Long = 300L * flaps, amp: Float = 5f, seed: Int = 0): List<SensorSample> =
        FakeMotion(seed = seed).rest(600).flickMany(4 to amp, ms = ms).let { motion ->
            // flickMany는 한 주기만 만들어서, 여러 번 까딱은 이어 붙인다
            repeat(flaps - 1) { motion.flickMany(4 to amp * (0.9f + 0.2f * Random(seed + it).nextFloat()), ms = ms / flaps) }
            motion.rest(800).samples
        }

    /** 팔을 앞으로 뻗었다 돌아오기 (X축 가속도) — 다른 동작 */
    private fun push(seed: Int = 0) = FakeMotion(seed = seed).rest(600).flick(0, 8f).rest(800).samples

    private fun shapeOf(samples: List<SensorSample>) =
        DtwMatcher.shape(DtwMatcher.extractSegment(samples, WearingHand.LEFT)!!)

    @Test
    fun `녹화에서 동작 구간을 찾는다`() {
        val seg = DtwMatcher.extractSegment(push(), WearingHand.LEFT)

        assertNotNull(seg)
        assertTrue(seg!!.size in 10..60, "구간 길이: ${seg.size}")
        assertNull(DtwMatcher.extractSegment(FakeMotion().rest(2500).samples, WearingHand.LEFT))
    }

    @Test
    fun `모양은 항상 같은 길이다`() {
        assertEquals(DtwMatcher.LENGTH * 6, shapeOf(push()).size)
        assertEquals(DtwMatcher.LENGTH * 6, shapeOf(beckon(3)).size)
    }

    @Test
    fun `같은 모양끼리 거리는 0이고 다른 동작은 멀다`() {
        val a = shapeOf(beckon(3))

        assertEquals(0f, DtwMatcher.distance(a, a), 1e-6f)
        assertTrue(DtwMatcher.distance(a, shapeOf(push())) > DtwMatcher.distance(a, shapeOf(beckon(3, seed = 5))))
    }

    @Test
    fun `등록한 제스처를 알아듣고 다른 동작은 무시한다`() {
        val beckonGesture = DtwMatcher.register(
            "g1", "부르기", CarCommand.APPROACH,
            (1..5).map { shapeOf(beckon(3, seed = it)) },
        )

        val hit = DtwMatcher.match(shapeOf(beckon(3, seed = 42)), listOf(beckonGesture))
        assertEquals("부르기", hit?.gesture?.name)
        assertEquals(CarCommand.APPROACH, hit?.gesture?.command)

        assertNull(DtwMatcher.match(shapeOf(push(seed = 7)), listOf(beckonGesture)))
    }

    @Test
    fun `제스처가 여러 개면 더 닮은 쪽을 고른다`() {
        val beckonGesture = DtwMatcher.register("g1", "부르기", CarCommand.APPROACH, (1..4).map { shapeOf(beckon(3, seed = it)) })
        val pushGesture = DtwMatcher.register("g2", "밀기", CarCommand.STOP, (1..4).map { shapeOf(push(seed = it)) })
        val all = listOf(beckonGesture, pushGesture)

        assertEquals("밀기", DtwMatcher.match(shapeOf(push(seed = 99)), all)?.gesture?.name)
        assertEquals("부르기", DtwMatcher.match(shapeOf(beckon(3, seed = 99)), all)?.gesture?.name)
    }

    @Test
    fun `조금 빠르거나 느리게 해도 알아듣는다 (DTW)`() {
        val g = DtwMatcher.register("g1", "밀기", CarCommand.STOP, (1..4).map { shapeOf(push(seed = it)) })
        val slower = FakeMotion(seed = 77).rest(600).flick(0, 8f, ms = 520).rest(800).samples

        assertNotNull(DtwMatcher.match(shapeOf(slower), listOf(g)))
    }

    @Test
    fun `시연보다 훨씬 약한 움직임(타자 치기 등)은 기준 거리가 넓어도 무시한다`() {
        val templates = (1..5).map { shapeOf(beckon(3, seed = it)) }
        // 예전 방식처럼 기준 거리가 지나치게 넓게 저장된 제스처 (실제 워치에서 생긴 문제)
        val loose = CustomGesture("g1", "부르기", CarCommand.APPROACH, templates, threshold = 100f)
        // 같은 리듬이지만 아주 약한 손목 움직임 (시연 모양을 0.2배로 줄임)
        val typing = shapeOf(beckon(3, seed = 9)).map { it * 0.2f }.toFloatArray()

        assertNull(DtwMatcher.match(typing, listOf(loose)))
        assertNotNull(DtwMatcher.match(shapeOf(beckon(3, seed = 42)), listOf(loose))) // 제대로 하면 여전히 인식
    }

    @Test
    fun `기준 거리는 가만히 있음까지 거리보다 확실히 작다`() {
        val templates = (1..5).map { shapeOf(beckon(3, seed = it)) }
        val g = DtwMatcher.register("g1", "부르기", CarCommand.APPROACH, templates)
        val loose = g.copy(threshold = 100f)
        val minStill = templates.minOf { DtwMatcher.stillDistance(it) }

        assertTrue(g.threshold <= DtwMatcher.STILL_RATIO * minStill + 1e-4f)
        assertTrue(DtwMatcher.effectiveThreshold(loose) <= DtwMatcher.STILL_RATIO * minStill + 1e-4f)
        // "전혀 안 움직임"은 어떤 경우에도 기준 밖
        assertTrue(DtwMatcher.distance(FloatArray(DtwMatcher.LENGTH * 6), templates[0]) > DtwMatcher.effectiveThreshold(loose))
    }

    @Test
    fun `가장 가까운 제스처와 거리를 설명한다 (기준 맞추기용)`() {
        val templates = (1..5).map { shapeOf(beckon(3, seed = it)) }
        val g = DtwMatcher.register("g1", "부르기", CarCommand.APPROACH, templates)

        assertTrue(DtwMatcher.explain(shapeOf(beckon(3, seed = 42)), listOf(g))!!.endsWith("✓"))
        assertEquals("부르기 약함", DtwMatcher.explain(templates[0].map { it * 0.2f }.toFloatArray(), listOf(g)))
        assertNull(DtwMatcher.explain(templates[0], emptyList()))
    }

    @Test
    fun `기준 거리는 최솟값 아래로 내려가지 않는다`() {
        val s = shapeOf(push())
        val g = DtwMatcher.register("g", "같은 것만", CarCommand.STOP, listOf(s, s.copyOf()))

        assertTrue(g.threshold >= 0.6f)
    }
}
