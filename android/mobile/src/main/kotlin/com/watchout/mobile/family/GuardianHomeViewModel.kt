package com.watchout.mobile.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.watchout.mobile.alert.AlertError
import com.watchout.mobile.alert.AlertRepository
import com.watchout.mobile.alert.EmergencyAlert
import com.watchout.mobile.link.InviteCode
import com.watchout.mobile.link.LinkError
import com.watchout.mobile.link.LinkException
import com.watchout.mobile.link.LinkRepository
import com.watchout.mobile.link.LinkedPatient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 보호자 화면의 알림 한 줄: 어느 가족의 알림인지 이름을 붙인다 */
data class FamilyAlert(
    val patientName: String,
    val alert: EmergencyAlert,
)

/** 한 환자의 알림 묶음. [readable]이 false면 연결이 끊겨 읽을 수 없는 상태 */
private data class PatientAlerts(
    val patient: LinkedPatient,
    val alerts: List<EmergencyAlert>,
    val readable: Boolean,
)

data class GuardianHomeUiState(
    val isLoading: Boolean = true,
    val patients: List<LinkedPatient> = emptyList(),
    /** 환자가 연결을 끊어서 알림을 읽을 수 없는 환자 uid */
    val disconnected: Set<String> = emptySet(),
    /** 모든 가족의 알림, 새것부터 */
    val alerts: List<FamilyAlert> = emptyList(),
    /** [확인했어요]를 누르고 서버 응답을 기다리는 알림 id */
    val acking: Set<String> = emptySet(),
    // ── 코드 입력 창 ──
    val showCodeDialog: Boolean = false,
    val codeInput: String = "",
    val codeError: LinkError? = null,
    val isLinking: Boolean = false,
    // ── 안내 ──
    val message: String? = null,
    val errorMessage: String? = null,
) {
    /** 아직 아무도 확인하지 않은 알림 수 (화면 위 빨간 숫자) */
    val unacknowledgedCount: Int get() = alerts.count { !it.alert.isAcknowledged }
}

/**
 * 보호자 모드 첫 화면의 "두뇌" (#50).
 * 1) 연결된 가족 목록을 실시간으로 받고, 2) 가족마다 알림 목록을 구독해서 하나로 합친다.
 * 3) 초대 코드를 입력해 새 가족과 연결하고, 4) 알림에 [확인했어요]를 표시한다.
 *
 * @param uid 로그인한 보호자 uid
 * @param guardianName 환자 쪽과 "확인한 사람"에 보일 내 이름
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardianHomeViewModel(
    private val uid: String,
    private val guardianName: String,
    private val linkRepo: LinkRepository,
    private val alertRepo: AlertRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GuardianHomeUiState())
    val state: StateFlow<GuardianHomeUiState> = _state.asStateFlow()

    init {
        linkRepo.observePatients(uid)
            .catch { _state.update { it.copy(isLoading = false, errorMessage = LinkError.LOAD_FAILED.message) } }
            // 가족 목록이 바뀔 때마다(flatMapLatest) 예전 구독을 끊고 새 가족들의 알림을 다시 구독한다
            .flatMapLatest { patients -> observeAll(patients) }
            .onEach { groups ->
                _state.update { s ->
                    s.copy(
                        isLoading = false,
                        patients = groups.map { it.patient },
                        disconnected = groups.filterNot { it.readable }.map { it.patient.uid }.toSet(),
                        alerts = groups
                            .flatMap { g -> g.alerts.map { FamilyAlert(g.patient.name, it) } }
                            // 시각을 아직 모르는(방금 만든) 알림을 맨 위로
                            .sortedByDescending { it.alert.createdAtMillis ?: Long.MAX_VALUE },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /** 가족 여러 명의 알림 Flow를 하나로 합친다(combine). 한 명이 실패해도 나머지는 보인다. */
    private fun observeAll(patients: List<LinkedPatient>): Flow<List<PatientAlerts>> {
        if (patients.isEmpty()) return flowOf(emptyList())
        val flows = patients.map { p ->
            alertRepo.observe(p.uid)
                .map { PatientAlerts(p, it, readable = true) }
                // 환자가 연결을 끊으면 보안 규칙이 읽기를 막는다 → "연결이 끊겼어요"로 표시
                .catch { emit(PatientAlerts(p, emptyList(), readable = false)) }
        }
        return combine(flows) { it.toList() }
    }

    // ── 초대 코드 입력 ──

    fun openCodeDialog() = _state.update { it.copy(showCodeDialog = true, codeInput = "", codeError = null) }

    fun closeCodeDialog() = _state.update { if (it.isLinking) it else it.copy(showCodeDialog = false) }

    /** 숫자만, 최대 6자리까지 받는다 */
    fun onCodeChange(v: String) =
        _state.update { it.copy(codeInput = v.filter(Char::isDigit).take(InviteCode.LENGTH), codeError = null) }

    fun submitCode() {
        val s = _state.value
        if (s.isLinking) return
        val code = InviteCode.normalize(s.codeInput)
        if (code == null) {
            _state.update { it.copy(codeError = LinkError.INVALID_CODE) }
            return
        }
        _state.update { it.copy(isLinking = true, codeError = null) }
        viewModelScope.launch {
            try {
                val patient = linkRepo.acceptInvite(code, uid, guardianName)
                _state.update {
                    it.copy(isLinking = false, showCodeDialog = false, message = "${patient.name}님과 연결됐어요.")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val error = (e as? LinkException)?.error ?: LinkError.LINK_FAILED
                _state.update { it.copy(isLinking = false, codeError = error) }
            }
        }
    }

    // ── 알림 확인 · 연결 끊기 ──

    fun acknowledge(item: FamilyAlert) {
        val alert = item.alert
        if (alert.isAcknowledged || alert.id in _state.value.acking) return
        _state.update { it.copy(acking = it.acking + alert.id) }
        viewModelScope.launch {
            try {
                alertRepo.acknowledge(alert.patientUid, alert.id, uid, guardianName)
                _state.update { it.copy(acking = it.acking - alert.id) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.update { it.copy(acking = it.acking - alert.id, errorMessage = AlertError.ACK_FAILED.message) }
            }
        }
    }

    fun leave(patient: LinkedPatient) {
        viewModelScope.launch {
            try {
                linkRepo.leavePatient(uid, patient.uid)
                _state.update { it.copy(message = "${patient.name}님과 연결을 끊었어요.") }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = LinkError.UNLINK_FAILED.message) }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null, errorMessage = null) }

    companion object {
        fun factory(
            uid: String,
            guardianName: String,
            linkRepo: LinkRepository,
            alertRepo: AlertRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GuardianHomeViewModel(uid, guardianName, linkRepo, alertRepo) }
        }
    }
}
