package com.watchout.mobile.link

import kotlinx.coroutines.flow.Flow

/**
 * 환자 ↔ 보호자 연결 저장소의 "약속(인터페이스)".
 * 실제 구현은 [FirestoreLinkRepository], 테스트에서는 가짜 저장소를 쓴다.
 * 실패하면 [LinkException]을 던진다.
 */
interface LinkRepository {
    // ── 환자 쪽 ──

    /** 보호자 연락처([guardianId]) 앞으로 새 초대 코드를 만들어 돌려준다. */
    suspend fun createInvite(patientUid: String, patientName: String, guardianId: String): String

    /** 이 환자와 연결된 보호자 계정 목록 (실시간) */
    fun observeLinkedGuardians(patientUid: String): Flow<List<LinkedGuardian>>

    /** 환자가 보호자 계정과의 연결을 끊는다. 끊으면 그 보호자는 알림을 더 볼 수 없다. */
    suspend fun removeGuardianLink(patientUid: String, guardianUid: String)

    // ── 보호자 쪽 ──

    /** 초대 코드를 쓰고 환자와 연결한다. 연결된 환자를 돌려준다. */
    suspend fun acceptInvite(code: String, guardianUid: String, guardianName: String): LinkedPatient

    /** 이 보호자가 연결한 환자 목록 (실시간) */
    fun observePatients(guardianUid: String): Flow<List<LinkedPatient>>

    /** 보호자가 환자와의 연결을 끊는다 (양쪽 기록을 모두 지운다). */
    suspend fun leavePatient(guardianUid: String, patientUid: String)
}
