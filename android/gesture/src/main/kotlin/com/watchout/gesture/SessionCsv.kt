package com.watchout.gesture

/**
 * 연속 기록(세션) 파일 형식.
 *
 * 삼성 유니버설 제스처 로그(실제 시각)와 맞춰 보기 위해, 센서 값마다 **실제 시각(epoch ms)**을 붙인다.
 * 센서마다 값 개수가 달라서(가속도 3개, 심박 원시 신호는 여러 개) 한 줄에 센서 하나씩 쓴다.
 *
 * ```
 * # watchout session v1,hand=LEFT,imu_period_us=5000,ppg=Samsung HR Raw Sensor
 * epoch_ms,sensor,values...
 * 1795742707790,acc,0.12,0.3,9.81
 * 1795742707791,gyr,0.01,-0.02,0.003
 * 1795742707795,ppg,123456.0,...
 * ```
 */
object SessionCsv {
    const val HEADER = "epoch_ms,sensor,values..."
    const val ACC = "acc"
    const val GYRO = "gyr"
    const val PPG = "ppg"

    fun infoLine(info: Map<String, String>): String =
        "# watchout session v1," + info.entries.joinToString(",") { "${it.key}=${it.value.replace(',', ' ')}" }

    /**
     * 1초에 수백 번 불리므로 String.format을 쓰지 않는다(워치에서 느리다).
     * Float.toString은 로캘과 상관없이 점(.)을 쓰고, Python float()로 그대로 읽힌다.
     */
    fun line(epochMs: Long, sensor: String, values: FloatArray): String =
        appendLine(StringBuilder(16 + sensor.length + values.size * 12), epochMs, sensor, values).toString()

    /** [line]과 같은 내용을 [sb]에 이어 붙인다. 기록기가 StringBuilder 하나를 재사용해 쓰레기를 줄이는 용도 */
    fun appendLine(sb: StringBuilder, epochMs: Long, sensor: String, values: FloatArray): StringBuilder {
        sb.append(epochMs).append(',').append(sensor)
        for (v in values) sb.append(',').append(v)
        return sb
    }
}

/**
 * 센서 이벤트 시각을 실제 시각(epoch ms)으로 바꾼다.
 *
 * 안드로이드 센서 이벤트 시각은 "부팅 후 경과 시간(ns)"이다. 삼성 로그는 실제 시각을 쓴다.
 * 기록을 시작할 때 두 시계를 한 번 같이 읽어 두고, 그 차이로 변환한다.
 *
 * @param wallNowMs 시작 순간의 실제 시각 (System.currentTimeMillis)
 * @param elapsedNowNs 시작 순간의 부팅 후 경과 시간 (SystemClock.elapsedRealtimeNanos)
 */
class ClockConverter(private val wallNowMs: Long, private val elapsedNowNs: Long) {
    fun toEpochMs(eventTimestampNs: Long): Long = wallNowMs + (eventTimestampNs - elapsedNowNs) / 1_000_000
}
