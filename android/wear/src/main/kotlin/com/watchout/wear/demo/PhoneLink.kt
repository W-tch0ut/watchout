package com.watchout.wear.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.watchout.gesture.GestureSummary
import com.watchout.gesture.LinkState
import com.watchout.gesture.WatchLink
import com.watchout.wear.MainActivity

/**
 * 워치 ↔ 폰 연결 (Wear OS Data Layer). 규약은 [WatchLink].
 *
 * - 폰이 알려준 로봇카 연결 상태를 받아 화면에 보여주고, 연결되면 감지 서비스([WatchOutService])를 켠다.
 * - 알아들은 명령·낙상 의심을 폰에 보낸다([sendToPhone]). 폰이 로봇카에 전달한다.
 * - 폰이 시작한 제스처 등록을 [RemoteRegistrar]에 넘기고, 내 제스처 목록을 폰에 올린다.
 *
 * Data Layer는 폰 앱과 워치 앱의 applicationId와 서명이 같아야 서로 보인다 (wear/build.gradle.kts 참고).
 */
object PhoneLink {
    private const val TAG = "PhoneLink"
    private const val CH_PHONE = "phone"
    private const val NOTIFY_PENDING = 3
    private val main = Handler(Looper.getMainLooper())

    /** 감지 서비스를 켜야 하는데 백그라운드라 막혀서 못 켰다 (앱을 열면 켠다) */
    @Volatile
    private var startPending = false

    /** 폰(연결된 모든 노드)에 메시지를 보낸다 */
    fun sendToPhone(context: Context, path: String, text: String) {
        val app = context.applicationContext
        Wearable.getNodeClient(app).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) main.post { DemoState.log("⚠ 폰과 연결 안 됨") }
                nodes.forEach { Wearable.getMessageClient(app).sendMessage(it.id, path, text.toByteArray(Charsets.UTF_8)) }
            }
            .addOnFailureListener { Log.w(TAG, "폰에 보내지 못함: ${it.message}") }
    }

    /** 내 제스처 목록을 폰에 올린다 (폰 화면의 목록) */
    fun publishGestures(context: Context) {
        val list = CustomGestureStore(context).load().map { GestureSummary(it.id, it.name, it.command, it.templates.size) }
        val request = PutDataMapRequest.create(WatchLink.PATH_GESTURES).apply {
            dataMap.putString(WatchLink.KEY_JSON, WatchLink.gestureList(list))
            dataMap.putLong("ts", System.currentTimeMillis()) // 내용이 같아도 다시 전달되게
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context.applicationContext).putDataItem(request)
    }

    /** 앱 화면이 열릴 때: 폰이 마지막으로 알려준 상태를 읽고, 막혔던 감지 시작을 한다 */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        val uri = Uri.Builder().scheme("wear").path(WatchLink.PATH_STATUS).build()
        Wearable.getDataClient(app).getDataItems(uri).addOnSuccessListener { items ->
            val last = items.lastOrNull()
            val text = last?.let { DataMapItem.fromDataItem(it).dataMap.getString(WatchLink.KEY_JSON) }
            items.release()
            text?.let { applyStatus(app, it) }
        }
        if (startPending) {
            startPending = false
            runCatching { WatchOutService.start(app) }
            app.getSystemService(NotificationManager::class.java).cancel(NOTIFY_PENDING)
        }
        publishGestures(app)
    }

    /** 폰이 알려준 로봇카 상태를 화면에 반영하고, 감지 서비스를 켜거나 끈다 */
    fun applyStatus(context: Context, json: String) {
        val status = WatchLink.parseStatus(json) ?: return
        main.post {
            val before = DemoState.connection
            DemoState.connection = when (status.state) {
                LinkState.OFF -> DemoState.Connection.OFF
                LinkState.CONNECTING -> DemoState.Connection.CONNECTING
                LinkState.CONNECTED -> DemoState.Connection.CONNECTED
                LinkState.ERROR -> DemoState.Connection.ERROR
            }
            DemoState.carName = status.carName
            DemoState.connectionMessage = status.message
            DemoState.carStatus = if (status.state == LinkState.CONNECTED) status.message else ""
            if (before != DemoState.connection) {
                DemoState.log(if (status.state == LinkState.OFF) "⏹ 로봇카 연결 끊김" else "🔗 ${status.carName} ${status.state.label}")
            }
            when (status.state) {
                LinkState.OFF -> WatchOutService.stop(context)
                LinkState.CONNECTED -> startService(context)
                else -> Unit // 연결 중·잠깐 끊김: 켜져 있으면 그대로 둔다
            }
        }
    }

    private fun startService(context: Context) {
        try {
            WatchOutService.start(context)
        } catch (e: IllegalStateException) {
            // 안드로이드 12+는 앱이 화면에 없을 때 포그라운드 서비스를 못 띄우게 막을 수 있다.
            // 그러면 "눌러서 시작" 알림을 띄우고, 앱을 열면 시작한다.
            Log.w(TAG, "백그라운드에서 감지 시작이 막힘: ${e.message}")
            startPending = true
            notify(context, NOTIFY_PENDING, "로봇카 연결됨", "눌러서 제스처·낙상 감지를 켜 주세요")
        }
    }

    /** 앱을 여는 알림. [fullScreen]이면 화면이 꺼져 있어도 앱 화면을 바로 띄운다 (권한이 없으면 알림으로) */
    fun notify(context: Context, id: Int, title: String, text: String, fullScreen: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PHONE, "폰에서 온 알림", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, CH_PHONE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (fullScreen) b.setFullScreenIntent(open, true)
        nm.notify(id, b.build())
    }

    fun cancelNotification(context: Context, id: Int) =
        context.getSystemService(NotificationManager::class.java).cancel(id)
}

/** 폰이 보낸 것을 받는다. 앱 화면이 닫혀 있어도 시스템(구글 플레이 서비스)이 깨워서 알려준다 */
class PhoneLinkListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WatchLink.PATH_STATUS) continue
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WatchLink.KEY_JSON) ?: continue
            PhoneLink.applyStatus(applicationContext, json)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val text = String(event.data, Charsets.UTF_8)
        when (event.path) {
            WatchLink.MSG_FALL_RESULT -> WatchOutService.fallResult(applicationContext, text)
            WatchLink.MSG_REG -> WatchLink.parseRegRequest(text)?.let { RemoteRegistrar.handle(applicationContext, it) }
        }
    }
}
