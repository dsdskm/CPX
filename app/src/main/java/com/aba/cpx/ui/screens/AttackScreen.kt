package com.aba.cpx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aba.cpx.data.model.ColumnHeader
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.headers
import com.aba.cpx.data.model.rows
import com.aba.cpx.data.repository.GameRepository
import kotlinx.coroutines.launch


@Composable
fun AttackScreen(
    teamId: Int,
    teamName: String,
    gameId: String = "default_game",
    onSubmitted: () -> Unit,
    onNotMyTurn: () -> Unit,
    onGoMyStrategy: () -> Unit,
) {

    val colsCount = headers.size
    val required = 3

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val repo = remember { GameRepository() }

    var game by remember { mutableStateOf<Game?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // ✅ 공격 선택(3칸)
    var attackPicks by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }

    // ✅ 공격 대상 팀 (GameInfoBar에서 선택)
    var selectedTargetId by remember { mutableStateOf<Int?>(null) }

    // ✅ 선택 타겟 팀의 피격 현황(💥 표시용)
    var hitCells by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }

    // ✅ 공격 최종 확인
    var showConfirm by remember { mutableStateOf(false) }

    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(msg)
        }
    }

    // ✅ 게임 구독
    DisposableEffect(gameId) {
        val reg = repo.listenGame(
            gameId = gameId,
            onUpdate = { g -> game = g },
            onError = { e -> toast("게임 구독 오류: ${e.message ?: "unknown"}") }
        )
        onDispose { reg.remove() }
    }

    // ✅ 상태 플래그
    val isPaused = (game?.state == GameState.PAUSED)
    val isWorking = (game?.state == GameState.WORKING)

    // ✅ 내 턴(working + currentTeamId 일치)
    val isMyTurn = (isWorking && game?.currentTeamId == teamId)

    // ✅ 내 턴 아니면 콜백(working에서만 의미)
    LaunchedEffect(game?.state, game?.currentTeamId) {
        val g = game ?: return@LaunchedEffect
        if (g.state != GameState.WORKING) return@LaunchedEffect
        if (g.currentTeamId != teamId) onNotMyTurn()
    }

    // ✅ 팀 목록 (order 기반)
    val allTeams: List<Pair<Int, String>> = remember(game, teamId, teamName) {
        val ordered = game?.order.orEmpty()
            .sortedBy { it.order }
            .map { it.teamId to it.teamName }
        if (ordered.isNotEmpty()) ordered else listOf(teamId to teamName)
    }

    // ✅ 기본 타겟 세팅
    LaunchedEffect(game?.updatedAt, allTeams) {
        val g = game ?: return@LaunchedEffect
        if (selectedTargetId != null && allTeams.any { it.first == selectedTargetId }) return@LaunchedEffect

        val last = g.lastTargetByTeamId[teamId]
        val candidate = last?.takeIf { id ->
            id != teamId && allTeams.any { it.first == id }
        }
        selectedTargetId = candidate ?: allTeams.firstOrNull { it.first != teamId }?.first
    }

    // ✅ 타겟 변경 시: 선택 초기화 + 피격 구독 대상 변경
    DisposableEffect(selectedTargetId) {
        val tid = selectedTargetId

        attackPicks = emptyList()
        hitCells = emptySet()

        if (tid == null) {
            onDispose { }
        } else {
            val reg = repo.listenHitCellsForTeam(
                teamId = tid,
                onUpdate = { set -> hitCells = set },
                onError = { e -> toast("피격 좌표 구독 오류: ${e.message ?: "unknown"}") }
            )
            onDispose { reg.remove() }
        }
    }

    fun toggleCell(c: Int, r: Int) {
        // ✅ paused면 공격/선택 불가
        if (isPaused) {
            toast("게임이 일시중지(paused) 상태입니다. 재개 후 공격할 수 있습니다.")
            return
        }

        val key = c to r

        if (!isMyTurn) {
            toast("지금은 공격 차례가 아닙니다.")
            return
        }

        val tId = selectedTargetId
        if (tId == null) {
            toast("공격 대상 팀을 선택하세요.")
            return
        }
        if (tId == teamId) {
            toast("내 팀은 공격 대상이 될 수 없습니다.")
            return
        }
        if (isSubmitting) return

        if (attackPicks.contains(key)) {
            attackPicks = attackPicks.filterNot { it == key }
            return
        }
        if (attackPicks.size >= required) {
            toast("공격은 ${required}칸까지 선택할 수 있습니다.")
            return
        }
        attackPicks = attackPicks + key
    }

    val lastAttackedTeamId = game?.lastAttackedTeamId
    val tId = selectedTargetId

    // ✅ paused/working 상태를 명시적으로 반영
    val canAttack =
        isWorking &&             // ✅ WORKING일 때만 공격 가능
                isMyTurn &&
                !isSubmitting &&
                tId != null &&
                tId != teamId &&
                (lastAttackedTeamId == null || tId != lastAttackedTeamId) &&
                attackPicks.size == required

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F0F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GameInfoBar(
                game = game,
                myTeamId = teamId,
                myTeamName = teamName,
                mode = GameInfoBarMode.ATTACK,
                overrideOrder = allTeams,
                selectedTargetId = selectedTargetId,
                onTargetSelected = if (isMyTurn && !isSubmitting && !isPaused) {
                    { newId ->
                        if (newId == teamId) {
                            toast("내 팀은 공격 대상이 될 수 없습니다.")
                            return@GameInfoBar
                        }
                        if (lastAttackedTeamId != null && newId == lastAttackedTeamId) {
                            toast("직전 공격당한 팀은 연속으로 공격할 수 없습니다.")
                            return@GameInfoBar
                        }
                        selectedTargetId = newId
                    }
                } else null
            )

            // 그리드
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentSize()
                        .background(Color.White)
                        .border(1.dp, Color(0xFF999999))
                        .horizontalScroll(hScroll)
                        .verticalScroll(vScroll)
                        .padding(8.dp)
                ) {
                    Column {
                        Row {
                            AttackCellHeader("", 92.dp, 56.dp)
                            headers.forEach { AttackCellHeader("${it.title}\n${it.scoreText}", 88.dp, 56.dp) }
                        }

                        rows.forEachIndexed { r, rowName ->
                            Row {
                                AttackCellHeader(rowName, 92.dp, 44.dp)

                                for (c in 0 until colsCount) {
                                    val key = c to r
                                    val isHit = hitCells.contains(key)
                                    val isAttackPick = attackPicks.contains(key)

                                    AttackCell(
                                        width = 88.dp,
                                        height = 44.dp,
                                        background = Color.White,
                                        isHit = isHit,
                                        isAttackPick = isAttackPick,
                                        onClick = { toggleCell(c, r) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 하단 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onGoMyStrategy() },
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("나의 전략 확인") }

                Button(
                    onClick = {
                        if (isPaused) {
                            toast("게임이 일시중지(paused) 상태입니다. 재개 후 공격할 수 있습니다.")
                            return@Button
                        }
                        showConfirm = true
                    },
                    enabled = canAttack,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text(if (isSubmitting) "전송중..." else "공격(${attackPicks.size}/$required)") }
            }
        }

        // 공격 최종 확인
        if (showConfirm) {
            val g = game
            val target = tId
            val targetName = allTeams.firstOrNull { it.first == target }?.second ?: (target?.toString() ?: "-")

            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showConfirm = false },
                title = { Text("공격 제출") },
                text = { Text("선택한 ${required}칸으로 [$targetName] 팀을 공격할까요?\n※ 한 팀은 두 번 연속 공격당할 수 없습니다.") },
                confirmButton = {
                    Button(
                        enabled = canAttack && target != null && !isPaused,
                        onClick = {
                            if (isPaused) {
                                toast("게임이 일시중지(paused) 상태입니다. 재개 후 공격할 수 있습니다.")
                                return@Button
                            }

                            val token = g?.turnToken
                            if (g == null || token.isNullOrBlank()) {
                                toast("게임 정보(turnToken)가 없습니다.")
                                return@Button
                            }

                            val currentLast = g.lastAttackedTeamId
                            if (currentLast != null && target == currentLast) {
                                toast("직전 공격당한 팀은 공격할 수 없습니다.")
                                return@Button
                            }

                            showConfirm = false
                            isSubmitting = true

                            val payload = mapOf(
                                "type" to "attack",
                                "targetTeamId" to target,
                                "prevLastAttackedTeamId" to (currentLast ?: -1),
                                "cells" to attackPicks.map { (c, r) -> mapOf("c" to c, "r" to r) }
                            )

                            repo.submitTurn(
                                gameId = gameId,
                                teamId = teamId,
                                turnToken = token,
                                payload = payload,
                                onSuccess = {
                                    hitCells = hitCells + attackPicks.toSet()
                                    attackPicks = emptyList()
                                    isSubmitting = false
                                    toast("공격 제출 완료!")
                                    onSubmitted()
                                },
                                onFail = { e ->
                                    isSubmitting = false
                                    toast("공격 제출 실패: ${e.message ?: "unknown"}")
                                }
                            )
                        }
                    ) { Text("확인") }
                },
                dismissButton = {
                    TextButton(enabled = !isSubmitting, onClick = { showConfirm = false }) { Text("취소") }
                }
            )
        }

        SnackbarHost(
            hostState = snack,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        )
    }
}

/* ---------------- UI Helpers ---------------- */

@Composable
private fun AttackCellHeader(text: String, width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(1.dp, Color(0xFF666666))
            .background(Color(0xFFEFEFEF)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2
        )
    }
}

@Composable
private fun AttackCell(
    width: Dp,
    height: Dp,
    background: Color,
    isHit: Boolean,
    isAttackPick: Boolean,
    onClick: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(1.dp, Color(0xFF666666))
            .background(background)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (isAttackPick && !isHit) {
            Text("🎯", style = MaterialTheme.typography.titleMedium)
        }
        if (isHit) {
            Text("💥", style = MaterialTheme.typography.titleMedium)
        }
    }
}