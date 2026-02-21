package com.aba.cpx.data.repository

import com.aba.cpx.data.model.Team
import com.aba.cpx.data.model.TeamStatus
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class TeamRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private fun teamsCol() = db.collection("teams")

    fun listenTeams(
        onUpdate: (List<Team>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        return teamsCol()
            .orderBy("order")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    onError(e)
                    return@addSnapshotListener
                }

                val list = snapshot?.documents.orEmpty().mapNotNull { d ->
                    val idLong = d.getLong("id") ?: return@mapNotNull null
                    val name = d.getString("name") ?: return@mapNotNull null
                    val orderLong = d.getLong("order") ?: 0L
                    val pw = d.getString("password") ?: ""

                    val statusKey = d.getString("status")
                    val statusEnum = TeamStatus.fromKey(statusKey)

                    Team(
                        id = idLong.toInt(),
                        name = name,
                        order = orderLong.toInt(),
                        password = pw,
                        status = statusEnum
                    )
                }

                onUpdate(list)
            }
    }

    fun updateStatusByTeamId(
        teamId: Int,
        newStatus: TeamStatus,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        teamsCol()
            .whereEqualTo("id", teamId)
            .limit(1)
            .get()
            .addOnSuccessListener { qs ->
                val doc = qs.documents.firstOrNull()
                if (doc == null) {
                    onFail(IllegalStateException("teams에서 id=$teamId 문서를 찾을 수 없습니다."))
                    return@addOnSuccessListener
                }
                doc.reference.update("status", newStatus.key)
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e) }
            }
            .addOnFailureListener { e -> onFail(e) }
    }

    fun updateAllTeamsStatus(
        newStatus: TeamStatus,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        teamsCol()
            .get()
            .addOnSuccessListener { qs ->
                val batch = db.batch()
                qs.documents.forEach { doc ->
                    batch.update(
                        doc.reference,
                        mapOf(
                            "status" to newStatus.key,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit()
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
            }
            .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
    }

    /**
     * ✅ GameRepository가 트랜잭션 안에서 teams 상태를 바꿔야 할 때 사용
     * ⚠️ 전제: teams 문서 ID가 teamId(String) 형태로 저장되어 있어야 함
     */
    fun setTeamsStatusInTx(
        tx: com.google.firebase.firestore.Transaction,
        teamIds: List<Int>,
        newStatus: TeamStatus
    ) {
        if (teamIds.isEmpty()) return

        teamIds.distinct().forEach { tid ->
            val tRef = teamsCol().document(tid.toString())
            tx.set(
                tRef,
                mapOf(
                    "status" to newStatus.key,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }
    }

    // ------------------------------------------------------------------
    // ↓↓↓ 기존 코드 호환용: String 파라미터 버전 유지
    // ------------------------------------------------------------------

    @Deprecated("TeamStatus 버전(updateStatusByTeamId(teamId, TeamStatus, ...))을 사용하세요.")
    fun updateStatusByTeamId(
        teamId: Int,
        newStatus: String,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val normalized = TeamStatus.fromKey(newStatus)
        updateStatusByTeamId(teamId, normalized, onSuccess, onFail)
    }

    @Deprecated("TeamStatus 버전(updateAllTeamsStatus(TeamStatus, ...))을 사용하세요.")
    fun updateAllTeamsStatus(
        newStatus: String,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val normalized = TeamStatus.fromKey(newStatus)
        updateAllTeamsStatus(normalized, onSuccess, onFail)
    }

    suspend fun updateAllTeamsStatusSuspend(newStatus: TeamStatus) {
        val qs = db.collection("teams").get().await()
        val docs = qs.documents
        if (docs.isEmpty()) return

        docs.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { doc ->
                batch.set(
                    doc.reference,
                    mapOf(
                        "status" to newStatus.key,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
        }
    }
}