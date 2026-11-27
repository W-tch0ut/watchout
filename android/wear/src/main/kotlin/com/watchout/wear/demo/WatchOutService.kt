package com.watchout.wear.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.watchout.gesture.CarCommand
import com.watchout.gesture.CarProtocol
import com.watchout.gesture.CustomGesture
import com.watchout.gesture.DtwMatcher
import com.watchout.gesture.FallAlertTimer
import com.watchout.gesture.FallDetector
import com.watchout.gesture.MotionSegmenter
import com.watchout.gesture.SensorSample
import com.watchout.gesture.WatchLink
import com.watchout.gesture.WearingHand
import com.watchout.wear.MainActivity
import com.watchout.wear.SensorStreamer

/**
 * 데모의 백그라운드 서비스. 앱 화면을 닫아도 계속 돈다 (포그라운드 서비스 + 알림).
 *
 * 폰이 로봇카에 연결하면([PhoneLink]가 상태를 받고) 시작하고, 폰이 연결을 끊으면 멈춘다.
 * 워치는 로봇카와 직접 말하지 않는다. 모든 것을 폰에 보내고, 폰이 로봇카에 전달한다.
 *
 * 센서를 계속 읽어서
 * - 동작이 하나 끝날 때마다 "내 제스처"와 비교하고([DtwMatcher]), 맞으면 명령을 폰에 보낸다.
 * - 낙상을 감시하다가([FallDetector]) 의심되면 "괜찮으세요?" 10초 카운트다운([FallAlertTimer])을 한다.
 *   10초 안에 취소가 없으면 폰에 낙상 의심을 보내고(→ 로봇카가 카메라로 확인), 결과를 기다린다.
 *
 * 화면과는 [DemoState]로 상태를 나누고, 요청은 Intent action으로 받는다.
 */
