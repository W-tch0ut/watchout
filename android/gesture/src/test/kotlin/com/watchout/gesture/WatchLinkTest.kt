package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class WatchLinkTest {

    @Test
    fun `로봇카 상태를 만들고 읽는다`() {
        val text = WatchLink.status(LinkState.CONNECTED, "데모 로봇카(PC)", "다가갑니다", ts = 5)

        assertEquals(WatchStatus(LinkState.CONNECTED, "데모 로봇카(PC)", "다가갑니다"), WatchLink.parseStatus(text))
        assertEquals(WatchStatus(LinkState.OFF, null, ""), WatchLink.parseStatus(WatchLink.status(LinkState.OFF, null, "", 1)))
        assertNull(WatchLink.parseStatus("""{"state":"flying"}"""))
        assertNull(WatchLink.parseStatus("not json"))
    }

    @Test
    fun `제스처 목록을 줄로 주고받는다`() {
        val list = listOf(
            GestureSummary("g1", "부르기 (이리 와 손짓)", CarCommand.APPROACH, 5),
            GestureSummary("g2", "탭\t들어간\n이름", CarCommand.STOP, 3),
        )

        val parsed = WatchLink.parseGestureList(WatchLink.gestureList(list))

        assertEquals(list[0], parsed[0])
        assertEquals("탭 들어간 이름", parsed[1].name) // 구분 글자는 공백으로 바꾼다
        assertEquals(emptyList<GestureSummary>(), WatchLink.parseGestureList(""))
        assertEquals(emptyList<GestureSummary>(), WatchLink.parseGestureList("g\t이름\tfly\t1")) // 모르는 명령은 버림
    }

    @Test
    fun `등록 조작을 만들고 읽는다`() {
        assertEquals(
            RegRequest(RegAction.START, name = "부르기", command = CarCommand.APPROACH),
            WatchLink.parseRegRequest(WatchLink.regStart("부르기", CarCommand.APPROACH)),
        )
        assertEquals(RegRequest(RegAction.RECORD), WatchLink.parseRegRequest(WatchLink.regAction(RegAction.RECORD)))
        assertEquals(RegRequest(RegAction.SAVE), WatchLink.parseRegRequest(WatchLink.regAction(RegAction.SAVE)))
        assertEquals(RegRequest(RegAction.DELETE, id = "g1"), WatchLink.parseRegRequest(WatchLink.regDelete("g1")))
        assertThrows<IllegalArgumentException> { WatchLink.regAction(RegAction.START) }
    }

    @Test
    fun `형식이 틀린 등록 조작은 무시한다`() {
        assertNull(WatchLink.parseRegRequest("""{"action":"start","name":"x"}""")) // 명령 없음
        assertNull(WatchLink.parseRegRequest("""{"action":"delete"}""")) // id 없음
        assertNull(WatchLink.parseRegRequest("""{"action":"dance"}"""))
        assertNull(WatchLink.parseRegRequest("[]"))
    }

    @Test
    fun `등록 진행 상황을 주고받고 저장 가능 여부를 안다`() {
        val s = RegState(RegPhase.ADDED, "부르기", count = 3, message = "3번째 ✓")

        assertEquals(s, WatchLink.parseRegState(WatchLink.regState(s)))
        assertTrue(s.canSave)
        assertFalse(s.copy(count = 2).canSave) // 3번은 해야 저장
        assertFalse(s.copy(phase = RegPhase.SAVED).canSave)
        assertTrue(RegState(RegPhase.COUNTDOWN, secondsLeft = 2).busy)
        assertNull(WatchLink.parseRegState("""{"phase":"sleeping"}"""))
    }

    @Test
    fun `제스처 이름 후보는 모두 다른 이름이다`() {
        val names = GesturePresets.ALL.map { it.first }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `방송을 들으면 로봇카를 한 번씩만 찾는다`() {
        val port = ServerSocket(0).use { it.localPort } // 비어 있는 포트 하나 고르기
        val packet = """{"type":"watchout_car","id":"demo-car","name":"데모","host":"10.0.0.2","port":1883}"""
            .toByteArray(Charsets.UTF_8)
        val sender = Thread {
            DatagramSocket().use { s ->
                repeat(5) {
                    Thread.sleep(100)
                    s.send(DatagramPacket(packet, packet.size, InetAddress.getLoopbackAddress(), port))
                }
            }
        }.apply { start() }

        val found = CarScanner.scan(durationMs = 1200, port = port)
        sender.join()

        assertEquals(listOf(CarInfo("demo-car", "데모", "10.0.0.2", 1883)), found)
    }
}
