package com.watchout.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 명령 메시지 모양이 #69 이슈(→ #34)에 적힌 형식과 글자 하나까지 같은지 확인한다. */
class RobotCommandTest {

    @Test
    fun `명령별 JSON 형식`() {
        assertEquals("""{"cmd":"stop"}""", RobotCommand.Stop.toJson())
        assertEquals("""{"cmd":"approach","target":"user"}""", RobotCommand.Approach.toJson())
        assertEquals("""{"cmd":"follow","target":"user"}""", RobotCommand.Follow.toJson())
        assertEquals("""{"cmd":"grab","target":"remote"}""", RobotCommand.Grab(GrabTarget.REMOTE).toJson())
        assertEquals("""{"cmd":"turn","angle_deg":-90}""", RobotCommand.Turn(-90).toJson())
        assertEquals("""{"cmd":"move","dist_cm":30}""", RobotCommand.Move(30).toJson())
    }

    @Test
    fun `물건 영문 이름`() {
        assertEquals(
            listOf("phone", "medicine", "remote", "glasses", "cup"),
            GrabTarget.entries.map { it.id },
        )
    }

    @Test
    fun `CommandSender는 받은 명령을 그대로 넘긴다`() {
        val sent = mutableListOf<String>()
        val sender = CommandSender { sent += it.toJson() }

        sender.send(RobotCommand.Stop)

        assertEquals(listOf("""{"cmd":"stop"}"""), sent)
    }
}
