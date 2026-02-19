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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import kotlin.math.max

enum class TurnBarMode { VIEW, ATTACK }

@Composable
fun TurnOrderBar(
    game: Game?,
    myTeamId: Int? = null,
    myTeamName: String? = null,
    mode: TurnBarMode = TurnBarMode.VIEW,
    selectedCount: Int = 0,
    requiredCount: Int = 3,
    score: Int? = null, // (옵션) 강제로 현재점수만 override 하고 싶을 때
    onBack: (() -> Unit)? = null,
    overrideOrder: List<Pair<Int, String>>? = null,
    overrideScoresByTeamId: Map<Int, Int>? = null,
    rightContent: (@Composable () -> Unit)? = null,
    showScoresInOrderChips: Boolean = false, // ✅ 운영 계정에서만 true
) {
    val state = game?.state ?: GameState.READY

    val order = (overrideOrder?.mapIndexed { idx, (id, name) ->
        Triple(id, name, idx + 1)
    } ?: game?.order.orEmpty().sortedBy { it.order }.map {
        Triple(it.teamId, it.teamName, it.order)
    }).sortedBy { it.third }

    val totalTeams = order.size
    val turnIndex = game?.turnIndex ?: 0
    val currentTeamId = game?.currentTeamId

    val round = if (totalTeams <= 0) 0 else (turnIndex / totalTeams) + 1
    val posInRound = if (totalTeams <= 0) 0 else (turnIndex % totalTeams) + 1
    val roundShown = if (round <= 0) 0 else minOf(round, 10)

    val currentTeamName =
        order.firstOrNull { it.first == currentTeamId }?.second
            ?: (currentTeamId?.toString() ?: "-")

    val scores = overrideScoresByTeamId ?: game?.scoresByTeamId.orEmpty()
    val initialScores = game?.initialScoresByTeamId.orEmpty()

    // ✅ 점수는 "내 팀" 기준으로 계산 (요구사항)
    val scoreTeamId = myTeamId ?: currentTeamId
    val currentScore = if (scoreTeamId == null) 0 else (scores[scoreTeamId] ?: 0)
    val initialScore = if (scoreTeamId == null) null else initialScores[scoreTeamId]

    // ✅ 외부에서 score를 넘기면 '현재점수'만 override (초기점수는 그대로)
    val currentShown = score ?: currentScore
    val delta = if (initialScore == null) null else max(initialScore - currentShown, 0)

    // ✅ 시작/현재/감소를 "구분해서" 출력
    val scoreLabel = if (initialScore != null && delta != null) {
        "시작: ${initialScore}점 · 현재: ${currentShown}점 · 감소: -${delta}점"
    } else {
        "현재: ${currentShown}점"
    }

    val viewTeamName = myTeamName ?: "-"

    val stateLabelKo = when (state) {
        GameState.READY -> "대기"
        GameState.RUNNING -> "진행"
        GameState.PAUSED -> "일시중지"
        GameState.FINISHED -> "종료"
        GameState.ABORTED -> "중단됨"
        else -> state.name
    }
    val stateColor = when (state) {
        GameState.READY -> Color(0xFF9E9E9E)
        GameState.RUNNING -> Color(0xFF2196F3)
        GameState.PAUSED -> Color(0xFFFF9800)
        GameState.FINISHED -> Color(0xFF4CAF50)
        GameState.ABORTED -> MaterialTheme.colorScheme.error
        else -> Color(0xFF9E9E9E)
    }

    val line1 = buildString {
        append("상태: $stateLabelKo")
        if (state == GameState.RUNNING && totalTeams > 0) {
            append(" · 라운드: ${roundShown}R(${posInRound}/${totalTeams})")
            append(" · 현재턴: $currentTeamName")
        } else {
            append(" · 현재: $currentTeamName")
        }
        append(" · 팀: $viewTeamName")
        append(" · $scoreLabel")
    }

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
                    text = line1,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (rightContent != null) {
                    Spacer(Modifier.width(8.dp))
                    rightContent()
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                }

                Box(
                    modifier = Modifier
                        .background(stateColor, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stateLabelKo,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (order.isNotEmpty()) {
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    order.forEachIndexed { idx, (tid, tname, _) ->
                        val isCurrent = (state == GameState.RUNNING && tid == currentTeamId)
                        val chipBg = if (isCurrent) Color(0xFF7C4DFF) else Color(0xFFEFEFEF)
                        val chipFg = if (isCurrent) Color.White else Color(0xFF222222)

                        // ✅ 운영 계정일 때만 점수 포함
                        val chipText = if (showScoresInOrderChips && scores.isNotEmpty()) {
                            "$tname ${scores[tid] ?: 0}점"
                        } else {
                            tname
                        }

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
}
