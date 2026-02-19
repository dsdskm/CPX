package com.aba.cpx.data.repository

import com.aba.cpx.data.model.Team
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class TeamRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun listenTeams(
        onUpdate: (List<Team>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        return db.collection("teams")
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
                    val status = d.getString("status") ?: "waiting"

                    Team(
                        id = idLong.toInt(),
                        name = name,
                        order = orderLong.toInt(),
                        password = pw,
                        status = status
                    )
                }

                onUpdate(list)
            }
    }

    fun updateStatusByTeamId(
        teamId: Int,
        newStatus: String,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        db.collection("teams")
            .whereEqualTo("id", teamId)
            .limit(1)
            .get()
            .addOnSuccessListener { qs ->
                val doc = qs.documents.firstOrNull()
                if (doc == null) {
                    onFail(IllegalStateException("teams에서 id=$teamId 문서를 찾을 수 없습니다."))
                    return@addOnSuccessListener
                }
                doc.reference.update("status", newStatus)
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e) }
            }
            .addOnFailureListener { e -> onFail(e) }
    }

    /**
     * ✅ 모든 팀 상태를 일괄 변경
     */
    fun updateAllTeamsStatus(
        newStatus: String,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        db.collection("teams")
            .get()
            .addOnSuccessListener { qs ->
                val batch = db.batch()
                qs.documents.forEach { doc ->
                    batch.update(doc.reference, "status", newStatus)
                }
                batch.commit()
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e) }
            }
            .addOnFailureListener { e -> onFail(e) }
    }
}
