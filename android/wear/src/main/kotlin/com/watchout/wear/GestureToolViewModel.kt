package com.watchout.wear

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchout.gesture.Gesture
import com.watchout.gesture.GestureRecognizer
import com.watchout.gesture.GestureRecording
import com.watchout.gesture.GestureResult
import com.watchout.gesture.MotionSegmenter
import com.watchout.gesture.RuleBasedGestureRecognizer
import com.watchout.gesture.SensorSample
import com.watchout.gesture.WearingHand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/** 녹화 화면 진행 단계 */
sealed interface RecordPhase {
    data object Ready : RecordPhase
    data class Countdown(val secondsLeft: Int) : RecordPhase
    data object Recording : RecordPhase
    data class Saved(val count: Int) : RecordPhase
    data class Failed(val reason: String) : RecordPhase
}

/**
 * 제스처 툴의 상태와 동작을 모아 둔 곳.
 *
 * - 인식 모드: 센서 → [GestureRecognizer] → 결과 표시 + 진동
 * - 수집 모드: 3초 카운트다운 → 2.5초 녹화 → CSV 저장
 *
 * 화면(Compose)은 여기 있는 상태를 그리기만 한다. 다음 단계에서 MQTT 전송을 붙일 때도
 * [onGestureRecognized] 한 곳에만 추가하면 된다.
 */
class GestureToolViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("gesture_tool", Context.MODE_PRIVATE)
    private val streamer = SensorStreamer(app)
    private val store = RecordingStore(app)
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }

    val sensorsAvailable: Boolean = streamer.isAvailable
    val storagePath: String = store.directory.absolutePath

    var hand by mutableStateOf(
        runCatching { WearingHand.valueOf(prefs.getString(KEY_HAND, null) ?: "") }.getOrDefault(WearingHand.LEFT),
    )
        private set

    // 인식 모드 상태
    private val recognizer = RuleBasedGestureRecognizer(hand)
    var lastResult by mutableStateOf<GestureResult?>(null)
        private set
    val history = mutableStateListOf<GestureResult>()

    /** 지금 센서 값 (화면에 0.1초마다 갱신). 반응이 없을 때 센서가 들어오는지 확인하는 용도 */
    var live by mutableStateOf(LiveMotion())
        private set
    private var lastLiveUpdateMs = 0L
    private var sampleCount = 0

    // 수집 모드 상태
    var recordPhase by mutableStateOf<RecordPhase>(RecordPhase.Ready)
        private set
    var counts by mutableStateOf(store.counts())
        private set
    private var recordJob: Job? = null
    private var buffer: MutableList<SensorSample>? = null

    fun toggleHand() {
        hand = if (hand == WearingHand.LEFT) WearingHand.RIGHT else WearingHand.LEFT
        recognizer.hand = hand
        recognizer.reset()
        prefs.edit { putString(KEY_HAND, hand.name) }
    }

    // ---------- 인식 모드 ----------

    fun startRecognizing() {
        recognizer.reset()
        sampleCount = 0
        live = LiveMotion()
        streamer.start { sample ->
            updateLive(sample)
            recognizer.onSample(sample)?.let(::onGestureRecognized)
        }
    }

    private fun updateLive(s: SensorSample) {
        sampleCount++
        if (s.timestampMs - lastLiveUpdateMs < LIVE_INTERVAL_MS) return
        lastLiveUpdateMs = s.timestampMs
        live = LiveMotion(
            gyro = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz),
            accelOffGravity = abs(sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az) - MotionSegmenter.GRAVITY),
            samples = sampleCount,
        )
    }

    fun stopRecognizing() = streamer.stop()

    private fun onGestureRecognized(result: GestureResult) {
        lastResult = result
        history.add(0, result)
        if (history.size > HISTORY_SIZE) history.removeAt(history.lastIndex)
        // 제스처로 인식하면 또렷하게, 일상 동작이면 아주 짧게 진동
        vibrate(if (result.gesture == Gesture.IDLE) 20 else 80)
        // TODO(#42): 여기서 MQTT로 로봇카에 명령을 보낸다.
    }

    // ---------- 수집 모드 ----------

    /** 녹화 화면에 들어오면 센서를 켜 둔다. 녹화할 때만 값을 모은다. */
    fun enterRecordScreen() {
        recordPhase = RecordPhase.Ready
        streamer.start { sample -> buffer?.add(sample) }
    }

    fun leaveRecordScreen() {
        recordJob?.cancel()
        buffer = null
        streamer.stop()
    }

    fun record(label: Gesture) {
        if (recordJob?.isActive == true) return
        recordJob = viewModelScope.launch {
            for (s in COUNTDOWN_SECONDS downTo 1) {
                recordPhase = RecordPhase.Countdown(s)
                vibrate(30)
                delay(1000)
            }
            recordPhase = RecordPhase.Recording
            vibrate(150)
            val startedAt = System.currentTimeMillis()
            val samples = mutableListOf<SensorSample>().also { buffer = it }
            delay(RECORD_MS)
            buffer = null
            vibrate(60)

            if (samples.size < MIN_SAMPLES) {
                recordPhase = RecordPhase.Failed("센서 값이 너무 적어요 (${samples.size}개)")
                return@launch
            }
            val recording = GestureRecording(label, hand, startedAt, samples.toList())
            counts = withContext(Dispatchers.IO) {
                store.save(recording)
                store.counts()
            }
            recordPhase = RecordPhase.Saved(counts[label] ?: 0)
        }
    }

    fun deleteLatest(label: Gesture) {
        viewModelScope.launch {
            counts = withContext(Dispatchers.IO) {
                store.deleteLatest(label)
                store.counts()
            }
            recordPhase = RecordPhase.Ready
        }
    }

    // ---------- 삼성 제스처 수집 (연속 기록) ----------

    private val recorder = ContinuousRecorder(app)
    val ppgSensorName: String? = recorder.ppgSensor?.name
    val sessionPath: String = recorder.directory.absolutePath

    var session by mutableStateOf<SessionState?>(null)
        private set
    private var sessionJob: Job? = null

    /** @param withPpg 신체 센서 권한을 받았으면 true */
    fun startSession(withPpg: Boolean) {
        if (session != null) return
        val file = recorder.start(hand, withPpg && ppgSensorName != null) ?: return
        val startedAt = System.currentTimeMillis()
        val ppgOn = withPpg && ppgSensorName != null
        session = SessionState(file.name, 0, 0, 0, 0, ppgOn)
        sessionJob = viewModelScope.launch {
            while (true) {
                // 1초마다 갱신. 화면 글자가 바뀔 때마다 유니버설 제스처(접근성 서비스)도 화면을 다시 읽어서
                // 너무 자주 바꾸면 워치 전체가 느려진다.
                delay(1000)
                session = SessionState(
                    fileName = file.name,
                    elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt(),
                    acc = recorder.counts.get(0),
                    gyro = recorder.counts.get(1),
                    ppg = recorder.counts.get(2),
                    ppgOn = ppgOn,
                )
            }
        }
    }

    fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        recorder.stop()
        session = null
    }

    override fun onCleared() {
        streamer.stop()
        stopSession()
    }

    private fun vibrate(ms: Long) {
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val KEY_HAND = "hand"
        private const val HISTORY_SIZE = 5
        private const val COUNTDOWN_SECONDS = 3
        private const val RECORD_MS = 2500L
        private const val MIN_SAMPLES = 50 // 2.5초 × 50Hz = 125개가 정상
        private const val LIVE_INTERVAL_MS = 100L
    }
}

/**
 * 화면에 보여줄 지금 움직임.
 * @property gyro 회전 속도 크기 (rad/s). 1.5를 넘으면 동작 시작으로 본다.
 * @property accelOffGravity 가속도 크기가 중력(9.81)에서 벗어난 정도 (m/s²)
 * @property samples 인식 시작 후 받은 센서 값 개수. 늘지 않으면 센서가 안 들어오는 것이다.
 */
data class LiveMotion(val gyro: Float = 0f, val accelOffGravity: Float = 0f, val samples: Int = 0)

/** 연속 기록 진행 상황. 개수가 계속 늘어야 정상이다. */
data class SessionState(
    val fileName: String,
    val elapsedSec: Int,
    val acc: Int,
    val gyro: Int,
    val ppg: Int,
    val ppgOn: Boolean,
)
