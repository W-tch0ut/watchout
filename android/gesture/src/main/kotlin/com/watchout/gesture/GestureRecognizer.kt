package com.watchout.gesture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 센서 값을 하나씩 받아서 제스처를 알려주는 인식기.
 *
 * 지금은 [RuleBasedGestureRecognizer]를 쓴다. 데이터가 모이면 같은 인터페이스로
 * TFLite 모델 인식기를 만들어 갈아끼운다. 그래서 워치 앱 코드는 바꿀 필요가 없다.
 */
interface GestureRecognizer {
    /** @return 동작 하나가 끝났으면 결과, 아니면 null */
    fun onSample(sample: SensorSample): GestureResult?

    fun reset()
}

/**
 * 규칙 판단 기준값.
 *
 * 처음 값은 손목을 크게, 빠르게 움직인다고 가정하고 정했다. 실제 데이터를 보고 조정한다.
 */
data class RuleConfig(
    /** 손목 비틀기(X축 회전)로 볼 최소 회전량. 0.8 rad ≈ 46도 */
    val rotateMinRad: Float = 0.8f,
    /** 회전량이 이 값이면 회전 크기 점수가 꽉 찬다. */
    val rotateFullRad: Float = 1.2f,
    /** 팔 뻗기·휘두르기·누르기로 볼 최소 가속도 */
    val translateMinPeak: Float = 4.0f,
    /** 가속도가 이 값이면 크기 점수가 꽉 찬다. */
    val translateFullPeak: Float = 6.0f,
    /** 첫 봉우리를 찾을 때 "움직이기 시작했다"고 보는 자이로 값 (rad/s) */
    val lobeStartGyro: Float = 0.5f,
    /** 이 값보다 확신이 낮으면 [Gesture.IDLE]로 답한다. */
    val minConfidence: Float = 0.5f,
)

/**
 * 규칙 기반 제스처 인식기.
 *
 * 1. [MotionSegmenter]로 동작 하나를 잘라낸다.
 * 2. 손목 비틀기(X축 회전)가 크면 → 회전 방향으로 [Gesture.ROTATE_CW] / [Gesture.ROTATE_CCW]
 * 3. 아니면 가장 크게 움직인 축과, 그 축에서 처음 움직인 방향으로 판단한다.
 *    - X(손가락 방향): + 뻗기 / - 당기기
 *    - Y(손등을 위로 했을 때 왼쪽): + 왼쪽 / - 오른쪽 휘두르기
 *    - Z(손등 방향): - 아래로 누르기
 * 4. 한 축이 다른 축보다 뚜렷할수록 확신(confidence)이 높다.
 */
