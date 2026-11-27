package com.watchout.wear.demo

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchout.gesture.CarCommand
import com.watchout.gesture.CustomGesture
import com.watchout.gesture.DtwMatcher
import com.watchout.gesture.GesturePresets
import com.watchout.gesture.SensorSample
import com.watchout.gesture.TapCounter
import com.watchout.gesture.WatchLink
import com.watchout.gesture.WearingHand
import com.watchout.wear.SensorStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 데모 화면용 ViewModel: 내 제스처 목록·삭제, 개발자 모드, 낙상 감지 설정.
 * 로봇카 찾기·연결과 제스처 등록은 폰 앱이 한다 ([PhoneLink], [RemoteRegistrar]).
 * 여기 남은 워치 혼자 하는 제스처 등록은 개발자 모드용이다.
 * 연결된 뒤의 일(센서, 낙상)은 [WatchOutService]가 한다.
 */
class DemoViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CustomGestureStore(app)
    private val prefs = app.getSharedPreferences(WatchOutService.PREFS, Context.MODE_PRIVATE)

    // ---------- 로봇카 (연결은 폰 앱이 한다. 개발자 모드 버튼 명령만 폰을 거쳐 보낸다) ----------

    fun send(cmd: CarCommand) = WatchOutService.send(getApplication(), cmd)

    // ---------- 낙상 ----------

    var fallDetectionOn by mutableStateOf(prefs.getBoolean(WatchOutService.KEY_FALL_ON, true))
        private set

    fun toggleFallDetection() {
        fallDetectionOn = !fallDetectionOn
        prefs.edit { putBoolean(WatchOutService.KEY_FALL_ON, fallDetectionOn) }
    }

    fun testFall() = WatchOutService.testFall(getApplication())
    fun cancelFall() = WatchOutService.cancelFall(getApplication())

    // ---------- 개발자 모드 ----------

    /** 켜져 있으면 메뉴에 개발 도구(낙상 테스트, 실시간 인식, 데이터 모으기 등)가 보인다 */
    var devMode by mutableStateOf(prefs.getBoolean(WatchOutService.KEY_DEV_MODE, false))
        private set
    private val devTaps = TapCounter()

    /**
     * 메뉴 제목을 눌렀을 때. 7번 연달아 누르면 개발자 모드를 켜거나 끈다.
     * @return 화면에 잠깐 보여줄 안내 (없으면 null)
     */
    fun onTitleTap(): String? {
        val left = devTaps.onTap(System.currentTimeMillis())
        return when {
            left == 0 -> {
                changeDevMode(!devMode)
                if (devMode) "개발자 모드를 켰어요" else "개발자 모드를 껐어요"
            }
            left <= 3 -> "${left}번 더 누르면 개발자 모드 ${if (devMode) "끄기" else "켜기"}"
            else -> null
        }
    }

    fun changeDevMode(on: Boolean) {
        devMode = on
        prefs.edit { putBoolean(WatchOutService.KEY_DEV_MODE, on) }
    }

    // ---------- 내 제스처 ----------

    var gestures by mutableStateOf(store.load())
        private set

    fun delete(id: String) {
        store.delete(id)
        reloadGestures()
        PhoneLink.publishGestures(getApplication())
    }

    /** 바꾼 뒤: 다시 읽고, 서비스와 다른 화면(다른 ViewModel)에도 바뀌었다고 알린다 */
    private fun reloadGestures() {
        refreshGestures()
        DemoState.gesturesVersion++
    }

    /**
     * 파일에서 다시 읽기만 한다. 화면이 다시 보일 때 부른다.
     * 파일이 기준이다. 이 ViewModel이 만들어진 뒤에 다른 화면(같은 앱의 다른 창)이
     * 제스처를 등록했을 수 있어서, 처음에 읽은 목록을 그대로 믿지 않는다.
     */
    fun refreshGestures() {
        gestures = store.load()
    }

    /** 등록 진행 상황 */
    sealed interface RegPhase {
        data object Ready : RegPhase
        data class Countdown(val secondsLeft: Int) : RegPhase
        data object Recording : RegPhase
        data class Added(val count: Int) : RegPhase
        data class Failed(val reason: String) : RegPhase
        data object Saved : RegPhase
    }

    var regPhase by mutableStateOf<RegPhase>(RegPhase.Ready)
        private set
    val regShapes = mutableStateListOf<FloatArray>()
    private val streamer = SensorStreamer(app)
    private var buffer: MutableList<SensorSample>? = null
    private var regJob: Job? = null

    fun beginRegistration() {
        DemoState.registering = true
        regShapes.clear()
        regPhase = RegPhase.Ready
        streamer.start { buffer?.add(it) }
    }

    fun endRegistration() {
        regJob?.cancel()
        buffer = null
        streamer.stop()
        DemoState.registering = false
    }

    /** 3초 세고 2.5초 녹화한 뒤, 동작 구간을 찾아 모양으로 저장한다 */
    fun recordSample() {
        if (regJob?.isActive == true) return
        regJob = viewModelScope.launch {
            for (s in 3 downTo 1) {
                regPhase = RegPhase.Countdown(s)
                delay(1000)
            }
            regPhase = RegPhase.Recording
            val samples = mutableListOf<SensorSample>().also { buffer = it }
            delay(RECORD_MS)
            buffer = null
            val segment = withContext(Dispatchers.Default) { DtwMatcher.extractSegment(samples.toList(), hand()) }
            if (segment == null) {
                regPhase = RegPhase.Failed("동작이 안 잡혔어요. 조금 더 크게 해 보세요")
            } else {
                regShapes.add(DtwMatcher.shape(segment))
                regPhase = RegPhase.Added(regShapes.size)
            }
        }
    }

    fun saveGesture(name: String, command: CarCommand) {
        if (regShapes.size < MIN_SAMPLES) return
        val gesture: CustomGesture = DtwMatcher.register("g" + System.currentTimeMillis(), name, command, regShapes.toList())
        store.add(gesture)
        reloadGestures()
        PhoneLink.publishGestures(getApplication())
        regPhase = RegPhase.Saved
    }

    private fun hand(): WearingHand = runCatching {
        WearingHand.valueOf(getApplication<Application>().getSharedPreferences("gesture_tool", Context.MODE_PRIVATE).getString("hand", null) ?: "")
    }.getOrDefault(WearingHand.LEFT)

    override fun onCleared() = endRegistration()

    companion object {
        const val MIN_SAMPLES = WatchLink.MIN_SAMPLES
        const val GOOD_SAMPLES = WatchLink.GOOD_SAMPLES
        private const val RECORD_MS = 2500L

        /** 제스처 이름 후보 → 추천 명령 (폰과 같은 목록) */
        val NAME_PRESETS: List<Pair<String, CarCommand>> = GesturePresets.ALL
    }
}
