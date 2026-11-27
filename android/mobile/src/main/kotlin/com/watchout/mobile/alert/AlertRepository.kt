package com.watchout.mobile.alert

import kotlinx.coroutines.flow.Flow

/**
 * 응급 알림 저장소의 "약속(인터페이스)".
 * 실제 구현은 [FirestoreAlertRepository], 테스트에서는 가짜 저장소를 쓴다.
 */
interface AlertRepository {
    /** [patientUid] 환자의 최근 알림 (새것부터). 환자 본인과 연결된 보호자만 읽을 수 있다. */
    fun observe(patientUid: String): Flow<List<EmergencyAlert>>

    /** 새 알림을 만들고 id를 돌려준다. 실패하면 예외. */
    suspend fun create(patientUid: String, alert: NewAlert): String

    /** 보호자가 [확인했어요]를 누른다. 실패하면 예외. */
    suspend fun acknowledge(patientUid: String, alertId: String, guardianUid: String, guardianName: String)
}
