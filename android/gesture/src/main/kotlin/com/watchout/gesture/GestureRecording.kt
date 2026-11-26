package com.watchout.gesture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 학습용으로 녹화한 제스처 한 번.
 *
 * CSV 형식 (팀 합의 전 제안):
 * ```
 * # label=push,hand=LEFT,rate_hz=50,started_at=2026-11-27T10:15:30.120+09:00
 * timestamp_ms,ax,ay,az,gx,gy,gz
 * 0,0.01234,0.10000,9.81000,0.00100,0.00000,0.00200
 * ```
 * - 첫 줄은 `#`로 시작하는 정보 줄이다. pandas에서는 `pd.read_csv(path, comment="#")`로 읽는다.
 * - timestamp_ms는 녹화 시작 시점을 0으로 한 상대 시간이다.
 * - 센서 값은 착용 손 보정 **전** 원본이다. 보정은 학습 코드에서 hand 값을 보고 한다.
 */
data class GestureRecording(
    val label: Gesture,
    val hand: WearingHand,
    val startedAtEpochMs: Long,
    val samples: List<SensorSample>,
    val rateHz: Int = 50,
) {
    fun toCsv(): String = buildString {
        val startedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(Instant.ofEpochMilli(startedAtEpochMs).atZone(ZoneId.systemDefault()))
        append("# label=${label.id},hand=${hand.name},rate_hz=$rateHz,started_at=$startedAt\n")
        append(HEADER).append('\n')
        val t0 = samples.firstOrNull()?.timestampMs ?: 0L
        for (s in samples) {
            append(s.timestampMs - t0)
            for (v in floatArrayOf(s.ax, s.ay, s.az, s.gx, s.gy, s.gz)) {
                append(',').append(String.format(Locale.ROOT, "%.5f", v))
            }
            append('\n')
        }
    }

    /** 예) `push__20261127-101530-120.csv` — 라벨이 파일 이름 앞에 있어서 정렬·집계가 쉽다. */
    fun fileName(): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .format(Instant.ofEpochMilli(startedAtEpochMs).atZone(ZoneId.systemDefault()))
        return "${label.id}__$stamp.csv"
    }

    companion object {
        const val HEADER = "timestamp_ms,ax,ay,az,gx,gy,gz"

        /** 파일 이름에서 라벨을 읽는다. 형식이 다르면 null */
        fun labelFromFileName(name: String): Gesture? {
            val id = name.substringBefore("__", missingDelimiterValue = "")
            return Gesture.entries.firstOrNull { it.id == id }
        }
    }
}
