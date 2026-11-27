package com.watchout.mobile.sms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime

/** 문자 설정 화면이 그릴 내용 */
data class SmsUiState(
    val settings: SmsSettings = SmsSettings(),
    /** 저장된 값과 다른가 (저장 버튼 켜기) */
    val dirty: Boolean = false,
    val saved: Boolean = false,
    val preview: String = "",
)

/**
 * 문자 설정 화면의 "두뇌": 이름·주소·참고를 고치고, 보낼 문자를 미리 보여주고, 저장한다.
 * @param now 미리보기 시각 (테스트에서 고정하려고 바꿔 끼운다)
 */
class SmsViewModel(
    private val store: SmsSettingsStore,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    private var savedSettings = store.load()
    private val _state = MutableStateFlow(stateFor(savedSettings, saved = false))
    val state: StateFlow<SmsUiState> = _state.asStateFlow()

    fun onName(v: String) = edit { it.copy(name = v.take(SmsTemplate.MAX_NAME)) }

    fun onAddress(v: String) = edit { it.copy(address = v.take(SmsTemplate.MAX_ADDRESS)) }

    fun onNote(v: String) = edit { it.copy(note = v.take(SmsTemplate.MAX_NOTE)) }

    /** 앞뒤 공백을 정리해서 저장한다 */
    fun save() {
        val s = _state.value.settings
        val clean = SmsSettings(s.name.trim(), s.address.trim(), s.note.trim())
        store.save(clean)
        savedSettings = clean
        _state.value = stateFor(clean, saved = true)
    }

    private fun edit(change: (SmsSettings) -> SmsSettings) = _state.update {
        val next = change(it.settings)
        it.copy(settings = next, dirty = next != savedSettings, saved = false, preview = SmsTemplate.preview(next, now()))
    }

    private fun stateFor(s: SmsSettings, saved: Boolean) =
        SmsUiState(settings = s, dirty = false, saved = saved, preview = SmsTemplate.preview(s, now()))

    companion object {
        fun factory(store: SmsSettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { SmsViewModel(store) }
        }
    }
}