class RuleBasedGestureRecognizer(
    var hand: WearingHand = WearingHand.LEFT,
    private val config: RuleConfig = RuleConfig(),
    segmenterConfig: SegmenterConfig = SegmenterConfig(),
) : GestureRecognizer {

    private val segmenter = MotionSegmenter(segmenterConfig)

    override fun onSample(sample: SensorSample): GestureResult? {
        val frames = segmenter.onSample(sample.normalizedFor(hand)) ?: return null
        return classify(frames)
    }

    override fun reset() = segmenter.reset()

    /** 잘라낸 구간 하나를 판단한다. 테스트에서 직접 부를 수 있게 공개한다. */
    fun classify(frames: List<MotionFrame>): GestureResult {
        val features = extractFeatures(frames, config.lobeStartGyro)
        val (gesture, confidence) = decide(features)
        return GestureResult(
            gesture = if (confidence >= config.minConfidence) gesture else Gesture.IDLE,
            confidence = confidence,
            startMs = frames.first().timestampMs,
            endMs = frames.last().timestampMs,
            features = features,
        )
    }

    private fun decide(f: GestureFeatures): Pair<Gesture, Float> {
        val rx = abs(f.rotation.x)
        val rOther = max(abs(f.rotation.y), abs(f.rotation.z))
        if (rx >= config.rotateMinRad) {
            val dominance = rx / (rx + rOther)
            val confidence = clamp01((dominance - 0.5f) * 2f) * min(1f, rx / config.rotateFullRad)
            return (if (f.rotation.x > 0) Gesture.ROTATE_CW else Gesture.ROTATE_CCW) to confidence
        }

        val peaks = floatArrayOf(f.linearPeak.x, f.linearPeak.y, f.linearPeak.z)
        val axis = peaks.indices.maxBy { peaks[it] }
        val peak = peaks[axis]
        if (peak < config.translateMinPeak) return Gesture.IDLE to 0f

        val dominance = peak / peaks.sum()
        val confidence = clamp01((dominance - 1f / 3f) * 1.5f) * min(1f, peak / config.translateFullPeak)
        val positive = f.linearFirstSign[axis] > 0
        val gesture = when (axis) {
            0 -> if (positive) Gesture.PUSH else Gesture.PULL
            1 -> if (positive) Gesture.SWING_LEFT else Gesture.SWING_RIGHT
            else -> if (positive) Gesture.IDLE else Gesture.PRESS_DOWN // 위로 들기는 아직 쓰지 않는다
        }
        return gesture to confidence
    }

    companion object {
        fun extractFeatures(frames: List<MotionFrame>, lobeStartGyro: Float): GestureFeatures {
            require(frames.isNotEmpty()) { "빈 구간" }
            val times = frames.map { it.timestampMs }
            val rotation = Vec3(
                firstLobeIntegral(times, frames.map { it.gx }, lobeStartGyro),
                firstLobeIntegral(times, frames.map { it.gy }, lobeStartGyro),
                firstLobeIntegral(times, frames.map { it.gz }, lobeStartGyro),
            )
            val axes = listOf(frames.map { it.lx }, frames.map { it.ly }, frames.map { it.lz })
            val peaks = axes.map { values -> values.maxOf { abs(it) } }
            val signs = axes.mapIndexed { i, values -> firstSign(values, peaks[i] * 0.5f) }
            return GestureFeatures(
                durationMs = times.last() - times.first(),
                rotation = Vec3(rotation.x, rotation.y, rotation.z),
                linearPeak = Vec3(peaks[0], peaks[1], peaks[2]),
                linearFirstSign = Vec3(signs[0], signs[1], signs[2]),
            )
        }

        /**
         * 값이 처음으로 [startThreshold]를 넘은 곳부터 부호가 바뀌기 전까지 적분한다.
         * 갔다가 돌아오는 회전에서 "간 쪽"의 회전량만 구한다. (rad/s × s = rad)
         */
        fun firstLobeIntegral(times: List<Long>, values: List<Float>, startThreshold: Float): Float {
            val start = values.indexOfFirst { abs(it) >= startThreshold }
            if (start < 0) return 0f
            val sign = if (values[start] > 0) 1f else -1f
            // 봉우리가 시작되기 직전(0 또는 반대 부호) 지점부터 적분해야 올라가는 부분까지 들어간다.
            var from = start
            while (from > 0 && values[from - 1] * sign > 0) from--
            if (from > 0) from--
            var sum = 0f
            for (i in from + 1 until values.size) {
                if (i > start && values[i] * sign <= 0) break
                // 반대 부호 쪽은 0으로 잘라서 봉우리 면적만 더한다.
                val a = max(values[i - 1] * sign, 0f)
                val b = max(values[i] * sign, 0f)
                sum += (a + b) / 2f * (times[i] - times[i - 1]) / 1000f
            }
            return sum * sign
        }

        private fun firstSign(values: List<Float>, threshold: Float): Float {
            if (threshold <= 0f) return 0f
            val first = values.firstOrNull { abs(it) >= threshold } ?: return 0f
            return if (first > 0) 1f else -1f
        }

        private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
    }
}
