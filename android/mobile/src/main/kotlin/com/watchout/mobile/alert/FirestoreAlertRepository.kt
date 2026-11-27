package com.watchout.mobile.alert

import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Firestore `users/{환자 uid}/alerts`에 응급 알림을 저장하는 실제 구현 (#50).
 *
 * - 읽기: 환자 본인 + 연결된 보호자 (users/{환자}/links/{보호자 uid}가 있는 사람)
 * - 만들기: 환자 본인 (나중에 로봇카는 서버용 관리자 키로 만든다)
 * - 고치기: 연결된 보호자가 "확인" 필드만
 *
 * 사진은 무료 요금제에서 Cloud Storage를 새로 만들 수 없어서, 작게 줄인 JPEG을 문서 안(Blob)에 넣는다.
 */
class FirestoreAlertRepository(private val db: FirebaseFirestore) : AlertRepository {

    private fun alerts(patientUid: String): CollectionReference =
        db.collection("users").document(patientUid).collection("alerts")

    override fun observe(patientUid: String): Flow<List<EmergencyAlert>> = callbackFlow {
        val registration = alerts(patientUid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(MAX_SHOWN)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().map { it.toAlert(patientUid) })
            }
        awaitClose { registration.remove() }
    }

    private fun DocumentSnapshot.toAlert(patientUid: String): EmergencyAlert {
        // 방금 만든 알림은 서버 시각이 아직 없다 → ESTIMATE: 폰 시각으로 대신 채운다
        val created = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return EmergencyAlert(
            id = id,
            patientUid = patientUid,
            createdAtMillis = created?.toDate()?.time,
            moving = getBoolean("moving") ?: false,
            source = AlertSource.fromFirestore(getString("source")),
            photo = getBlob("photo")?.toBytes()?.let(::AlertPhoto),
            ackedByName = getString("ackedByName"),
        )
    }

    override suspend fun create(patientUid: String, alert: NewAlert): String {
        val data = buildMap {
            put("kind", "fall")
            put("source", alert.source.firestoreValue)
            put("moving", alert.moving)
            put("call119", "simulated") // 119는 실제로 연락하지 않는다 (CLAUDE.md)
            alert.photo?.let { put("photo", Blob.fromBytes(it.jpeg)) }
            put("createdAt", FieldValue.serverTimestamp())
        }
        return alerts(patientUid).add(data).await().id
    }

    override suspend fun acknowledge(patientUid: String, alertId: String, guardianUid: String, guardianName: String) {
        val data = mapOf(
            "ackedBy" to guardianUid,
            "ackedByName" to guardianName,
            "ackedAt" to FieldValue.serverTimestamp(),
        )
        alerts(patientUid).document(alertId).update(data).await()
    }

    private companion object {
        const val MAX_SHOWN = 20L
    }
}
