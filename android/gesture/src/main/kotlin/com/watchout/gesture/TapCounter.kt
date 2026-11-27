package com.watchout.gesture

/**
 * "여러 번 연달아 누르기"를 센다. 개발자 모드 켜기에 쓴다 (안드로이드 '빌드 번호 7번 누르기'와 같은 방식).
 *
 * 누른 사이가 [gapMs]보다 길면 처음부터 다시 센다. [required]번을 채우면 0을 돌려주고 다시 처음부터 센다.
 */
class TapCounter(private val required: Int = 7, private val gapMs: Long = 1500L) {
    private var count = 0
    private var lastMs = 0L

    /** @return 앞으로 더 눌러야 하는 횟수. 0이면 방금 다 채운 것 */
    fun onTap(nowMs: Long): Int {
        if (count > 0 && nowMs - lastMs > gapMs) count = 0
        lastMs = nowMs
        count++
        if (count >= required) {
            count = 0
            return 0
        }
        return required - count
    }
}
