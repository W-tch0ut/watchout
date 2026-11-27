package com.watchout.command

/**
 * 워치 → 로봇카 명령 메시지.
 *
 * 제스처로 부르든 말로 부르든 로봇카는 **같은 메시지**를 받는다. 그래서 로봇카는 명령이
 * 어디서 왔는지 신경 쓸 필요가 없다.
 *
 * 형식은 #34가 기준이다(아직 확정 전). 지금은 #69 이슈 본문에 적힌 모양을 그대로 따른다.
 * ```
 * {"cmd":"stop"}
 * {"cmd":"approach","target":"user"}
 * {"cmd":"grab","target":"phone"}
 * {"cmd":"turn","angle_deg":90}     왼쪽 +, 오른쪽 -
 * {"cmd":"move","dist_cm":30}       앞 +, 뒤 -
 * {"cmd":"follow","target":"user"}
 * ```
 */
sealed interface RobotCommand {
    /** 로봇카로 보낼 JSON 한 줄. 값이 모두 정해진 영문·숫자라 따로 이스케이프할 글자가 없다. */
    fun toJson(): String

    /** 멈춰. 어떤 명령보다 먼저 처리한다. */
    data object Stop : RobotCommand {
        override fun toJson() = """{"cmd":"stop"}"""
    }

    /** 사용자에게 다가오기 ("이리 와") */
    data object Approach : RobotCommand {
        override fun toJson() = """{"cmd":"approach","target":"$USER"}"""
    }

    /** 물건 가져오기 ("휴대폰 가져다줘") */
    data class Grab(val target: GrabTarget) : RobotCommand {
        override fun toJson() = """{"cmd":"grab","target":"${target.id}"}"""
    }

    /** 제자리 회전. [angleDeg]가 양수면 왼쪽(반시계), 음수면 오른쪽(시계). */
    data class Turn(val angleDeg: Int) : RobotCommand {
        override fun toJson() = """{"cmd":"turn","angle_deg":$angleDeg}"""
    }

    /** 앞뒤 이동. [distCm]가 양수면 앞으로, 음수면 뒤로. */
    data class Move(val distCm: Int) : RobotCommand {
        override fun toJson() = """{"cmd":"move","dist_cm":$distCm}"""
    }

    /** 사용자 따라오기 */
    data object Follow : RobotCommand {
        override fun toJson() = """{"cmd":"follow","target":"$USER"}"""
    }

    companion object {
        /** approach·follow의 대상. 지금은 사용자 한 명뿐이다. */
        const val USER = "user"
    }
}

/**
 * 로봇팔로 가져올 수 있는 물건.
 *
 * @property id 명령 메시지에 들어가는 영문 이름 (로봇팔 팀의 물건 찾기와 맞춘다)
 * @property label 화면에 보여줄 한국어 이름
 * @property words 말에서 이 물건을 찾을 때 쓰는 낱말
 */
enum class GrabTarget(val id: String, val label: String, val words: List<String>) {
    PHONE("phone", "휴대폰", listOf("휴대폰", "핸드폰", "스마트폰", "전화기", "폰")),
    MEDICINE("medicine", "약", listOf("약통", "약봉지", "약")),
    REMOTE("remote", "리모컨", listOf("리모컨", "리모콘")),
    GLASSES("glasses", "안경", listOf("안경")),
    CUP("cup", "컵", listOf("컵", "물컵")),
}

/**
 * 명령을 로봇카로 보내는 길.
 *
 * 실제 통신(BLE 또는 MQTT)은 #42에서 정한다. 그 전까지 워치 앱은 화면·로그에 JSON만 보여 주는
 * 구현을 쓰고, #42가 끝나면 구현만 바꿔 끼운다. 해석기·화면 코드는 바꿀 필요가 없다.
 */
fun interface CommandSender {
    fun send(command: RobotCommand)
}
