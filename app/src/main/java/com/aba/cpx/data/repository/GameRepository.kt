package com.aba.cpx.data.repository

import android.util.Log
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameOrderItem
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.Team
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class GameRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        private const val MAX_ROUNDS = 10
        private val COL_POINTS = listOf(10, 10, 8, 8, 6, 6, 4, 4, 2, 2)

        private val PLACEMENTS_COL_CANDIDATES = listOf(
            "powerPlacements",
            "powerPlacement",
            "power_placements"
        )
        private const val CANONICAL_PLACEMENTS_COL = "powerPlacements"
    }

    private fun gameRef(gameId: String) =
        db.collection("games").document(gameId)

    private fun turnsCol(gameId: String) =
        db.collection("games").document(gameId).collection("turns")

    // -------------------------------
    // 점수 map 변환
    // -------------------------------
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

    // ✅ Map<Int, List<Int>> 파싱 (attackedTargetsByTeamId)
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

    // ✅ Map<Int, Int> 파싱 (lastTargetByTeamId)
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

    // -------------------------------
    // ✅ 전력배치 점수 계산 (시작점수)
    // -------------------------------
    private fun computePlacementScoreFromSnapshot(doc: DocumentSnapshot?): Int {
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

    // -------------------------------
    // ✅ 전력배치 문서 찾기
    // -------------------------------
    private fun fetchPlacementSnapshot(teamId: Int): Task<DocumentSnapshot?> {
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

    // ✅ submitTurn/수동입력에서 query 못하므로 ref를 미리 확정
    private fun resolvePlacementRef(teamId: Int): Task<DocumentReference> {
        return fetchPlacementSnapshot(teamId).continueWith { t ->
            val snap = t.result
            snap?.reference ?: db.collection(CANONICAL_PLACEMENTS_COL).document(teamId.toString())
        }
    }

    // -------------------------------
    // ✅ 라이브 게임 문서 없으면 READY 생성
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
                    "state" to GameState.READY.name,
                    "order" to emptyList<Map<String, Any>>(),
                    "turnIndex" to 0,
                    "currentTeamId" to null,
                    "turnToken" to null,
                    "lastAttackedTeamId" to null,
                    "scoresByTeamId" to emptyMap<String, Int>(),
                    // ✅ 추가: 원점수(시작점수)
                    "initialScoresByTeamId" to emptyMap<String, Int>(),
                    // ✅ 공격기록
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
                    val stateStr = snap.getString("state") ?: GameState.READY.name
                    val state = runCatching { GameState.valueOf(stateStr) }
                        .getOrDefault(GameState.READY)

                    val orderList = (snap.get("order") as? List<Map<String, Any>>)
                        .orEmpty()
                        .map { m ->
                            GameOrderItem(
                                teamId = (m["teamId"] as? Number)?.toInt() ?: 0,
                                teamName = (m["teamName"] as? String) ?: "",
                                order = (m["order"] as? Number)?.toInt() ?: 0
                            )
                        }

                    val scores = parseScoresMap(snap.get("scoresByTeamId"))
                    // ✅ 추가: 원점수
                    val initialScores = parseScoresMap(snap.get("initialScoresByTeamId"))

                    val attackedTargetsByTeamId =
                        parseIntListMap(snap.get("attackedTargetsByTeamId"))
                    val lastTargetByTeamId = parseIntMap(snap.get("lastTargetByTeamId"))

                    val game = Game(
                        id = snap.id,
                        state = state,
                        order = orderList,
                        turnIndex = (snap.getLong("turnIndex") ?: 0L).toInt(),
                        currentTeamId = snap.getLong("currentTeamId")?.toInt(),
                        turnToken = snap.getString("turnToken"),
                        lastAttackedTeamId = snap.getLong("lastAttackedTeamId")?.toInt(),
                        startedAt = snap.getTimestamp("startedAt")?.toDate()?.time,
                        endedAt = snap.getTimestamp("endedAt")?.toDate()?.time,
                        updatedAt = snap.getTimestamp("updatedAt")?.toDate()?.time,
                        scoresByTeamId = scores,
                        // ✅ 추가
                        initialScoresByTeamId = initialScores,
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

        val tasks: List<Task<DocumentSnapshot?>> = sorted.map { t -> fetchPlacementSnapshot(t.id) }
        Log.d("kkh", "tasks ${tasks.size}")
        Tasks.whenAllSuccess<DocumentSnapshot?>(tasks)
            .addOnSuccessListener { snaps ->
                val scoreByTeam = mutableMapOf<Int, Int>()
                sorted.forEachIndexed { idx, team ->
                    val doc = snaps.getOrNull(idx)
                    val initialScore = computePlacementScoreFromSnapshot(doc)
                    scoreByTeam[team.id] = initialScore
                }

                val scoresInitFs = toFirestoreScoresMap(scoreByTeam)

                db.runTransaction { tx ->
                    val snap = tx.get(ref)

                    val currentStateStr = snap.getString("state") ?: GameState.READY.name
                    val currentState = runCatching { GameState.valueOf(currentStateStr) }
                        .getOrDefault(GameState.READY)

                    if (snap.exists() && (currentState == GameState.RUNNING || currentState == GameState.PAUSED)) {
                        throw IllegalStateException("이미 게임이 진행중입니다. (state=$currentState)")
                    }

                    val firstTeamId = sorted.first().id

                    tx.set(
                        ref,
                        mapOf(
                            "state" to GameState.RUNNING.name,
                            "order" to orderSnapshot,
                            "turnIndex" to 0,
                            "currentTeamId" to firstTeamId,
                            "turnToken" to UUID.randomUUID().toString(),
                            "lastAttackedTeamId" to null,
                            "scoresByTeamId" to scoresInitFs,
                            // ✅ 추가: 원점수 저장
                            "initialScoresByTeamId" to scoresInitFs,
                            // ✅ 공격기록 초기화
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
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
            }
            .addOnFailureListener { e ->
                onFail(e as? Exception ?: Exception(e))
            }
    }

    fun pauseGame(gameId: String, onSuccess: () -> Unit, onFail: (Exception) -> Unit) {
        setState(gameId, from = GameState.RUNNING, to = GameState.PAUSED, onSuccess, onFail)
    }

    fun resumeGame(gameId: String, onSuccess: () -> Unit, onFail: (Exception) -> Unit) {
        setState(gameId, from = GameState.PAUSED, to = GameState.RUNNING, onSuccess, onFail)
    }

    // -------------------------------
    // ✅ 종료 + 아카이브 + READY 초기화
    // -------------------------------
    fun finishGameWithArchiveAndReset(
        liveGameId: String,
        onSuccess: (archiveGameId: String) -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val liveRef = gameRef(liveGameId)

        val fmt = SimpleDateFormat("yyyyMMddHHmm", Locale.KOREA)
        val archiveId = fmt.format(Date())
        val archiveRef = gameRef(archiveId)

        db.runTransaction { tx ->
            val snap = tx.get(liveRef)
            if (!snap.exists()) {
                tx.set(
                    liveRef,
                    mapOf(
                        "state" to GameState.READY.name,
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
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge()
                )
                throw IllegalStateException("라이브 게임 문서가 없어 READY로 생성했습니다. 다시 시도하세요.")
            }

            val stateStr = snap.getString("state") ?: GameState.READY.name
            val state = runCatching { GameState.valueOf(stateStr) }.getOrDefault(GameState.READY)

            if (state != GameState.RUNNING && state != GameState.PAUSED) {
                throw IllegalStateException("종료할 수 없는 상태입니다. (state=$state)")
            }

            val order = (snap.get("order") as? List<Map<String, Any>>).orEmpty()
            val turnIndex = (snap.getLong("turnIndex") ?: 0L).toInt()
            val currentTeamId = snap.getLong("currentTeamId")?.toInt()
            val turnToken = snap.getString("turnToken")
            val startedAt = snap.get("startedAt")

            val scores = snap.get("scoresByTeamId") ?: emptyMap<String, Int>()
            val initialScores = snap.get("initialScoresByTeamId") ?: emptyMap<String, Int>()

            val lastAttackedTeamId = snap.get("lastAttackedTeamId")
            val attackedTargetsByTeamId =
                snap.get("attackedTargetsByTeamId") ?: emptyMap<String, List<Int>>()
            val lastTargetByTeamId = snap.get("lastTargetByTeamId") ?: emptyMap<String, Int>()

            tx.set(
                archiveRef,
                mapOf(
                    "state" to GameState.FINISHED.name,
                    "order" to order,
                    "turnIndex" to turnIndex,
                    "currentTeamId" to currentTeamId,
                    "turnToken" to turnToken,
                    "lastAttackedTeamId" to lastAttackedTeamId,
                    "scoresByTeamId" to scores,
                    "initialScoresByTeamId" to initialScores,
                    "attackedTargetsByTeamId" to attackedTargetsByTeamId,
                    "lastTargetByTeamId" to lastTargetByTeamId,
                    "startedAt" to startedAt,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "sourceLiveGameId" to liveGameId,
                ),
                SetOptions.merge()
            )

            // 라이브 FINISHED 찍고(기록용)
            tx.update(
                liveRef,
                mapOf(
                    "state" to GameState.FINISHED.name,
                    "endedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            // READY 초기화
            tx.set(
                liveRef,
                mapOf(
                    "state" to GameState.READY.name,
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
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge()
            )

            archiveId
        }.addOnSuccessListener { id -> onSuccess(id) }
            .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
    }

    // -------------------------------
    // ✅ 강제로 다음 턴으로 이동
    // -------------------------------
    fun forceAdvanceTurn(gameId: String, onSuccess: () -> Unit, onFail: (Exception) -> Unit) {
        val ref = gameRef(gameId)

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) throw IllegalStateException("게임 문서가 없습니다.")

            val stateStr = snap.getString("state") ?: GameState.READY.name
            val state = runCatching { GameState.valueOf(stateStr) }
                .getOrDefault(GameState.READY)

            if (state != GameState.RUNNING) {
                throw IllegalStateException("RUNNING 상태에서만 가능합니다. (state=$state)")
            }

            val turnIndex = (snap.getLong("turnIndex") ?: 0L).toInt()
            val orderList = (snap.get("order") as? List<Map<String, Any>>).orEmpty()
            if (orderList.isEmpty()) throw IllegalStateException("order가 비어 있습니다.")

            val teamCount = orderList.size
            val maxTurns = teamCount * MAX_ROUNDS
            val nextIndex = turnIndex + 1

            if (nextIndex >= maxTurns) {
                tx.update(
                    ref,
                    mapOf(
                        "state" to GameState.FINISHED.name,
                        "turnIndex" to nextIndex,
                        "currentTeamId" to null,
                        "turnToken" to null,
                        "endedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            } else {
                val nextTeamId = (orderList[nextIndex % teamCount]["teamId"] as? Number)?.toInt()
                    ?: throw IllegalStateException("nextTeamId 파싱 실패")

                tx.update(
                    ref,
                    mapOf(
                        "turnIndex" to nextIndex,
                        "currentTeamId" to nextTeamId,
                        "turnToken" to UUID.randomUUID().toString(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
    }

    // -------------------------------
    // ✅ 공격 턴 제출
    // - 타겟 전력배치 placements와 비교해서 "맞춘 칸"만 점수 차감
    // - 타겟 전력배치에 hitCells 누적
    // - lastAttackedTeamId 갱신 + 턴 진행
    // - attackedTargetsByTeamId / lastTargetByTeamId 업데이트 (DB 유지)
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

        resolvePlacementRef(targetTeamId)
            .addOnSuccessListener { placementRef ->
                db.runTransaction { tx ->
                    val gSnap = tx.get(gRef)
                    if (!gSnap.exists()) throw IllegalStateException("game not found")

                    val state = gSnap.getString("state") ?: GameState.READY.name
                    val currentTeamId = (gSnap.getLong("currentTeamId") ?: -1L).toInt()
                    val serverToken = gSnap.getString("turnToken") ?: ""
                    val turnIndex = (gSnap.getLong("turnIndex") ?: 0L).toInt()

                    val lastAttackedServer = gSnap.getLong("lastAttackedTeamId")?.toInt() ?: -1

                    if (state != GameState.RUNNING.name) throw IllegalStateException("not running")
                    if (currentTeamId != teamId) throw IllegalStateException("not your turn")
                    if (serverToken.isBlank() || serverToken != turnToken) {
                        throw IllegalStateException("invalid turn token")
                    }

                    if (targetTeamId == teamId) throw IllegalStateException("cannot attack self")
                    if (lastAttackedServer != -1 && targetTeamId == lastAttackedServer) {
                        throw IllegalStateException("cannot attack same team twice in a row")
                    }
                    if (prevLast != -1 && prevLast != lastAttackedServer) {
                        throw IllegalStateException("state changed, retry")
                    }

                    // ✅ 타겟 placement 읽기
                    val pSnap = tx.get(placementRef)

                    // placements -> Set key(c:r)
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

                    // 기존 hitCells -> Set key(c:r)
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

                        // 점수차감: 배치에 존재 + 아직 처음 맞음
                        if (onPlacement && !alreadyHit) {
                            if (c in COL_POINTS.indices) damage += COL_POINTS[c]
                        }
                    }

                    // ✅ 점수 반영 (타겟 차감)
                    val scores = parseScoresMap(gSnap.get("scoresByTeamId")).toMutableMap()
                    val prevTargetScore = scores[targetTeamId] ?: 0
                    val nextTargetScore = max(prevTargetScore - damage, 0)
                    scores[targetTeamId] = nextTargetScore

                    // ✅ 턴 진행
                    val orderList = (gSnap.get("order") as? List<Map<String, Any>>).orEmpty()
                    if (orderList.isEmpty()) throw IllegalStateException("order is empty")

                    val teamCount = orderList.size
                    val maxTurns = teamCount * MAX_ROUNDS
                    val nextIndex = turnIndex + 1

                    val updatesGame = mutableMapOf<String, Any?>(
                        "lastAttackedTeamId" to targetTeamId,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "scoresByTeamId" to toFirestoreScoresMap(scores),

                        // ✅ 공격 기록 유지 (팀별)
                        "lastTargetByTeamId.${teamId}" to targetTeamId,
                        "attackedTargetsByTeamId.${teamId}" to FieldValue.arrayUnion(targetTeamId),
                    )

                    if (nextIndex >= maxTurns) {
                        updatesGame["state"] = GameState.FINISHED.name
                        updatesGame["turnIndex"] = nextIndex
                        updatesGame["currentTeamId"] = null
                        updatesGame["turnToken"] = null
                        updatesGame["endedAt"] = FieldValue.serverTimestamp()
                    } else {
                        val nextTeamId =
                            (orderList[nextIndex % teamCount]["teamId"] as? Number)?.toInt()
                                ?: throw IllegalStateException("nextTeamId parse failed")

                        updatesGame["turnIndex"] = nextIndex
                        updatesGame["currentTeamId"] = nextTeamId
                        updatesGame["turnToken"] = UUID.randomUUID().toString()
                    }

                    tx.update(gRef, updatesGame)

                    // ✅ 타겟 placement에 hitCells 저장
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

                    // ✅ 턴 로그
                    val turnLogRef = turnsCol(gameId).document()
                    tx.set(
                        turnLogRef,
                        mapOf(
                            "type" to "attack",
                            "attackerTeamId" to teamId,
                            "targetTeamId" to targetTeamId,
                            "cells" to picks.map { (c, r) -> mapOf("c" to c, "r" to r) },
                            "damage" to damage,
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

    // -------------------------------
    // ✅ 수동 피격 입력 (턴/점수/턴인덱스 건드리지 않음)
    // -------------------------------
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

            val stateStr = snap.getString("state") ?: GameState.READY.name
            val state = runCatching { GameState.valueOf(stateStr) }
                .getOrDefault(GameState.READY)

            if (state != from) throw IllegalStateException("$from 상태에서만 $to 로 변경 가능합니다. (state=$state)")

            tx.update(
                ref,
                mapOf(
                    "state" to to.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            null
        }.addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFail(e as? Exception ?: Exception(e)) }
    }

    // -------------------------------
    // ✅ hitCells 구독 (팀별)
    // -------------------------------
    fun listenHitCellsForTeam(
        teamId: Int,
        onUpdate: (Set<Pair<Int, Int>>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        val ref = db.collection(CANONICAL_PLACEMENTS_COL).document(teamId.toString())
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

    // (기존 호환용 alias)
    fun listenHitCells(
        teamId: Int,
        onUpdate: (Set<Pair<Int, Int>>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration = listenHitCellsForTeam(teamId, onUpdate, onError)

    // -------------------------------
    // ✅ hitCells 수동 추가 (canonical powerPlacements/{teamId})
    // -------------------------------
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

        val ref = db.collection(CANONICAL_PLACEMENTS_COL).document(teamId.toString())
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
}
