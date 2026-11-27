package com.watchout.wear.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.watchout.gesture.RegState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 데모 화면과 백그라운드 서비스가 함께 보는 상태.
 *
 * 연결 상태는 폰이 알려준다(워치는 로봇카와 직접 연결하지 않는다, [PhoneLink]).
 * 서비스가 값을 바꾸고 화면은 그리기만 한다. Compose 상태라서 값이 바뀌면 화면이 저절로 다시 그려진다.
 * 값은 화면 스레드에서만 바꾼다. (서비스는 Handler로 화면 스레드에 넘겨서 바꾼다)
 */
object DemoState {
    enum class Connection { OFF, CONNECTING, CONNECTED, ERROR }

    /** 폰 ↔ 로봇카 연결 상태 (폰이 알려준다) */
    var connection by mutableStateOf(Connection.OFF)
    var connectionMessage by mutableStateOf("")
    var carName by mutableStateOf<String?>(null)

    /** 로봇카가 지금 하는 일 ("사용자 쪽으로 다가갑니다" 등) */
    var carStatus by mutableStateOf("")

    /** 폰에서 시작한 제스처 등록 진행 상황 (null이면 등록 중 아님) */
    var remoteReg by mutableStateOf<RegState?>(null)

    /** 최근 일어난 일 (제스처 인식, 명령 전송, 낙상 등). 최신이 앞 */
    val events = mutableStateListOf<String>()

    var fall by mutableStateOf<FallUi?>(null)

    /** 내 제스처를 등록하는 동안에는 백그라운드 인식을 잠시 멈춘다 */
    var registering by mutableStateOf(false)

    /** 내 제스처 목록이 바뀌면 올린다. 서비스가 보고 다시 읽는다 */
    var gesturesVersion by mutableIntStateOf(0)

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        events.add(0, "$time $message")
        while (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
    }

    private const val MAX_EVENTS = 8
}

/** 메뉴·연결 화면에 보여줄 한 줄 요약 */
fun DemoState.connectionSummary(): String = when (connection) {
    DemoState.Connection.OFF -> "연결 안 됨"
    DemoState.Connection.CONNECTING -> connectionMessage.ifEmpty { "연결 중…" }
    DemoState.Connection.CONNECTED -> "${carName ?: "로봇카"} 연결됨"
    DemoState.Connection.ERROR -> "끊김: $connectionMessage"
}

/** 낙상 알림 화면에 보여줄 상태 */
data class FallUi(val phase: Phase, val secondsLeft: Int, val message: String) {
    enum class Phase { ASKING, SENT, CONFIRMED, NOT_CONFIRMED, CANCELLED }
}
