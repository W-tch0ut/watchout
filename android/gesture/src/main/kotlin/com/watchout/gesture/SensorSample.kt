package com.watchout.gesture

/**
 * 워치 센서 한 번 측정값 (6축).
 *
 * 좌표축은 Android 기기 좌표계를 따른다. 워치 화면을 기준으로:
 * - X: 화면 오른쪽 (3시 방향, 용두 쪽)
 * - Y: 화면 위쪽 (12시 방향)
 * - Z: 화면에서 튀어나오는 방향
 *
 * @property timestampMs 측정 시각 (ms). 기기 부팅 후 경과 시간이라 시각 비교에만 쓴다.
 * @property ax 가속도 X (m/s², 중력 포함)
 * @property gx 자이로 X (rad/s), 오른손 법칙 기준 회전 속도
 */
data class SensorSample(
    val timestampMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
) {
    /**
     * 착용한 손에 맞춰 축을 맞춘 값을 돌려준다.
     *
     * 왼손에 기본 방향으로 차면 +X가 손가락 쪽을 향한다. 이것을 기준으로 삼는다.
     * 오른손에 기본 방향으로 차면 워치가 Z축 기준으로 180도 돌아간 셈이다.
     * 그래서 X와 Y의 부호를 뒤집으면 왼손과 같은 기준이 된다.
     */
    fun normalizedFor(hand: WearingHand): SensorSample = when (hand) {
        WearingHand.LEFT -> this
        WearingHand.RIGHT -> copy(ax = -ax, ay = -ay, gx = -gx, gy = -gy)
    }
}

/** 워치를 찬 손. 오른손은 워치 방향 설정을 바꾸지 않은 기본 상태(용두가 팔꿈치 쪽)를 뜻한다. */
enum class WearingHand { LEFT, RIGHT }
