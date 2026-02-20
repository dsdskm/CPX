package com.aba.cpx.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.aba.cpx.data.model.ColumnHeader
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.headers
import com.aba.cpx.data.model.rows
import com.aba.cpx.data.repository.GameRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerPlacementViewerScreen(
    teamId: Int,
    teamName: String,
    onBack: () -> Unit,
    gameId: String = "default_game",
    onGoAttack: (() -> Unit)? = null,
    score: Int? = null,
) {

    val colsCount = headers.size
    val rowsCount = rows.size

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    val db = remember { FirebaseFirestore.getInstance() }
    val gameRepo = remember { GameRepository() }

    // -------------------------
    // game listen (점수/순서/현재턴)
    // -------------------------
    var game by remember { mutableStateOf<Game?>(null) }
    var isGameLoading by remember { mutableStateOf(true) }
    var gameErr by remember { mutableStateOf<String?>(null) }

    DisposableEffect(gameId) {
        val reg = gameRepo.listenGame(
            gameId = gameId,
            onUpdate = { g ->
                game = g
                isGameLoading = false
                gameErr = null
            },
            onError = { e ->
                isGameLoading = false
                gameErr = e.message ?: "게임 정보를 불러오지 못했습니다."
            }
        )
        onDispose { reg.remove() }
    }

    // -------------------------
    // powerPlacements/{teamId} listen
    // -------------------------
    var cellToType by remember { mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap()) }
    var hitCells by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    var isPlacementLoading by remember { mutableStateOf(true) }
    var placementErr by remember { mutableStateOf<String?>(null) }

    DisposableEffect(teamId) {
        var reg: ListenerRegistration? = null
        val ref = db.collection("powerPlacements").document(teamId.toString())

        reg = ref.addSnapshotListener { snap, e ->
            if (e != null) {
                isPlacementLoading = false
                placementErr = e.message ?: "전력 배치 정보를 불러오지 못했습니다."
                return@addSnapshotListener
            }

            if (snap == null || !snap.exists()) {
                cellToType = emptyMap()
                hitCells = emptySet()
                isPlacementLoading = false
                placementErr = null
                return@addSnapshotListener
            }

            try {
                val placements = (snap.get("placements") as? List<*>) ?: emptyList<Any?>()
                val placedMap = mutableMapOf<Pair<Int, Int>, String>()
                placements.forEach { pAny ->
                    val p = pAny as? Map<*, *> ?: return@forEach
                    val type = (p["type"] as? String) ?: "default"
                    val cells = p["cells"] as? List<*> ?: return@forEach
                    cells.forEach { cAny ->
                        val cm = cAny as? Map<*, *> ?: return@forEach
                        val c = (cm["c"] as? Number)?.toInt() ?: return@forEach
                        val r = (cm["r"] as? Number)?.toInt() ?: return@forEach
                        placedMap[c to r] = type
                    }
                }

                val hits = (snap.get("hitCells") as? List<*>) ?: emptyList<Any?>()
                val hitSet = hits.mapNotNull { hAny ->
                    val hm = hAny as? Map<*, *> ?: return@mapNotNull null
                    val c = (hm["c"] as? Number)?.toInt() ?: return@mapNotNull null
                    val r = (hm["r"] as? Number)?.toInt() ?: return@mapNotNull null
                    c to r
                }.toSet()

                cellToType = placedMap
                hitCells = hitSet
                isPlacementLoading = false
                placementErr = null
            } catch (ex: Exception) {
                isPlacementLoading = false
                placementErr = ex.message ?: "전력 배치 파싱 실패"
            }
        }

        onDispose { reg?.remove() }
    }

    // -------------------------
    // score calc (현재점수만 표시)
    // -------------------------
    val currentScores = game?.scoresByTeamId.orEmpty()
    val currentScore = score ?: (currentScores[teamId] ?: 0)

    // footer 계산(배치 셀 개수 기반)
    val colPoints: List<Int> = remember(headers) {
        headers.map { h -> h.scoreText.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }
    val filledCells: List<Pair<Int, Int>> = remember(cellToType) { cellToType.keys.toList() }
    val colFilledCount: List<Int> = remember(filledCells) {
        val counts = IntArray(colsCount)
        filledCells.forEach { (c, r) ->
            if (c in 0 until colsCount && r in 0 until rowsCount) counts[c] += 1
        }
        counts.toList()
    }
    val colScoreSum: List<Int> = remember(colFilledCount, colPoints) {
        List(colsCount) { c -> colFilledCount[c] * colPoints[c] }
    }

    // grid 영역 사이즈 확보
    var gridPxSize by remember { mutableStateOf(Size(0f, 0f)) }
    val density = LocalDensity.current
    fun pxToDp(px: Float): Dp = with(density) { px.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F0F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding() + 12.dp)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = navBarPadding.calculateBottomPadding() + 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ✅ 상단 한 줄: (좌)상태 + 순서칩  (우)팀/점수
            TopInlineBar(
                game = game,
                teamId = teamId,
                teamName = teamName,
                currentScore = currentScore
            )

            // 로딩/에러
            if (isGameLoading || isPlacementLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (gameErr != null) {
                Text(
                    text = gameErr!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (placementErr != null) {
                Text(
                    text = placementErr!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // ✅ 그리드 영역
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { sz ->
                        gridPxSize = Size(sz.width.toFloat(), sz.height.toFloat())
                    }
            ) {
                val gridW = pxToDp(gridPxSize.width)
                val gridH = pxToDp(gridPxSize.height)
                if (gridW.value <= 0f || gridH.value <= 0f) return@Box

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .border(1.dp, Color(0xFF999999))
                        .padding(8.dp)
                ) {
                    val leftColW = (gridW * 0.18f).coerceIn(76.dp, 110.dp)
                    val cellW = ((gridW - leftColW) / colsCount).coerceIn(62.dp, 110.dp)

                    val headerH = (gridH * 0.12f).coerceIn(44.dp, 64.dp)
                    val footerH = (gridH * 0.10f).coerceIn(40.dp, 56.dp)
                    val bodyH = (gridH - headerH - footerH).coerceAtLeast(0.dp)
                    val rowH = (bodyH / rowsCount).coerceIn(34.dp, 72.dp)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(hScroll)
                            .verticalScroll(vScroll)
                    ) {
                        Column {
                            Row {
                                TableCell("", leftColW, headerH, true, background = null, onClick = null)
                                headers.forEach {
                                    TableCell("${it.title}\n${it.scoreText}", cellW, headerH, true, background = null, onClick = null)
                                }
                            }

                            rows.forEachIndexed { rowIdx, rowName ->
                                Row {
                                    TableCell(rowName, leftColW, rowH, true, background = null, onClick = null)

                                    for (colIdx in 0 until colsCount) {
                                        val key = colIdx to rowIdx
                                        val type = cellToType[key]
                                        val bg = typeToUnitColor(type)

                                        Box(
                                            modifier = Modifier.width(cellW).height(rowH),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            TableCell("", cellW, rowH, false, background = bg, onClick = null)

                                            if (hitCells.contains(key)) {
                                                Text("💥", style = MaterialTheme.typography.titleMedium)
                                            }
                                        }
                                    }
                                }
                            }

                            Row {
                                TableCell("세로라인\n배치점수", leftColW, footerH, true, background = null, onClick = null)
                                headers.forEachIndexed { colIdx, _ ->
                                    TableCell("${colScoreSum[colIdx]}점", cellW, footerH, false, background = null, onClick = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ✅ 상단 “한 줄” 바
 * - 좌: 상태 배지 + 순서칩(스크롤) + 현재턴 깜빡임 (WORKING/PAUSED)
 * - 우: 팀명 + 점수
 */
@Composable
private fun TopInlineBar(
    game: Game?,
    teamId: Int,
    teamName: String,
    currentScore: Int,
) {
    val state = game?.state ?: GameState.WAITING
    val stateLabel = state.labelKo
    val stateColor = when (state) {
        GameState.WAITING -> Color(0xFF9E9E9E)
        GameState.WORKING -> Color(0xFF2196F3)
        GameState.PAUSED -> Color(0xFFFF9800)
        GameState.COMPLETED -> Color(0xFF4CAF50)
    }

    val order = remember(game) {
        game?.order.orEmpty()
            .sortedBy { it.order }
            .map { it.teamId to it.teamName }
    }
    val currentTeamId = game?.currentTeamId
    val shouldBlinkCurrent = (state == GameState.WORKING || state == GameState.PAUSED)

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
            // ✅ 좌측 끝: 게임 상태 배지
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

            Spacer(Modifier.width(10.dp))

            // 좌측: 순서칩(스크롤)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (order.isEmpty()) {
                    Text(
                        text = "순서: -",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777777),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    order.forEachIndexed { idx, (tid, tname) ->
                        val isCurrent = shouldBlinkCurrent && currentTeamId != null && tid == currentTeamId

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
            }

            Spacer(Modifier.width(10.dp))

            // 우측: 팀/점수
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "팀 #$teamId",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${teamName} · ${currentScore}점",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun typeToUnitColor(type: String?): Color {
    if (type.isNullOrBlank()) return Color.White
    val t = type.trim().uppercase()
    return when {
        t == "TANK" -> Color(0xFFFF2D2D)
        t.startsWith("CANNON") -> Color(0xFF6EC8FF)
        t == "INFANTRY" -> Color(0xFF222222)
        else -> Color.White
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    height: Dp,
    isHeader: Boolean,
    background: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val baseBg = if (isHeader) Color(0xFFEFEFEF) else Color.White
    val bg = background ?: baseBg

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(1.dp, Color(0xFF666666))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
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