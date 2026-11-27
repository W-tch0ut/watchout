package com.watchout.gesture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 사용자가 직접 등록한 제스처 하나.
 *
 * @property templates 시연한 동작들. 각각 [DtwMatcher.LENGTH]개 프레임 × 6채널을 한 줄로 편 값
 * @property threshold 이 거리 안에 들어와야 이 제스처로 인정한다 (등록할 때 자동으로 정함)
 */
data class CustomGesture(
    val id: String,
    val name: String,
    val command: CarCommand,
    val templates: List<FloatArray>,
    val threshold: Float,
)

data class CustomMatch(val gesture: CustomGesture, val distance: Float)

/**
 * 몇 번만 시연해도 알아듣는 "내 제스처" 인식기 (DTW: 동적 시간 정렬).
 *
 * - 학습이 필요 없다. 등록한 동작 모양(템플릿)과 새 동작이 얼마나 닮았는지 거리를 잰다.
 * - DTW는 빠르게 하든 천천히 하든 시간 축을 늘였다 줄였다 맞춰서 비교한다.
 * - 데이터가 많이 모이면 TFLite 모델(#44)로 바꾼다.
 *
 * 동작 구간은 [MotionSegmenter]가 자른 프레임(중력을 뺀 가속도 + 자이로)을 쓴다.
 */
object DtwMatcher {
    /** 동작 하나를 이 길이로 늘이거나 줄여서 비교한다 (약 0.8초 × 50Hz 정도) */
    const val LENGTH = 40
    private const val CHANNELS = 6

    /** DTW에서 시간 어긋남을 허용하는 폭 (프레임) */
    private const val BAND = 8

    /** 가속도(m/s²)와 자이로(rad/s)의 크기 차이를 맞추는 배율 */
    private const val LINEAR_SCALE = 0.3f

    /** 등록할 때 정하는 기준 거리의 배율과 최솟값 */
    private const val THRESHOLD_FACTOR = 1.5f
    private const val MIN_THRESHOLD = 0.6f

    /**
     * 기준 거리는 "가만히 있음"까지 거리의 이 비율을 넘지 않는다.
     * 시연이 들쭉날쭉하면 기준 거리가 넓어지는데, 너무 넓으면 거의 안 움직여도(타자 치기 등)
     * 기준 안에 들어와 버린다. 그래서 "안 움직인 것"과는 확실히 떨어져 있게 상한을 둔다.
     */
    const val STILL_RATIO = 0.75f

    /** 새 동작의 세기(최대 회전 속도, 평균 움직임)가 시연 중 가장 약한 것의 이 비율은 넘어야 비교한다 */
    const val MIN_ENERGY_RATIO = 0.4f

    /** 동작 구간을 비교용 모양(길이 [LENGTH] × 6채널)으로 바꾼다 */
    fun shape(frames: List<MotionFrame>): FloatArray {
        require(frames.size >= 2) { "프레임이 너무 적어요" }
        val raw = frames.map {
            floatArrayOf(
                it.lx * LINEAR_SCALE, it.ly * LINEAR_SCALE, it.lz * LINEAR_SCALE,
                it.gx, it.gy, it.gz,
            )
        }
        val out = FloatArray(LENGTH * CHANNELS)
        for (k in 0 until LENGTH) {
            val pos = k * (raw.size - 1).toFloat() / (LENGTH - 1)
            val i = pos.toInt().coerceAtMost(raw.size - 2)
            val f = pos - i
            for (c in 0 until CHANNELS) {
                out[k * CHANNELS + c] = raw[i][c] * (1 - f) + raw[i + 1][c] * f
            }
        }
        return out
    }

