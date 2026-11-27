package com.watchout.gesture

/**
 * 폰 ↔ 워치 메시지 규약 (Wear OS Data Layer).
 *
 * 역할 나눔:
 * - **폰**이 로봇카를 찾고 MQTT로 연결한다. 로봇카와 말하는 것은 폰뿐이다.
 * - **워치**는 센서(제스처·낙상)만 맡는다. 알아들은 명령·낙상 의심을 폰에 보내면 폰이 로봇카에 전달한다.
 * - 제스처 등록도 폰 화면에서 시작하고, 워치는 시연을 녹화한다 (손짓하는 손으로 워치를 누르지 않아도 되게).
 *
 * 두 가지 통로를 쓴다.
 * - **데이터 항목(DataItem)**: 마지막 값이 남는 "상태". 한쪽이 꺼져 있다가 켜져도 마지막 값을 받는다.
 * - **메시지(Message)**: 한 번 보내고 끝나는 "사건". 상대가 연결돼 있지 않으면 사라진다.
 *
 * | 경로 | 종류 | 방향 | 내용 |
 * |---|---|---|---|
 * | [PATH_STATUS] | 데이터 | 폰 → 워치 | 로봇카 연결 상태 `{"state":"connected","car":"데모 로봇카","msg":"사용자 쪽으로 다가갑니다","ts":..}` |
 * | [PATH_GESTURES] | 데이터 | 워치 → 폰 | 내 제스처 목록 ([gestureList]) |
 * | [MSG_CMD] | 메시지 | 워치 → 폰 | 명령 ([CarProtocol.command] 그대로) → 폰이 로봇카에 전달 |
 * | [MSG_FALL] | 메시지 | 워치 → 폰 | 낙상 의심 ([CarProtocol.fallSuspected] 그대로) → 폰이 로봇카에 전달 |
 * | [MSG_FALL_RESULT] | 메시지 | 폰 → 워치 | 로봇카가 보낸 낙상 확인 결과 그대로 |
 * | [MSG_REG] | 메시지 | 폰 → 워치 | 제스처 등록 조작 ([regStart], [regAction], [regDelete]) |
 * | [MSG_REG_STATE] | 메시지 | 워치 → 폰 | 제스처 등록 진행 상황 ([regState]) |
 *
 * 데이터 항목은 내용이 같으면 다시 전달되지 않는다. 그래서 상태에는 매번 ts를 넣는다.
 * 데이터 항목 안에는 글자 하나를 [KEY_JSON]으로 넣고, 메시지는 UTF-8 글자를 그대로 보낸다.
 */
object WatchLink {
    const val PATH_STATUS = "/watchout/status"
    const val PATH_GESTURES = "/watchout/gestures"
    const val MSG_CMD = "/watchout/msg/cmd"
    const val MSG_FALL = "/watchout/msg/fall"
    const val MSG_FALL_RESULT = "/watchout/msg/fall_result"
    const val MSG_REG = "/watchout/msg/reg"
    const val MSG_REG_STATE = "/watchout/msg/reg_state"
    const val KEY_JSON = "json"

    // ---------- 로봇카 연결 상태 (폰 → 워치) ----------

    fun status(state: LinkState, carName: String?, message: String, ts: Long): String = Json.obj(
        linkedMapOf("state" to state.id, "car" to carName, "msg" to message, "ts" to ts),
    )

    fun parseStatus(text: String): WatchStatus? {
        val m = runCatching { Json.parseFlat(text) }.getOrNull() ?: return null
        val state = LinkState.fromId(m["state"] as? String ?: return null) ?: return null
        return WatchStatus(state, m["car"] as? String, m["msg"] as? String ?: "")
    }

    // ---------- 내 제스처 목록 (워치 → 폰) ----------

