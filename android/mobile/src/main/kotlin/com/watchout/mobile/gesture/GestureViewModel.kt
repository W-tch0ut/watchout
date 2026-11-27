package com.watchout.mobile.gesture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.watchout.gesture.CarCommand
import com.watchout.gesture.GestureSummary
import com.watchout.gesture.RegAction
import com.watchout.gesture.RegPhase
import com.watchout.gesture.RegState
import com.watchout.gesture.WatchLink
import com.watchout.mobile.car.WatchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 폰에서 진행 중인 제스처 등록 */
data class Registration(
    val name: String,
    val command: CarCommand,
    /** 워치가 알려준 진행 상황. 아직 워치가 대답하지 않았으면 null */
    val watch: RegState? = null,
)

/** 제스처 화면이 그릴 내용 */
data class GestureUiState(
    /** 워치에 등록된 제스처. 아직 못 받았으면 null */
    val gestures: List<GestureSummary>? = null,
    /** 진행 중인 등록 (null이면 등록 창이 닫혀 있다) */
    val registration: Registration? = null,
    val message: String? = null,
)

/**
 * 폰의 "내 제스처" 화면 두뇌.
 *
 * 등록 흐름: 폰에서 이름·명령 고르기 → [start] → 워치에 등록 화면이 뜬다("워치를 확인하세요!")
 * → 폰의 '시연'([record])을 누를 때마다 워치가 3초 세고 동작을 녹화 → 3번 이상이면 [save].
 * 동작 녹화·모양 저장은 센서가 있는 워치가 하고, 폰은 버튼과 진행 상황만 맡는다.
 */
class GestureViewModel(private val watch: WatchRepository) : ViewModel() {

    private val _state = MutableStateFlow(GestureUiState())
    val state: StateFlow<GestureUiState> = _state.asStateFlow()

    init {
        watch.gestures.onEach { g -> _state.update { it.copy(gestures = g) } }.launchIn(viewModelScope)
        watch.regState.onEach { r ->
            _state.update { s -> s.copy(registration = s.registration?.copy(watch = r)) }
            if (r?.phase == RegPhase.SAVED) refresh()
        }.launchIn(viewModelScope)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { watch.refreshGestures() }
    }

    /** 등록 시작: 워치에 등록 화면을 띄운다 */
    fun start(name: String, command: CarCommand) {
        watch.clearRegState()
        _state.update { it.copy(registration = Registration(name, command), message = null) }
        send(WatchLink.regStart(name, command))
    }

    /** 시연 한 번 (워치가 3초 세고 녹화) */
    fun record() {
        val reg = _state.value.registration ?: return
        if (reg.watch?.busy == true) return // 녹화 중에는 또 누르지 않게
        send(WatchLink.regAction(RegAction.RECORD))
    }

    fun save() {
        val reg = _state.value.registration ?: return
        if (reg.watch?.canSave != true) return
        send(WatchLink.regAction(RegAction.SAVE))
    }

    /** 등록 창 닫기. 저장 전이면 워치에도 취소를 알린다 */
    fun close() {
        val reg = _state.value.registration ?: return
        if (reg.watch?.phase != RegPhase.SAVED) send(WatchLink.regAction(RegAction.CANCEL))
        _state.update { it.copy(registration = null) }
    }

    fun delete(id: String) = send(WatchLink.regDelete(id))

    private fun send(text: String) {
        viewModelScope.launch {
            val ok = watch.sendMessage(WatchLink.MSG_REG, text)
            if (!ok) _state.update { it.copy(message = "워치가 연결돼 있지 않아요. Galaxy Wearable에서 워치를 연결해 주세요") }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(watch: WatchRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { GestureViewModel(watch) }
        }
    }
}