class WatchOutService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var streamer: SensorStreamer
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val segmenter = MotionSegmenter()
    private val fallDetector = FallDetector()
    private val fallTimer = FallAlertTimer()
    private var fallImpactG = 0f
    private var fallSource = ""

    private var gestures: List<CustomGesture> = emptyList()
    private var gesturesVersion = -1
    private var hand = WearingHand.LEFT
    private var lastHitMs = Long.MIN_VALUE / 2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        streamer = SensorStreamer(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stopSelf()
            ACTION_SEND -> CarCommand.fromId(intent.getStringExtra(EXTRA_CMD) ?: "")?.let { sendCommand(it, "button", null) }
            ACTION_TEST_FALL -> triggerFall(3.0f, "test_button")
            ACTION_CANCEL_FALL -> cancelFall()
            ACTION_FALL_RESULT -> intent.getStringExtra(EXTRA_JSON)?.let(::showFallResult)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val notification = ongoingNotification("${DemoState.carName ?: "로봇카"} 연결됨 · 제스처 듣는 중")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFY_ONGOING, notification)
        }
        if (running) return
        running = true
        hand = runCatching {
            WearingHand.valueOf(getSharedPreferences("gesture_tool", MODE_PRIVATE).getString("hand", null) ?: "")
        }.getOrDefault(WearingHand.LEFT)
        // 화면이 꺼져도 센서가 멈추지 않게 CPU를 깨워 둔다 (데모용. 배터리를 많이 쓴다)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchout:service")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        streamer.start(::onSample)
        DemoState.log("👂 제스처·낙상 감지 시작")
    }

    // ---------- 센서 ----------

    private fun onSample(s: SensorSample) {
        if (prefs().getBoolean(KEY_FALL_ON, true)) {
            fallDetector.onSample(s)?.let { triggerFall(it, "sensor") }
        }
        if (DemoState.registering || DemoState.fall != null) return
        val frames = segmenter.onSample(s.normalizedFor(hand)) ?: return
        if (gesturesVersion != DemoState.gesturesVersion) {
            gestures = CustomGestureStore(this).load()
            gesturesVersion = DemoState.gesturesVersion
        }
        if (gestures.isEmpty()) return
        // 한 번 알아들으면 잠깐 쉰다. 손짓 하나가 여러 번 잡히거나, 손을 내리는 동작이 또 잡히지 않게
        if (s.timestampMs - lastHitMs < HIT_COOLDOWN_MS) return
        val shape = DtwMatcher.shape(frames)
        // 개발자 모드: 동작마다 가장 가까운 제스처와 거리/기준을 기록해서 기준을 맞출 수 있게 한다
        if (prefs().getBoolean(KEY_DEV_MODE, false)) {
            DtwMatcher.explain(shape, gestures)?.let { DemoState.log("· $it") }
        }
        val hit = DtwMatcher.match(shape, gestures) ?: return
        lastHitMs = s.timestampMs
        Haptics.pulse(this, longArrayOf(0, 60, 80, 60))
        sendCommand(hit.gesture.command, "gesture", hit.gesture.name)
    }

    /** 명령을 폰에 보낸다. 폰이 로봇카에 전달한다 */
    private fun sendCommand(cmd: CarCommand, source: String, gesture: String?) {
        PhoneLink.sendToPhone(this, WatchLink.MSG_CMD, CarProtocol.command(cmd, source, gesture, System.currentTimeMillis()))
        DemoState.log(if (gesture != null) "✋ $gesture → ${cmd.label}" else "▶ ${cmd.label}")
    }

    // ---------- 낙상 ----------

    private fun triggerFall(impactG: Float, source: String) {
        if (DemoState.fall?.phase == FallUi.Phase.ASKING) return
        fallImpactG = impactG
        fallSource = source
        fallTimer.reset()
        fallTimer.start(System.currentTimeMillis())
        DemoState.fall = FallUi(FallUi.Phase.ASKING, 10, "괜찮으세요?")
        DemoState.log("⚠ 낙상 의심 (${"%.1f".format(impactG)}g)")
        Haptics.pulse(this, longArrayOf(0, 500, 300, 500, 300, 500))
        notifyFall("괜찮으세요?", "10초 안에 '괜찮아요'를 누르지 않으면 로봇카가 확인하러 갑니다", withCancel = true)
        main.post(fallTick)
    }

    private val fallTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            when (fallTimer.tick(now)) {
                FallAlertTimer.Phase.COUNTDOWN -> {
                    DemoState.fall = FallUi(FallUi.Phase.ASKING, fallTimer.secondsLeft(now), "괜찮으세요?")
                    if (fallTimer.secondsLeft(now) % 3 == 0) Haptics.pulse(this@WatchOutService, longArrayOf(0, 200))
                    main.postDelayed(this, 250)
                }
                FallAlertTimer.Phase.SEND -> {
                    PhoneLink.sendToPhone(
                        this@WatchOutService,
                        WatchLink.MSG_FALL,
                        CarProtocol.fallSuspected(now, fallImpactG, fallSource),
                    )
                    DemoState.fall = FallUi(FallUi.Phase.SENT, 0, "로봇카가 확인하러 가는 중…")
                    DemoState.log("🚗 폰 → 로봇카에 낙상 확인 요청")
                    notifyFall("로봇카가 확인하러 가는 중", "카메라로 상태를 확인합니다", withCancel = false)
                }
                else -> Unit
            }
        }
    }

    private fun cancelFall() {
        if (fallTimer.cancel()) {
            main.removeCallbacks(fallTick)
            DemoState.fall = FallUi(FallUi.Phase.CANCELLED, 0, "괜찮다고 하셔서 알리지 않았어요")
            DemoState.log("🙆 괜찮아요 (취소)")
            Haptics.stop(this)
        } else {
            DemoState.fall = null // 결과 화면 닫기
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFY_FALL)
        main.postDelayed({ if (DemoState.fall?.phase == FallUi.Phase.CANCELLED) DemoState.fall = null }, 3000)
    }

    /** 로봇카의 낙상 확인 결과 (폰이 전달) */
    private fun showFallResult(json: String) {
        val r = CarProtocol.parseFallResult(json) ?: return
        DemoState.fall = FallUi(
            if (r.confirmed) FallUi.Phase.CONFIRMED else FallUi.Phase.NOT_CONFIRMED,
            0,
            r.message.ifEmpty { if (r.confirmed) "보호자에게 알렸어요" else "쓰러진 모습이 보이지 않았어요" },
        )
        DemoState.log(if (r.confirmed) "📨 낙상 확인 → 보호자 알림" else "👀 낙상 아님")
        notifyFall(if (r.confirmed) "보호자에게 알렸어요" else "낙상이 아니었어요", r.message, withCancel = false)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (running) streamer.stop()
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        DemoState.log("⏹ 감지 멈춤")
        super.onDestroy()
    }

    // ---------- 알림 ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "로봇카 연결", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_FALL, "낙상 알림", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) },
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoingNotification(text: String): Notification =
        Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("WatchOut")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyFall(title: String, text: String, withCancel: Boolean) {
        val b = Notification.Builder(this, CH_FALL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
        if (withCancel) {
            // 앱 화면이 꺼져 있어도 "괜찮으세요?" 팝업(앱 화면)을 바로 띄운다.
            // 권한이 없는 기기에서는 일반 알림(헤드업)으로 대신 뜬다.
            b.setFullScreenIntent(openAppIntent(), true)
            val cancel = PendingIntent.getService(
                this, 1, Intent(this, WatchOutService::class.java).setAction(ACTION_CANCEL_FALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "괜찮아요", cancel).build())
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFY_FALL, b.build())
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val PREFS = "demo"
        const val KEY_FALL_ON = "fall_on"
        const val KEY_DEV_MODE = "dev_mode"
        private const val HIT_COOLDOWN_MS = 2000L

        private const val ACTION_START = "watchout.START"
        private const val ACTION_STOP = "watchout.STOP"
        private const val ACTION_SEND = "watchout.SEND"
        private const val ACTION_TEST_FALL = "watchout.TEST_FALL"
        private const val ACTION_CANCEL_FALL = "watchout.CANCEL_FALL"
        private const val ACTION_FALL_RESULT = "watchout.FALL_RESULT"
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_JSON = "json"
        private const val CH_ONGOING = "ongoing"
        private const val CH_FALL = "fall"
        private const val NOTIFY_ONGOING = 1
        private const val NOTIFY_FALL = 2

        /** 감지 시작 (폰이 로봇카에 연결했을 때). 앱이 백그라운드면 안드로이드가 막을 수 있다 → 예외 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchOutService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchOutService::class.java))
        }

        private fun request(context: Context, action: String, extra: Pair<String, String>? = null) {
            if (DemoState.connection == DemoState.Connection.OFF) return
            runCatching {
                context.startService(
                    Intent(context, WatchOutService::class.java).setAction(action).apply {
                        extra?.let { putExtra(it.first, it.second) }
                    },
                )
            }
        }

        fun send(context: Context, cmd: CarCommand) = request(context, ACTION_SEND, EXTRA_CMD to cmd.id)
        fun testFall(context: Context) = request(context, ACTION_TEST_FALL)
        fun cancelFall(context: Context) = request(context, ACTION_CANCEL_FALL)
        fun fallResult(context: Context, json: String) = request(context, ACTION_FALL_RESULT, EXTRA_JSON to json)
    }
}
