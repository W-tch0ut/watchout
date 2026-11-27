package com.watchout.command

/** 말 한 마디를 해석한 결과 */
sealed interface VoiceResult {
    /** 알아들었다. 이 명령을 보내면 된다. */
    data class Understood(val command: RobotCommand) : VoiceResult

    /** "가져다줘"는 들었는데 물건 이름이 없다. "어떤 물건?" 하고 다시 묻는다. */
    data object AskTarget : VoiceResult

    /** 알아듣지 못했다. 명령을 보내지 않고 화면·진동으로 알린다. */
    data object NotUnderstood : VoiceResult
}

/**
 * 받아쓴 글자 → 로봇카 명령. 상태가 없는 순수 함수 모음이라 같은 말은 언제나 같은 결과다.
 *
 * 규칙(#69 이슈 본문 기준): 공백·문장 부호를 지운 뒤 아래 순서대로 낱말이 들어 있는지 본다.
 * **순서가 중요하다.** 앞 규칙이 이긴다.
 *
 * 1. 멈춤     — 다른 말과 섞여 있어도 stop ("이리 와 아니 멈춰" → stop)
 * 2. 가져오기 — "가져다·갖다·…" + 물건. 물건이 없으면 [VoiceResult.AskTarget]
 * 3. 회전     — "돌아·회전·돌려" + 방향. "뒤로 돌아"가 5번(뒤로 이동)보다 먼저 걸리도록 앞에 둔다.
 * 4. 따라오기
 * 5. 이동     — "앞으로/뒤로" + "가·와·이동"
 * 6. 다가오기 — "이리 와" 같은 말. "와"만 있는 짧은 말은 5번 뒤에 둬야 "앞으로 와"가 이동이 된다.
 */
object VoiceCommandParser {

    private val STOP_WORDS = listOf("멈춰", "멈춰라", "멈추세요", "정지", "스톱", "스탑", "그만")
    private val GRAB_WORDS = listOf("가져다", "갖다", "가져와", "가지고와", "집어")
    private val TURN_WORDS = listOf("돌아", "회전", "돌려")
    private val FOLLOW_WORDS = listOf("따라와", "따라오", "따라다")
    private val MOVE_VERBS = listOf("가", "와", "이동")
    private val APPROACH_WORDS = listOf("이리와", "이리로와", "여기로와", "여기와", "와줘", "와봐", "오세요", "이리오")

    /**
     * 물건 낱말을 **긴 것부터** 찾는다.
     * "휴대폰"에는 "폰"이, "약통"에는 "약"이 들어 있어서 짧은 것부터 보면 엉뚱한 낱말에 걸릴 수 있다.
     */
    private val TARGET_WORDS: List<Pair<String, GrabTarget>> =
        GrabTarget.entries
            .flatMap { target -> target.words.map { it to target } }
            .sortedByDescending { it.first.length }

    const val TURN_LEFT_DEG = 90
    const val TURN_RIGHT_DEG = -90
    const val TURN_BACK_DEG = 180
    const val MOVE_CM = 30

    /**
     * 공백·문장 부호·기호를 지우고 영문은 소문자로 바꾼다. 한글·영문·숫자만 남는다.
     * 음성 인식기가 "이리 와." / "이리와!" 처럼 띄어쓰기·부호를 제멋대로 붙이기 때문이다.
     */
    fun normalize(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    /** 멈춤 낱말이 있는가. 중간 받아쓰기(partial)에서 문장이 끝나기 전에 바로 멈추려고 따로 둔다. */
    fun isStop(text: String): Boolean = normalize(text).containsAny(STOP_WORDS)

    /** 말에서 물건 이름만 찾는다. "어떤 물건?" 다음 대답("휴대폰")을 받을 때 쓴다. */
    fun findTarget(text: String): GrabTarget? {
        val t = normalize(text)
        return TARGET_WORDS.firstOrNull { (word, _) -> word in t }?.second
    }

    /** 말 한 마디를 해석한다. 위 규칙 1~6을 차례로 본다. */
    fun parse(text: String): VoiceResult {
        val t = normalize(text)
        if (t.isEmpty()) return VoiceResult.NotUnderstood

        // 1. 멈춤
        if (t.containsAny(STOP_WORDS)) return understood(RobotCommand.Stop)

        // 2. 가져오기
        if (t.containsAny(GRAB_WORDS)) {
            val target = findTarget(t) ?: return VoiceResult.AskTarget
            return understood(RobotCommand.Grab(target))
        }

        // 3. 회전 — 방향이 없으면("돌아와") 회전이 아니므로 다음 규칙으로 넘어간다.
        if (t.containsAny(TURN_WORDS)) {
            turnAngle(t)?.let { return understood(RobotCommand.Turn(it)) }
        }

        // 4. 따라오기
        if (t.containsAny(FOLLOW_WORDS)) return understood(RobotCommand.Follow)

        // 5. 이동
        if (t.containsAny(MOVE_VERBS)) {
            when {
                "앞으로" in t -> return understood(RobotCommand.Move(MOVE_CM))
                "뒤로" in t -> return understood(RobotCommand.Move(-MOVE_CM))
            }
        }

        // 6. 다가오기
        if (t.containsAny(APPROACH_WORDS)) return understood(RobotCommand.Approach)

        return VoiceResult.NotUnderstood
    }

    private fun turnAngle(t: String): Int? = when {
        "왼쪽" in t -> TURN_LEFT_DEG
        "오른쪽" in t -> TURN_RIGHT_DEG
        "뒤로" in t || "반대" in t -> TURN_BACK_DEG
        else -> null
    }

    private fun understood(command: RobotCommand) = VoiceResult.Understood(command)

    private fun String.containsAny(words: List<String>) = words.any { it in this }
}
