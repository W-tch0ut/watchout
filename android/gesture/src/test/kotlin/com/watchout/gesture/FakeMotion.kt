package com.watchout.gesture

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 테스트용 가짜 센서 데이터 만들기 (50Hz).
 *
 * 워치를 손등이 위로 오게(화면이 하늘을 보게) 왼손에 찼다고 가정한다.
 * 가만히 있으면 가속도는 Z축으로 +9.81만 나온다.
 */
class FakeMotion(private val noise: Float = 0.05f, seed: Int = 1) {
    private val random = Random(seed)
    private var t = 0L
    val samples = mutableListOf<SensorSample>()

    fun rest(ms: Long): FakeMotion = motion(ms) { _ -> FloatArray(6) }

    /**
     * 갔다가 돌아오는 동작 하나. 한 주기 사인파라서 앞 절반은 [axis] 방향(+ 이면 양수), 뒤 절반은 반대다.
     * @param axis 0~2 = 가속도 X/Y/Z, 3~5 = 자이로 X/Y/Z
     */
    fun flick(axis: Int, amplitude: Float, ms: Long = 400): FakeMotion = motion(ms) { phase ->
        FloatArray(6).also { it[axis] = amplitude * sin(2 * PI * phase).toFloat() }
    }

    /** 여러 축을 동시에 움직이는 동작 */
    fun flickMany(vararg axisAmplitude: Pair<Int, Float>, ms: Long = 400): FakeMotion = motion(ms) { phase ->
        FloatArray(6).also { v -> axisAmplitude.forEach { (a, amp) -> v[a] = amp * sin(2 * PI * phase).toFloat() } }
    }

    /** 오래 계속 흔들기 (걷기, 손 털기 같은 일상 동작) */
    fun shake(ms: Long, amplitude: Float = 8f, periodMs: Long = 300): FakeMotion = motion(ms) { _ ->
        FloatArray(6).also { it[1] = amplitude * sin(2 * PI * t / periodMs).toFloat() }
    }

    private fun motion(ms: Long, values: (phase: Double) -> FloatArray): FakeMotion {
        val start = t
        while (t < start + ms) {
            val v = values((t - start).toDouble() / ms)
            samples += SensorSample(
                timestampMs = t,
                ax = v[0] + n(), ay = v[1] + n(), az = 9.81f + v[2] + n(),
                gx = v[3] + n(), gy = v[4] + n(), gz = v[5] + n(),
            )
            t += 20
        }
        return this
    }

    private fun n() = (random.nextFloat() * 2 - 1) * noise
}

/** 샘플을 차례로 넣고 나온 결과를 모두 모은다. */
fun GestureRecognizer.runAll(samples: List<SensorSample>): List<GestureResult> =
    samples.mapNotNull { onSample(it) }