    /** 한 줄에 제스처 하나: `id<TAB>이름<TAB>명령id<TAB>시연 횟수`. (규약 JSON이 목록을 못 담아서 줄로 나눈다) */
    fun gestureList(gestures: List<GestureSummary>): String = gestures.joinToString("\n") { g ->
        listOf(g.id, g.name, g.command.id, g.samples.toString()).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    fun parseGestureList(text: String): List<GestureSummary> = text.lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 4) return@mapNotNull null
        val command = CarCommand.fromId(parts[2]) ?: return@mapNotNull null
        GestureSummary(parts[0], parts[1], command, parts[3].toIntOrNull() ?: 0)
    }

    // ---------- 제스처 등록 (폰 ↔ 워치) ----------

    /** 폰 → 워치: [name] 제스처를 [command]에 연결해서 등록을 시작한다 */
    fun regStart(name: String, command: CarCommand): String =
        Json.obj(linkedMapOf("action" to RegAction.START.id, "name" to name, "command" to command.id))

    /** 폰 → 워치: 시연 한 번 녹화 / 저장 / 취소 */
    fun regAction(action: RegAction): String {
        require(action != RegAction.START && action != RegAction.DELETE) { "시작·삭제는 regStart·regDelete로 만든다" }
        return Json.obj(linkedMapOf("action" to action.id))
    }

    /** 폰 → 워치: 제스처 지우기 */
    fun regDelete(id: String): String = Json.obj(linkedMapOf("action" to RegAction.DELETE.id, "id" to id))

    fun parseRegRequest(text: String): RegRequest? {
        val m = runCatching { Json.parseFlat(text) }.getOrNull() ?: return null
        val action = RegAction.fromId(m["action"] as? String ?: return null) ?: return null
        return when (action) {
            RegAction.START -> {
                val name = m["name"] as? String ?: return null
                val command = CarCommand.fromId(m["command"] as? String ?: return null) ?: return null
                RegRequest(action, name = name, command = command)
            }
            RegAction.DELETE -> RegRequest(action, id = m["id"] as? String ?: return null)
            else -> RegRequest(action)
        }
    }

    /** 워치 → 폰: 등록 진행 상황 */
    fun regState(state: RegState): String = Json.obj(
        linkedMapOf(
            "phase" to state.phase.id,
            "name" to state.name,
            "count" to state.count,
            "seconds" to state.secondsLeft,
            "msg" to state.message,
        ),
    )

    fun parseRegState(text: String): RegState? {
        val m = runCatching { Json.parseFlat(text) }.getOrNull() ?: return null
        val phase = RegPhase.fromId(m["phase"] as? String ?: return null) ?: return null
        return RegState(
            phase = phase,
            name = m["name"] as? String ?: "",
            count = (m["count"] as? Number)?.toInt() ?: 0,
            secondsLeft = (m["seconds"] as? Number)?.toInt() ?: 0,
            message = m["msg"] as? String ?: "",
        )
    }

    /** 이만큼 시연해야 저장할 수 있다 / 이만큼이면 충분하다 */
    const val MIN_SAMPLES = 3
    const val GOOD_SAMPLES = 5
}

/** 로봇카 연결 상태 (폰이 로봇카와 연결한 상태) */
enum class LinkState(val id: String, val label: String) {
    OFF("off", "연결 안 됨"),
    CONNECTING("connecting", "연결 중"),
    CONNECTED("connected", "연결됨"),
    ERROR("error", "연결 끊김"),
    ;

    companion object {
        fun fromId(id: String): LinkState? = entries.firstOrNull { it.id == id }
    }
}

/** 폰이 워치에 알려준 로봇카 상태. [message]는 로봇카가 보낸 지금 하는 일 */
data class WatchStatus(val state: LinkState, val carName: String?, val message: String)

/** 폰 화면에 보여줄 제스처 요약 (동작 모양 데이터는 워치에만 있다) */
data class GestureSummary(val id: String, val name: String, val command: CarCommand, val samples: Int)

/** 폰이 워치에 보내는 등록 조작 */
enum class RegAction(val id: String) {
    START("start"),
    RECORD("record"),
    SAVE("save"),
    CANCEL("cancel"),
    DELETE("delete"),
    ;

    companion object {
        fun fromId(id: String): RegAction? = entries.firstOrNull { it.id == id }
    }
}

data class RegRequest(
    val action: RegAction,
    val name: String? = null,
    val command: CarCommand? = null,
    val id: String? = null,
)

/** 제스처 등록 단계 (워치가 폰에 알려준다) */
enum class RegPhase(val id: String) {
    /** 시연을 기다리는 중 (폰에서 '시연'을 누르면 시작) */
    READY("ready"),
    COUNTDOWN("countdown"),
    RECORDING("recording"),
    ADDED("added"),
    FAILED("failed"),
    SAVED("saved"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromId(id: String): RegPhase? = entries.firstOrNull { it.id == id }
    }
}

data class RegState(
    val phase: RegPhase,
    val name: String = "",
    /** 지금까지 저장된 시연 수 */
    val count: Int = 0,
    /** COUNTDOWN일 때 남은 초 */
    val secondsLeft: Int = 0,
    val message: String = "",
) {
    val canSave: Boolean get() = count >= WatchLink.MIN_SAMPLES && phase != RegPhase.SAVED && phase != RegPhase.CANCELLED
    val busy: Boolean get() = phase == RegPhase.COUNTDOWN || phase == RegPhase.RECORDING
}

/** 제스처 이름 후보(워치·폰에서 글자 입력 없이 고른다) → 추천 명령 */
object GesturePresets {
    val ALL: List<Pair<String, CarCommand>> = listOf(
        "부르기 (이리 와 손짓)" to CarCommand.APPROACH,
        "멈춰" to CarCommand.STOP,
        "따라와" to CarCommand.FOLLOW,
        "왼쪽으로" to CarCommand.TURN_LEFT,
        "오른쪽으로" to CarCommand.TURN_RIGHT,
        "휴대폰 가져와" to CarCommand.GRAB_PHONE,
        "내 제스처 1" to CarCommand.FORWARD,
        "내 제스처 2" to CarCommand.BACKWARD,
    )
}
