package com.watchout.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceConversationTest {

    private val conv = VoiceConversation()

    /** 버튼 한 번 누르고 말하는 과정을 흉내 낸다: partial 여러 개 → final */
    private fun speak(vararg partials: String, final: List<String>): Pair<List<RobotCommand>, VoiceResult?> {
        conv.startTurn()
        val early = partials.mapNotNull(conv::onPartial)
        return early to conv.onFinal(final)
    }

    @Test
    fun `말하는 도중 멈춤이 들리면 바로 stop 하고 끝에서 다시 보내지 않는다`() {
        val (early, final) = speak("이리", "이리 와 멈", "이리 와 멈춰", "이리 와 멈춰", final = listOf("이리 와 멈춰"))

        assertEquals(listOf(RobotCommand.Stop), early) // 한 번만
        assertNull(final) // 이미 보냈으니 또 보내지 않는다
    }

    @Test
    fun `멈춤이 없으면 partial에서는 아무것도 보내지 않는다`() {
        val (early, final) = speak("휴대", "휴대폰 가져", final = listOf("휴대폰 가져다줘"))

        assertTrue(early.isEmpty())
        assertEquals(VoiceResult.Understood(RobotCommand.Grab(GrabTarget.PHONE)), final)
    }

    @Test
    fun `다음 마디에서는 다시 멈춤을 보낼 수 있다`() {
        speak("멈춰", final = listOf("멈춰"))
        val (early, _) = speak("정지", final = listOf("정지"))

        assertEquals(listOf(RobotCommand.Stop), early)
    }

    @Test
    fun `물건을 물은 뒤 물건 이름만 말하면 가져오기`() {
        val (_, first) = speak(final = listOf("가져다줘"))
        assertEquals(VoiceResult.AskTarget, first)
        assertTrue(conv.awaitingTarget)

        val (_, second) = speak(final = listOf("안경"))
        assertEquals(VoiceResult.Understood(RobotCommand.Grab(GrabTarget.GLASSES)), second)
        assertFalse(conv.awaitingTarget)
    }

    @Test
    fun `물건을 한 번만 묻고 잊는다`() {
        speak(final = listOf("가져다줘"))
        speak(final = listOf("음…")) // 물건 이름이 아님 → 못 알아들음, 질문은 끝

        val (_, third) = speak(final = listOf("휴대폰"))
        assertEquals(VoiceResult.NotUnderstood, third) // 그냥 "휴대폰"은 명령이 아니다
    }

    @Test
    fun `물건을 기다리는 중에도 멈춤이 이긴다`() {
        speak(final = listOf("가져다줘"))
        val (early, final) = speak("휴대폰 말고 그만", final = listOf("휴대폰 말고 그만"))

        assertEquals(listOf(RobotCommand.Stop), early)
        assertNull(final)
        assertFalse(conv.awaitingTarget)
    }

    @Test
    fun `여러 후보 중 처음으로 알아들은 것을 쓴다`() {
        val (_, final) = speak(final = listOf("일이 와", "이리 와", "앞으로 가"))

        assertEquals(VoiceResult.Understood(RobotCommand.Approach), final)
    }

    @Test
    fun `후보를 하나도 못 알아들으면 NotUnderstood`() {
        val (_, final) = speak(final = listOf("오늘 날씨", "오늘 날 씨"))

        assertEquals(VoiceResult.NotUnderstood, final)
    }

    @Test
    fun `후보가 없어도 NotUnderstood`() {
        assertEquals(VoiceResult.NotUnderstood, speak(final = emptyList()).second)
    }

    @Test
    fun `reset 하면 기다리던 질문을 잊는다`() {
        speak(final = listOf("가져다줘"))
        conv.reset()

        assertFalse(conv.awaitingTarget)
    }
}
