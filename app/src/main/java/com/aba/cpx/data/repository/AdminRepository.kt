package com.aba.cpx.data.repository

import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.TeamStatus
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 운영/관리 기능(종료, 초기화, 운영화면 전용 조회)을 화면이 아닌 Repository 레이어에서 수행
 */
class AdminRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        // 전력배치 점수(열별 점수)
        private val COL_POINTS = listOf(10, 10, 8, 8, 6, 6, 4, 4, 2, 2)
        private const val POWER_PLACEMENTS_COL = "powerPlacements"
    }

    private fun gamesRef() = db.collection("games")
    private fun teamsRef() = db.collection("teams")
    private fun powerPlacementsRef() = db.collection(POWER_PLACEMENTS_COL)

    // ------------------------------------------------------------
    // ✅ 운영 화면 전용: 전력배치 점수 로딩 (powerPlacements만 사용)
    // ------------------------------------------------------------
    suspend fun loadPlacementScores(teamIds: List<Int>): Map<Int, Int> {
        if (teamIds.isEmpty()) return emptyMap()

        val result = mutableMapOf<Int, Int>()

        for (teamId in teamIds) {
            val snap = fetchPowerPlacementDoc(teamId)
            result[teamId] = computePlacementScore(snap)
        }

        return result
    }

    private suspend fun fetchPowerPlacementDoc(teamId: Int): DocumentSnapshot? {
        // 1) powerPlacements/{teamId} 직접 접근
        val direct = powerPlacementsRef().document(teamId.toString()).get().await()
        if (direct.exists()) return direct

        // 2) 같은 컬렉션에서 teamId 필드로 조회 (후보 컬렉션 사용 안 함)
        val qs = powerPlacementsRef()
            .whereEqualTo("teamId", teamId)
            .limit(1)
            .get()
            .await()

        return qs.documents.firstOrNull()
    }

    private fun computePlacementScore(doc: DocumentSnapshot?): Int {
        if (doc == null || !doc.exists()) return 0

        val placements = doc.get("placements") as? List<*> ?: return 0
        var sum = 0

        placements.forEach { pAny ->
            val p = pAny as? Map<*, *> ?: return@forEach
            val cells = p["cells"] as? List<*> ?: return@forEach
            cells.forEach { cAny ->
                val cm = cAny as? Map<*, *> ?: return@forEach
                val c = (cm["c"] as? Number)?.toInt() ?: return@forEach
                if (c in COL_POINTS.indices) sum += COL_POINTS[c]
            }
        }
        return sum
    }

    // ------------------------------------------------------------
    // ✅ 종료:
    // - games/{gameId} 문서를 그대로 복사하여 games/{archiveId}로 저장 (날짜 기반 ID)
    // - games/{gameId}.state = completed
    // - 모든 teams.status = completed
    // ------------------------------------------------------------
    suspend fun finishGameAndArchiveAndCompleteTeams(gameId: String): String {
        val liveRef = gamesRef().document(gameId)

        // 날짜 기반 ID: ms까지 포함
        val fmt = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.KOREA)
        val archiveId = fmt.format(Date())
        val archiveRef = gamesRef().document(archiveId)

        // 1) 게임 문서 스냅샷 생성 + live completed
        db.runTransaction { tx ->
            val liveSnap = tx.get(liveRef)
            if (!liveSnap.exists()) {
                throw IllegalStateException("games/$gameId 문서가 없습니다.")
            }

            // "그대로 카피": 모든 필드 복사
            val liveData = liveSnap.data ?: emptyMap<String, Any?>()

            val archiveData = HashMap<String, Any?>()
            archiveData.putAll(liveData)
            archiveData["sourceLiveGameId"] = gameId
            archiveData["archivedAt"] = FieldValue.serverTimestamp()

            tx.set(archiveRef, archiveData, SetOptions.merge())

            // live 게임 completed
            tx.set(
                liveRef,
                mapOf(
                    "state" to GameState.COMPLETED.key,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "currentTeamId" to null,
                    "turnToken" to null,
                ),
                SetOptions.merge()
            )

            null
        }.await()

        // 2) 모든 팀 status completed
        setAllTeamsStatus(TeamStatus.COMPLETED)

        return archiveId
    }

    // ------------------------------------------------------------
    // ✅ 초기화:
    // - teams.status = preparing
    // - games/{gameId} 필드 전체 초기화 + state waiting
    // - games/{gameId}/turns 서브컬렉션 모두 삭제
    // - powerPlacements 모든 문서 초기화(placements/hitCells 비움)
    // ------------------------------------------------------------
    suspend fun resetAll(gameId: String) {
        // 1) 팀 preparing
        setAllTeamsStatus(TeamStatus.PREPARING)

        // 2) 게임 문서 필드 전체 초기화(merge로 덮어쓰기)
        val liveRef = gamesRef().document(gameId)
        liveRef.set(
            mapOf(
                "state" to GameState.WAITING.key,
                "order" to emptyList<Map<String, Any>>(),
                "turnIndex" to 0,
                "currentTeamId" to null,
                "turnToken" to null,
                "lastAttackedTeamId" to null,
                "scoresByTeamId" to emptyMap<String, Int>(),
                "initialScoresByTeamId" to emptyMap<String, Int>(),
                "attackedTargetsByTeamId" to emptyMap<String, List<Int>>(),
                "lastTargetByTeamId" to emptyMap<String, Int>(),
                "startedAt" to null,
                "endedAt" to null,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()

        // 3) turns 서브컬렉션 전부 삭제
        deleteAllDocumentsInSubCollection(liveRef.collection("turns"))

        // 4) powerPlacements 전체 초기화
        resetAllPowerPlacements()
    }

    // -------------------------
    // 내부 헬퍼들
    // -------------------------
    private suspend fun setAllTeamsStatus(newStatus: TeamStatus) {
        val qs = teamsRef().get().await()
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

    private suspend fun resetAllPowerPlacements() {
        val qs = powerPlacementsRef().get().await()
        val docs = qs.documents
        if (docs.isEmpty()) return

        docs.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { doc ->
                val teamId = (doc.getLong("teamId")?.toInt())
                    ?: doc.id.toIntOrNull()
                    ?: 0

                batch.set(
                    doc.reference,
                    mapOf(
                        "teamId" to teamId,
                        "placements" to emptyList<Map<String, Any?>>(),
                        "hitCells" to emptyList<Map<String, Any?>>(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
        }
    }

    /**
     * 서브컬렉션 전체 삭제(클라이언트에서 가능한 방식)
     * - 1회 배치/쿼리 제한 고려하여 450개씩 반복
     */
    private suspend fun deleteAllDocumentsInSubCollection(col: CollectionReference) {
        while (true) {
            val snap = col.limit(450).get().await()
            if (snap.isEmpty) break

            val batch = db.batch()
            snap.documents.forEach { d -> batch.delete(d.reference) }
            batch.commit().await()
        }
    }
}