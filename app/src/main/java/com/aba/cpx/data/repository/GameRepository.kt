package com.aba.cpx.data.repository

import android.util.Log
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameOrderItem
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.Team
import com.aba.cpx.data.model.TeamStatus
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class GameRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val powerPlacementRepo: PowerPlacementRepository = PowerPlacementRepository(db),
    private val teamRepo: TeamRepository = TeamRepository(db),
) {
    companion object {
        const val MAX_ROUNDS = 10
    }

    private fun gameRef(gameId: String) =
        db.collection("games").document(gameId)

    private fun turnsCol(gameId: String) =
        db.collection("games").document(gameId).collection("turns")

    private fun parseGameState(stateStr: String?): GameState {
        if (stateStr.isNullOrBlank()) return GameState.WAITING

        val normalized = stateStr.trim().lowercase()
        GameState.entries.firstOrNull { it.key == normalized }?.let { return it }

        return when (stateStr.trim().uppercase()) {
            "READY" -> GameState.WAITING
            "RUNNING" -> GameState.WORKING
            "PAUSED" -> GameState.PAUSED
            "FINISHED" -> GameState.COMPLETED
            "ABORTED" -> GameState.COMPLETED
            else -> GameState.WAITING
        }
    }

    private fun parseScoresMap(raw: Any?): Map<Int, Int> {
        val m = raw as? Map<*, *> ?: return emptyMap()
        val out = mutableMapOf<Int, Int>()
        for ((k, v) in m) {
            val keyInt = when (k) {
                is String -> k.toIntOrNull()
                is Number -> k.toInt()
                else -> null
            } ?: continue

            val valueInt = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            } ?: 0

            out[keyInt] = valueInt
        }
        return out
    }

    private fun toFirestoreScoresMap(scores: Map<Int, Int>): Map<String, Int> =
        scores.entries.associate { (k, v) -> k.toString() to v }

    private fun parseIntListMap(raw: Any?): Map<Int, List<Int>> {
        val m = raw as? Map<*, *> ?: return emptyMap()
        val out = mutableMapOf<Int, List<Int>>()
        for ((k, v) in m) {
            val keyInt = when (k) {
                is String -> k.toIntOrNull()
                is Number -> k.toInt()
                else -> null
            } ?: continue

            val list = (v as? List<*>)?.mapNotNull {
                when (it) {
                    is Number -> it.toInt()
                    is String -> it.toIntOrNull()
                    else -> null
                }
            } ?: emptyList()

            out[keyInt] = list
        }
        return out
    }

    private fun parseIntMap(raw: Any?): Map<Int, Int> {
        val m = raw as? Map<*, *> ?: return emptyMap()
        val out = mutableMapOf<Int, Int>()
        for ((k, v) in m) {
            val keyInt = when (k) {
                is String -> k.toIntOrNull()
                is Number -> k.toInt()
                else -> null
            } ?: continue

            val valueInt = when (v) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            } ?: continue

            out[keyInt] = valueInt
        }
        return out
    }

    private fun recomputeScores(
        initial: Map<Int, Int>,
        damageTaken: Map<Int, Int>,
        bonus: Map<Int, Int>,
    ): Map<Int, Int> {
        val teamIds = (initial.keys + damageTaken.keys + bonus.keys).toSet()
        val out = mutableMapOf<Int, Int>()
        for (tid in teamIds) {
            val init = initial[tid] ?: 0
            val dmg = damageTaken[tid] ?: 0
            val bon = bonus[tid] ?: 0
            out[tid] = max(init - dmg + bon, 0)
        }
        return out
    }

    // ------------------------------------------------------------
    // ✅ (운영) 게임 종료 + 아카이브
    // ------------------------------------------------------------
    suspend fun finishGameAndArchive(gameId: String): String {
        val liveRef = gameRef(gameId)

        val fmt = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.KOREA)
        val archiveId = fmt.format(Date())
        val archiveRef = gameRef(archiveId)

        db.runTransaction { tx ->
            val liveSnap = tx.get(liveRef)
            if (!liveSnap.exists()) throw IllegalStateException("games/$gameId 문서가 없습니다.")

            val liveData = liveSnap.data ?: emptyMap<String, Any?>()
            val archiveData = HashMap<String, Any?>().apply {
                putAll(liveData)
                put("state", GameState.COMPLETED.key)
                put("sourceLiveGameId", gameId)
                put("currentTeamId", null)
                put("turnToken", null)
                put("archivedAt", FieldValue.serverTimestamp())
            }

            tx.set(archiveRef, archiveData, SetOptions.merge())

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

        return archiveId
    }

    // ------------------------------------------------------------
    // ✅ (운영) 라이브 게임 초기화 + turns 삭제
    // ------------------------------------------------------------
    suspend fun resetLiveGame(gameId: String) {
        val liveRef = gameRef(gameId)

        liveRef.set(
            mapOf(
                "state" to GameState.WAITING.key,
                "order" to emptyList<Map<String, Any>>(),
                "turnIndex" to 0,
                "currentTeamId" to null,
                "turnToken" to null,

                "lastAttackedTeamId" to null,
                "lastAttackedStreak" to 0, // ✅ 추가

                "scoresByTeamId" to emptyMap<String, Int>(),
                "initialScoresByTeamId" to emptyMap<String, Int>(),
                "finalScoresByTeamId" to emptyMap<String, Int>(),
                "damageTakenByTeamId" to emptyMap<String, Int>(),
                "bonusByTeamId" to emptyMap<String, Int>(),
                "attackedTargetsByTeamId" to emptyMap<String, List<Int>>(),
                "lastTargetByTeamId" to emptyMap<String, Int>(),

                "startedAt" to null,
                "endedAt" to null,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()

        deleteAllDocumentsInSubCollection(turnsCol(gameId))
    }

    private suspend fun deleteAllDocumentsInSubCollection(col: CollectionReference) {
        while (true) {
            val snap = col.limit(450).get().await()
            if (snap.isEmpty) break

            val batch = db.batch()
            snap.documents.forEach { d -> batch.delete(d.reference) }
            batch.commit().await()
        }
    }

    // -------------------------------
    // ✅ 라이브 게임 문서 없으면 WAITING 생성
    // -------------------------------
    fun ensureLiveGameExists(
        gameId: String,
        onSuccess: () -> Unit = {},
        onFail: (Exception) -> Unit = {}
    ) {
        val ref = gameRef(gameId)
        ref.get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    onSuccess()
                    return@addOnSuccessListener
                }

                val data = hashMapOf<String, Any?>(
                    "state" to GameState.WAITING.key,
                    "order" to emptyList<Map<String, Any>>(),
                    "turnIndex" to 0,
                    "currentTeamId" to null,
                    "turnToken" to null,

                    "lastAttackedTeamId" to null,
                    "lastAttackedStreak" to 0, // ✅ 추가

                    "scoresByTeamId" to emptyMap<String, Int>(),
                    "initialScoresByTeamId" to emptyMap<String, Int>(),
                    "finalScoresByTeamId" to emptyMap<String, Int>(),
                    "damageTakenByTeamId" to emptyMap<String, Int>(),
                    "bonusByTeamId" to emptyMap<String, Int>(),

                    "attackedTargetsByTeamId" to emptyMap<String, List<Int>>(),
                    "lastTargetByTeamId" to emptyMap<String, Int>(),

                    "startedAt" to null,
                    "endedAt" to null,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )

                ref.set(data, SetOptions.merge())
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e) }
            }
            .addOnFailureListener { e -> onFail(e) }
    }

    // -------------------------------
    // ✅ 게임 문서 실시간 구독
    // -------------------------------
    fun listenGame(
        gameId: String,
        onUpdate: (Game?) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        ensureLiveGameExists(gameId)

        return gameRef(gameId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    onError(err)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) {
                    ensureLiveGameExists(gameId)
                    onUpdate(null)
                    return@addSnapshotListener
                }

                try {
                    val stateStr = snap.getString("state")
                    val state = parseGameState(stateStr)

                    val orderList = (snap.get("order") as? List<Map<String, Any>>)
                        .orEmpty()
                        .map { m ->
                            GameOrderItem(
                                teamId = (m["teamId"] as? Number)?.toInt() ?: 0,
                                teamName = (m["teamName"] as? String) ?: "",
                                order = (m["order"] as? Number)?.toInt() ?: 0
                            )
                        }

                    val initialScores = parseScoresMap(snap.get("initialScoresByTeamId"))
                    val finalScores = parseScoresMap(snap.get("finalScoresByTeamId"))
                    val damageTaken = parseScoresMap(snap.get("damageTakenByTeamId"))
                    val bonus = parseScoresMap(snap.get("bonusByTeamId"))

                    val storedScores = parseScoresMap(snap.get("scoresByTeamId"))
                    val recomputed = recomputeScores(initialScores, damageTaken, bonus)
                    val scores =
                        if (storedScores.isEmpty() && recomputed.isNotEmpty()) recomputed else storedScores

                    val attackedTargetsByTeamId = parseIntListMap(snap.get("attackedTargetsByTeamId"))
                    val lastTargetByTeamId = parseIntMap(snap.get("lastTargetByTeamId"))

                    // ✅ 추가 파싱
                    val lastAttackedStreak = (snap.getLong("lastAttackedStreak") ?: 0L).toInt()

                    val game = Game(
                        id = snap.id,
                        state = state,
                        order = orderList,
                        turnIndex = (snap.getLong("turnIndex") ?: 0L).toInt(),
                        currentTeamId = snap.getLong("currentTeamId")?.toInt(),
                        turnToken = snap.getString("turnToken"),
                        lastAttackedTeamId = snap.getLong("lastAttackedTeamId")?.toInt(),
                        lastAttackedStreak = lastAttackedStreak, // ✅

                        startedAt = snap.getTimestamp("startedAt")?.toDate()?.time,
                        endedAt = snap.getTimestamp("endedAt")?.toDate()?.time,
                        updatedAt = snap.getTimestamp("updatedAt")?.toDate()?.time,

                        scoresByTeamId = scores,
                        initialScoresByTeamId = initialScores,
                        finalScoresByTeamId = finalScores,
                        damageTakenByTeamId = damageTaken,
                        bonusByTeamId = bonus,
                        attackedTargetsByTeamId = attackedTargetsByTeamId,
                        lastTargetByTeamId = lastTargetByTeamId
                    )
                    onUpdate(game)
                } catch (e: Exception) {
                    onError(e)
                }
            }
    }

    // -------------------------------
    // ✅ 게임 시작 (시작 점수 = 전력배치 점수)
    // -------------------------------
    fun startGame(
        gameId: String,
        teamsInOrder: List<Team>,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val ref = gameRef(gameId)

        val sorted = teamsInOrder.sortedBy { it.order }
        if (sorted.isEmpty()) {
            onFail(IllegalStateException("팀이 없습니다."))
            return
        }

        val orderSnapshot = sorted.map {
            mapOf(
                "teamId" to it.id,
                "teamName" to it.name,
                "order" to it.order
            )
        }

        val tasks: List<Task<DocumentSnapshot?>> =
            sorted.map { t -> powerPlacementRepo.fetchPlacementSnapshot(t.id) }

        Log.d("kkh", "tasks ${tasks.size}")

        Tasks.whenAllSuccess<DocumentSnapshot?>(tasks)
            .addOnSuccessListener { snaps ->
                val initialByTeam = mutableMapOf<Int, Int>()
                sorted.forEachIndexed { idx, team ->
                    val doc = snaps.getOrNull(idx)
                    val initialScore = powerPlacementRepo.computePlacementScoreFromSnapshot(doc)
                    initialByTeam[team.id] = initialScore
                }

                val initialFs = toFirestoreScoresMap(initialByTeam)

                db.runTransaction { tx ->
                    val snap = tx.get(ref)

                    val currentStateStr = snap.getString("state")
                    val currentState = parseGameState(currentStateStr)

                    if (snap.exists() && (currentState == GameState.WORKING || currentState == GameState.PAUSED)) {
                        throw IllegalStateException("이미 게임이 진행중입니다. (state=${currentState.key})")
                    }

                    val firstTeamId = sorted.first().id

                    tx.set(
                        ref,
                        mapOf(
                            "state" to GameState.WORKING.key,
                            "order" to orderSnapshot,
                            "turnIndex" to 0,
                            "currentTeamId" to firstTeamId,
                            "turnToken" to UUID.randomUUID().toString(),

                            "lastAttackedTeamId" to null,
                            "lastAttackedStreak" to 0, // ✅ 추가

                            "initialScoresByTeamId" to initialFs,
                            "damageTakenByTeamId" to emptyMap<String, Int>(),
                            "bonusByTeamId" to emptyMap<String, Int>(),

                            "scoresByTeamId" to initialFs,
                            "finalScoresByTeamId" to emptyMap<String, Int>(),

                            "attackedTargetsByTeamId" to emptyMap<String, List<Int>>(),
                            "lastTargetByTeamId" to emptyMap<String, Int>(),
                            "startedAt" to FieldValue.serverTimestamp(),
                            "endedAt" to null,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                        SetOptions.merge()
                    )
                    null
                }
                    .addOnSuccessListener {
                        teamRepo.updateAllTeamsStatus(
                            TeamStatus.WORKING,
                            onSuccess = onSuccess,
                            onFail = onFail
                        )
                    }
                    .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
            }
            .addOnFailureListener { e ->
                onFail(e as? Exception ?: Exception(e))
            }
    }

    fun pauseGame(gameId: String, onSuccess: () -> Unit, onFail: (Exception) -> Unit) {
        setState(gameId, from = GameState.WORKING, to = GameState.PAUSED, onSuccess, onFail)
    }

    fun resumeGame(gameId: String, onSuccess: () -> Unit, onFail: (Exception) -> Unit) {
        setState(gameId, from = GameState.PAUSED, to = GameState.WORKING, onSuccess, onFail)
    }

    // -------------------------------
    // ✅ 공격 턴 제출
    // -------------------------------
    fun submitTurn(
        gameId: String,
        teamId: Int,
        turnToken: String,
        payload: Map<String, Any?>,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit,
    ) {
        val type = payload["type"] as? String ?: "attack"
        if (type != "attack") {
            onFail(IllegalArgumentException("unsupported payload.type=$type"))
            return
        }

        val targetTeamId = (payload["targetTeamId"] as? Number)?.toInt()
            ?: return onFail(IllegalArgumentException("targetTeamId missing"))

        val prevLast = (payload["prevLastAttackedTeamId"] as? Number)?.toInt() ?: -1
        val prevStreak = (payload["prevLastAttackedStreak"] as? Number)?.toInt() ?: -1 // ✅ 추가

        val cellsRaw = payload["cells"] as? List<*> ?: emptyList<Any?>()
        val picks: List<Pair<Int, Int>> = cellsRaw.mapNotNull { any ->
            val m = any as? Map<*, *> ?: return@mapNotNull null
            val c = (m["c"] as? Number)?.toInt() ?: return@mapNotNull null
            val r = (m["r"] as? Number)?.toInt() ?: return@mapNotNull null
            c to r
        }

        if (picks.size != 3) {
            onFail(IllegalArgumentException("cells must be exactly 3"))
            return
        }
        if (picks.toSet().size != picks.size) {
            onFail(IllegalArgumentException("duplicate cells in payload"))
            return
        }

        val gRef = gameRef(gameId)

        powerPlacementRepo.resolvePlacementRef(targetTeamId)
            .addOnSuccessListener { placementRef ->
                db.runTransaction { tx ->
                    val gSnap = tx.get(gRef)
                    if (!gSnap.exists()) throw IllegalStateException("game not found")

                    val state = parseGameState(gSnap.getString("state"))
                    val currentTeamId = (gSnap.getLong("currentTeamId") ?: -1L).toInt()
                    val serverToken = gSnap.getString("turnToken") ?: ""
                    val turnIndex = (gSnap.getLong("turnIndex") ?: 0L).toInt()

                    val lastAttackedServer = gSnap.getLong("lastAttackedTeamId")?.toInt() ?: -1
                    val lastStreakServer = (gSnap.getLong("lastAttackedStreak") ?: 0L).toInt() // ✅

                    if (state != GameState.WORKING) throw IllegalStateException("not working")
                    if (currentTeamId != teamId) throw IllegalStateException("not your turn")
                    if (serverToken.isBlank() || serverToken != turnToken) {
                        throw IllegalStateException("invalid turn token")
                    }

                    if (targetTeamId == teamId) throw IllegalStateException("cannot attack self")

                    // ✅✅✅ 변경: 2번까지는 허용, 3번째부터 금지
                    if (lastAttackedServer != -1 && targetTeamId == lastAttackedServer && lastStreakServer >= 2) {
                        throw IllegalStateException("cannot attack same team 3 times in a row")
                    }

                    // ✅ 레이스 방지
                    if (prevLast != -1 && prevLast != lastAttackedServer) {
                        throw IllegalStateException("state changed, retry")
                    }
                    if (prevStreak != -1 && prevStreak != lastStreakServer) {
                        throw IllegalStateException("state changed, retry")
                    }

                    val pSnap = tx.get(placementRef)

                    val placementKeys = mutableSetOf<String>()
                    val placements = (pSnap.get("placements") as? List<*>) ?: emptyList<Any?>()
                    placements.forEach { pAny ->
                        val pm = pAny as? Map<*, *> ?: return@forEach
                        val cells = pm["cells"] as? List<*> ?: return@forEach
                        cells.forEach { cAny ->
                            val cm = cAny as? Map<*, *> ?: return@forEach
                            val c = (cm["c"] as? Number)?.toInt() ?: return@forEach
                            val r = (cm["r"] as? Number)?.toInt() ?: return@forEach
                            placementKeys.add("$c:$r")
                        }
                    }

                    val existingHitKeys = mutableSetOf<String>()
                    val existingHits = (pSnap.get("hitCells") as? List<*>) ?: emptyList<Any?>()
                    existingHits.forEach { hAny ->
                        val hm = hAny as? Map<*, *> ?: return@forEach
                        val c = (hm["c"] as? Number)?.toInt() ?: return@forEach
                        val r = (hm["r"] as? Number)?.toInt() ?: return@forEach
                        existingHitKeys.add("$c:$r")
                    }

                    val at = Timestamp.now()

                    val newHitMaps = mutableListOf<Map<String, Any?>>()
                    var damage = 0
                    var hitCount = 0

                    picks.forEach { (c, r) ->
                        val key = "$c:$r"
                        val alreadyHit = existingHitKeys.contains(key)
                        val onPlacement = placementKeys.contains(key)

                        if (!alreadyHit) {
                            newHitMaps.add(
                                mapOf(
                                    "c" to c,
                                    "r" to r,
                                    "attackerTeamId" to teamId,
                                    "at" to at
                                )
                            )
                        }

                        if (onPlacement && !alreadyHit) {
                            hitCount += 1
                            damage += powerPlacementRepo.pointsForCol(c)
                        }
                    }

                    val bonusGain = (hitCount * 3) + (if (hitCount == 3) 3 else 0)

                    val initial = parseScoresMap(gSnap.get("initialScoresByTeamId"))
                    val damageTaken = parseScoresMap(gSnap.get("damageTakenByTeamId")).toMutableMap()
                    val bonus = parseScoresMap(gSnap.get("bonusByTeamId")).toMutableMap()

                    if (damage > 0) {
                        damageTaken[targetTeamId] = (damageTaken[targetTeamId] ?: 0) + damage
                    }

                    if (bonusGain > 0) {
                        bonus[teamId] = (bonus[teamId] ?: 0) + bonusGain
                    }

                    val scores = recomputeScores(initial, damageTaken, bonus)

                    val orderList = (gSnap.get("order") as? List<Map<String, Any>>).orEmpty()
                    if (orderList.isEmpty()) throw IllegalStateException("order is empty")

                    val teamCount = orderList.size
                    val maxTurns = teamCount * MAX_ROUNDS
                    val nextIndex = turnIndex + 1

                    // ✅✅✅ streak 업데이트(같은 팀이면 +1, 아니면 1)
                    val newStreak =
                        if (lastAttackedServer != -1 && targetTeamId == lastAttackedServer) (lastStreakServer + 1)
                        else 1

                    val updatesGame = mutableMapOf<String, Any?>(
                        "lastAttackedTeamId" to targetTeamId,
                        "lastAttackedStreak" to newStreak, // ✅ 추가
                        "updatedAt" to FieldValue.serverTimestamp(),

                        "damageTakenByTeamId" to toFirestoreScoresMap(damageTaken),
                        "bonusByTeamId" to toFirestoreScoresMap(bonus),
                        "scoresByTeamId" to toFirestoreScoresMap(scores),

                        "lastTargetByTeamId.${teamId}" to targetTeamId,
                        "attackedTargetsByTeamId.${teamId}" to FieldValue.arrayUnion(targetTeamId),
                    )

                    val isLastTurn = (nextIndex >= maxTurns)

                    if (isLastTurn) {
                        updatesGame["state"] = GameState.COMPLETED.key
                        updatesGame["turnIndex"] = (maxTurns - 1).coerceAtLeast(0)
                        updatesGame["currentTeamId"] = null
                        updatesGame["turnToken"] = null
                        updatesGame["endedAt"] = FieldValue.serverTimestamp()
                        updatesGame["finalScoresByTeamId"] = toFirestoreScoresMap(scores)
                    } else {
                        val round = nextIndex / teamCount
                        val posInRound = nextIndex % teamCount
                        val roundStartOffset = round % teamCount
                        val rotatedIndex = (roundStartOffset + posInRound) % teamCount

                        val nextTeamId =
                            (orderList[rotatedIndex]["teamId"] as? Number)?.toInt()
                                ?: throw IllegalStateException("nextTeamId parse failed")

                        updatesGame["turnIndex"] = nextIndex
                        updatesGame["currentTeamId"] = nextTeamId
                        updatesGame["turnToken"] = UUID.randomUUID().toString()
                    }

                    tx.update(gRef, updatesGame)

                    if (isLastTurn) {
                        val teamIdsInOrder = orderList.mapNotNull { (it["teamId"] as? Number)?.toInt() }
                        teamRepo.setTeamsStatusInTx(tx, teamIdsInOrder, TeamStatus.COMPLETED)
                    }

                    if (newHitMaps.isNotEmpty()) {
                        tx.set(
                            placementRef,
                            mapOf(
                                "teamId" to targetTeamId,
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "hitCells" to FieldValue.arrayUnion(*newHitMaps.toTypedArray())
                            ),
                            SetOptions.merge()
                        )
                    } else {
                        if (!pSnap.exists()) {
                            tx.set(
                                placementRef,
                                mapOf(
                                    "teamId" to targetTeamId,
                                    "updatedAt" to FieldValue.serverTimestamp(),
                                    "hitCells" to emptyList<Map<String, Any?>>()
                                ),
                                SetOptions.merge()
                            )
                        }
                    }

                    val fmt = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.KOREA)
                    val tsId = fmt.format(Date())
                    val turnLogRef = turnsCol(gameId).document(tsId)
                    tx.set(
                        turnLogRef,
                        mapOf(
                            "type" to "attack",
                            "attackerTeamId" to teamId,
                            "targetTeamId" to targetTeamId,
                            "cells" to picks.map { (c, r) -> mapOf("c" to c, "r" to r) },
                            "damage" to damage,
                            "hitCount" to hitCount,
                            "bonusGain" to bonusGain,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "turnIndex" to turnIndex
                        ),
                        SetOptions.merge()
                    )

                    null
                }
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
            }
            .addOnFailureListener { e ->
                onFail(e as? Exception ?: Exception(e))
            }
    }

    private fun setState(
        gameId: String,
        from: GameState,
        to: GameState,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val ref = gameRef(gameId)

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) throw IllegalStateException("게임 문서가 없습니다.")

            val state = parseGameState(snap.getString("state"))

            if (state != from) {
                throw IllegalStateException("${from.key} 상태에서만 ${to.key} 로 변경 가능합니다. (state=${state.key})")
            }

            tx.update(
                ref,
                mapOf(
                    "state" to to.key,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
    }
}