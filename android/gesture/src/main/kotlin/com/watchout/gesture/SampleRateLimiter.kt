package com.watchout.gesture

/**
 * 센서 값이 너무 자주 들어올 때 일정 간격으로 솎아낸다.
 *
 * 기기마다 실제 센서 주기가 요청한 50Hz보다 빠를 수 있다. [minIntervalMs]보다 가까운 값은 버려서
 * 대략 일정한 주기로 맞춘다.
 *
 * "아직 하나도 안 받았음"은 null로 표시한다. 예전에는 Long.MIN_VALUE로 표시했는데,
 * `지금 - Long.MIN_VALUE`가 오버플로로 음수가 되어 모든 값을 버리는 버그가 있었다.
 */
class SampleRateLimiter(private val minIntervalMs: Long) {

    private var lastAcceptedMs: Long? = null

    /** @return 이 시각의 값을 쓰면 true, 버리면 false */
    fun accept(timestampMs: Long): Boolean {
        val last = lastAcceptedMs
        if (last != null && timestampMs - last < minIntervalMs) return false
        lastAcceptedMs = timestampMs
        return true
    }

    fun reset() {
        lastAcceptedMs = null
    }
}
