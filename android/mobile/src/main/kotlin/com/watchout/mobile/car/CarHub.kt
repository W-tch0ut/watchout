package com.watchout.mobile.car

import com.watchout.gesture.CarInfo
import com.watchout.gesture.GestureSummary
import com.watchout.gesture.LinkState
import com.watchout.gesture.RegState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 폰 ↔ 로봇카 연결 상태. [message]는 로봇카가 보낸 지금 하는 일(또는 끊긴 이유) */
data class CarLinkState(
    val state: LinkState = LinkState.OFF,
    val car: CarInfo? = null,
    val message: String = "",
)

/**
 * 폰 앱 전체가 함께 보는 로봇카·워치 상태.
 *
 * 값을 바꾸는 쪽: [CarConnectionService](로봇카 연결), [WatchMessageListener](워치가 보낸 것).
 * 보는 쪽: 화면(ViewModel). StateFlow라서 여러 스레드에서 바꿔도 안전하고, 바뀌면 화면이 다시 그려진다.
 */
object CarHub {
    val link = MutableStateFlow(CarLinkState())

    /** 최근 일 (워치 제스처 → 명령 전달, 낙상 등). 최신이 앞 */
    val events = MutableStateFlow<List<String>>(emptyList())

    /** 워치에 등록된 내 제스처. 아직 못 받았으면 null */
    val gestures = MutableStateFlow<List<GestureSummary>?>(null)

    /** 워치가 알려준 제스처 등록 진행 상황 */
    val reg = MutableStateFlow<RegState?>(null)

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        events.update { (listOf("$time $message") + it).take(MAX_EVENTS) }
    }

    private const val MAX_EVENTS = 10
}
