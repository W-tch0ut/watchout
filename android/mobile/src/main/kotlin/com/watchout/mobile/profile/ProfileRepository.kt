package com.watchout.mobile.profile

import kotlinx.coroutines.flow.Flow

/**
 * 사용자 정보(지금은 역할만) 저장소.
 * 화면·ViewModel은 이 약속만 알고, 실제 저장 방법(Firestore)은 [FirestoreProfileRepository]가 맡는다.
 * 테스트에서는 가짜 저장소로 바꿔 끼운다.
 */
interface ProfileRepository {
    /** [uid]의 역할을 실시간으로 흘려보낸다. 아직 안 골랐으면 null */
    fun observeRole(uid: String): Flow<UserRole?>

    /** [uid]의 역할을 저장한다. 실패하면 예외를 던진다. */
    suspend fun setRole(uid: String, role: UserRole)
}
