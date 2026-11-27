package com.watchout.gesture

import kotlin.math.abs
import kotlin.math.sqrt

/** 중력을 뺀 가속도(lx, ly, lz)와 자이로(gx, gy, gz) 한 프레임. */
data class MotionFrame(
    val timestampMs: Long,
    val lx: Float,
    val ly: Float,
    val lz: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
) {
    val linearMagnitude: Float get() = sqrt(lx * lx + ly * ly + lz * lz)
    val gyroMagnitude: Float get() = sqrt(gx * gx + gy * gy + gz * gz)
}

/**
 * 동작 구간 자르기 기준값.
 *
 * 단위: 자이로 rad/s, 가속도 m/s², 시간 ms.
 * 실제 워치로 데이터를 모은 뒤 조정한다.
 */
data class SegmenterConfig(
    /** 중력 추정 저역통과 필터의 시간 상수. 길수록 중력이 천천히 따라온다. */
    val gravityTauMs: Float = 300f,
    /** 이 값을 넘으면 동작이 시작된 것으로 본다. */
    val startGyro: Float = 1.5f,
    val startLinear: Float = 3.0f,
    /**
     * 자이로가 [quietGyro]보다 작고, 가속도 크기가 중력(9.81)과 [quietLinear] 이내로 가까운 상태가
     * [quietHoldMs] 동안 이어지면 동작이 끝난 것으로 본다.
     * 가속도 "크기"만 보기 때문에 손목이 어느 방향으로 기울어진 채 멈춰도 판단할 수 있다.
     */
    val quietGyro: Float = 0.6f,
    val quietLinear: Float = 1.2f,
    val quietHoldMs: Long = 200,
    /** 너무 짧은 움직임(떨림)과 너무 긴 움직임(걷기, 계속 흔들기)은 제스처로 보지 않는다. */
    val minDurationMs: Long = 150,
    val maxDurationMs: Long = 2000,
    /** 동작이 끝난 뒤 이 시간 동안은 새 동작을 받지 않는다. 팔이 제자리로 돌아오는 반동을 무시한다. */
    val cooldownMs: Long = 500,
    /** 시작 기준을 넘기 직전 프레임도 구간에 넣는다. 동작의 첫 움직임을 놓치지 않기 위해서다. */
    val preRollMs: Long = 100,
)

/**
 * 연속된 센서 값에서 "제스처 하나에 해당하는 구간"을 잘라낸다.
 *
 * 1. 가속도에서 중력을 뺀다. 중력은 저역통과 필터로 추정한다.
 *    동작이 진행되는 동안에는 중력 추정을 멈춘다. 빠른 움직임이 중력으로 섞여 들어가지 않게 하기 위해서다.
 * 2. 움직임이 시작 기준을 넘으면 구간을 열고, 조용한 상태가 이어지면 구간을 닫는다.
 *    "조용함"은 손목 방향과 상관없는 값(자이로 크기, 가속도 크기 ≈ 중력)으로 판단한다.
 *    고정해 둔 중력으로 판단하면 동작 후 손목이 조금만 기울어도 영원히 "움직이는 중"이 되기 때문이다.
 * 3. 구간이 끝나면 중력 추정값을 지금 방향으로 바로 맞춘다.
 * 4. 길이가 적당한 구간만 돌려준다.
 */
class MotionSegmenter(private val config: SegmenterConfig = SegmenterConfig()) {

    private enum class State { IDLE, ACTIVE, ABORTED }

    private var state = State.IDLE
    private var gravity: FloatArray? = null
    private var lastTimestampMs: Long? = null
    private val preRoll = ArrayDeque<MotionFrame>()
    private val active = ArrayList<MotionFrame>()
    private var activeStartMs = 0L
    private var lastLoudMs = 0L
    private var cooldownUntilMs = Long.MIN_VALUE

