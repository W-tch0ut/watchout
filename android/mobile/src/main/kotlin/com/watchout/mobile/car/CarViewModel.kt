package com.watchout.mobile.car

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.watchout.gesture.CarInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 로봇카 연결을 켜고 끄는 "약속". 실제는 [ServiceCarSession], 테스트는 가짜 */
interface CarSession {
    val link: StateFlow<CarLinkState>
    val events: StateFlow<List<String>>
    fun connect(car: CarInfo)
    fun disconnect()
}

/** [CarConnectionService]를 켜고 끄고, 상태는 [CarHub]에서 본다 */
class ServiceCarSession(context: Context) : CarSession {
    private val app = context.applicationContext
    override val link: StateFlow<CarLinkState> = CarHub.link
    override val events: StateFlow<List<String>> = CarHub.events
    override fun connect(car: CarInfo) = CarConnectionService.connect(app, car)
    override fun disconnect() = CarConnectionService.disconnect(app)
}

/** 로봇카 화면이 그릴 내용 */
data class CarUiState(
    val scanning: Boolean = false,
    val cars: List<CarInfo> = emptyList(),
    /** 찾기 결과 안내 ("2대 찾았어요" 등). 아직 안 찾았으면 null */
    val scanMessage: String? = null,
    /** 폰 ↔ 로봇카 연결 */
    val link: CarLinkState = CarLinkState(),
    /** 블루투스로 연결된 워치가 있나. 확인 전이면 null */
    val watchConnected: Boolean? = null,
    /** 최근 일 (워치 제스처 → 명령, 낙상 등) */
    val events: List<String> = emptyList(),
)

/**
 * 로봇카 화면의 "두뇌".
 * 폰이 Wi-Fi로 로봇카를 찾아 직접 연결한다. 워치는 폰을 통해 명령을 보내고, 연결 상태만 본다.
 */
class CarViewModel(
    private val finder: CarFinder,
    private val session: CarSession,
    private val watch: WatchRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CarUiState())
    val state: StateFlow<CarUiState> = _state.asStateFlow()

    init {
        session.link.onEach { l -> _state.update { it.copy(link = l) } }.launchIn(viewModelScope)
        session.events.onEach { e -> _state.update { it.copy(events = e) } }.launchIn(viewModelScope)
        checkWatch()
    }

    fun checkWatch() {
        viewModelScope.launch {
            val connected = watch.isWatchConnected()
            _state.update { it.copy(watchConnected = connected) }
        }
    }

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, scanMessage = "로봇카를 찾는 중… (4초)") }
        viewModelScope.launch {
            val found = try {
                finder.scan()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null
            }
            _state.update {
                it.copy(
                    scanning = false,
                    cars = found.orEmpty(),
                    scanMessage = when {
                        found == null -> "찾지 못했어요. Wi-Fi에 연결돼 있나요?"
                        found.isEmpty() -> "찾지 못했어요. 로봇카(데모 PC)가 켜져 있고 같은 Wi-Fi인가요?"
                        else -> "${found.size}대 찾았어요. 눌러서 연결해요"
                    },
                )
            }
        }
    }

    /** 폰이 이 로봇카에 연결한다 */
    fun connect(car: CarInfo) = session.connect(car)

    fun disconnect() = session.disconnect()

    companion object {
        fun factory(finder: CarFinder, session: CarSession, watch: WatchRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { CarViewModel(finder, session, watch) } }
    }
}
