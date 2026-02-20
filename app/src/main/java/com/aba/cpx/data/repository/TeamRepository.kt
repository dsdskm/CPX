package com.aba.cpx.data.repository

import com.aba.cpx.data.model.Team
import com.aba.cpx.data.model.TeamStatus
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

                    // ✅ DB에는 영어 String이 저장되어 있음 → enum으로 안전 변환
                    // - null/알 수 없는 값이면 PREPARING으로 처리됨(fromKey 내부)
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

    /**
     * ✅ (권장) 팀 상태 업데이트: enum으로 받아서 DB에는 key(String)로 저장
     */
    fun updateStatusByTeamId(
        teamId: Int,
        newStatus: TeamStatus,
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
                doc.reference.update("status", newStatus.key)
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e) }
            }
            .addOnFailureListener { e -> onFail(e) }
    }

    /**
     * ✅ (권장) 모든 팀 상태를 일괄 변경: enum으로 받아서 DB에는 key(String)로 저장
     */
    fun updateAllTeamsStatus(
        newStatus: TeamStatus,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        db.collection("teams")
            .get()
            .addOnSuccessListener { qs ->
                val batch = db.batch()
                qs.documents.forEach { doc ->
                    batch.update(doc.reference, "status", newStatus.key)
                }
                batch.commit()
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e) }
            }
            .addOnFailureListener { e -> onFail(e) }
    }

    // ------------------------------------------------------------------
    // ↓↓↓ 기존 코드 호환용: String 파라미터를 쓰는 호출부가 많으면 바로 깨지니까 유지
    // ------------------------------------------------------------------

    /**
     * ⚠️ 기존 호환용 (비권장)
     * String으로 들어온 값을 enum으로 정규화 후, DB에는 enum.key로 저장
     */
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

    /**
     * ⚠️ 기존 호환용 (비권장)
     * String으로 들어온 값을 enum으로 정규화 후, DB에는 enum.key로 저장
     */
    @Deprecated("TeamStatus 버전(updateAllTeamsStatus(TeamStatus, ...))을 사용하세요.")
    fun updateAllTeamsStatus(
        newStatus: String,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val normalized = TeamStatus.fromKey(newStatus)
        updateAllTeamsStatus(normalized, onSuccess, onFail)
    }
}