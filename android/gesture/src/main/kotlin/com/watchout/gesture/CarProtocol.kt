package com.watchout.gesture

/**
 * 워치 ↔ 로봇카 메시지 규약 (데모용 제안. 팀 합의(#34) 후 확정한다).
 *
 * 통신은 MQTT를 쓴다. 로봇카(또는 데모 PC)가 브로커를 열고, 워치가 접속한다.
 *
 * | 토픽 | 방향 | 내용 |
 * |---|---|---|
 * | `watchout/cars/{carId}/cmd` | 워치 → 차 | 명령 `{"cmd":"approach","target":"user","source":"gesture","gesture":"부르기","ts":...}` |
 * | `watchout/cars/{carId}/status` | 차 → 워치 | 상태 `{"state":"moving","msg":"사용자에게 가는 중"}` |
 * | `watchout/cars/{carId}/fall` | 워치 → 차 | 낙상 의심 `{"event":"fall_suspected","ts":...,"impact_g":3.1}` |
 * | `watchout/cars/{carId}/fall/result` | 차 → 워치 | 확인 결과 `{"confirmed":true,"msg":"보호자에게 알렸습니다"}` |
 *
 * 로봇카 찾기: 차는 Wi-Fi에 1초마다 UDP 방송(포트 [DISCOVERY_PORT])으로 자기를 알린다.
 * `{"type":"watchout_car","id":"demo-car","name":"데모 로봇카","host":"192.168.0.34","port":1883}`
 */
object CarProtocol {
    const val DISCOVERY_PORT = 47474
    const val DISCOVERY_TYPE = "watchout_car"

    fun cmdTopic(carId: String) = "watchout/cars/$carId/cmd"
    fun statusTopic(carId: String) = "watchout/cars/$carId/status"
    fun fallTopic(carId: String) = "watchout/cars/$carId/fall"
    fun fallResultTopic(carId: String) = "watchout/cars/$carId/fall/result"

    /**
     * 명령 메시지. 앞부분은 음성 명령(#71 `RobotCommand`, CLAUDE.md 예시)과 같은 형식이다.
     * 예) `{"cmd":"grab","target":"phone"}`, `{"cmd":"turn","angle_deg":90}`
     * 뒤에 어디서 왔는지(source: gesture/button/voice)·제스처 이름·시각을 덧붙인다. 로봇카는 모르는 필드는 무시한다.
     */
    fun command(cmd: CarCommand, source: String, gesture: String?, ts: Long): String =
        Json.obj(
            linkedMapOf<String, Any?>("cmd" to cmd.wireCmd).apply {
                putAll(cmd.args)
                put("source", source)
                put("gesture", gesture)
                put("ts", ts)
            },
        )

    fun fallSuspected(ts: Long, impactG: Float, source: String): String =
        Json.obj(
            linkedMapOf(
                "event" to "fall_suspected",
                "ts" to ts,
                "impact_g" to impactG,
                "source" to source,
            ),
        )

    /** 방송 패킷을 읽는다. 우리 형식이 아니면 null */
    fun parseDiscovery(text: String): CarInfo? {
        val m = runCatching { Json.parseFlat(text) }.getOrNull() ?: return null
        if (m["type"] != DISCOVERY_TYPE) return null
        val id = m["id"] as? String ?: return null
        val host = m["host"] as? String ?: return null
        val port = (m["port"] as? Number)?.toInt() ?: 1883
        return CarInfo(id, m["name"] as? String ?: id, host, port)
    }

    fun parseStatus(text: String): CarStatus? {
        val m = runCatching { Json.parseFlat(text) }.getOrNull() ?: return null
        return CarStatus(m["state"] as? String ?: "unknown", m["msg"] as? String ?: "")
    }

    fun parseFallResult(text: String): FallResult? {
        val m = runCatching { Json.parseFlat(text) }.getOrNull() ?: return null
        val confirmed = m["confirmed"] as? Boolean ?: return null
        return FallResult(confirmed, m["msg"] as? String ?: "")
    }
}

