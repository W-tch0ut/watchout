package com.watchout.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CarProtocolTest {

    @Test
    fun `명령 메시지는 cmd와 값, source, gesture, ts를 담는다`() {
        val json = CarProtocol.command(CarCommand.APPROACH, "gesture", "부르기", 1_795_000_000_000)

        assertEquals(
            """{"cmd":"approach","target":"user","source":"gesture","gesture":"부르기","ts":1795000000000}""",
            json,
        )
    }

    @Test
    fun `명령 앞부분은 음성 명령(#71)과 같은 형식이다`() {
        // #71 RobotCommand.toJson()이 만드는 값과 같아야 로봇카가 제스처·음성을 똑같이 받는다
        val expected = mapOf(
            CarCommand.STOP to """{"cmd":"stop"""",
            CarCommand.APPROACH to """{"cmd":"approach","target":"user"""",
            CarCommand.FOLLOW to """{"cmd":"follow","target":"user"""",
            CarCommand.GRAB_PHONE to """{"cmd":"grab","target":"phone"""",
            CarCommand.TURN_LEFT to """{"cmd":"turn","angle_deg":90""",
            CarCommand.TURN_RIGHT to """{"cmd":"turn","angle_deg":-90""",
            CarCommand.FORWARD to """{"cmd":"move","dist_cm":30""",
            CarCommand.BACKWARD to """{"cmd":"move","dist_cm":-30""",
        )
        for ((cmd, prefix) in expected) {
            val json = CarProtocol.command(cmd, "gesture", null, 1)
            assertEquals(true, json.startsWith("$prefix,\"source\""), "$cmd → $json")
        }
    }

    @Test
    fun `음성 명령(#71)에 source와 시각을 덧붙인다`() {
        assertEquals(
            """{"cmd":"grab","target":"phone","source":"voice","ts":7}""",
            CarProtocol.withSource("""{"cmd":"grab","target":"phone"}""", "voice", 7),
        )
        assertEquals(
            """{"cmd":"turn","angle_deg":-90,"source":"voice","ts":7}""",
            CarProtocol.withSource("""{"cmd":"turn","angle_deg":-90}""", "voice", 7),
        )
        assertEquals("not json", CarProtocol.withSource("not json", "voice", 7)) // 명령이 아니면 그대로
    }

    @Test
    fun `버튼으로 보낸 명령은 gesture가 null이다`() {
        val m = Json.parseFlat(CarProtocol.command(CarCommand.STOP, "button", null, 1))

        assertEquals("stop", m["cmd"])
        assertNull(m["gesture"])
    }

    @Test
    fun `낙상 의심 메시지`() {
        val m = Json.parseFlat(CarProtocol.fallSuspected(5, 3.25f, "test_button"))

        assertEquals("fall_suspected", m["event"])
        assertEquals(3.25, m["impact_g"])
        assertEquals("test_button", m["source"])
    }

    @Test
    fun `토픽은 차 아이디로 나뉜다`() {
        assertEquals("watchout/cars/demo-car/cmd", CarProtocol.cmdTopic("demo-car"))
        assertEquals("watchout/cars/demo-car/fall/result", CarProtocol.fallResultTopic("demo-car"))
    }

    @Test
    fun `로봇카 방송을 읽고 다른 형식은 무시한다`() {
        val car = CarProtocol.parseDiscovery(
            """{"type":"watchout_car","id":"demo-car","name":"데모 로봇카","host":"192.168.0.34","port":1883}""",
        )!!

        assertEquals(CarInfo("demo-car", "데모 로봇카", "192.168.0.34", 1883), car)
        assertEquals("tcp://192.168.0.34:1883", car.brokerUri)
        assertNull(CarProtocol.parseDiscovery("""{"type":"something_else","id":"x","host":"h"}"""))
        assertNull(CarProtocol.parseDiscovery("hello"))
    }

    @Test
    fun `상태와 낙상 확인 결과를 읽는다`() {
        assertEquals(CarStatus("moving", "가는 중"), CarProtocol.parseStatus("""{"state":"moving","msg":"가는 중"}"""))
        assertEquals(FallResult(true, "알림 보냄"), CarProtocol.parseFallResult("""{"confirmed":true,"msg":"알림 보냄"}"""))
        assertNull(CarProtocol.parseFallResult("""{"msg":"확인 결과 없음"}"""))
    }

    @Test
    fun `JSON 문자열의 특수문자를 주고받을 수 있다`() {
        val text = "따옴표\" 역슬래시\\ 줄바꿈\n탭\t"
        val json = Json.obj(mapOf("t" to text))

        assertEquals(text, Json.parseFlat(json)["t"])
        assertEquals("가", Json.parseFlat("""{"t":"가"}""")["t"])
    }

    @Test
    fun `깨진 JSON은 예외`() {
        assertThrows<IllegalArgumentException> { Json.parseFlat("""{"a":1""") }
        assertThrows<IllegalArgumentException> { Json.parseFlat("""{"a":1} x""") }
    }

    @Test
    fun `명령 아이디로 명령을 찾는다`() {
        assertEquals(CarCommand.GRAB_PHONE, CarCommand.fromId("grab_phone"))
        assertNull(CarCommand.fromId("fly"))
    }
}
