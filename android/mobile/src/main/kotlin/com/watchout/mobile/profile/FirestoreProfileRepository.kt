package com.watchout.mobile.profile

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Firestore `users/{uid}` 문서에 역할을 저장하는 실제 구현.
 * 보안 규칙으로 본인만 읽고 쓸 수 있고, role 값은 "patient" / "guardian"만 허용한다.
 */
class FirestoreProfileRepository(private val db: FirebaseFirestore) : ProfileRepository {

    private fun userDoc(uid: String): DocumentReference = db.collection("users").document(uid)

    override fun observeRole(uid: String): Flow<UserRole?> = callbackFlow {
        val registration = userDoc(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            // 문서가 없으면(처음 로그인) getString이 null → 역할 없음
            trySend(UserRole.fromFirestore(snapshot?.getString("role")))
        }
        awaitClose { registration.remove() }
    }

    override suspend fun setRole(uid: String, role: UserRole) {
        // merge: users/{uid} 문서의 다른 필드(나중에 #50에서 추가될 것)를 지우지 않고 role만 바꾼다
        val data = mapOf("role" to role.firestoreValue, "updatedAt" to FieldValue.serverTimestamp())
        userDoc(uid).set(data, SetOptions.merge()).await()
    }
}
