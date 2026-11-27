package com.watchout.command

/**
 * 버튼을 누르고 말하는 "한 번 한 번"을 이어서 다루는 대화 상태.
 *
 * [VoiceCommandParser]는 한 문장만 보는 순수 함수라 앞뒤 사정을 모른다. 여기서 두 가지를 기억한다.
 *
 * 1. **멈춤은 한 번만**: 말하는 도중(partial)에 "멈춰"가 들리면 바로 stop을 보낸다.
 *    그 뒤 문장이 끝나서(final) 같은 "멈춰"가 또 오면 두 번 보내지 않는다.
 * 2. **"어떤 물건?" 다음 대답**: "가져다줘"만 듣고 물건을 물었다면, 다음 한 마디에서
 *    물건 이름("휴대폰")만 들어도 grab으로 알아듣는다. 한 번 묻고 나면 잊는다(계속 기다리지 않는다).
 *
 * 안드로이드에 의존하지 않아서 JUnit으로 시나리오를 그대로 시험할 수 있다.
 * 사용 순서: [startTurn] → [onPartial] 여러 번 → [onFinal] 한 번.
 */
class VoiceConversation {

    /** "어떤 물건?"을 물어 놓고 대답을 기다리는 중인가 */
    var awaitingTarget: Boolean = false
        private set

    /** 이번 한 마디에서 이미 stop을 보냈는가 */
    private var stopSentThisTurn = false

    /** 버튼을 눌러 새로 듣기 시작할 때 부른다. */
    fun startTurn() {
        stopSentThisTurn = false
    }

    /**
     * 말하는 도중의 받아쓰기. 멈춤 낱말이 처음 들렸을 때만 [RobotCommand.Stop]을 돌려준다.
     * 그 밖의 명령은 문장이 끝날 때까지 기다린다("앞으로… 가지 마"처럼 뒤에서 뜻이 바뀔 수 있어서).
     */
    fun onPartial(text: String): RobotCommand? {
        if (stopSentThisTurn || !VoiceCommandParser.isStop(text)) return null
        stopSentThisTurn = true
        awaitingTarget = false
        return RobotCommand.Stop
    }

    /**
     * 문장이 끝났을 때의 받아쓰기 후보들. 음성 인식기는 비슷한 후보를 여러 개 주므로
     * (예: "이리 와", "이리와", "일이 와") 앞에서부터 보고 처음으로 알아들은 것을 쓴다.
     *
     * @return 보낼 명령이 있으면 [VoiceResult.Understood].
     *   partial에서 이미 stop을 보냈다면 다시 보내지 않도록 null을 돌려준다.
     */
    fun onFinal(candidates: List<String>): VoiceResult? {
        if (stopSentThisTurn) return null

        val wasAwaiting = awaitingTarget
        awaitingTarget = false

        // "어떤 물건?" 다음이면 물건 이름만 들어도 된다. 단, 멈춤이 섞여 있으면 멈춤이 이긴다.
        if (wasAwaiting && candidates.none(VoiceCommandParser::isStop)) {
            candidates.firstNotNullOfOrNull(VoiceCommandParser::findTarget)?.let {
                return VoiceResult.Understood(RobotCommand.Grab(it))
            }
        }

        val results = candidates.map(VoiceCommandParser::parse)
        val result = results.firstOrNull { it is VoiceResult.Understood }
            ?: results.firstOrNull { it is VoiceResult.AskTarget }
            ?: VoiceResult.NotUnderstood

        if (result is VoiceResult.AskTarget) awaitingTarget = true
        return result
    }

    /** 화면을 나가거나 오류가 나면 기다리던 질문을 잊는다. */
    fun reset() {
        awaitingTarget = false
        stopSentThisTurn = false
    }
}
