package com.aba.cpx.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aba.cpx.R
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

    var game by remember { mutableStateOf<Game?>(null, neverEqualPolicy()) }
    var isSubmitting by remember { mutableStateOf(false) }

    var attackPicks by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var selectedTargetId by remember { mutableStateOf<Int?>(null) }

    var showConfirm by remember { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }

    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(msg)
        }
    }

    DisposableEffect(gameId) {
        val reg = repo.listenGame(
            gameId = gameId,
            onUpdate = { g -> game = g },
            onError = { e -> toast("게임 구독 오류: ${e.message ?: "unknown"}") }
        )
        onDispose { reg.remove() }
    }

    val isPaused = (game?.state == GameState.PAUSED)
    val isWorking = (game?.state == GameState.WORKING)
    val isMyTurn = (isWorking && game?.currentTeamId == teamId)

    LaunchedEffect(game?.state, game?.currentTeamId) {
        val g = game ?: return@LaunchedEffect
        if (g.state != GameState.WORKING) return@LaunchedEffect
        if (g.currentTeamId != teamId) onNotMyTurn()
    }

    val allTeams: List<Pair<Int, String>> = remember(game, teamId, teamName) {
        val ordered = game?.order.orEmpty()
            .sortedBy { it.order }
            .map { it.teamId to it.teamName }
        if (ordered.isNotEmpty()) ordered else listOf(teamId to teamName)
    }

    val targetCandidates: List<Pair<Int, String>> = remember(allTeams, teamId) {
        allTeams.filter { it.first != teamId }
    }

    LaunchedEffect(game?.updatedAt, allTeams) {
        val g = game ?: return@LaunchedEffect
        if (selectedTargetId != null && allTeams.any { it.first == selectedTargetId }) return@LaunchedEffect

        val last = g.lastTargetByTeamId[teamId]
        val candidate = last?.takeIf { id ->
            id != teamId && allTeams.any { it.first == id }
        }
        selectedTargetId = candidate ?: allTeams.firstOrNull { it.first != teamId }?.first
    }

    DisposableEffect(selectedTargetId) {
        attackPicks = emptyList()
        onDispose { }
    }

    fun toggleCell(c: Int, r: Int) {
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

    val canAttack =
        isWorking &&
                isMyTurn &&
                !isSubmitting &&
                tId != null &&
                tId != teamId &&
                (lastAttackedTeamId == null || tId != lastAttackedTeamId) &&
                attackPicks.size == required

    val orderList = remember(allTeams) { allTeams }
    val totalTeams = orderList.size
    val turnIndex = game?.turnIndex ?: 0
    val currentTeamId = game?.currentTeamId

    val round = if (totalTeams <= 0) 0 else (turnIndex / totalTeams) + 1
    val posInRound = if (totalTeams <= 0) 0 else (turnIndex % totalTeams) + 1
    val roundShown = if (round <= 0) 0 else minOf(round, 10)

    val currentTeamName = remember(currentTeamId, orderList) {
        if (currentTeamId == null) "-"
        else (orderList.firstOrNull { it.first == currentTeamId }?.second ?: currentTeamId.toString())
    }

    val initial = game?.initialScoresByTeamId?.get(teamId) ?: 0
    val damageTaken = game?.damageTakenByTeamId?.get(teamId) ?: 0
    val bonus = game?.bonusByTeamId?.get(teamId) ?: 0
    val current = game?.scoresByTeamId?.get(teamId) ?: 0
    val scoreExpr = "${initial}(최초점수) - ${damageTaken}(피격) + ${bonus}(명중) = ${current}점"

    val selectedTargetName = remember(tId, orderList) {
        if (tId == null) "-" else (orderList.firstOrNull { it.first == tId }?.second ?: tId.toString())
    }

    val infinite = rememberInfiniteTransition(label = "blink")
    val blinkAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )
    val shouldBlinkCurrent = (game?.state == GameState.WORKING || game?.state == GameState.PAUSED)

    Box(modifier = Modifier.fillMaxSize()) {

        // ✅ 배경 이미지
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // ✅ 약한 오버레이(원하면 alpha만 조절)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.10f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CommonTopBar(
                game = game,
                roundShown = roundShown,
                posInRound = posInRound,
                totalTeams = totalTeams,
                currentTeamName = currentTeamName,
                orderList = orderList,
                currentTeamId = currentTeamId,
                shouldBlinkCurrent = shouldBlinkCurrent,
                blinkAlpha = blinkAlpha,
                rightContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        // ✅ 버튼 배경 흰색(OutlinedButton 기본은 투명이라 잘 안 보임)
                        OutlinedButton(
                            onClick = { showTargetDialog = true },
                            enabled = isMyTurn && !isSubmitting && !isPaused,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.White.copy(alpha = 0.6f),
                                disabledContentColor = Color.Black.copy(alpha = 0.6f),
                            )
                        ) {
                            Text(
                                text = "공격 대상 $selectedTargetName",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.Black
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // ✅ 점수 문구 검은색
                        Text(
                            text = scoreExpr,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.Black
                        )
                    }
                }
            )

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
                            headers.forEach {
                                AttackCellHeader("${it.title}\n${it.scoreText}", 88.dp, 56.dp)
                            }
                        }

                        rows.forEachIndexed { r, rowName ->
                            Row {
                                AttackCellHeader(rowName, 92.dp, 44.dp)

                                for (c in 0 until colsCount) {
                                    val key = c to r
                                    val isAttackPick = attackPicks.contains(key)

                                    AttackCell(
                                        width = 88.dp,
                                        height = 44.dp,
                                        background = Color.White,
                                        isAttackPick = isAttackPick,
                                        onClick = { toggleCell(c, r) }
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
                // ✅ 버튼 배경 흰색 + 글자 검은색
                OutlinedButton(
                    onClick = { onGoMyStrategy() },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = Color.White.copy(alpha = 0.6f),
                        disabledContentColor = Color.Black.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) { Text("나의 전략 확인", color = Color.Black) }

                // ✅ 공격 버튼도 흰색 배경으로
                Button(
                    onClick = {
                        if (isPaused) {
                            toast("게임이 일시중지(paused) 상태입니다. 재개 후 공격할 수 있습니다.")
                            return@Button
                        }
                        showConfirm = true
                    },
                    enabled = canAttack,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = Color.White.copy(alpha = 0.6f),
                        disabledContentColor = Color.Black.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) { Text(if (isSubmitting) "전송중..." else "공격(${attackPicks.size}/$required)", color = Color.Black) }
            }
        }

        if (showConfirm) {
            val g = game
            val target = tId

            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showConfirm = false },
                title = { Text("공격 제출") },
                text = {
                    Text(
                        "선택한 ${required}칸으로 [$selectedTargetName] 팀을 공격할까요?\n" +
                                "※ 한 팀은 두 번 연속 공격당할 수 없습니다."
                    )
                },
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
                    TextButton(
                        enabled = !isSubmitting,
                        onClick = { showConfirm = false }
                    ) { Text("취소") }
                }
            )
        }

        if (showTargetDialog) {
            AlertDialog(
                onDismissRequest = { showTargetDialog = false },
                title = { Text("공격 대상 선택") },
                text = {
                    if (targetCandidates.isEmpty()) {
                        Text("선택할 대상이 없습니다.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            targetCandidates.forEach { (id, name) ->
                                val selected = (selectedTargetId == id)
                                OutlinedButton(
                                    onClick = {
                                        if (!isMyTurn) {
                                            toast("지금은 공격 차례가 아닙니다.")
                                            return@OutlinedButton
                                        }
                                        if (id == teamId) {
                                            toast("내 팀은 공격 대상이 될 수 없습니다.")
                                            return@OutlinedButton
                                        }
                                        if (lastAttackedTeamId != null && id == lastAttackedTeamId) {
                                            toast("직전 공격당한 팀은 연속으로 공격할 수 없습니다.")
                                            return@OutlinedButton
                                        }
                                        selectedTargetId = id
                                        showTargetDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = isMyTurn && !isSubmitting && !isPaused,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (selected) Color(0xFFEDE7F6) else Color.White,
                                        contentColor = Color.Black,
                                        disabledContainerColor = Color.White.copy(alpha = 0.6f),
                                        disabledContentColor = Color.Black.copy(alpha = 0.6f),
                                    )
                                ) {
                                    Text(
                                        text = if (selected) "✓ $name" else name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTargetDialog = false }) { Text("닫기") }
                }
            )
        }

        SnackbarHost(
            hostState = snack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

/* ---------------- 공통 상단바 ---------------- */

@Composable
private fun CommonTopBar(
    game: Game?,
    roundShown: Int,
    posInRound: Int,
    totalTeams: Int,
    currentTeamName: String,
    orderList: List<Pair<Int, String>>,
    currentTeamId: Int?,
    shouldBlinkCurrent: Boolean,
    blinkAlpha: Float,
    rightContent: @Composable () -> Unit,
) {
    val state = game?.state ?: GameState.WAITING
    val stateLabel = when (state) {
        GameState.WAITING -> "대기"
        GameState.WORKING -> "진행"
        GameState.PAUSED -> "일시정지"
        GameState.COMPLETED -> "종료"
    }
    val stateColor = when (state) {
        GameState.WAITING -> Color(0xFF9E9E9E)
        GameState.WORKING -> Color(0xFF2196F3)
        GameState.PAUSED -> Color(0xFFFF9800)
        GameState.COMPLETED -> Color(0xFF4CAF50)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(12.dp)),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.widthIn(min = 170.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(stateColor, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stateLabel,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = if (totalTeams > 0) "${roundShown}R (${roundShown}/${10})" else "-R",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF444444),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "현재팀: $currentTeamName",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.Black
                )
            }

            Spacer(Modifier.width(10.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (orderList.isEmpty()) {
                    Text(
                        text = "순서: -",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777777),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    orderList.forEachIndexed { idx, (tid, tname) ->
                        val isCurrent = (shouldBlinkCurrent && currentTeamId != null && tid == currentTeamId)
                        val chipBg = if (isCurrent) Color(0xFF7C4DFF) else Color(0xFFEFEFEF)
                        val chipFg = if (isCurrent) Color.White else Color(0xFF222222)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .alpha(blinkAlpha)
                                        .background(Color(0xFF7C4DFF), CircleShape)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .then(if (isCurrent) Modifier.alpha(blinkAlpha) else Modifier)
                                    .background(chipBg, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = tname,
                                    color = chipFg,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (idx != orderList.lastIndex) {
                            Text(
                                text = "→",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF888888),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Box(
                modifier = Modifier.widthIn(min = 190.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                rightContent()
            }
        }
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
            maxLines = 2,
            color = Color.Black
        )
    }
}

@Composable
private fun AttackCell(
    width: Dp,
    height: Dp,
    background: Color,
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
        if (isAttackPick) {
            Text("🎯", style = MaterialTheme.typography.titleMedium)
        }
    }
}