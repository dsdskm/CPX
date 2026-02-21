package com.aba.cpx.data.repository

import com.aba.cpx.data.model.PowerPlacement
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class PowerPlacementRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        private const val CANONICAL_COL = "powerPlacements"

        // (레거시 대응) 과거 컬렉션명 후보들
        private val PLACEMENTS_COL_CANDIDATES = listOf(
            "powerPlacements",
            "powerPlacement",
            "power_placements"
        )

        // 전력배치 점수(열별 점수)
        private val COL_POINTS = listOf(10, 10, 8, 8, 6, 6, 4, 4, 2, 2)
    }

    private fun colRef() = db.collection(CANONICAL_COL)
    private fun docRef(teamId: Int) = colRef().document(teamId.toString())

    // ------------------------------------------------------------
    // ✅ 점수 유틸 (GameRepository에서도 사용)
    // ------------------------------------------------------------
    fun pointsForCol(c: Int): Int = COL_POINTS.getOrElse(c) { 0 }

    fun computePlacementScoreFromSnapshot(doc: DocumentSnapshot?): Int {
        if (doc == null || !doc.exists()) return 0

        val placements = doc.get("placements") as? List<*> ?: return 0
        var sum = 0

        placements.forEach { pAny ->
            val p = pAny as? Map<*, *> ?: return@forEach
            val cells = p["cells"] as? List<*> ?: return@forEach
            cells.forEach { cAny ->
                val cm = cAny as? Map<*, *> ?: return@forEach
                val c = (cm["c"] as? Number)?.toInt() ?: return@forEach
                sum += pointsForCol(c)
            }
        }
        return sum
    }

    // ------------------------------------------------------------
    // ✅ (호환) 기존 Callback API 유지
    // ------------------------------------------------------------
    fun savePlacement(
        doc: PowerPlacement,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val data = mapOf(
            "teamId" to doc.teamId,
            "placements" to doc.placements.map { p ->
                mapOf(
                    "id" to p.id,
                    "type" to p.type,
                    "cells" to p.cells.map { cell -> mapOf("c" to cell.c, "r" to cell.r) }
                )
            },
            "updatedAt" to FieldValue.serverTimestamp()
        )

        docRef(doc.teamId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFail(e) }
    }

    fun loadOnce(
        teamId: Int,
        onSuccess: (PowerPlacement?) -> Unit,
        onFail: (Exception) -> Unit
    ) {
        docRef(teamId)
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.toObject(PowerPlacement::class.java))
            }
            .addOnFailureListener { e -> onFail(e) }
    }

    // ------------------------------------------------------------
    // ✅ AdminRepository용: 전력배치 점수 로딩(suspend)
    // ------------------------------------------------------------
    suspend fun loadPlacementScores(teamIds: List<Int>): Map<Int, Int> {
        if (teamIds.isEmpty()) return emptyMap()

        val result = mutableMapOf<Int, Int>()
        for (teamId in teamIds) {
            val snap = fetchCanonicalPowerPlacementDoc(teamId)
            result[teamId] = computePlacementScoreFromSnapshot(snap)
        }
        return result
    }

    private suspend fun fetchCanonicalPowerPlacementDoc(teamId: Int): DocumentSnapshot? {
        val direct = docRef(teamId).get().await()
        if (direct.exists()) return direct

        val qs = colRef()
            .whereEqualTo("teamId", teamId)
            .limit(1)
            .get()
            .await()

        return qs.documents.firstOrNull()
    }

    // ------------------------------------------------------------
    // ✅ GameRepository용(트랜잭션/Task 기반):
    // - 레거시 후보 컬렉션까지 포함해서 "가장 먼저 발견되는" placement 문서 찾기
    // ------------------------------------------------------------
    fun fetchPlacementSnapshot(teamId: Int): Task<DocumentSnapshot?> {
        fun tryOneCollection(colName: String): Task<DocumentSnapshot?> {
            val directRef = db.collection(colName).document(teamId.toString())
            return directRef.get().continueWithTask { t ->
                val snap = t.result
                if (snap != null && snap.exists()) {
                    Tasks.forResult(snap)
                } else {
                    db.collection(colName)
                        .whereEqualTo("teamId", teamId)
                        .limit(1)
                        .get()
                        .continueWith { qsTask ->
                            val qs: QuerySnapshot? = qsTask.result
                            qs?.documents?.firstOrNull()
                        }
                }
            }
        }

        var chain: Task<DocumentSnapshot?> = Tasks.forResult(null)
        PLACEMENTS_COL_CANDIDATES.forEach { col ->
            chain = chain.continueWithTask { prev ->
                val prevSnap = prev.result
                if (prevSnap != null && prevSnap.exists()) {
                    Tasks.forResult(prevSnap)
                } else {
                    tryOneCollection(col)
                }
            }
        }
        return chain
    }

    /**
     * submitTurn/수동입력에서 query 못하므로 ref를 미리 확정
     * - 발견된 스냅샷이 있으면 그 reference
     * - 없으면 canonical(powerPlacements/{teamId})로 fallback
     */
    fun resolvePlacementRef(teamId: Int): Task<DocumentReference> {
        return fetchPlacementSnapshot(teamId).continueWith { t ->
            val snap = t.result
            snap?.reference ?: colRef().document(teamId.toString())
        }
    }

    // ------------------------------------------------------------
    // ✅ hitCells: 구독/수동추가
    // ------------------------------------------------------------
    fun listenHitCellsForTeam(
        teamId: Int,
        onUpdate: (Set<Pair<Int, Int>>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        val ref = colRef().document(teamId.toString())
        return ref.addSnapshotListener { snap, err ->
            if (err != null) {
                onError(err)
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) {
                onUpdate(emptySet())
                return@addSnapshotListener
            }

            try {
                val hits = (snap.get("hitCells") as? List<*>) ?: emptyList<Any?>()
                val set = hits.mapNotNull { hAny ->
                    val hm = hAny as? Map<*, *> ?: return@mapNotNull null
                    val c = (hm["c"] as? Number)?.toInt() ?: return@mapNotNull null
                    val r = (hm["r"] as? Number)?.toInt() ?: return@mapNotNull null
                    c to r
                }.toSet()
                onUpdate(set)
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    fun addHitCellsManualForTeam(
        teamId: Int,
        cells: List<Pair<Int, Int>>,
        attackerTeamId: Int? = null,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        if (cells.isEmpty()) {
            onFail(IllegalArgumentException("cells empty"))
            return
        }

        val ref = colRef().document(teamId.toString())
        val at = Timestamp.now()

        val hitMaps = cells.distinct().map { (c, r) ->
            mutableMapOf<String, Any?>(
                "c" to c,
                "r" to r,
                "at" to at
            ).apply {
                if (attackerTeamId != null) put("attackerTeamId", attackerTeamId)
            }
        }

        ref.set(
            mapOf(
                "teamId" to teamId,
                "updatedAt" to FieldValue.serverTimestamp(),
                "hitCells" to FieldValue.arrayUnion(*hitMaps.toTypedArray())
            ),
            SetOptions.merge()
        ).addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
    }

    fun addHitManual(
        targetTeamId: Int,
        cells: List<Pair<Int, Int>>,
        attackerTeamId: Int? = null,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        if (cells.isEmpty()) {
            onFail(IllegalArgumentException("cells empty"))
            return
        }

        resolvePlacementRef(targetTeamId)
            .addOnSuccessListener { placementRef ->
                val at = Timestamp.now()
                val hitMaps = cells.distinct().map { (c, r) ->
                    mutableMapOf<String, Any?>(
                        "c" to c,
                        "r" to r,
                        "at" to at
                    ).apply {
                        if (attackerTeamId != null) put("attackerTeamId", attackerTeamId)
                    }
                }

                placementRef.set(
                    mapOf(
                        "teamId" to targetTeamId,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "hitCells" to FieldValue.arrayUnion(*hitMaps.toTypedArray())
                    ),
                    SetOptions.merge()
                ).addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
            }
            .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
    }

    // ------------------------------------------------------------
    // ✅ AdminRepository용: powerPlacements 전체 초기화(suspend)
    // ------------------------------------------------------------
    suspend fun resetAllPowerPlacements() {
        val qs = colRef().get().await()
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
}