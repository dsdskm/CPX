package com.aba.cpx.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState

enum class GameInfoBarMode { VIEW, ATTACK }

@Composable
fun GameInfoBar(
    game: Game?,
    myTeamId: Int? = null,
    myTeamName: String? = null,
    mode: GameInfoBarMode = GameInfoBarMode.VIEW,

    // ATTACK에서 선택 카운트 표시용(원하면 유지)
    selectedCount: Int = 0,
    requiredCount: Int = 3,

    // (옵션) 현재 점수 override
    score: Int? = null,

    onBack: (() -> Unit)? = null,

    overrideOrder: List<Pair<Int, String>>? = null,
    overrideScoresByTeamId: Map<Int, Int>? = null,

    // ✅ 내 턴이면 공격 대상 선택
    selectedTargetId: Int? = null,
    onTargetSelected: ((targetTeamId: Int) -> Unit)? = null,

    showScoresInOrderChips: Boolean = false,
) {
    val state = game?.state ?: GameState.WAITING

    val order: List<Triple<Int, String, Int>> = (overrideOrder?.mapIndexed { idx, (id, name) ->
        Triple(id, name, idx + 1)
    } ?: game?.order.orEmpty()
        .sortedBy { it.order }
        .map { Triple(it.teamId, it.teamName, it.order) }
            ).sortedBy { it.third }

    val totalTeams = order.size
    val turnIndex = game?.turnIndex ?: 0
    val currentTeamId = game?.currentTeamId

    // ✅ 라운드/포지션(정지 중에도 동일 계산)
    val round = if (totalTeams <= 0) 0 else (turnIndex / totalTeams) + 1
    val posInRound = if (totalTeams <= 0) 0 else (turnIndex % totalTeams) + 1
    val roundShown = if (round <= 0) 0 else minOf(round, 10)

    val scores = overrideScoresByTeamId ?: game?.scoresByTeamId.orEmpty()
    val myScore = score ?: (if (myTeamId == null) 0 else (scores[myTeamId] ?: 0))

    // ✅ 내 턴 판단은 "WORKING일 때만" 공격 가능
    val isMyTurn = remember(state, currentTeamId, myTeamId) {
        state == GameState.WORKING && myTeamId != null && currentTeamId == myTeamId
    }

    // ✅ 내 턴이 아닐 때 마지막 공격팀 표시
    val lastTargetId = remember(game, myTeamId) {
        if (myTeamId == null) null else game?.lastTargetByTeamId?.get(myTeamId)
    }
    val lastTargetName = remember(lastTargetId, order) {
        if (lastTargetId == null) "-" else (order.firstOrNull { it.first == lastTargetId }?.second ?: lastTargetId.toString())
    }

    val viewTeamName = myTeamName ?: (myTeamId?.toString() ?: "-")

    // ✅ 내 턴일 때 타겟 후보(내 팀 제외)
    val targetCandidates = remember(order, myTeamId) {
        order.filter { it.first != myTeamId }.map { it.first to it.second }
    }

    var showTargetDialog by remember { mutableStateOf(false) }

    // 1줄(좌): 라운드 + 내점수
    val leftLine = buildString {
        if (totalTeams > 0) append("${roundShown}R(${posInRound}/${totalTeams})") else append("-R")
        append(" · ")
        append("$viewTeamName ${myScore}점")
        if (mode == GameInfoBarMode.ATTACK) append(" · 선택: $selectedCount/$requiredCount")
    }

    // 1줄(우)
    val rightLine = if (isMyTurn) {
        val selName = selectedTargetId?.let { id ->
            order.firstOrNull { it.first == id }?.second ?: id.toString()
        }
        if (selName != null) "대상: $selName" else "공격 대상 선택"
    } else {
        "마지막 공격: $lastTargetName"
    }

    // ✅ 깜빡임 애니메이션 (PAUSED에서도 유지)
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

    // ✅ "현재턴 강조"는 WORKING뿐 아니라 PAUSED에서도 표시/깜빡임 유지
    val shouldBlinkCurrent = remember(state) { state == GameState.WORKING || state == GameState.PAUSED }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(12.dp)),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ----------------------
            // 1줄: 라운드/내점수(좌) + (내턴이면 버튼 / 아니면 마지막공격)(우)
            // ----------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(6.dp))
                }

                Text(
                    text = leftLine,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(10.dp))

                if (isMyTurn && onTargetSelected != null) {
                    OutlinedButton(
                        onClick = { showTargetDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(text = rightLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(
                        text = rightLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF555555),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ----------------------
            // 2줄: 팀 순서 + 현재턴 깜빡임 (PAUSED에서도 유지)
            // ----------------------
            if (order.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    order.forEachIndexed { idx, (tid, tname, _) ->
                        val isCurrent = (shouldBlinkCurrent && currentTeamId != null && tid == currentTeamId)

                        val chipBg = if (isCurrent) Color(0xFF7C4DFF) else Color(0xFFEFEFEF)
                        val chipFg = if (isCurrent) Color.White else Color(0xFF222222)

                        val chipText = if (showScoresInOrderChips && scores.isNotEmpty()) {
                            "$tname ${scores[tid] ?: 0}점"
                        } else tname

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
                                    text = chipText,
                                    color = chipFg,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (idx != order.lastIndex) {
                            Text(
                                text = "→",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF888888),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "순서: -",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF777777),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // ----------------------
    // 공격 대상 선택 다이얼로그 (내 팀 제외)
    // ----------------------
    if (showTargetDialog && isMyTurn && onTargetSelected != null) {
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
                                    onTargetSelected(id)
                                    showTargetDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (selected) {
                                    ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEDE7F6))
                                } else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text(
                                    text = if (selected) "✓ $name" else name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
}