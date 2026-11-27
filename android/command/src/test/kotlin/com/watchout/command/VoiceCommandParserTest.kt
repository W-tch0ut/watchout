package com.watchout.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class VoiceCommandParserTest {

    private fun json(text: String): String? =
        (VoiceCommandParser.parse(text) as? VoiceResult.Understood)?.command?.toJson()

    // ---------- 완료 조건 세 문장 ----------

    @Test
    fun `이리 와 - 다가오기`() {
        assertEquals("""{"cmd":"approach","target":"user"}""", json("이리 와"))
    }

    @Test
    fun `휴대폰 가져다줘 - 휴대폰 가져오기`() {
        assertEquals("""{"cmd":"grab","target":"phone"}""", json("휴대폰 가져다줘"))
    }

    @Test
    fun `멈춰 - 정지`() {
        assertEquals("""{"cmd":"stop"}""", json("멈춰"))
    }

    // ---------- 1. 멈춤 ----------

    @ParameterizedTest
    @ValueSource(strings = ["멈춰", "멈춰라", "멈추세요", "정지", "스톱", "스탑", "그만", "그만해!", "로봇아 정지."])
    fun `멈춤 낱말은 모두 stop`(text: String) {
        assertEquals(VoiceResult.Understood(RobotCommand.Stop), VoiceCommandParser.parse(text))
        assertTrue(VoiceCommandParser.isStop(text))
    }

    @Test
    fun `다른 명령과 섞여 있어도 멈춤이 이긴다`() {
        assertEquals("""{"cmd":"stop"}""", json("이리 와 아니 멈춰"))
        assertEquals("""{"cmd":"stop"}""", json("휴대폰 가져오지 말고 그만"))
        assertEquals("""{"cmd":"stop"}""", json("앞으로 가다가 정지"))
    }

    @Test
    fun `멈춤 낱말이 없으면 isStop은 거짓`() {
        assertFalse(VoiceCommandParser.isStop("이리 와"))
        assertFalse(VoiceCommandParser.isStop(""))
    }

    // ---------- 2. 가져오기 ----------

    @Test
    fun `물건마다 맞는 target`() {
        assertEquals("""{"cmd":"grab","target":"phone"}""", json("핸드폰 갖다 줘"))
        assertEquals("""{"cmd":"grab","target":"medicine"}""", json("약 가져와"))
        assertEquals("""{"cmd":"grab","target":"remote"}""", json("리모콘 집어 줘"))
        assertEquals("""{"cmd":"grab","target":"glasses"}""", json("안경 가지고 와"))
        assertEquals("""{"cmd":"grab","target":"cup"}""", json("물컵 가져다줄래?"))
    }

    @Test
    fun `긴 물건 이름부터 찾는다`() {
        // "스마트폰"에는 "폰"이, "약통"에는 "약"이 들어 있지만 결과는 같아야 한다.
        assertEquals(GrabTarget.PHONE, VoiceCommandParser.findTarget("스마트폰"))
        assertEquals(GrabTarget.MEDICINE, VoiceCommandParser.findTarget("약통"))
        assertEquals(GrabTarget.CUP, VoiceCommandParser.findTarget("물컵"))
        assertNull(VoiceCommandParser.findTarget("의자"))
    }

    @Test
    fun `물건 이름이 없으면 어떤 물건인지 묻는다`() {
        assertEquals(VoiceResult.AskTarget, VoiceCommandParser.parse("가져다줘"))
        assertEquals(VoiceResult.AskTarget, VoiceCommandParser.parse("저거 집어 줘"))
    }

    // ---------- 3. 회전 ----------

    @Test
    fun `회전 방향과 각도`() {
        assertEquals("""{"cmd":"turn","angle_deg":90}""", json("왼쪽으로 돌아"))
        assertEquals("""{"cmd":"turn","angle_deg":-90}""", json("오른쪽으로 회전"))
        assertEquals("""{"cmd":"turn","angle_deg":180}""", json("반대로 돌려"))
    }

    @Test
    fun `뒤로 돌아는 뒤로 이동이 아니라 180도 회전`() {
        // 회전(3번)이 이동(5번)보다 먼저 검사되기 때문에 생기는 결과. 순서를 바꾸면 이 테스트가 깨진다.
        assertEquals("""{"cmd":"turn","angle_deg":180}""", json("뒤로 돌아"))
    }

    @Test
    fun `방향 없는 돌아는 회전으로 보지 않는다`() {
        assertEquals(VoiceResult.NotUnderstood, VoiceCommandParser.parse("돌아"))
    }

    // ---------- 4. 따라오기 ----------

    @Test
    fun `따라오기`() {
        assertEquals("""{"cmd":"follow","target":"user"}""", json("나 따라와"))
        assertEquals("""{"cmd":"follow","target":"user"}""", json("따라오세요"))
        assertEquals("""{"cmd":"follow","target":"user"}""", json("따라다녀"))
    }

    // ---------- 5. 이동 ----------

    @Test
    fun `앞뒤 이동`() {
        assertEquals("""{"cmd":"move","dist_cm":30}""", json("앞으로 가"))
        assertEquals("""{"cmd":"move","dist_cm":30}""", json("앞으로 이동"))
        assertEquals("""{"cmd":"move","dist_cm":-30}""", json("뒤로 가"))
    }

    @Test
    fun `앞으로 와는 다가오기가 아니라 이동`() {
        // 이동(5번)이 다가오기(6번)보다 먼저 검사된다.
        assertEquals("""{"cmd":"move","dist_cm":30}""", json("앞으로 와"))
    }

    // ---------- 6. 다가오기 ----------

    @ParameterizedTest
    @ValueSource(strings = ["이리와", "이리로 와", "여기로 와", "여기 와", "와줘", "와 봐", "이리 오세요", "이리 오너라"])
    fun `다가오기 낱말`(text: String) {
        assertEquals(VoiceResult.Understood(RobotCommand.Approach), VoiceCommandParser.parse(text))
    }

    // ---------- 못 알아들음 ----------

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "?!", "오늘 날씨 어때", "로봇아"])
    fun `명령이 아니면 NotUnderstood`(text: String) {
        assertEquals(VoiceResult.NotUnderstood, VoiceCommandParser.parse(text))
    }

    @Test
    fun `띄어쓰기와 부호는 무시한다`() {
        assertEquals(VoiceCommandParser.parse("이리와"), VoiceCommandParser.parse(" 이리,  와!! "))
        assertEquals("이리와abc1", VoiceCommandParser.normalize("이리 와. ABC 1"))
    }
}
