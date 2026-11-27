package com.watchout.mobile.car

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.watchout.gesture.GestureSummary
import com.watchout.gesture.RegState
import com.watchout.gesture.WatchLink
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * 폰 ↔ 워치 연결의 "약속". 실제 구현은 [DataLayerWatchRepository], 테스트에서는 가짜를 쓴다.
 * 메시지 형식은 워치와 같은 [WatchLink] (:gesture 모듈)를 쓴다.
 */
interface WatchRepository {
    /** 워치에 등록된 내 제스처. 아직 못 받았으면 null */
    val gestures: StateFlow<List<GestureSummary>?>

    /** 워치가 알려준 제스처 등록 진행 상황 */
    val regState: StateFlow<RegState?>

    /** 블루투스로 연결된 워치가 있는지 */
    suspend fun isWatchConnected(): Boolean

    /** 연결된 워치(모두)에 메시지를 보낸다. 받은 워치가 하나라도 있으면 true */
    suspend fun sendMessage(path: String, text: String): Boolean

    /** 워치가 마지막으로 올려 둔 제스처 목록을 다시 읽는다 */
    suspend fun refreshGestures()

    /** 지난 등록 진행 상황을 지운다 (새 등록을 시작할 때) */
    fun clearRegState()
}

/**
 * Wear OS Data Layer로 워치와 주고받는다. 받은 값은 [CarHub]에 모인다([WatchMessageListener]).
 * 워치 앱과 applicationId(com.watchout.mobile)·서명이 같아야 서로 보인다.
 */
class DataLayerWatchRepository(context: Context) : WatchRepository {
    private val app = context.applicationContext
    private val dataClient = Wearable.getDataClient(app)
    private val nodeClient = Wearable.getNodeClient(app)

    override val gestures: StateFlow<List<GestureSummary>?> = CarHub.gestures
    override val regState: StateFlow<RegState?> = CarHub.reg

    override suspend fun isWatchConnected(): Boolean =
        runCatching { nodeClient.connectedNodes.await().isNotEmpty() }.getOrDefault(false)

    override suspend fun sendMessage(path: String, text: String): Boolean = WatchBridge.send(app, path, text)

    override suspend fun refreshGestures() {
        val uri = Uri.Builder().scheme("wear").path(WatchLink.PATH_GESTURES).build()
        runCatching {
            dataClient.getDataItems(uri).await().use { items ->
                items.lastOrNull()?.let { item ->
                    val text = DataMapItem.fromDataItem(item).dataMap.getString(WatchLink.KEY_JSON).orEmpty()
                    CarHub.gestures.value = WatchLink.parseGestureList(text)
                }
            }
        }
    }

    override fun clearRegState() {
        CarHub.reg.value = null
    }
}

/** 워치로 보내기 (서비스·리스너·화면이 함께 쓴다) */
object WatchBridge {
    /** 연결된 워치 모두에 메시지. 받은 워치가 하나라도 있으면 true */
    suspend fun send(context: Context, path: String, text: String): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val client = Wearable.getMessageClient(context)
        nodes.map { client.sendMessage(it.id, path, text.toByteArray(Charsets.UTF_8)) }.forEach { it.await() }
        nodes.isNotEmpty()
    }.getOrDefault(false)

    /** 로봇카 연결 상태를 워치에 알린다. 워치가 꺼져 있어도 다음에 켜면 마지막 값을 받는다 */
    fun publishStatus(context: Context, state: CarLinkState) {
        val json = WatchLink.status(state.state, state.car?.name, state.message, System.currentTimeMillis())
        val request = PutDataMapRequest.create(WatchLink.PATH_STATUS).apply {
            dataMap.putString(WatchLink.KEY_JSON, json)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}
