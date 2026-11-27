package com.watchout.mobile.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 모드 화면이 그릴 내용.
 *
 * @property role 저장된 역할. null이면 아직 안 고름
 * @property isLoading 서버에서 역할을 처음 받아오는 중
 * @property isChoosing 사용자가 "모드 바꾸기"를 눌러 선택 화면을 보고 있음
 */
data class RoleUiState(
    val role: UserRole? = null,
    val isLoading: Boolean = true,
    val isChoosing: Boolean = false,
    val isSaving: Boolean = false,
    val error: ProfileError? = null,
) {
    /** 선택 화면을 보여줘야 하나: 아직 안 골랐거나, 바꾸는 중 */
    val showSelect: Boolean get() = !isLoading && (role == null || isChoosing)

    /** 선택 화면에 "취소"를 보여줄까: 이미 고른 모드가 있을 때만 돌아갈 곳이 있다 */
    val canCancel: Boolean get() = role != null
}

/**
 * 로그인한 사용자([uid]) 한 명의 모드(본인/보호자)를 정하는 "두뇌".
 * 저장된 역할을 실시간으로 받아서, MainActivity가 어떤 화면을 그릴지 정하게 한다.
 */
class RoleViewModel(
    private val uid: String,
    private val repo: ProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RoleUiState())
    val state: StateFlow<RoleUiState> = _state.asStateFlow()

    init {
        repo.observeRole(uid)
            .onEach { role -> _state.update { it.copy(role = role, isLoading = false) } }
            .catch { _state.update { it.copy(isLoading = false, error = ProfileError.LOAD_FAILED) } }
            .launchIn(viewModelScope)
    }

    /** 모드를 고른다. 저장에 성공하면 실시간 구독으로 role이 바뀌어 화면이 넘어간다. */
    fun choose(role: UserRole) {
        if (_state.value.isSaving) return // 여러 번 눌러도 한 번만 저장
        if (role == _state.value.role) { // 같은 모드를 다시 고르면 저장할 필요 없음
            _state.update { it.copy(isChoosing = false) }
            return
        }
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                repo.setRole(uid, role)
                // 서버 응답을 기다리지 않고 바로 화면을 바꾼다 (구독으로도 같은 값이 곧 들어온다)
                _state.update { it.copy(role = role, isSaving = false, isChoosing = false) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = ProfileError.SAVE_FAILED) }
            }
        }
    }

    /** "모드 바꾸기": 저장된 역할은 그대로 두고 선택 화면만 연다 */
    fun startChange() = _state.update { it.copy(isChoosing = true, error = null) }

    /** 선택 화면에서 취소 → 원래 모드 화면으로 */
    fun cancelChange() = _state.update { it.copy(isChoosing = false, error = null) }

    companion object {
        fun factory(uid: String, repo: ProfileRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { RoleViewModel(uid, repo) }
        }
    }
}
