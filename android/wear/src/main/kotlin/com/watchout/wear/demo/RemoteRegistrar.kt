package com.watchout.wear.demo

import android.content.Context
import com.watchout.gesture.CarCommand
import com.watchout.gesture.DtwMatcher
import com.watchout.gesture.RegAction
import com.watchout.gesture.RegPhase
import com.watchout.gesture.RegRequest
import com.watchout.gesture.RegState
import com.watchout.gesture.SensorSample
import com.watchout.gesture.WatchLink
import com.watchout.gesture.WearingHand
import com.watchout.wear.SensorStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 폰에서 시작한 제스처 등록을 워치에서 진행한다.
 *
 * 폰: 이름·명령 고르기 → [RegAction.START] → '시연'을 누를 때마다 [RegAction.RECORD] → [RegAction.SAVE]
 * 워치: 등록 화면을 띄우고(앱이 꺼져 있으면 알림으로), 시연마다 3초 세고(진동) 2.5초 녹화 →
 *       동작 모양을 모아 저장. 단계가 바뀔 때마다 폰에 알린다([WatchLink.MSG_REG_STATE]).
 *
 * 손짓하는 손으로 워치를 누르지 않아도 되도록 버튼은 폰에 있다.
 * 동작 녹화·모양 저장은 센서가 있는 워치에서만 한다 (센서 원본은 폰으로 보내지 않는다).
 */
object RemoteRegistrar {
    private const val NOTIFY_REG = 4
    private const val RECORD_MS = 2500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var streamer: SensorStreamer? = null
    private var buffer: MutableList<SensorSample>? = null
    private val shapes = mutableListOf<FloatArray>()
    private var name = ""
    private var command = CarCommand.APPROACH
    private var job: Job? = null

    fun handle(context: Context, req: RegRequest) {
        val app = context.applicationContext
        scope.launch {
            when (req.action) {
                RegAction.START -> start(app, req.name ?: return@launch, req.command ?: return@launch)
                RegAction.RECORD -> record(app)
                RegAction.SAVE -> save(app)
                RegAction.CANCEL -> finish(app, RegState(RegPhase.CANCELLED, name, shapes.size, message = "취소했어요"))
                RegAction.DELETE -> {
                    CustomGestureStore(app).delete(req.id ?: return@launch)
                    DemoState.gesturesVersion++
                    DemoState.log("🗑 제스처 삭제 (폰)")
                    PhoneLink.publishGestures(app)
                }
            }
        }
    }

    private fun start(app: Context, name: String, command: CarCommand) {
        stopSensors()
        this.name = name
        this.command = command
        shapes.clear()
        DemoState.registering = true // 등록하는 동안 백그라운드 제스처 인식은 쉰다
        streamer = SensorStreamer(app).also { it.start { s -> buffer?.add(s) } }
        report(app, RegState(RegPhase.READY, name, 0, message = "폰에서 '시연'을 누르세요"))
        Haptics.pulse(app, longArrayOf(0, 120))
        // 앱이 꺼져 있으면 등록 화면을 띄운다 (화면이 꺼져 있어도 켜지게)
        PhoneLink.notify(app, NOTIFY_REG, "제스처 등록: $name", "워치를 보세요. 폰에서 '시연'을 누르면 시작해요", fullScreen = true)
    }

    private fun record(app: Context) {
        if (streamer == null || job?.isActive == true) return
        job = scope.launch {
            for (s in 3 downTo 1) {
                report(app, RegState(RegPhase.COUNTDOWN, name, shapes.size, secondsLeft = s))
                Haptics.pulse(app, longArrayOf(0, 40))
                delay(1000)
            }
            report(app, RegState(RegPhase.RECORDING, name, shapes.size, message = "지금!"))
            Haptics.pulse(app, longArrayOf(0, 250)) // "지금!" — 손목으로 느낄 수 있게 길게
            val samples = mutableListOf<SensorSample>().also { buffer = it }
            delay(RECORD_MS)
            buffer = null
            val segment = withContext(Dispatchers.Default) { DtwMatcher.extractSegment(samples.toList(), hand(app)) }
            if (segment == null) {
                report(app, RegState(RegPhase.FAILED, name, shapes.size, message = "동작이 안 잡혔어요. 조금 더 크게 해 보세요"))
                Haptics.pulse(app, longArrayOf(0, 60, 80, 60))
            } else {
                shapes += DtwMatcher.shape(segment)
                report(app, RegState(RegPhase.ADDED, name, shapes.size, message = "${shapes.size}번째 저장 ✓"))
            }
        }
    }

    private fun save(app: Context) {
        if (shapes.size < WatchLink.MIN_SAMPLES || job?.isActive == true) return
        val gesture = DtwMatcher.register("g" + System.currentTimeMillis(), name, command, shapes.toList())
        CustomGestureStore(app).add(gesture)
        DemoState.gesturesVersion++
        DemoState.log("✋ '$name' 등록 (폰)")
        PhoneLink.publishGestures(app)
        finish(app, RegState(RegPhase.SAVED, name, shapes.size, message = "등록했어요!"))
    }

    private fun finish(app: Context, last: RegState) {
        job?.cancel()
        stopSensors()
        report(app, last)
        PhoneLink.cancelNotification(app, NOTIFY_REG)
        // 결과를 잠깐 보여주고 화면에서 내린다
        scope.launch {
            delay(2500)
            if (DemoState.remoteReg == last) DemoState.remoteReg = null
        }
    }

    private fun stopSensors() {
        buffer = null
        streamer?.stop()
        streamer = null
        DemoState.registering = false
    }

    private fun report(app: Context, state: RegState) {
        DemoState.remoteReg = state
        PhoneLink.sendToPhone(app, WatchLink.MSG_REG_STATE, WatchLink.regState(state))
    }

    private fun hand(app: Context): WearingHand = runCatching {
        WearingHand.valueOf(app.getSharedPreferences("gesture_tool", Context.MODE_PRIVATE).getString("hand", null) ?: "")
    }.getOrDefault(WearingHand.LEFT)
}
