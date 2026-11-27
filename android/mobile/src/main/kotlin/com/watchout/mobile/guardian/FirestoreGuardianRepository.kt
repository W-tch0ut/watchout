package com.watchout.mobile.guardian

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Firestore에 보호자 연락처를 저장하는 실제 구현.
 *
 * 보안:
 * - Firestore는 디스크에 저장할 때 자동으로 암호화하고(AES-256), 앱과 서버 사이도 HTTPS로 암호화한다.
 * - 거기에 보안 규칙(firebase/firestore.rules)으로 "로그인한 본인만" 자기 연락처를 읽고 쓰게 막는다.
 * - 연락처 값은 로그(Logcat)에 찍지 않는다.
 */
class FirestoreGuardianRepository(private val db: FirebaseFirestore) : GuardianRepository {

    private fun guardians(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("guardians")

    override fun observe(uid: String): Flow<List<Guardian>> = callbackFlow {
        // 실시간 구독: 목록이 바뀔 때마다 콜백이 불리고, 그 값을 Flow로 흘려보낸다.
        val registration = guardians(uid).orderBy("name").addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val list = snapshot?.documents.orEmpty().map { doc ->
                Guardian(
                    id = doc.id,
                    name = doc.getString("name").orEmpty(),
                    relation = doc.getString("relation").orEmpty(),
                    phone = doc.getString("phone").orEmpty(),
                )
            }
            trySend(list)
        }
        // 화면이 닫히면 구독을 끊는다 (배터리·데이터 절약)
        awaitClose { registration.remove() }
    }

    override suspend fun save(uid: String, guardian: Guardian) {
        val data = mapOf(
            "name" to guardian.name,
            "relation" to guardian.relation,
            "phone" to guardian.phone,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val id = guardian.id
        if (id == null) {
            guardians(uid).add(data).await()
        } else {
            guardians(uid).document(id).set(data).await()
        }
    }

    override suspend fun delete(uid: String, guardianId: String) {
        guardians(uid).document(guardianId).delete().await()
    }
}
