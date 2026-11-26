package com.watchout.gesture

/**
 * 인식할 제스처 목록.
 *
 * 모든 제스처는 "빠르게 갔다가 돌아오기" 한 번이다. 예를 들어 [PUSH]는 팔을 앞으로 툭 뻗었다가
 * 바로 돌아오는 동작이다. 돌아오는 동작까지 하나로 묶어야 되돌아오는 움직임이
 * 반대 제스처([PULL])로 잘못 인식되지 않는다.
 *
 * 삼성 유니버설 제스처(주먹 쥐기, 손가락 맞대기, 손목 두 번 흔들기)와 겹치지 않도록
 * 손가락이 아니라 손목·팔 전체를 쓰는 동작만 넣었다.
 *
 * @property id 데이터 파일과 (나중에) 명령 메시지에 쓰는 영문 이름
 * @property label 화면에 보여줄 한국어 이름
 */
enum class Gesture(val id: String, val label: String, val howTo: String) {
    ROTATE_CW("rotate_cw", "손목 시계방향", "팔꿈치에서 손 쪽을 볼 때 시계방향으로 손목을 돌렸다 돌아오기"),
    ROTATE_CCW("rotate_ccw", "손목 반시계방향", "팔꿈치에서 손 쪽을 볼 때 반시계방향으로 손목을 돌렸다 돌아오기"),
    PUSH("push", "앞으로 뻗기", "팔을 손가락 방향으로 툭 뻗었다 돌아오기"),
    PULL("pull", "당기기", "팔을 몸 쪽으로 툭 당겼다 돌아오기"),
    SWING_LEFT("swing_left", "왼쪽 휘두르기", "손등을 위로 하고 팔을 왼쪽으로 휘둘렀다 돌아오기"),
    SWING_RIGHT("swing_right", "오른쪽 휘두르기", "손등을 위로 하고 팔을 오른쪽으로 휘둘렀다 돌아오기"),
    PRESS_DOWN("press_down", "아래로 누르기", "손등을 위로 하고 손을 아래로 툭 눌렀다 돌아오기"),

    /** 제스처가 아닌 움직임. 인식기가 확신이 없을 때의 답이자, 데이터 수집 때 "일상 동작" 라벨이다. */
    IDLE("idle", "일상 동작", "걷기, 컵 들기, 머리 만지기처럼 평소 움직임을 자유롭게"),
    ;

    companion object {
        fun fromId(id: String): Gesture =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("알 수 없는 제스처: $id")
    }
}

/**
 * 인식 결과.
 *
 * @property confidence 0~1. 규칙 기반에서는 "가장 큰 움직임이 다른 움직임보다 얼마나 뚜렷한가"를 뜻한다.
 * @property features 판단 근거. 툴 화면에서 디버깅용으로 보여준다.
 */
data class GestureResult(
    val gesture: Gesture,
    val confidence: Float,
    val startMs: Long,
    val endMs: Long,
    val features: GestureFeatures,
)

/**
 * 한 동작 구간에서 뽑은 특징값.
 *
 * "첫 번째 봉우리"는 값이 처음으로 크게 움직인 뒤 부호가 바뀌기 전까지의 구간이다.
 * 갔다가 돌아오는 동작에서 "가는 방향"만 보기 위해 쓴다.
 *
 * @property rotation 축별 첫 봉우리 회전량 (rad, 부호 포함). X축이 손목 비틀기다.
 * @property linearPeak 축별 중력을 뺀 가속도의 최댓값 (m/s², 절댓값)
 * @property linearFirstSign 축별 가속도 첫 봉우리의 부호 (+1, -1, 움직임이 없으면 0)
 */
data class GestureFeatures(
    val durationMs: Long,
    val rotation: Vec3,
    val linearPeak: Vec3,
    val linearFirstSign: Vec3,
)

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun get(axis: Int): Float = when (axis) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("axis=$axis")
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}
