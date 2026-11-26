package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GestureRecordingTest {

    private val recording = GestureRecording(
        label = Gesture.PUSH,
        hand = WearingHand.RIGHT,
        startedAtEpochMs = 1_800_000_000_000,
        samples = listOf(
            SensorSample(5000, 0.1f, 0.2f, 9.81f, 0f, 0f, 0.5f),
            SensorSample(5020, -1.25f, 0f, 9.8f, 1f, 0f, 0f),
        ),
    )

    @Test
    fun `CSV는 정보 줄, 헤더, 시작을 0으로 맞춘 시간 순서다`() {
        val lines = recording.toCsv().trimEnd().lines()

        assertTrue(lines[0].startsWith("# label=push,hand=RIGHT,rate_hz=50,started_at="))
        assertEquals(GestureRecording.HEADER, lines[1])
        assertEquals("0,0.10000,0.20000,9.81000,0.00000,0.00000,0.50000", lines[2])
        assertEquals("20,-1.25000,0.00000,9.80000,1.00000,0.00000,0.00000", lines[3])
        assertEquals(4, lines.size)
    }

    @Test
    fun `파일 이름 앞부분으로 라벨을 다시 읽을 수 있다`() {
        val name = recording.fileName()

        assertTrue(name.matches(Regex("""push__\d{8}-\d{6}-\d{3}\.csv""")), name)
        assertEquals(Gesture.PUSH, GestureRecording.labelFromFileName(name))
        assertEquals(Gesture.ROTATE_CW, GestureRecording.labelFromFileName("rotate_cw__x.csv"))
        assertNull(GestureRecording.labelFromFileName("memo.csv"))
    }
}
