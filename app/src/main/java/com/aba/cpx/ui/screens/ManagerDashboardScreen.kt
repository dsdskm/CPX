package com.aba.cpx.ui.screens

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aba.cpx.R
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.Team
import com.aba.cpx.data.model.TeamStatus
import com.aba.cpx.data.repository.AdminRepository
import com.aba.cpx.data.repository.GameRepository
import com.aba.cpx.data.repository.GameRepository.Companion.MAX_ROUNDS
import com.aba.cpx.data.repository.TeamRepository
import kotlinx.coroutines.launch
import kotlin.math.min

// ✅ 라운드별 순서 회전(표시/정렬용) - Team.order 기준
private fun rotateTeamsForRound(
    teamsByOrder: List<Team>,
    turnIndex: Int
): List<Team> {
    if (teamsByOrder.isEmpty()) return teamsByOrder
    val n = teamsByOrder.size
    if (n <= 1) return teamsByOrder
    val round = if (turnIndex >= 0) turnIndex / n else 0
    val offset = ((round % n) + n) % n
    if (offset == 0) return teamsByOrder
    return teamsByOrder.drop(offset) + teamsByOrder.take(offset)
}

@Composable
fun ManagerDashboardScreen(
    onViewPlacement: (teamId: Int, teamName: String, score: Int?) -> Unit,
    onLogout: () -> Unit,
    gameId: String = "default_game"
) {
    val teamRepo = remember { TeamRepository() }
    val gameRepo = remember { GameRepository() }
    val adminRepo = remember { AdminRepository() }

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

    var showLogoutDialog by remember { mutableStateOf(false) }

    var placementScores by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var isPlacementLoading by remember { mutableStateOf(false) }
    var placementErrorMsg by remember { mutableStateOf<String?>(null) }

    var relistenKey by remember { mutableIntStateOf(0) }
    var resetEpoch by remember { mutableIntStateOf(0) }

    val snackHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch {
            snackHostState.currentSnackbarData?.dismiss()
            snackHostState.showSnackbar(msg)
        }
    }

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

    DisposableEffect(gameId, relistenKey) {
        isGameLoading = true
        gameErrorMsg = null

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

    val gameState = game?.state ?: GameState.WAITING
    val teamsByOrder = remember(teams) { teams.sortedBy { it.order } }
    val turnIndex = game?.turnIndex ?: 0

    val allReady = remember(teams) {
        teams.isNotEmpty() && teams.all { it.status == TeamStatus.READY }
    }
    val notReadyTeamsPreview = remember(teams) {
        teams.filter { it.status != TeamStatus.READY }.sortedBy { it.order }
    }

    val currentTeamName = remember(game, teams) {
        val ct = game?.currentTeamId ?: return@remember null
        teams.firstOrNull { it.id == ct }?.name
    }

    val canStart = allReady && !isSubmitting && (gameState == GameState.WAITING)
    val startStateOk = !isSubmitting && (gameState == GameState.WAITING)

    val canPause = !isSubmitting && gameState == GameState.WORKING
    val canResume = !isSubmitting && gameState == GameState.PAUSED
    val canTogglePauseResume = canPause || canResume

    val toggleText = when (gameState) {
        GameState.WORKING -> "중단"
        GameState.PAUSED -> "재개"
        else -> "중단/재개"
    }

    val canFinish = !isSubmitting && (gameState == GameState.WORKING || gameState == GameState.PAUSED)
    val canReset = !isSubmitting && teams.isNotEmpty() && (gameState == GameState.COMPLETED)

    // ✅ WAITING 상태에서 전력배치 점수 로딩
    LaunchedEffect(gameState, teams, resetEpoch) {
        if (gameState != GameState.WAITING) return@LaunchedEffect
        if (teams.isEmpty()) return@LaunchedEffect

        isPlacementLoading = true
        placementErrorMsg = null

        try {
            val sorted = teams.sortedBy { it.order }
            val teamIds = sorted.map { it.id }
            placementScores = adminRepo.loadPlacementScores(teamIds)
        } catch (e: Exception) {
            placementScores = emptyMap()
            placementErrorMsg = e.message ?: "전력배치 점수 로딩 실패"
        } finally {
            isPlacementLoading = false
        }
    }

    // ✅ 라운드 표기
    val roundNumber = remember(gameState, game) {
        if (gameState != GameState.WORKING && gameState != GameState.PAUSED) 0
        else {
            val orderCount = game?.order?.size ?: teamsByOrder.size
            val tIdx = game?.turnIndex ?: 0
            if (orderCount <= 0) 0 else (tIdx / orderCount) + 1
        }
    }
    val roundLabel = if (roundNumber <= 0) "-" else roundNumber.toString()

    val currentTeamText = remember(gameState, currentTeamName) {
        if (gameState != GameState.WORKING && gameState != GameState.PAUSED) ""
        else currentTeamName ?: ""
    }

    // ✅✅✅ 핵심: 팀 리스트 표시 순서를 “이번 라운드 회전 순서”로 변경
    val rotatedTeamsForThisRound = remember(teamsByOrder, turnIndex) {
        rotateTeamsForRound(teamsByOrder, turnIndex)
    }

    // ✅ 화면에 보여줄 팀 리스트
    val shownTeams = remember(gameState, rotatedTeamsForThisRound, teamsByOrder, teams, game) {
        when (gameState) {
            GameState.COMPLETED -> {
                // ✅ 종료 화면은 점수순 유지(원하면 여기도 회전순으로 바꿀 수 있음)
                teams.sortedWith(
                    compareByDescending<Team> { t ->
                        game?.finalScoresByTeamId?.get(t.id)
                            ?: game?.scoresByTeamId?.get(t.id)
                            ?: 0
                    }.thenBy { it.order }
                )
            }
            GameState.WORKING, GameState.PAUSED -> rotatedTeamsForThisRound
            else -> teamsByOrder
        }
    }

    val disabledBg = Color(0xFFE0E0E0)
    val disabledFg = Color(0xFF777777)

    Box(modifier = Modifier.fillMaxSize()) {

        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.10f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "운영 화면",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = { showLogoutDialog = true },
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                        disabledContainerColor = disabledBg,
                        disabledContentColor = disabledFg
                    )
                ) {
                    Text("로그아웃")
                }
            }

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
                            val stateLabelKo = remember(gameState) { gameState.labelKo }
                            val stateColor = when (gameState) {
                                GameState.WAITING -> Color(0xFF9E9E9E)
                                GameState.WORKING -> Color(0xFF2196F3)
                                GameState.PAUSED -> Color(0xFFFF9800)
                                GameState.COMPLETED -> Color(0xFF4CAF50)
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
                                        text = stateLabelKo,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Text(
                                    text = buildString {
                                        append("라운드 ($roundLabel/$MAX_ROUNDS)")
                                        if (currentTeamText.isNotBlank()) append("  ·  현재팀: $currentTeamText")
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // ✅ 이번 라운드 순서 안내(요청한 “회전 순서” 표시)
                            if (gameState == GameState.WORKING || gameState == GameState.PAUSED) {
                                Spacer(Modifier.height(8.dp))
                                val seq = rotatedTeamsForThisRound.joinToString(" → ") { it.name }
                                Text(
                                    text = "이번 라운드 순서: $seq",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF444444),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

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
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(shownTeams, key = { it.id }) { t ->
                            val scoreValue: Int = when (gameState) {
                                GameState.WAITING -> placementScores[t.id] ?: 0
                                GameState.COMPLETED -> {
                                    game?.finalScoresByTeamId?.get(t.id)
                                        ?: game?.scoresByTeamId?.get(t.id)
                                        ?: 0
                                }
                                else -> game?.scoresByTeamId?.get(t.id) ?: 0
                            }

                            val initialScore: Int = when (gameState) {
                                GameState.WAITING -> placementScores[t.id] ?: 0
                                else -> game?.initialScoresByTeamId?.get(t.id) ?: 0
                            }
                            val damageTaken: Int = game?.damageTakenByTeamId?.get(t.id) ?: 0
                            val bonus: Int = game?.bonusByTeamId?.get(t.id) ?: 0

                            TeamStatusRow(
                                team = t,
                                score = scoreValue,
                                initialScore = initialScore,
                                damageTaken = damageTaken,
                                bonus = bonus,
                                isCurrentTurn = (
                                        (gameState == GameState.WORKING || gameState == GameState.PAUSED)
                                                && game?.currentTeamId == t.id
                                        ),
                                onViewPlacement = { teamId2, teamName2, scoreOrNull ->
                                    onViewPlacement(teamId2, teamName2, scoreOrNull)
                                }
                            )
                        }

                        if (gameState == GameState.WAITING) {
                            item {
                                when {
                                    isPlacementLoading -> Text(
                                        text = "전력배치 점수 계산중...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (!startStateOk) {
                            toast("게임 시작은 대기중 상태에서만 가능합니다.")
                            return@Button
                        }
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
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = disabledBg,
                        disabledContentColor = disabledFg
                    )
                ) { Text(if (isSubmitting) "처리중..." else "게임 시작") }

                OutlinedButton(
                    onClick = {
                        if (!canTogglePauseResume) return@OutlinedButton
                        isSubmitting = true

                        if (gameState == GameState.WORKING) {
                            gameRepo.pauseGame(
                                gameId = gameId,
                                onSuccess = {
                                    isSubmitting = false
                                    toast("일시중지 했습니다.")
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
                                    toast("재개 했습니다.")
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
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = disabledBg,
                        disabledContentColor = disabledFg
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = canTogglePauseResume)
                ) { Text(toggleText) }

                Button(
                    onClick = { showFinishConfirm = true },
                    enabled = canFinish,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                        disabledContainerColor = disabledBg,
                        disabledContentColor = disabledFg
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) { Text("종료") }

                OutlinedButton(
                    onClick = {
                        if (!canReset) {
                            toast("초기화는 게임이 '종료(completed)' 상태일 때만 가능합니다.")
                            return@OutlinedButton
                        }
                        showResetConfirm = true
                    },
                    enabled = canReset,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = disabledBg,
                        disabledContentColor = disabledFg
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = canReset)
                ) { Text("초기화") }
            }
        }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text("로그아웃") },
                text = { Text("로그아웃 하시겠습니까?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showLogoutDialog = false
                            onLogout()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("로그아웃", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) { Text("취소") }
                }
            )
        }

        if (showStartConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showStartConfirm = false },
                title = { Text("게임 시작") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("정해진 순서대로 게임을 시작합니다.\n전력배치 점수로 초기 점수를 세팅합니다.")
                        if (!allReady) {
                            val names = notReadyTeamsPreview.joinToString(", ") { it.name }
                            Text(
                                text = if (names.isBlank())
                                    "⚠️ 모든 팀이 준비 완료여야 시작할 수 있습니다."
                                else
                                    "⚠️ 모든 팀이 준비 완료여야 시작할 수 있습니다.\n미준비: $names",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (teamsByOrder.isNotEmpty()) {
                            Text("1라운드 시작 순서:", style = MaterialTheme.typography.titleSmall)
                            Text(
                                teamsByOrder.joinToString(" → ") { it.name },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "※ 라운드가 바뀌면 시작 팀이 1칸씩 회전합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = canStart,
                        onClick = {
                            isSubmitting = true
                            val teamsInOrder = teams.sortedBy { it.order }

                            gameRepo.startGame(
                                gameId = gameId,
                                teamsInOrder = teamsInOrder,
                                onSuccess = {
                                    isSubmitting = false
                                    showStartConfirm = false
                                    toast("게임을 시작했습니다.")
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
                    TextButton(enabled = !isSubmitting, onClick = { showStartConfirm = false }) { Text("아니오") }
                }
            )
        }

        if (showFinishConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showFinishConfirm = false },
                title = { Text("게임 종료") },
                text = { Text("게임 결과를 저장한 후, 게임/팀 상태를 완료로 변경합니다.\n진행할까요?\n지난 게임 결과는 사이트에서 확인이 가능합니다.") },
                confirmButton = {
                    Button(
                        enabled = canFinish && !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            isSubmitting = true
                            scope.launch {
                                try {
                                    val archiveId = adminRepo.finishGameAndArchiveAndCompleteTeams(gameId)
                                    isSubmitting = false
                                    showFinishConfirm = false
                                    toast("종료 완료 + 스냅샷 저장: $archiveId")
                                } catch (e: Exception) {
                                    isSubmitting = false
                                    showFinishConfirm = false
                                    toast("종료 실패: ${e.message ?: "unknown"}")
                                }
                            }
                        }
                    ) { Text(if (isSubmitting) "처리중..." else "종료") }
                },
                dismissButton = {
                    TextButton(enabled = !isSubmitting, onClick = { showFinishConfirm = false }) { Text("취소") }
                }
            )
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showResetConfirm = false },
                title = { Text("전체 초기화") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("전체 게임 데이터와 팀 데이터를 초기화 하시겠습니까?")
                        Text("※ 되돌릴 수 없습니다.", color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    Button(
                        enabled = canReset && !isSubmitting,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            isSubmitting = true
                            scope.launch {
                                try {
                                    adminRepo.resetAll(gameId)
                                    Log.d("KKH", "reset done, gameId=$gameId")

                                    placementScores = emptyMap()
                                    placementErrorMsg = null
                                    isPlacementLoading = false

                                    game = null
                                    isGameLoading = true
                                    gameErrorMsg = null

                                    relistenKey++
                                    resetEpoch++

                                    isSubmitting = false
                                    showResetConfirm = false
                                    toast("초기화 완료")
                                } catch (e: Exception) {
                                    isSubmitting = false
                                    showResetConfirm = false
                                    toast("초기화 실패: ${e.message ?: "unknown"}")
                                }
                            }
                        }
                    ) { Text(if (isSubmitting) "처리중..." else "초기화") }
                },
                dismissButton = {
                    TextButton(enabled = !isSubmitting, onClick = { showResetConfirm = false }) { Text("취소") }
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
    initialScore: Int,
    damageTaken: Int,
    bonus: Int,
    isCurrentTurn: Boolean,
    onViewPlacement: (teamId: Int, teamName: String, score: Int?) -> Unit
) {
    val statusKo = remember(team.status) { team.status.labelKo }

    val statusColor = remember(team.status) {
        when (team.status) {
            TeamStatus.PREPARING -> Color(0xFFFFC107)
            TeamStatus.READY -> Color(0xFF4CAF50)
            TeamStatus.WORKING -> Color(0xFF2196F3)
            TeamStatus.COMPLETED -> Color(0xFF9E9E9E)
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
                    text = "${team.order}번 ${team.name}  ·  ${score}점",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "${initialScore}(최초점수) - ${damageTaken}(피격) + ${bonus}(명중) = ${score}점",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                            Text("현재 턴", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Button(onClick = { onViewPlacement(team.id, team.name, score) }) {
                Text("전력 배치 보기")
            }
        }
    }
}