    /** 두 모양 사이의 DTW 거리 (프레임당 평균 거리) */
    fun distance(a: FloatArray, b: FloatArray): Float {
        val n = LENGTH
        val inf = Float.MAX_VALUE / 4
        var prev = FloatArray(n + 1) { inf }
        var cur = FloatArray(n + 1) { inf }
        prev[0] = 0f
        for (i in 1..n) {
            cur.fill(inf)
            val from = max(1, i - BAND)
            val to = min(n, i + BAND)
            for (j in from..to) {
                val cost = frameDistance(a, i - 1, b, j - 1)
                cur[j] = cost + min(prev[j], min(cur[j - 1], prev[j - 1]))
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[n] / n
    }

    private fun frameDistance(a: FloatArray, i: Int, b: FloatArray, j: Int): Float {
        var sum = 0f
        for (c in 0 until CHANNELS) {
            val d = a[i * CHANNELS + c] - b[j * CHANNELS + c]
            sum += d * d
        }
        return sqrt(sum)
    }

    /**
     * 시연 여러 개로 제스처를 만든다.
     * 기준 거리 = (시연끼리 서로 가장 가까운 거리 중 가장 먼 값) × 1.5
     * 시연끼리 들쭉날쭉하면 기준이 넓어지고, 일정하면 좁아진다.
     */
    fun register(id: String, name: String, command: CarCommand, shapes: List<FloatArray>): CustomGesture {
        require(shapes.size >= 2) { "두 번 이상 시연해야 해요" }
        val nearest = shapes.indices.map { i ->
            shapes.indices.filter { it != i }.minOf { j -> distance(shapes[i], shapes[j]) }
        }
        val threshold = max(MIN_THRESHOLD, nearest.max() * THRESHOLD_FACTOR)
        return CustomGesture(id, name, command, shapes, min(threshold, stillCap(shapes)))
    }

    /**
     * 가장 닮은 제스처. 어느 제스처의 기준 거리 안에도 안 들어오면 null.
     *
     * 오인식을 막는 두 가지 검사를 거친다.
     * 1. 세기: 새 동작이 시연보다 훨씬 약하면(타자, 손목 살짝 돌림) 비교하지 않는다.
     * 2. 기준 거리 상한: 예전에 저장된 넓은 기준 거리도 [effectiveThreshold]로 줄여서 쓴다.
     */
    fun match(shape: FloatArray, gestures: List<CustomGesture>): CustomMatch? =
        gestures
            .filter { isStrongEnough(shape, it) }
            .map { g -> CustomMatch(g, g.templates.minOf { distance(shape, it) }) }
            .filter { it.distance <= effectiveThreshold(it.gesture) }
            .minByOrNull { it.distance / effectiveThreshold(it.gesture) }

    /**
     * 기준과 상관없이 가장 가까운 제스처와 그 이유. 기준을 맞출 때(개발자 모드) 화면에 보여주려고 쓴다.
     * 예) "부르기 2.8/3.3 ✓", "부르기 4.1/3.3", "부르기 약함"
     */
    fun explain(shape: FloatArray, gestures: List<CustomGesture>): String? {
        val g = gestures.minByOrNull { g -> g.templates.minOf { distance(shape, it) } / effectiveThreshold(g) } ?: return null
        if (!isStrongEnough(shape, g)) return "${g.name} 약함"
        val d = g.templates.minOf { distance(shape, it) }
        val t = effectiveThreshold(g)
        return "${g.name} %.1f/%.1f%s".format(d, t, if (d <= t) " ✓" else "")
    }

    /** 실제로 쓰는 기준 거리: 저장된 값과 "가만히 있음" 상한 중 작은 값 */
    fun effectiveThreshold(g: CustomGesture): Float = min(g.threshold, stillCap(g.templates))

    /** 모양이 "가만히 있음"(모든 값 0)에서 떨어진 거리 = 프레임마다 크기의 평균 */
    fun stillDistance(shape: FloatArray): Float {
        var sum = 0f
        for (k in 0 until LENGTH) sum += frameNorm(shape, k, 0)
        return sum / LENGTH
    }

    /** 가장 빠르게 돌린 순간의 회전 속도 (rad/s) */
    fun peakGyro(shape: FloatArray): Float = (0 until LENGTH).maxOf { frameNorm(shape, it, 3) }

    private fun stillCap(templates: List<FloatArray>): Float = STILL_RATIO * templates.minOf { stillDistance(it) }

    private fun isStrongEnough(shape: FloatArray, g: CustomGesture): Boolean =
        peakGyro(shape) >= MIN_ENERGY_RATIO * g.templates.minOf { peakGyro(it) } &&
            stillDistance(shape) >= MIN_ENERGY_RATIO * g.templates.minOf { stillDistance(it) }

    /** 프레임 [k]의 크기. from=0이면 6채널 전부, from=3이면 자이로 3채널만 */
    private fun frameNorm(shape: FloatArray, k: Int, from: Int): Float {
        var sum = 0f
        for (c in from until CHANNELS) {
            val v = shape[k * CHANNELS + c]
            sum += v * v
        }
        return sqrt(sum)
    }

    /**
     * 녹화 한 번(약 2.5초)에서 동작 구간을 찾는다. 여러 개면 가장 크게 움직인 구간.
     * 녹화가 동작 중에 끝났을 수 있어서, 마지막 값을 0.5초 더 넣어 마무리한다.
     */
    fun extractSegment(samples: List<SensorSample>, hand: WearingHand): List<MotionFrame>? {
        if (samples.isEmpty()) return null
        val segmenter = MotionSegmenter()
        val last = samples.last()
        val padded = samples + (1..25).map { last.copy(timestampMs = last.timestampMs + it * 20L) }
        return padded
            .mapNotNull { segmenter.onSample(it.normalizedFor(hand)) }
            .maxByOrNull { seg -> seg.sumOf { (abs(it.gx) + abs(it.gy) + abs(it.gz)).toDouble() } }
    }
}
