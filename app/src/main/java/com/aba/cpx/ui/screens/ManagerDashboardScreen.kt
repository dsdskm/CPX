// ===============================
// ManagerDashboardScreen.kt (전체 수정본)
// - 기존 기능 유지
// - ✅ "초기화" 버튼 추가
//   -> 모든 팀 status를 waiting으로
//   -> powerPlacements/{teamId} 문서를 초기값으로 reset
// - ✅ suspend+await 방식으로 배치 처리
// - ✅ 게임 시작 성공 시: READY였던 팀들의 status를 모두 working으로 변경
// - ✅ 운영자가 게임 종료 시키면: 모든 팀 status를 finished로 변경
// - ✅ 초기화 버튼은 "종료된 상태(FINISHED)"에서만 활성화
// ===============================
package com.aba.cpx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.Team
import com.aba.cpx.data.repository.GameRepository
import com.aba.cpx.data.repository.TeamRepository
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun ManagerDashboardScreen(
    onViewPlacement: (teamId: Int, teamName: String, score: Int?) -> Unit,
    gameId: String = "default_game"
) {
    val teamRepo = remember { TeamRepository() }
    val gameRepo = remember { GameRepository() }
    val db = remember { FirebaseFirestore.getInstance() }

    var teams by remember { mutableStateOf<List<Team>>(emptyList()) }
    var isTeamsLoading by remember { mutableStateOf(true) }
    var teamsErrorMsg by remember { mutableStateOf<String?>(null) }

    var game by remember { mutableStateOf<Game?>(null) }
    var isGameLoading by remember { mutableStateOf(true) }
    var gameErrorMsg by remember { mutableStateOf<String?>(null) }

    var showStartConfirm by remember { mutableStateOf(false) }
    var showFinishConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    // ✅ READY 상태에서도 보여줄 "전력배치 기반 시작점수" 캐시
    var placementScores by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var isPlacementLoading by remember { mutableStateOf(false) }
    var placementErrorMsg by remember { mutableStateOf<String?>(null) }

    val snackHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch {
            snackHostState.currentSnackbarData?.dismiss()
            snackHostState.showSnackbar(msg)
        }
    }

    // ✅ teams 실시간 구독
    DisposableEffect(Unit) {
        val reg = teamRepo.listenTeams(
            onUpdate = { list ->
                teams = list
                isTeamsLoading = false
                teamsErrorMsg = null
            },
            onError = { e ->
                teamsErrorMsg = e.message ?: "팀 상태를 불러오지 못했습니다."
                isTeamsLoading = false
            }
        )
        onDispose { reg.remove() }
    }

    // ✅ game 실시간 구독
    DisposableEffect(gameId) {
        gameRepo.ensureLiveGameExists(gameId)
        val reg = gameRepo.listenGame(
            gameId = gameId,
            onUpdate = { g ->
                game = g
                isGameLoading = false
                gameErrorMsg = null
            },
            onError = { e ->
                gameErrorMsg = e.message ?: "게임 상태를 불러오지 못했습니다."
                isGameLoading = false
            }
        )
        onDispose { reg.remove() }
    }

    val gameState = game?.state ?: GameState.READY
    val teamsInOrderPreview = remember(teams) { teams.sortedBy { it.order } }

    // ✅ 모든 팀 READY 여부
    val allReady = remember(teams) {
        teams.isNotEmpty() && teams.all { it.status.lowercase() == "ready" }
    }
    val notReadyTeamsPreview = remember(teams) {
        teams.filter { it.status.lowercase() != "ready" }.sortedBy { it.order }
    }

    val currentTeamName = remember(game, teams) {
        val ct = game?.currentTeamId ?: return@remember null
        teams.firstOrNull { it.id == ct }?.name
    }

    // ✅ 게임 시작 가능 조건(상태 + allReady)
    val canStart =
        allReady &&
                !isSubmitting &&
                (gameState == GameState.READY || gameState == GameState.FINISHED || gameState == GameState.ABORTED)

    // ✅ 버튼 눌러서 안내 가능
    val startStateOk =
        !isSubmitting && (gameState == GameState.READY || gameState == GameState.FINISHED || gameState == GameState.ABORTED)

    // ✅ 중단/재개(토글)
    val canPause = !isSubmitting && gameState == GameState.RUNNING
    val canResume = !isSubmitting && gameState == GameState.PAUSED
    val canTogglePauseResume = canPause || canResume

    val toggleText = when (gameState) {
        GameState.RUNNING -> "중단"
        GameState.PAUSED -> "재개"
        else -> "중단/재개"
    }

    val canFinish = !isSubmitting && (gameState == GameState.RUNNING || gameState == GameState.PAUSED)

    // ✅ 초기화 버튼: "종료된 상태(FINISHED)"에서만 활성화
    val canReset = !isSubmitting && teams.isNotEmpty() && gameState == GameState.FINISHED

    // --------------------------------------------
    // ✅ READY 상태에서 미리 전력배치 점수 로딩
    // --------------------------------------------
    LaunchedEffect(gameState, teams) {
        if (gameState != GameState.READY) return@LaunchedEffect
        if (teams.isEmpty()) return@LaunchedEffect

        isPlacementLoading = true
        placementErrorMsg = null

        val colPoints = listOf(10, 10, 8, 8, 6, 6, 4, 4, 2, 2)

        fun computePlacementScore(doc: DocumentSnapshot?): Int {
            if (doc == null || !doc.exists()) return 0
            val placements = doc.get("placements") as? List<*> ?: return 0
            var sum = 0
            placements.forEach { pAny ->
                val p = pAny as? Map<*, *> ?: return@forEach
                val cells = p["cells"] as? List<*> ?: return@forEach
                cells.forEach { cAny ->
                    val cm = cAny as? Map<*, *> ?: return@forEach
                    val c = (cm["c"] as? Number)?.toInt() ?: return@forEach
                    if (c in colPoints.indices) sum += colPoints[c]
                }
            }
            return sum
        }

        val colCandidates = listOf("powerPlacements", "powerPlacement", "power_placements")

        fun fetchPlacement(teamId: Int): Task<DocumentSnapshot?> {
            fun tryOne(col: String): Task<DocumentSnapshot?> {
                val direct = db.collection(col).document(teamId.toString())
                return direct.get().continueWithTask { t ->
                    val snap = t.result
                    if (snap != null && snap.exists()) {
                        Tasks.forResult(snap)
                    } else {
                        db.collection(col)
                            .whereEqualTo("teamId", teamId)
                            .limit(1)
                            .get()
                            .continueWith { qs ->
                                qs.result?.documents?.firstOrNull()
                            }
                    }
                }
            }

            var chain: Task<DocumentSnapshot?> = Tasks.forResult(null)
            colCandidates.forEach { col ->
                chain = chain.continueWithTask { prev ->
                    val prevSnap = prev.result
                    if (prevSnap != null && prevSnap.exists()) Tasks.forResult(prevSnap)
                    else tryOne(col)
                }
            }
            return chain
        }

        val sorted = teams.sortedBy { it.order }
        val tasks = sorted.map { t -> fetchPlacement(t.id) }

        Tasks.whenAllSuccess<DocumentSnapshot?>(tasks)
            .addOnSuccessListener { snaps ->
                val m = mutableMapOf<Int, Int>()
                sorted.forEachIndexed { idx, team ->
                    val doc = snaps.getOrNull(idx)
                    m[team.id] = computePlacementScore(doc)
                }
                placementScores = m
                isPlacementLoading = false
            }
            .addOnFailureListener { e ->
                placementScores = emptyMap()
                isPlacementLoading = false
                placementErrorMsg = e.message ?: "전력배치 점수 로딩 실패"
            }
    }

    // ✅ 상단 1줄에 필요한 라운드 계산
    val roundText = remember(gameState, game) {
        if (gameState != GameState.RUNNING) return@remember "-"
        val orderCount = game?.order?.size ?: 0
        val turnIndex = game?.turnIndex ?: 0
        if (orderCount <= 0) "-" else "${(turnIndex / orderCount) + 1}R"
    }

    val currentTeamText = remember(gameState, game, currentTeamName) {
        if (gameState != GameState.RUNNING) "-"
        else currentTeamName ?: (game?.currentTeamId?.toString() ?: "-")
    }

    // --------------------------------------------
    // ✅ 초기화(suspend): 팀 status=waiting + powerPlacements 초기화
    // --------------------------------------------
    suspend fun runResetAllSuspend(targetTeams: List<Team>) {
        if (targetTeams.isEmpty()) throw IllegalStateException("팀이 없습니다.")
        fun <T> List<T>.chunked450(): List<List<T>> = this.chunked(450)

        targetTeams.chunked450().forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { t ->
                val ref = db.collection("teams").document(t.id.toString())
                batch.set(
                    ref,
                    mapOf("status" to "waiting", "updatedAt" to FieldValue.serverTimestamp()),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
        }

        targetTeams.map { it.id }.chunked450().forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { teamId ->
                val ref = db.collection("powerPlacements").document(teamId.toString())
                batch.set(
                    ref,
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

    // --------------------------------------------
    // ✅ 게임 시작 성공 시: READY였던 팀들만 working으로 변경 (suspend)
    // --------------------------------------------
    suspend fun setReadyTeamsToWorkingSuspend(targetTeams: List<Team>) {
        val readyTeams = targetTeams.filter { it.status.equals("ready", ignoreCase = true) }
        if (readyTeams.isEmpty()) return
        fun <T> List<T>.chunked450(): List<List<T>> = this.chunked(450)

        readyTeams.chunked450().forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { t ->
                val ref = db.collection("teams").document(t.id.toString())
                batch.set(
                    ref,
                    mapOf("status" to "working", "updatedAt" to FieldValue.serverTimestamp()),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
        }
    }

    // --------------------------------------------
    // ✅ 게임 종료 성공 시: 모든 팀 status=finished (suspend)
    // --------------------------------------------
    suspend fun setAllTeamsToFinishedSuspend(targetTeams: List<Team>) {
        if (targetTeams.isEmpty()) return
        fun <T> List<T>.chunked450(): List<List<T>> = this.chunked(450)

        targetTeams.chunked450().forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { t ->
                val ref = db.collection("teams").document(t.id.toString())
                batch.set(
                    ref,
                    mapOf("status" to "finished", "updatedAt" to FieldValue.serverTimestamp()),
                    SetOptions.merge()
                )
            }
            batch.commit().await()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F0F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "운영 화면", style = MaterialTheme.typography.titleLarge)

            // ✅ 게임 상태 카드 (한 줄만)
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    when {
                        isGameLoading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) { CircularProgressIndicator() }
                        }

                        gameErrorMsg != null -> {
                            Text(
                                text = gameErrorMsg!!,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }

                        else -> {
                            val stateLabelKo = remember(gameState) {
                                when (gameState) {
                                    GameState.READY -> "대기"
                                    GameState.RUNNING -> "진행"
                                    GameState.PAUSED -> "일시중지"
                                    GameState.FINISHED -> "종료"
                                    GameState.ABORTED -> "중단됨"
                                    else -> gameState.name
                                }
                            }
                            val cs = MaterialTheme.colorScheme
                            val stateColor = when (gameState) {
                                GameState.READY -> Color(0xFF9E9E9E)
                                GameState.RUNNING -> Color(0xFF2196F3)
                                GameState.PAUSED -> Color(0xFFFF9800)
                                GameState.FINISHED -> Color(0xFF4CAF50)
                                GameState.ABORTED -> cs.error
                                else -> Color(0xFF9E9E9E)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(stateColor)
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "상태: $stateLabelKo",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Text(
                                    text = "라운드: $roundText  ·  현재팀: $currentTeamText",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // ✅ 팀 리스트
            when {
                isTeamsLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator() }
                }

                teamsErrorMsg != null -> {
                    Text(
                        text = teamsErrorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                teams.isEmpty() -> {
                    Text(
                        text = "팀이 없습니다.",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(teams.sortedBy { it.order }, key = { it.id }) { t ->
                            val score: Int = when (gameState) {
                                GameState.READY -> placementScores[t.id] ?: 0
                                else -> game?.scoresByTeamId?.get(t.id) ?: 0
                            }

                            TeamStatusRow(
                                team = t,
                                score = score,
                                isCurrentTurn = (gameState == GameState.RUNNING && game?.currentTeamId == t.id),
                                onViewPlacement = { teamId, teamName, scoreOrNull ->
                                    onViewPlacement(teamId, teamName, scoreOrNull)
                                }
                            )
                        }

                        if (gameState == GameState.READY) {
                            item {
                                when {
                                    isPlacementLoading -> Text(
                                        text = "전력배치 점수 계산중...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF666666),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )

                                    placementErrorMsg != null -> Text(
                                        text = "전력배치 점수 오류: $placementErrorMsg",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ✅ 하단 버튼(1줄/균등) - 4개
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (!startStateOk) return@Button
                        if (!allReady) {
                            val names = notReadyTeamsPreview.joinToString(", ") { it.name }
                            toast(
                                if (names.isBlank())
                                    "모든 팀이 준비 완료(READY)여야 게임을 시작할 수 있습니다."
                                else
                                    "모든 팀이 준비 완료(READY)여야 시작 가능\n미준비: $names"
                            )
                        } else {
                            showStartConfirm = true
                        }
                    },
                    enabled = startStateOk,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text(if (isSubmitting) "처리중..." else "게임 시작") }

                OutlinedButton(
                    onClick = {
                        if (!canTogglePauseResume) return@OutlinedButton
                        isSubmitting = true

                        if (gameState == GameState.RUNNING) {
                            gameRepo.pauseGame(
                                gameId = gameId,
                                onSuccess = {
                                    isSubmitting = false
                                    toast("일시중지(PAUSED) 했습니다.")
                                },
                                onFail = { e ->
                                    isSubmitting = false
                                    toast("실패: ${e.message ?: "unknown"}")
                                }
                            )
                        } else if (gameState == GameState.PAUSED) {
                            gameRepo.resumeGame(
                                gameId = gameId,
                                onSuccess = {
                                    isSubmitting = false
                                    toast("재개(RUNNING) 했습니다.")
                                },
                                onFail = { e ->
                                    isSubmitting = false
                                    toast("실패: ${e.message ?: "unknown"}")
                                }
                            )
                        } else {
                            isSubmitting = false
                            toast("현재 상태에서는 중단/재개를 할 수 없습니다.")
                        }
                    },
                    enabled = canTogglePauseResume,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text(toggleText) }

                Button(
                    onClick = { showFinishConfirm = true },
                    enabled = canFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("종료") }

                // ✅ 초기화 버튼: FINISHED에서만 enabled
                OutlinedButton(
                    onClick = {
                        if (!canReset) {
                            toast("초기화는 게임이 '종료(FINISHED)' 상태일 때만 가능합니다.")
                            return@OutlinedButton
                        }
                        showResetConfirm = true
                    },
                    enabled = canReset,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("초기화") }
            }
        }

        // ✅ 시작 Confirm
        if (showStartConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showStartConfirm = false },
                title = { Text("게임 시작") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("팀 order 순서대로 게임을 시작합니다.\n전력배치 점수로 초기 점수를 세팅합니다.")

                        if (!allReady) {
                            val names = notReadyTeamsPreview.joinToString(", ") { it.name }
                            Text(
                                text = if (names.isBlank())
                                    "⚠️ 모든 팀이 준비 완료(READY)여야 시작할 수 있습니다."
                                else
                                    "⚠️ 모든 팀이 준비 완료(READY)여야 시작할 수 있습니다.\n미준비: $names",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (teamsInOrderPreview.isNotEmpty()) {
                            Text("시작 순서:", style = MaterialTheme.typography.titleSmall)
                            Text(
                                teamsInOrderPreview.joinToString(" → ") { it.name },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = canStart,
                        onClick = {
                            if (!allReady) {
                                val names = notReadyTeamsPreview.joinToString(", ") { it.name }
                                toast(
                                    if (names.isBlank())
                                        "모든 팀이 준비 완료(READY)여야 게임을 시작할 수 있습니다."
                                    else
                                        "모든 팀이 준비 완료(READY)여야 시작 가능\n미준비: $names"
                                )
                                return@Button
                            }

                            isSubmitting = true
                            val snapshotTeams = teams
                            val teamsInOrder = snapshotTeams.sortedBy { it.order }

                            gameRepo.startGame(
                                gameId = gameId,
                                teamsInOrder = teamsInOrder,
                                onSuccess = {
                                    scope.launch {
                                        try {
                                            setReadyTeamsToWorkingSuspend(snapshotTeams)
                                            isSubmitting = false
                                            showStartConfirm = false
                                            toast("게임을 시작했습니다. (RUNNING) / 팀 상태: WORKING")
                                        } catch (e: Exception) {
                                            isSubmitting = false
                                            showStartConfirm = false
                                            toast("게임은 시작됐지만 팀 상태 변경 실패: ${e.message ?: "unknown"}")
                                        }
                                    }
                                },
                                onFail = { e ->
                                    isSubmitting = false
                                    toast("실패: ${e.message ?: "unknown"}")
                                }
                            )
                        }
                    ) { Text("예") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isSubmitting,
                        onClick = { showStartConfirm = false }
                    ) { Text("아니오") }
                }
            )
        }

        // ✅ 종료 Confirm
        if (showFinishConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showFinishConfirm = false },
                title = { Text("게임 종료") },
                text = { Text("게임을 종료하고 기록 저장 후 라이브 게임을 READY로 초기화합니다.\n진행할까요?") },
                confirmButton = {
                    Button(
                        enabled = canFinish,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            isSubmitting = true
                            val snapshotTeams = teams

                            gameRepo.finishGameWithArchiveAndReset(
                                liveGameId = gameId,
                                onSuccess = { archiveId ->
                                    scope.launch {
                                        try {
                                            setAllTeamsToFinishedSuspend(snapshotTeams)
                                            isSubmitting = false
                                            showFinishConfirm = false
                                            toast("종료 완료. 기록 저장: $archiveId / 팀 상태: FINISHED")
                                        } catch (e: Exception) {
                                            isSubmitting = false
                                            showFinishConfirm = false
                                            toast("게임은 종료됐지만 팀 상태 변경 실패: ${e.message ?: "unknown"}")
                                        }
                                    }
                                },
                                onFail = { e ->
                                    isSubmitting = false
                                    toast("실패: ${e.message ?: "unknown"}")
                                }
                            )
                        }
                    ) { Text("종료") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isSubmitting,
                        onClick = { showFinishConfirm = false }
                    ) { Text("취소") }
                }
            )
        }

        // ✅ 초기화 Confirm
        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showResetConfirm = false },
                title = { Text("전체 초기화") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("아래 작업을 수행합니다.")
                        Text("1) 모든 팀 상태 → waiting", style = MaterialTheme.typography.bodyMedium)
                        Text("2) powerPlacements 모든 문서 → 초기값(placements/hitCells 비움)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "※ 되돌릴 수 없습니다.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = canReset && !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            isSubmitting = true
                            val snapshotTeams = teams

                            scope.launch {
                                try {
                                    runResetAllSuspend(snapshotTeams)
                                    isSubmitting = false
                                    showResetConfirm = false
                                    placementScores = emptyMap()
                                    toast("초기화 완료: 팀 waiting / powerPlacements 초기화")
                                } catch (e: Exception) {
                                    isSubmitting = false
                                    toast("초기화 실패: ${e.message ?: "unknown"}")
                                }
                            }
                        }
                    ) { Text(if (isSubmitting) "처리중..." else "초기화") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isSubmitting,
                        onClick = { showResetConfirm = false }
                    ) { Text("취소") }
                }
            )
        }

        SnackbarHost(
            hostState = snackHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun TeamStatusRow(
    team: Team,
    score: Int,
    isCurrentTurn: Boolean,
    onViewPlacement: (teamId: Int, teamName: String, score: Int?) -> Unit
) {
    val statusKey = remember(team.status) { team.status.lowercase() }

    val statusKo = remember(statusKey) {
        when (statusKey) {
            "waiting" -> "대기중"
            "ready" -> "준비 완료"
            "working" -> "진행중"
            "finished" -> "종료"
            "completed" -> "완료"
            "paused" -> "중지"
            else -> team.status
        }
    }

    val statusColor = remember(statusKey) {
        when (statusKey) {
            "waiting" -> Color(0xFFFFC107)
            "ready" -> Color(0xFF4CAF50)
            "working" -> Color(0xFF2196F3)
            "finished" -> Color(0xFF4CAF50)
            else -> Color(0xFF9E9E9E)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${team.order}. ${team.name}  ·  ${score}점",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(text = statusKo, style = MaterialTheme.typography.bodyMedium)

                    if (isCurrentTurn) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF7C4DFF))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "현재 턴",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onViewPlacement(team.id, team.name, score) }
            ) {
                Text("전력 배치 보기")
            }
        }
    }
}
