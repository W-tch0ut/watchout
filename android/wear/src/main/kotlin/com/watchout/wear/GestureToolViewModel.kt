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
import com.watchout.gesture.RuleBasedGestureRecognizer
import com.watchout.gesture.SensorSample
import com.watchout.gesture.WearingHand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        streamer.start { sample ->
            recognizer.onSample(sample)?.let(::onGestureRecognized)
        }
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

    override fun onCleared() {
        streamer.stop()
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
    }
}
