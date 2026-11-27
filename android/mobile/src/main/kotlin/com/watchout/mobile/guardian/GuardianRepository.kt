package com.watchout.mobile.guardian

import kotlinx.coroutines.flow.Flow

/**
 * 보호자 연락처 저장소의 "약속(인터페이스)".
 * 실제 구현은 [FirestoreGuardianRepository], 테스트에서는 가짜 저장소를 쓴다.
 */
interface GuardianRepository {
    /** [uid] 사용자의 보호자 목록. 서버에서 바뀌면 새 목록이 다시 흘러온다. */
    fun observe(uid: String): Flow<List<Guardian>>

    /** id가 null이면 새로 추가, 있으면 수정한다. 실패하면 예외를 던진다. */
    suspend fun save(uid: String, guardian: Guardian)

    suspend fun delete(uid: String, guardianId: String)
}
