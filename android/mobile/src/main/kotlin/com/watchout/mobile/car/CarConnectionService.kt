package com.watchout.mobile.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.watchout.gesture.CarInfo
import com.watchout.gesture.CarProtocol
import com.watchout.gesture.LinkState
import com.watchout.gesture.WatchLink
import com.watchout.mobile.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 폰이 로봇카와 MQTT 연결을 유지하는 서비스 (앱 화면을 닫아도 돈다. 알림이 떠 있다).
 *
 * - 로봇카의 상태·낙상 확인 결과를 받아 [CarHub]와 워치에 알린다.
 * - 워치가 보낸 명령·낙상 의심([WatchMessageListener])을 로봇카에 전달한다 ([forward]).
 *
 * 워치는 로봇카와 직접 말하지 않는다. 로봇카와의 통신은 이 서비스 하나가 맡는다.
 */
class CarConnectionService : Service(), CarLink.Listener {

    private var link: CarLink? = null
    private var car: CarInfo? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CH_ONGOING, "로봇카 연결", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(CH_FALL, "낙상 알림", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val info = intent?.let {
            CarInfo(
                it.getStringExtra(EXTRA_ID) ?: return@let null,
                it.getStringExtra(EXTRA_NAME) ?: "로봇카",
                it.getStringExtra(EXTRA_HOST) ?: return@let null,
                it.getIntExtra(EXTRA_PORT, 1883),
            )
        }
        val notification = ongoing("${info?.name ?: "로봇카"}에 연결 중…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFY_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFY_ONGOING, notification)
        }
        if (info == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (car?.id != info.id) { // 처음 연결하거나 다른 로봇카로 바꿨다
            link?.close()
            car = info
            setState(LinkState.CONNECTING, "연결 중…")
            link = CarLink(info, this).also { it.connect() }
        }
        return START_NOT_STICKY
    }

    /** 워치에서 온 메시지를 로봇카로 보낸다. 연결돼 있지 않으면 false */
    private fun send(topicOf: (String) -> String, json: String): Boolean {
        val c = car ?: return false
        if (CarHub.link.value.state != LinkState.CONNECTED) return false
        link?.send(topicOf(c.id), json)
        return true
    }

    // ---------- 로봇카 (CarLink.Listener, 통신 스레드에서 불림) ----------

    override fun onConnected() {
        setState(LinkState.CONNECTED, "연결됨")
        CarHub.log("🔗 ${car?.name} 연결")
    }

    override fun onDisconnected(reason: String) = setState(LinkState.ERROR, reason)

    override fun onStatus(json: String) {
        val status = CarProtocol.parseStatus(json) ?: return
        setState(LinkState.CONNECTED, status.message.ifEmpty { status.state })
    }

    override fun onFallResult(json: String) {
        val result = CarProtocol.parseFallResult(json) ?: return
        val title = if (result.confirmed) "쓰러짐 확인 → 보호자에게 알렸어요" else "쓰러진 모습이 보이지 않았어요"
        CarHub.log(if (result.confirmed) "📨 $title" else "👀 $title")
        notifyFall(title, result.message)
        scope.launch { WatchBridge.send(this@CarConnectionService, WatchLink.MSG_FALL_RESULT, json) }
    }

    private fun setState(state: LinkState, message: String) {
        CarHub.link.update { CarLinkState(state, car, message) }
        WatchBridge.publishStatus(this, CarHub.link.value)
        val text = when (state) {
            LinkState.CONNECTED -> "${car?.name} 연결됨 · $message"
            LinkState.ERROR -> "연결 끊김: $message"
            else -> message
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ONGOING, ongoing(text))
    }

    override fun onDestroy() {
        instance = null
        link?.close()
        link = null
        car = null
        CarHub.link.value = CarLinkState()
        WatchBridge.publishStatus(this, CarHub.link.value)
        CarHub.log("⏹ 로봇카 연결 끊음")
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 알림 ----------

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(text: String): Notification = Notification.Builder(this, CH_ONGOING)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("WatchOut 로봇카")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    /** 낙상 의심·결과를 폰에도 알린다 (보호자 문자는 로봇카가 보낸다) */
    fun notifyFall(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFY_FALL,
            Notification.Builder(this, CH_FALL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CH_ONGOING = "car"
        private const val CH_FALL = "fall"
        private const val NOTIFY_ONGOING = 1
        private const val NOTIFY_FALL = 2
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        @Volatile
        private var instance: CarConnectionService? = null

        fun connect(context: Context, car: CarInfo) {
            context.startForegroundService(
                Intent(context, CarConnectionService::class.java)
                    .putExtra(EXTRA_ID, car.id).putExtra(EXTRA_NAME, car.name)
                    .putExtra(EXTRA_HOST, car.host).putExtra(EXTRA_PORT, car.port),
            )
        }

        fun disconnect(context: Context) {
            context.stopService(Intent(context, CarConnectionService::class.java))
        }

        /** 워치가 보낸 명령을 로봇카로. 로봇카에 연결돼 있지 않으면 false */
        fun forwardCommand(json: String): Boolean = instance?.send(CarProtocol::cmdTopic, json) ?: false

        /** 워치가 보낸 낙상 의심을 로봇카로. 폰에도 알린다. 연결돼 있지 않으면 false */
        fun forwardFall(json: String): Boolean {
            val service = instance ?: return false
            service.notifyFall("⚠ 낙상 의심", "워치가 충격을 감지했고 10초간 응답이 없어요. 로봇카가 확인하러 가요")
            return service.send(CarProtocol::fallTopic, json)
        }
    }
}
