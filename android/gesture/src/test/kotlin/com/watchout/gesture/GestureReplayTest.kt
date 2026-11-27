package com.watchout.gesture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 워치에서 녹화한 CSV를 PC에서 인식기에 그대로 넣어 보는 분석 도구.
 *
 * 실제 워치에서 인식이 안 될 때 "센서 값이 얼마나 컸는지, 어디서 막혔는지"를 본다.
 * 평소 테스트(CI)에서는 건너뛰고, 환경 변수 REPLAY_DIR이 있을 때만 실행된다.
 *
 * 실행 (PowerShell):
 * ```
 * $env:REPLAY_DIR = "C:\Users\...\W@tch0ut\data\gestures"
 * .\gradlew :gesture:test --tests "*GestureReplayTest*" -i
 * ```
 */
@EnabledIfEnvironmentVariable(named = "REPLAY_DIR", matches = ".+")
class GestureReplayTest {

    @Test
    fun `녹화 파일을 인식기에 넣어 결과를 출력한다`() {
        val dir = File(System.getenv("REPLAY_DIR"))
        val files = dir.walk().filter { it.isFile && it.extension == "csv" }.sortedBy { it.name }.toList()
        println("=== ${files.size}개 파일: ${dir.absolutePath}")

        for (file in files) {
            val rec = GestureRecording.fromCsv(file.readText())
            val s = rec.samples
            val maxGyro = s.maxOfOrNull { sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) } ?: 0f
            val maxAccelOff = s.maxOfOrNull {
                abs(sqrt(it.ax * it.ax + it.ay * it.ay + it.az * it.az) - MotionSegmenter.GRAVITY)
            } ?: 0f
            val rate = if (s.size > 1) 1000.0 * (s.size - 1) / (s.last().timestampMs - s.first().timestampMs) else 0.0
            val rest = s.firstOrNull()

            val recognizer = RuleBasedGestureRecognizer(rec.hand)
            val results = s.mapNotNull { recognizer.onSample(it) }
            // 녹화가 끝날 때 아직 동작 중이면 결과가 안 나온다. 마지막 값을 0.5초 더 넣어 마무리한다.
            val tail = s.lastOrNull()?.let { last ->
                (1..25).mapNotNull { i -> recognizer.onSample(last.copy(timestampMs = last.timestampMs + i * 20L)) }
            }.orEmpty()

            println(
                String.format(
                    Locale.ROOT,
                    "%-40s 라벨=%-11s 샘플=%3d(%.0fHz) 최대회전=%.2f 최대가속=%.2f 시작자세=(%.1f,%.1f,%.1f)",
                    file.name, rec.label.id, s.size, rate, maxGyro, maxAccelOff,
                    rest?.ax ?: 0f, rest?.ay ?: 0f, rest?.az ?: 0f,
                ),
            )
            (results + tail).forEach { r ->
                val f = r.features
                println(
                    String.format(
                        Locale.ROOT,
                        "    → %-11s 확신=%.2f 길이=%dms 회전=(%.2f,%.2f,%.2f) 가속최대=(%.1f,%.1f,%.1f) 첫방향=(%.0f,%.0f,%.0f)",
                        r.gesture.id, r.confidence, f.durationMs,
                        f.rotation.x, f.rotation.y, f.rotation.z,
                        f.linearPeak.x, f.linearPeak.y, f.linearPeak.z,
                        f.linearFirstSign.x, f.linearFirstSign.y, f.linearFirstSign.z,
                    ),
                )
            }
            if (results.isEmpty() && tail.isEmpty()) println("    → (인식 결과 없음)")
        }
    }
}
