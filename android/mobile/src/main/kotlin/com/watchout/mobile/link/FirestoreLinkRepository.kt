package com.watchout.mobile.link

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Firestore로 환자 ↔ 보호자를 연결하는 실제 구현 (#50).
 *
 * 저장 위치
 * - invites/{6자리 코드}              : patientUid, patientName, guardianId, createdAt  (10분 동안만 유효, 한 번 쓰면 지움)
 * - users/{환자}/links/{보호자 uid}   : guardianId, guardianName, inviteCode, linkedAt  ("이 보호자는 내 알림을 봐도 돼요" 허락증)
 * - users/{보호자}/patients/{환자 uid}: name, linkedAt                                   (보호자가 고른 환자 목록)
 *
 * 누가 무엇을 할 수 있는지는 firebase/firestore.rules가 서버에서 검사한다.
 * 특히 links 문서는 "아직 유효한 초대 코드"가 있을 때만 만들 수 있다.
 */
class FirestoreLinkRepository(private val db: FirebaseFirestore) : LinkRepository {

    private fun invite(code: String): DocumentReference = db.collection("invites").document(code)

    private fun link(patientUid: String, guardianUid: String): DocumentReference =
        db.collection("users").document(patientUid).collection("links").document(guardianUid)

    private fun patient(guardianUid: String, patientUid: String): DocumentReference =
        db.collection("users").document(guardianUid).collection("patients").document(patientUid)

    /** 트랜잭션 안에서 "이 코드는 이미 누가 쓰고 있다"를 알리는 표시 */
    private class CodeTaken : Exception()

    override suspend fun createInvite(patientUid: String, patientName: String, guardianId: String): String {
        val data = mapOf(
            "patientUid" to patientUid,
            "patientName" to patientName,
            "guardianId" to guardianId,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        // 6자리 코드는 100만 가지라 다른 사람 코드와 겹칠 수 있다 → 겹치면 새 코드로 다시 (최대 5번)
        repeat(MAX_TRIES) {
            val code = InviteCode.generate()
            try {
                // 트랜잭션: "비어 있는지 확인 → 쓰기"를 한 번에 처리해서 동시에 같은 코드를 만들어도 하나만 성공한다
                db.runTransaction { tx ->
                    if (tx.get(invite(code)).exists()) throw CodeTaken()
                    tx.set(invite(code), data)
                }.await()
                return code
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!e.isCodeTaken()) throw LinkException(LinkError.INVITE_FAILED, e)
            }
        }
        throw LinkException(LinkError.INVITE_FAILED)
    }

    /** Firestore가 트랜잭션 예외를 한 번 감싸서 던질 수 있어서 원인까지 살펴본다 */
    private fun Throwable.isCodeTaken(): Boolean = this is CodeTaken || cause?.isCodeTaken() == true

    override fun observeLinkedGuardians(patientUid: String): Flow<List<LinkedGuardian>> = callbackFlow {
        val registration = db.collection("users").document(patientUid).collection("links")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents.orEmpty().map { doc ->
                    LinkedGuardian(
                        guardianUid = doc.id,
                        guardianId = doc.getString("guardianId").orEmpty(),
                        guardianName = doc.getString("guardianName").orEmpty(),
                    )
                }
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    override suspend fun removeGuardianLink(patientUid: String, guardianUid: String) {
        try {
            link(patientUid, guardianUid).delete().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            throw LinkException(LinkError.UNLINK_FAILED, e)
        }
    }

    override suspend fun acceptInvite(code: String, guardianUid: String, guardianName: String): LinkedPatient {
        val snapshot = try {
            invite(code).get().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            throw LinkException(LinkError.LINK_FAILED, e)
        }
        if (!snapshot.exists()) throw LinkException(LinkError.NOT_FOUND)

        val patientUid = snapshot.getString("patientUid").orEmpty()
        val createdAt = snapshot.getTimestamp("createdAt")?.toDate()?.time
        when {
            patientUid.isEmpty() -> throw LinkException(LinkError.NOT_FOUND)
            patientUid == guardianUid -> throw LinkException(LinkError.SELF)
            InviteCode.isExpired(createdAt, System.currentTimeMillis()) -> throw LinkException(LinkError.EXPIRED)
        }
        val linked = LinkedPatient(uid = patientUid, name = snapshot.getString("patientName").orEmpty())

        // 세 가지를 한 묶음(batch)으로: 전부 성공하거나 전부 실패한다
        val batch = db.batch()
        batch.set(
            link(patientUid, guardianUid),
            mapOf(
                "guardianId" to snapshot.getString("guardianId").orEmpty(),
                "guardianName" to guardianName,
                "inviteCode" to code,
                "linkedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(
            patient(guardianUid, patientUid),
            mapOf("name" to linked.name, "linkedAt" to FieldValue.serverTimestamp()),
        )
        batch.delete(invite(code)) // 한 번 쓴 코드는 바로 지운다
        try {
            batch.commit().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 시계 차이 등으로 서버가 만료라고 판단하면 권한 거부가 온다
            throw LinkException(LinkError.LINK_FAILED, e)
        }
        return linked
    }

    override fun observePatients(guardianUid: String): Flow<List<LinkedPatient>> = callbackFlow {
        val registration = db.collection("users").document(guardianUid).collection("patients")
            .orderBy("name")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().map { LinkedPatient(it.id, it.getString("name").orEmpty()) })
            }
        awaitClose { registration.remove() }
    }

    override suspend fun leavePatient(guardianUid: String, patientUid: String) {
        val batch = db.batch()
        batch.delete(link(patientUid, guardianUid))
        batch.delete(patient(guardianUid, patientUid))
        try {
            batch.commit().await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            throw LinkException(LinkError.UNLINK_FAILED, e)
        }
    }

    private companion object {
        const val MAX_TRIES = 5
    }
}