    /**
     * 센서 값 하나를 넣는다.
     * @return 구간이 막 끝났으면 그 구간의 프레임들, 아니면 null
     */
    fun onSample(sample: SensorSample): List<MotionFrame>? {
        val frame = toFrame(sample)
        val t = frame.timestampMs
        val loud = frame.gyroMagnitude > config.startGyro || frame.linearMagnitude > config.startLinear
        val accelMagnitude = sqrt(sample.ax * sample.ax + sample.ay * sample.ay + sample.az * sample.az)
        val quiet = frame.gyroMagnitude < config.quietGyro &&
            abs(accelMagnitude - GRAVITY) < config.quietLinear

        when (state) {
            State.IDLE -> {
                if (loud && t >= cooldownUntilMs) {
                    state = State.ACTIVE
                    active.clear()
                    active.addAll(preRoll)
                    active.add(frame)
                    activeStartMs = t
                    lastLoudMs = t
                    preRoll.clear()
                } else {
                    pushPreRoll(frame)
                }
            }

            State.ACTIVE -> {
                active.add(frame)
                if (!quiet) lastLoudMs = t
                if (t - activeStartMs > config.maxDurationMs) {
                    // 너무 오래 움직이면 제스처가 아니다. 조용해질 때까지 기다렸다가 버린다.
                    state = State.ABORTED
                    active.clear()
                } else if (t - lastLoudMs >= config.quietHoldMs) {
                    snapGravityTo(sample)
                    return finishSegment(t)
                }
            }

            State.ABORTED -> {
                if (!quiet) lastLoudMs = t
                if (t - lastLoudMs >= config.quietHoldMs) {
                    snapGravityTo(sample)
                    state = State.IDLE
                    cooldownUntilMs = t + config.cooldownMs
                }
            }
        }
        return null
    }

    fun reset() {
        state = State.IDLE
        gravity = null
        lastTimestampMs = null
        preRoll.clear()
        active.clear()
        cooldownUntilMs = Long.MIN_VALUE
    }

    private fun finishSegment(now: Long): List<MotionFrame>? {
        state = State.IDLE
        cooldownUntilMs = now + config.cooldownMs
        // 끝에 붙은 조용한 프레임은 빼고, 마지막으로 움직인 프레임까지만 남긴다.
        val segment = active.filter { it.timestampMs <= lastLoudMs }
        active.clear()
        val duration = lastLoudMs - activeStartMs
        return if (duration in config.minDurationMs..config.maxDurationMs) segment else null
    }

    private fun pushPreRoll(frame: MotionFrame) {
        preRoll.addLast(frame)
        while (preRoll.isNotEmpty() && frame.timestampMs - preRoll.first().timestampMs > config.preRollMs) {
            preRoll.removeFirst()
        }
    }

    /** 멈춘 순간의 가속도를 그대로 중력으로 삼는다. 동작 뒤 손목 방향이 바뀌었어도 바로 따라간다. */
    private fun snapGravityTo(s: SensorSample) {
        gravity = floatArrayOf(s.ax, s.ay, s.az)
    }

    private fun toFrame(s: SensorSample): MotionFrame {
        val g = gravity ?: floatArrayOf(s.ax, s.ay, s.az).also { gravity = it }
        val dt = (s.timestampMs - (lastTimestampMs ?: s.timestampMs)).coerceAtLeast(0L).toFloat()
        lastTimestampMs = s.timestampMs
        // 동작 중(ACTIVE)에만 중력을 고정한다. 버린 동작을 기다리는 동안(ABORTED)에는 계속 따라간다.
        if (state != State.ACTIVE) {
            val alpha = config.gravityTauMs / (config.gravityTauMs + dt)
            g[0] = alpha * g[0] + (1 - alpha) * s.ax
            g[1] = alpha * g[1] + (1 - alpha) * s.ay
            g[2] = alpha * g[2] + (1 - alpha) * s.az
        }
        return MotionFrame(s.timestampMs, s.ax - g[0], s.ay - g[1], s.az - g[2], s.gx, s.gy, s.gz)
    }

    companion object {
        const val GRAVITY = 9.81f
    }
}
