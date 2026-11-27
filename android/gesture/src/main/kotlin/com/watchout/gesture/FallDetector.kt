package com.watchout.gesture

import kotlin.math.sqrt

/**
 * 낙상 판단 기준값 (데모용. 실제 넘어짐 데이터로 조정해야 한다).
 * 가속도 단위는 g(9.81 m/s² = 1g).
 *
 * 손목은 손짓만으로도 3~5g가 쉽게 나온다. 그래서 "충격만" 보면 손짓을 낙상으로 착각한다(실제로 생긴 문제).
 * 사람이 서 있다가 넘어지면 0.3초쯤 몸이 떨어지는 구간(자유낙하)이 있으므로, 그것을 반드시 요구한다.
 */
data class FallConfig(
    /** 이 값보다 가벼우면 떨어지는 중(자유낙하)으로 본다 */
    val freeFallG: Float = 0.5f,
    /** 자유낙하가 이만큼 이어져야 한다. 손목을 휙 내리는 정도(0.1초 안팎)는 걸러진다 */
    val freeFallMinMs: Long = 150,
    /** 이 값보다 크면 바닥에 부딪힌 충격으로 본다 */
    val impactG: Float = 3.0f,
    /**
     * 자유낙하 없이 충격만으로 낙상으로 볼 기준. 기본은 끔(무한대).
     * 주저앉듯 넘어지는 경우를 잡으려면 값을 넣는데, 손짓과 구분이 어려워 데모에서는 쓰지 않는다.
     */
    val impactOnlyG: Float = Float.POSITIVE_INFINITY,
    /** 자유낙하가 끝난 뒤 이 시간 안에 충격이 와야 한다 */
    val impactWindowMs: Long = 1000,
    /** 충격 뒤 이 시간 동안 거의 움직이지 않으면 낙상으로 본다 */
    val stillMs: Long = 2500,
    val stillGyro: Float = 0.8f,
    val stillAccelDevG: Float = 0.3f,
)

/**
 * 워치 가속도·자이로로 낙상을 의심한다.
 *
 * 1. 자유낙하: 가속도 크기가 0.5g 아래로 150ms 이상 (떨어지는 동안 몸이 가벼워진다)
 * 2. 충격: 그 뒤 1초 안에 3g를 넘는 충격
 * 3. 정지: 충격 뒤 2.5초 동안 거의 움직이지 않음 (쓰러져 있음)
 *
 * 세 단계를 모두 지나면 낙상 의심이다. 확정은 로봇카 카메라가 한다(이중 확인).
 */
class FallDetector(private val config: FallConfig = FallConfig()) {

    private enum class State { NORMAL, FREE_FALL, AWAIT_IMPACT, AWAIT_STILL }

    private var state = State.NORMAL
    private var freeFallStartMs = 0L
    private var freeFallEndMs = 0L
    private var impactMs = 0L
    private var peakG = 0f
    private var lastMovingMs = 0L

    /** @return 낙상 의심이면 충격 크기(g), 아니면 null */
    fun onSample(s: SensorSample): Float? {
        val g = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az) / MotionSegmenter.GRAVITY
        val gyro = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
        val t = s.timestampMs

        when (state) {
            State.NORMAL -> when {
                g < config.freeFallG -> { state = State.FREE_FALL; freeFallStartMs = t }
                g > config.impactOnlyG -> startStill(t, g)
            }

            State.FREE_FALL -> if (g >= config.freeFallG) {
                state = if (t - freeFallStartMs >= config.freeFallMinMs) State.AWAIT_IMPACT else State.NORMAL
                freeFallEndMs = t
                if (state == State.AWAIT_IMPACT && g > config.impactG) startStill(t, g)
            }

            State.AWAIT_IMPACT -> when {
                g > config.impactG -> startStill(t, g)
                t - freeFallEndMs > config.impactWindowMs -> state = State.NORMAL
            }

            State.AWAIT_STILL -> {
                // 부딪힌 직후 0.3초는 튕기는 반동이라 보지 않는다.
                if (t - impactMs < BOUNCE_MS) {
                    peakG = maxOf(peakG, g)
                    lastMovingMs = t
                } else {
                    val moving = gyro > config.stillGyro || kotlin.math.abs(g - 1f) > config.stillAccelDevG
                    if (moving) lastMovingMs = t
                    if (t - lastMovingMs >= config.stillMs) {
                        state = State.NORMAL
                        return peakG
                    }
                    // 충격 뒤 오래 계속 움직이면(일어나서 걸어감) 낙상이 아니다.
                    if (t - impactMs > config.stillMs * 3) state = State.NORMAL
                }
            }
        }
        return null
    }

    fun reset() { state = State.NORMAL }

    private fun startStill(t: Long, g: Float) {
        state = State.AWAIT_STILL
        impactMs = t
        peakG = g
        lastMovingMs = t
    }

    private companion object {
        const val BOUNCE_MS = 300L
    }
}

/**
 * "괜찮으세요?" 10초 취소 판정.
 *
 * 낙상 의심이 나오면 [start]로 카운트다운을 시작한다. 10초 안에 [cancel]하면 알리지 않는다.
 * 10초가 지나면 [tick]이 [Phase.SEND]를 한 번 돌려주고, 그때 로봇카에 알린다.
 */
class FallAlertTimer(private val countdownMs: Long = 10_000) {

    enum class Phase { IDLE, COUNTDOWN, CANCELLED, SEND }

    var phase = Phase.IDLE
        private set
    private var startedMs = 0L

    fun start(nowMs: Long) {
        if (phase == Phase.COUNTDOWN) return // 이미 묻는 중이면 다시 시작하지 않는다
        phase = Phase.COUNTDOWN
        startedMs = nowMs
    }

    /** 사용자가 "괜찮아요"를 눌렀다. 카운트다운 중일 때만 취소된다. */
    fun cancel(): Boolean {
        if (phase != Phase.COUNTDOWN) return false
        phase = Phase.CANCELLED
        return true
    }

    /** 남은 시간(초, 올림). 카운트다운 중이 아니면 0 */
    fun secondsLeft(nowMs: Long): Int =
        if (phase != Phase.COUNTDOWN) 0 else ((countdownMs - (nowMs - startedMs) + 999) / 1000).toInt().coerceAtLeast(0)

    /** 시간을 흘려보낸다. 10초가 지나는 순간 한 번만 [Phase.SEND]를 돌려준다. */
    fun tick(nowMs: Long): Phase {
        if (phase == Phase.COUNTDOWN && nowMs - startedMs >= countdownMs) {
            phase = Phase.SEND
            return Phase.SEND
        }
        return phase
    }

    fun reset() { phase = Phase.IDLE }
}
