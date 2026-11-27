package com.watchout.mobile.fakes

import com.watchout.mobile.alert.AlertRepository
import com.watchout.mobile.alert.EmergencyAlert
import com.watchout.mobile.alert.NewAlert
import com.watchout.mobile.link.LinkError
import com.watchout.mobile.link.LinkException
import com.watchout.mobile.link.LinkRepository
import com.watchout.mobile.link.LinkedGuardian
import com.watchout.mobile.link.LinkedPatient
import com.watchout.mobile.sms.SmsSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * #50 테스트용 가짜 부품들. Firestore·문자 대신 메모리에만 기록한다.
 * 진짜 전화번호 대신 가짜 번호(010-0000-000x)만 쓴다.
 */

/** 보낸 문자를 기억만 하는 가짜 문자 발송기 */
class FakeSmsSender(var permitted: Boolean = true) : SmsSender {
    val sent = mutableListOf<Pair<String, String>>()

    /** 이 번호로 보내면 실패한다 */
    val failingPhones = mutableSetOf<String>()

    override fun canSend(): Boolean = permitted

    override fun send(phone: String, text: String) {
        check(permitted)
        if (phone in failingPhones) error("send failed")
        sent += phone to text
    }
}

/** 메모리 속 초대 코드·연결 기록 */
class FakeLinkRepository : LinkRepository {
    data class StoredInvite(val patientUid: String, val patientName: String, val guardianId: String, val expired: Boolean)

    val invites = mutableMapOf<String, StoredInvite>()

    /** key = 환자 uid */
    private val links = mutableMapOf<String, MutableStateFlow<List<LinkedGuardian>>>()

    /** key = 보호자 uid */
    private val patients = mutableMapOf<String, MutableStateFlow<List<LinkedPatient>>>()

    var nextCode = "123456"
    var failCreate = false
    var failUnlink = false
    var failObservePatients = false

    private fun linksOf(uid: String) = links.getOrPut(uid) { MutableStateFlow(emptyList()) }

    private fun patientsOf(uid: String) = patients.getOrPut(uid) { MutableStateFlow(emptyList()) }

    override suspend fun createInvite(patientUid: String, patientName: String, guardianId: String): String {
        if (failCreate) throw LinkException(LinkError.INVITE_FAILED)
        invites[nextCode] = StoredInvite(patientUid, patientName, guardianId, expired = false)
        return nextCode
    }

    override fun observeLinkedGuardians(patientUid: String): Flow<List<LinkedGuardian>> = linksOf(patientUid)

    override suspend fun removeGuardianLink(patientUid: String, guardianUid: String) {
        if (failUnlink) throw LinkException(LinkError.UNLINK_FAILED)
        linksOf(patientUid).value = linksOf(patientUid).value.filterNot { it.guardianUid == guardianUid }
    }

    override suspend fun acceptInvite(code: String, guardianUid: String, guardianName: String): LinkedPatient {
        val inv = invites[code] ?: throw LinkException(LinkError.NOT_FOUND)
        if (inv.patientUid == guardianUid) throw LinkException(LinkError.SELF)
        if (inv.expired) throw LinkException(LinkError.EXPIRED)
        invites.remove(code) // 한 번 쓰면 사라진다
        linksOf(inv.patientUid).value += LinkedGuardian(guardianUid, inv.guardianId, guardianName)
        val p = LinkedPatient(inv.patientUid, inv.patientName)
        patientsOf(guardianUid).value += p
        return p
    }

    override fun observePatients(guardianUid: String): Flow<List<LinkedPatient>> =
        if (failObservePatients) flow { error("permission denied") } else patientsOf(guardianUid)

    override suspend fun leavePatient(guardianUid: String, patientUid: String) {
        if (failUnlink) throw LinkException(LinkError.UNLINK_FAILED)
        linksOf(patientUid).value = linksOf(patientUid).value.filterNot { it.guardianUid == guardianUid }
        patientsOf(guardianUid).value = patientsOf(guardianUid).value.filterNot { it.uid == patientUid }
    }

    /** 테스트 준비용: 환자 쪽 연결 기록만 바로 넣기 */
    fun putLink(patientUid: String, link: LinkedGuardian) {
        linksOf(patientUid).value += link
    }
}

/** 메모리 속 응급 알림 */
class FakeAlertRepository : AlertRepository {
    /** key = 환자 uid */
    private val alerts = mutableMapOf<String, MutableStateFlow<List<EmergencyAlert>>>()
    val created = mutableListOf<Pair<String, NewAlert>>()

    /** 이 환자의 알림은 읽을 수 없다 (연결이 끊긴 상황) */
    val unreadable = mutableSetOf<String>()
    var failCreate = false
    var failAck = false
    private var nextId = 1

    private fun of(uid: String) = alerts.getOrPut(uid) { MutableStateFlow(emptyList()) }

    override fun observe(patientUid: String): Flow<List<EmergencyAlert>> = of(patientUid).map { list ->
        if (patientUid in unreadable) error("permission denied")
        list.sortedByDescending { it.createdAtMillis }
    }

    override suspend fun create(patientUid: String, alert: NewAlert): String {
        if (failCreate) error("network")
        created += patientUid to alert
        val id = "a${nextId++}"
        of(patientUid).value += EmergencyAlert(
            id = id,
            patientUid = patientUid,
            createdAtMillis = nextId * 1000L,
            moving = alert.moving,
            source = alert.source,
            photo = alert.photo,
            ackedByName = null,
        )
        return id
    }

    override suspend fun acknowledge(patientUid: String, alertId: String, guardianUid: String, guardianName: String) {
        if (failAck) error("network")
        of(patientUid).value = of(patientUid).value.map {
            if (it.id == alertId && it.ackedByName == null) it.copy(ackedByName = guardianName) else it
        }
    }

    /** 테스트 준비용: 알림을 바로 넣기 */
    fun put(alert: EmergencyAlert) {
        of(alert.patientUid).value += alert
    }
}
