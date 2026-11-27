package com.watchout.mobile.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 추가·수정 창에 입력 중인 값. null이면 창이 닫힌 상태. */
data class GuardianEditor(
    val id: String? = null,
    val name: String = "",
    val relation: String = "",
    val phone: String = "",
    val error: GuardianError? = null,
    val isSaving: Boolean = false,
) {
    val isNew: Boolean get() = id == null
}

/**
 * 보호자 연락처 화면의 "두뇌". 로그인한 사용자([uid]) 한 명의 연락처만 다룬다.
 */
class GuardianViewModel(
    private val uid: String,
    private val repo: GuardianRepository,
) : ViewModel() {

    private val _listError = MutableStateFlow<GuardianError?>(null)

    /** 목록 불러오기·삭제 실패 안내 */
    val listError: StateFlow<GuardianError?> = _listError.asStateFlow()

    val guardians: StateFlow<List<Guardian>> = repo.observe(uid)
        .catch {
            _listError.value = GuardianError.LOAD_FAILED
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _editor = MutableStateFlow<GuardianEditor?>(null)
    val editor: StateFlow<GuardianEditor?> = _editor.asStateFlow()

    fun startAdd() {
        if (guardians.value.size >= GuardianValidator.MAX_GUARDIANS) {
            _listError.value = GuardianError.TOO_MANY
            return
        }
        _editor.value = GuardianEditor()
    }

    fun startEdit(g: Guardian) {
        _editor.value = GuardianEditor(
            id = g.id,
            name = g.name,
            relation = g.relation,
            phone = GuardianValidator.formatPhone(g.phone),
        )
    }

    fun onNameChange(v: String) = _editor.update { it?.copy(name = v, error = null) }

    fun onRelationChange(v: String) = _editor.update { it?.copy(relation = v, error = null) }

    fun onPhoneChange(v: String) = _editor.update { it?.copy(phone = v, error = null) }

    fun cancelEdit() {
        _editor.value = null
    }

    fun dismissListError() {
        _listError.value = null
    }

    /** 입력값 검사 → 통과하면 저장 → 성공하면 창 닫기 */
    fun save() {
        val e = _editor.value ?: return
        if (e.isSaving) return
        val input = Guardian(id = e.id, name = e.name, relation = e.relation, phone = e.phone)
        when (val v = GuardianValidator.validate(input, guardians.value.size)) {
            is GuardianValidation.Invalid -> _editor.update { it?.copy(error = v.error) }
            is GuardianValidation.Valid -> {
                _editor.update { it?.copy(isSaving = true, error = null) }
                viewModelScope.launch {
                    try {
                        repo.save(uid, v.guardian)
                        _editor.value = null
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        _editor.update { it?.copy(isSaving = false, error = GuardianError.SAVE_FAILED) }
                    }
                }
            }
        }
    }

    fun delete(guardianId: String) {
        viewModelScope.launch {
            try {
                repo.delete(uid, guardianId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _listError.value = GuardianError.DELETE_FAILED
            }
        }
    }

    companion object {
        fun factory(uid: String, repo: GuardianRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { GuardianViewModel(uid, repo) }
        }
    }
}
