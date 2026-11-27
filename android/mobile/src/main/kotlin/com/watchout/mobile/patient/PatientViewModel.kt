package com.watchout.mobile.patient

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.watchout.mobile.alert.AlertError
import com.watchout.mobile.alert.AlertPhoto
import com.watchout.mobile.alert.AlertRepository
import com.watchout.mobile.alert.AlertSource
import com.watchout.mobile.alert.NewAlert
import com.watchout.mobile.guardian.Guardian
import com.watchout.mobile.link.LinkError
import com.watchout.mobile.link.LinkException
import com.watchout.mobile.link.LinkRepository
import com.watchout.mobile.link.LinkedGuardian
import com.watchout.mobile.sms.SmsMessages
import com.watchout.mobile.sms.SmsSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 초대 코드를 만든 결과. 문자를 못 보냈으면 화면에 코드를 크게 보여 줘서 직접 알려 주게 한다. */
data class InviteResult(
    val guardianName: String,
    val code: String,
    val smsSent: Boolean,
)

/**
 * 응급 알림을 보낸 결과.
 * 119는 항상 "시뮬레이션"이라 결과에 따로 담지 않는다 (화면에 "실제로 연락하지 않았어요"를 보여준다).
 *
 * @property alertSaved 보호자 앱 알림 목록에 저장됐나
 * @property smsSent 문자를 보낸 보호자 수
 * @property smsFailed 문자를 못 보낸 보호자 수 (권한 없음 포함)
 */
data class EmergencyResult(
    val alertSaved: Boolean,
    val smsSent: Int,
    val smsFailed: Int,
    val noSmsPermission: Boolean,
)

data class PatientUiState(
    val linkedGuardians: List<LinkedGuardian> = emptyList(),
    /** 지금 초대 코드를 만들고 있는 보호자 연락처 id */
    val invitingId: String? = null,
    val invite: InviteResult? = null,
    val isSendingEmergency: Boolean = false,
    val emergency: EmergencyResult? = null,
    val errorMessage: String? = null,
) {
    /** 이 연락처로 연결된 보호자 계정이 있나 */
    fun isLinked(guardianId: String?): Boolean = linkedGuardians.any { it.guardianId == guardianId }
}

/**
 * 본인(환자) 모드의 "연결 + 응급" 두뇌 (#50).
 * - 보호자 연락처마다 초대 코드를 만들어 문자로 보낸다.
 * - [테스트 응급 알림]: 알림 저장(보호자 앱 목록) + 보호자 번호로 실제 문자 + 119 시뮬레이션.
 *
 * 문자 권한은 화면이 미리 물어보고, 권한이 없으면 문자 없이 진행한다.
 *
 * @param patientName 문자와 보호자 화면에 보일 환자 이름
 * @param testPhoto 테스트 알림에 붙일 사진을 만드는 함수 (안드로이드 그림 기능이 필요해서 밖에서 받는다)
 * @param clock 지금 시각 (테스트에서 바꿔 끼운다)
 */
class PatientViewModel(
    private val uid: String,
    private val patientName: String,
    private val linkRepo: LinkRepository,
    private val alertRepo: AlertRepository,
    private val sms: SmsSender,
    private val testPhoto: () -> AlertPhoto?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(PatientUiState())
    val state: StateFlow<PatientUiState> = _state.asStateFlow()

    init {
        linkRepo.observeLinkedGuardians(uid)
            .onEach { list -> _state.update { it.copy(linkedGuardians = list) } }
            .catch { _state.update { it.copy(errorMessage = LinkError.LOAD_FAILED.message) } }
            .launchIn(viewModelScope)
    }

    /** 보호자 연락처 [guardian] 앞으로 초대 코드를 만들고 그 번호로 문자를 보낸다. */
    fun sendInvite(guardian: Guardian) {
        val guardianId = guardian.id ?: return
        if (_state.value.invitingId != null) return // 한 번에 하나씩
        _state.update { it.copy(invitingId = guardianId, errorMessage = null) }
        viewModelScope.launch {
            try {
                val code = linkRepo.createInvite(uid, patientName, guardianId)
                val sent = trySms(guardian.phone, SmsMessages.invite(patientName, code))
                _state.update {
                    it.copy(invitingId = null, invite = InviteResult(guardian.name, code, smsSent = sent))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val error = (e as? LinkException)?.error ?: LinkError.INVITE_FAILED
                _state.update { it.copy(invitingId = null, errorMessage = error.message) }
            }
        }
    }

    /**
     * [테스트 응급 알림]. 로봇카가 쓰러짐을 확정한 상황을 흉내 낸다.
     * 알림 저장이 실패해도(인터넷 끊김) 문자는 따로 보낸다. 응급 때는 하나라도 가는 게 중요하다.
     */
    fun sendTestEmergency(guardians: List<Guardian>, moving: Boolean) {
        if (_state.value.isSendingEmergency) return
        _state.update { it.copy(isSendingEmergency = true, errorMessage = null) }
        viewModelScope.launch {
            // 사진이 너무 크면 사진 없이 보낸다 (Firestore 문서 크기 제한)
            val photo = testPhoto()?.takeIf { it.jpeg.size <= AlertPhoto.MAX_BYTES }
            val saved = try {
                alertRepo.create(uid, NewAlert(moving = moving, source = AlertSource.TEST, photo = photo))
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                false
            }

            val text = SmsMessages.emergency(patientName, moving, clock(), isTest = true)
            val noPermission = !sms.canSend()
            var sent = 0
            guardians.forEach { if (trySms(it.phone, text)) sent++ }

            _state.update {
                it.copy(
                    isSendingEmergency = false,
                    emergency = EmergencyResult(saved, sent, guardians.size - sent, noPermission),
                    errorMessage = if (saved) null else AlertError.SEND_FAILED.message,
                )
            }
        }
    }

    /** 이 연락처([guardianId])로 연결된 보호자 계정과의 연결을 모두 끊는다. 연락처를 지울 때도 부른다. */
    fun unlink(guardianId: String) {
        val targets = _state.value.linkedGuardians.filter { it.guardianId == guardianId }
        viewModelScope.launch {
            targets.forEach { target ->
                try {
                    linkRepo.removeGuardianLink(uid, target.guardianUid)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    _state.update { it.copy(errorMessage = LinkError.UNLINK_FAILED.message) }
                }
            }
        }
    }

    fun dismissInvite() = _state.update { it.copy(invite = null) }

    fun dismissEmergency() = _state.update { it.copy(emergency = null) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    /** 권한이 없거나 보내다 실패하면 false. 번호·내용은 로그에 남기지 않는다. */
    private fun trySms(phone: String, text: String): Boolean {
        if (!sms.canSend()) return false
        return try {
            sms.send(phone, text)
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun factory(
            uid: String,
            patientName: String,
            linkRepo: LinkRepository,
            alertRepo: AlertRepository,
            sms: SmsSender,
            testPhoto: () -> AlertPhoto?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PatientViewModel(uid, patientName, linkRepo, alertRepo, sms, testPhoto) }
        }
    }
}