data class CarInfo(val id: String, val name: String, val host: String, val port: Int) {
    val brokerUri: String get() = "tcp://$host:$port"
}

data class CarStatus(val state: String, val message: String)

data class FallResult(val confirmed: Boolean, val message: String)

/**
 * 로봇카(와 로봇팔)에 보낼 수 있는 명령. 제스처마다 하나를 연결한다.
 *
 * @property id 워치 안에서 쓰는 이름 (내 제스처 저장 파일에 들어간다. 바꾸면 저장된 제스처가 깨진다)
 * @property wireCmd 로봇카로 보내는 `cmd` 값 (음성 명령 #71과 같은 형식)
 * @property args `cmd`와 함께 보내는 값. 회전은 왼쪽 +, 오른쪽 -. 이동은 앞 +, 뒤 -
 */
enum class CarCommand(
    val id: String,
    val label: String,
    val wireCmd: String,
    val args: Map<String, Any> = emptyMap(),
) {
    APPROACH("approach", "이리 와 (다가오기)", "approach", mapOf("target" to "user")),
    STOP("stop", "멈춰", "stop"),
    FOLLOW("follow", "따라와", "follow", mapOf("target" to "user")),
    TURN_LEFT("turn_left", "왼쪽으로 돌기", "turn", mapOf("angle_deg" to 90)),
    TURN_RIGHT("turn_right", "오른쪽으로 돌기", "turn", mapOf("angle_deg" to -90)),
    FORWARD("forward", "앞으로", "move", mapOf("dist_cm" to 30)),
    BACKWARD("backward", "뒤로", "move", mapOf("dist_cm" to -30)),

    /** 음성 명령(#71)에는 아직 없다. #34에서 넣을지 정한다 (제안) */
    GO_HOME("go_home", "제자리로", "go_home"),
    GRAB_PHONE("grab_phone", "로봇팔: 휴대폰 가져오기", "grab", mapOf("target" to "phone")),
    ;

    companion object {
        fun fromId(id: String): CarCommand? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 아주 작은 JSON 도구. 이 규약은 한 단계짜리 객체(문자열·숫자·참거짓·null)만 쓴다.
 * 순수 Kotlin 모듈이라 안드로이드의 org.json을 쓸 수 없어서 직접 만들었다.
 */
object Json {
    fun obj(values: Map<String, Any?>): String = values.entries.joinToString(",", "{", "}") { (k, v) ->
        "${quote(k)}:${value(v)}"
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Double -> if (v.isFinite()) v.toString() else "null"
        else -> quote(v.toString())
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    /** 한 단계짜리 JSON 객체를 읽는다. 숫자는 Long 또는 Double. 형식이 틀리면 예외 */
    fun parseFlat(text: String): Map<String, Any?> {
        val p = Parser(text.trim())
        val result = p.obj()
        p.skipWs()
        require(p.done()) { "JSON 뒤에 남은 글자가 있어요" }
        return result
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun done() = i >= s.length
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun expect(c: Char) {
            skipWs()
            require(i < s.length && s[i] == c) { "'$c'가 필요해요 (위치 $i)" }
            i++
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            expect('{')
            skipWs()
            if (s.getOrNull(i) == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = string()
                expect(':')
                skipWs()
                out[key] = value()
                skipWs()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("',' 또는 '}'가 필요해요 (위치 $i)")
                }
            }
        }

        private fun value(): Any? = when (val c = s.getOrNull(i)) {
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c != null && (c == '-' || c.isDigit())) number() else throw IllegalArgumentException("값이 아니에요 (위치 $i)")
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "$word 가 필요해요" }
            i += word.length
            return v
        }

        private fun number(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }

        private fun string(): String {
            require(s.getOrNull(i) == '"') { "문자열이 필요해요 (위치 $i)" }
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "문자열이 끝나지 않았어요" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e) // \" \\ \/
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
