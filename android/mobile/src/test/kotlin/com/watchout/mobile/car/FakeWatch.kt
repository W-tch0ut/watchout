package com.watchout.mobile.car

import com.watchout.gesture.GestureSummary
import com.watchout.gesture.RegState
import kotlinx.coroutines.flow.MutableStateFlow

/** 워치 대신 쓰는 가짜: 보낸 메시지를 기록하고, 워치가 보낼 값은 테스트가 직접 넣는다 */
class FakeWatch(var connected: Boolean = true) : WatchRepository {
    override val gestures = MutableStateFlow<List<GestureSummary>?>(null)
    override val regState = MutableStateFlow<RegState?>(null)
    val sent = mutableListOf<Pair<String, String>>()
    var refreshCount = 0

    override suspend fun isWatchConnected() = connected

    override suspend fun sendMessage(path: String, text: String): Boolean {
        if (!connected) return false
        sent += path to text
        return true
    }

    override suspend fun refreshGestures() {
        refreshCount++
    }

    override fun clearRegState() {
        regState.value = null
    }
}
