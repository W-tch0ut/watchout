package com.watchout.mobile.car

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.watchout.gesture.CarCommand
import com.watchout.gesture.Json
import com.watchout.gesture.WatchLink

/**
 * 워치가 보낸 것을 받는다. 앱 화면이 닫혀 있어도 시스템(구글 플레이 서비스)이 깨워서 알려준다.
 *
 * - 명령·낙상 의심 → 로봇카로 전달 ([CarConnectionService])
 * - 제스처 등록 진행 상황, 내 제스처 목록 → [CarHub] (화면이 본다)
 */
class WatchMessageListener : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val text = String(event.data, Charsets.UTF_8)
        when (event.path) {
            WatchLink.MSG_CMD -> {
                val sent = CarConnectionService.forwardCommand(text)
                CarHub.log(describeCommand(text) + if (sent) "" else " (로봇카 연결 안 됨)")
            }
            WatchLink.MSG_FALL -> {
                val sent = CarConnectionService.forwardFall(text)
                CarHub.log("🆘 낙상 의심" + if (sent) " → 로봇카 확인 요청" else " (로봇카 연결 안 됨)")
            }
            WatchLink.MSG_REG_STATE -> WatchLink.parseRegState(text)?.let { CarHub.reg.value = it }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != WatchLink.PATH_GESTURES) continue
            val text = DataMapItem.fromDataItem(e.dataItem).dataMap.getString(WatchLink.KEY_JSON).orEmpty()
            CarHub.gestures.value = WatchLink.parseGestureList(text)
        }
    }

    /** "✋ 부르기 → 이리 와 (다가오기)" 처럼 기록할 한 줄 */
    private fun describeCommand(json: String): String {
        val m = runCatching { Json.parseFlat(json) }.getOrNull() ?: return "명령"
        val gesture = m["gesture"] as? String
        val label = CarCommand.entries.firstOrNull { c ->
            c.wireCmd == m["cmd"] && c.args.all { (k, v) -> m[k]?.toString() == v.toString() }
        }?.label ?: (m["cmd"] as? String ?: "?")
        return if (gesture != null) "✋ $gesture → $label" else "▶ $label"
    }
}
