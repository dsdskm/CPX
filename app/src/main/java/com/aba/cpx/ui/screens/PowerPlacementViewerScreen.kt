package com.aba.cpx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.PowerPlacement
import com.aba.cpx.data.repository.GameRepository
import com.aba.cpx.data.repository.PowerPlacementRepository
import kotlinx.coroutines.launch

private data class ColumnHeader2(val title: String, val scoreText: String)
private enum class UnitType2 { TANK, CANNON1, CANNON2, CANNON3, INFANTRY }

@Composable
fun PowerPlacementViewerScreen(
    teamId: Int,
    teamName: String,
    score: Int? = null,
    onBack: () -> Unit,
    gameId: String = "default_game",
    onGoAttack: (() -> Unit)? = null
) {
    val headers = listOf(
        ColumnHeader2("사복부", "10점"),
        ColumnHeader2("통신대", "10점"),
        ColumnHeader2("레이더", "8점"),
        ColumnHeader2("무기고", "8점"),
        ColumnHeader2("보급소", "6점"),
        ColumnHeader2("비행장", "6점"),
        ColumnHeader2("병원", "4점"),
        ColumnHeader2("방송국", "4점"),
        ColumnHeader2("발전소", "2점"),
        ColumnHeader2("철도", "2점"),
    )
    val rows = listOf("서울", "수원", "인천", "오산", "천안", "대전", "전주", "광주", "대구", "부산")

    val colsCount = headers.size
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val placementRepo = remember { PowerPlacementRepository() }
    val gameRepo = remember { GameRepository() }

    var game by remember { mutableStateOf<Game?>(null) }

    // ✅ 드롭다운 선택 팀 (기본: 내 팀)
    var selectedTeamId by remember { mutableStateOf<Int?>(teamId) }

    // ✅ 내 팀 전략 배치(유닛) 저장/표시 (칸 배경색으로 칠하기)
    var myPlacementLoading by remember { mutableStateOf(true) }
    var myPlacementError by remember { mutableStateOf<String?>(null) }
    var myUnits by remember { mutableStateOf<Map<Pair<Int, Int>, UnitType2>>(emptyMap()) }

    // ✅ 선택팀 피격(hitCells) - 항상 구독 (💥 오버레이용)
    var hitLoading by remember { mutableStateOf(false) }
    var hitCells by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }

    // ✅ 수동 표기(다른 팀 선택 시)
    var manualMode by remember { mutableStateOf(false) }
    var manualPicks by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    var isSaving by remember { mutableStateOf(false) }

    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(msg: String) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(msg)
        }
    }

    fun parseType(type: String): UnitType2? =
        runCatching { UnitType2.valueOf(type) }.getOrNull()

    // ✅ 게임 구독
    DisposableEffect(gameId) {
        val reg = gameRepo.listenGame(
            gameId = gameId,
            onUpdate = { g -> game = g },
            onError = { /* 조용히 */ }
        )
        onDispose { reg.remove() }
    }

    // ✅ (기존 유지) 내 턴이면 공격 화면 자동 이동
    LaunchedEffect(game?.state, game?.currentTeamId) {
        val g = game ?: return@LaunchedEffect
        if (g.state != GameState.RUNNING) return@LaunchedEffect
        if (g.currentTeamId == teamId) onGoAttack?.invoke()
    }

    // ✅ 드롭다운 팀 목록
    val allTeams: List<Pair<Int, String>> = remember(game, teamId, teamName) {
        val ordered = game?.order.orEmpty()
            .sortedBy { it.order }
            .map { it.teamId to it.teamName }
        if (ordered.isNotEmpty()) ordered else listOf(teamId to teamName)
    }

    val isMyTeamSelected = (selectedTeamId == teamId)

    // ✅ 내 팀 배치 로드(1회)
    LaunchedEffect(teamId) {
        myPlacementLoading = true
        myPlacementError = null
        myUnits = emptyMap()

        placementRepo.loadOnce(
            teamId = teamId,
            onSuccess = { doc: PowerPlacement? ->
                val map = buildMap<Pair<Int, Int>, UnitType2> {
                    doc?.placements.orEmpty().forEach { p ->
                        val ut = parseType(p.type) ?: return@forEach
                        p.cells.forEach { c -> put(c.c to c.r, ut) }
                    }
                }
                myUnits = map
                myPlacementLoading = false
            },
            onFail = { e ->
                myPlacementError = e.message ?: "배치 정보를 불러오지 못했습니다."
                myPlacementLoading = false
            }
        )
    }

    // ✅ 선택된 팀 hitCells 구독(항상)
    DisposableEffect(selectedTeamId) {
        val tid = selectedTeamId

        // 팀 바뀌면 수동 선택 리셋
        manualPicks = emptySet()
        manualMode = false
        hitCells = emptySet()
        hitLoading = false

        if (tid == null) {
            onDispose { }
        } else {
            hitLoading = true
            val reg = gameRepo.listenHitCellsForTeam(
                teamId = tid,
                onUpdate = { set ->
                    hitCells = set
                    hitLoading = false
                },
                onError = { e ->
                    hitLoading = false
                    toast("피격 현황 구독 오류: ${e.message ?: "unknown"}")
                }
            )
            onDispose { reg.remove() }
        }
    }

    // ✅ 클릭 동작: 다른 팀 + 수동모드에서만 토글
    fun onCellClick(c: Int, r: Int) {
        if (isMyTeamSelected) return
        if (!manualMode) return
        if (isSaving) return

        val key = c to r
        manualPicks = if (manualPicks.contains(key)) manualPicks - key else manualPicks + key
    }

    val canSaveManual =
        !isMyTeamSelected &&
                manualMode &&
                !isSaving &&
                selectedTeamId != null &&
                manualPicks.isNotEmpty()

    val selectedName = allTeams.firstOrNull { it.first == selectedTeamId }?.second ?: "-"

    // ✅ 유닛 색 (내 팀 배치 배경색)
    val tankColor = Color(0xFFFF2D2D)
    val cannonColor = Color(0xFF6EC8FF)
    val infantryColor = Color(0xFF222222)
    fun unitColor(type: UnitType2): Color = when (type) {
        UnitType2.TANK -> tankColor
        UnitType2.CANNON1, UnitType2.CANNON2, UnitType2.CANNON3 -> cannonColor
        UnitType2.INFANTRY -> infantryColor
    }

    // ✅ 배경색 결정
    // - 내 팀: 유닛 있으면 유닛색, 없으면 흰색
    // - 다른 팀: 항상 흰색 (공격은 💥로만 표시)
    fun cellBg(c: Int, r: Int): Color {
        val key = c to r
        return if (isMyTeamSelected) {
            myUnits[key]?.let { unitColor(it) } ?: Color.White
        } else {
            Color.White
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TurnOrderBar(
                game = game,
                myTeamId = teamId,
                myTeamName = teamName,
                mode = TurnBarMode.VIEW,
                score = score,
                onBack = onBack,
                rightContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TargetDropdownAllTeams(
                            enabled = !isSaving,
                            teams = allTeams,
                            selectedId = selectedTeamId,
                            onSelect = { newId -> selectedTeamId = newId }
                        )

                        // ✅ 다른 팀 선택일 때만 수동 모드
                        if (!isMyTeamSelected) {
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = manualMode,
                                onClick = { if (!isSaving) manualMode = !manualMode },
                                label = { Text(if (manualMode) "수동표기 ON" else "수동표기 OFF") }
                            )
                        }
                    }
                }
            )

            // 안내
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("선택팀: $selectedName", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))

                    val msg = when {
                        hitLoading -> "피격 현황 불러오는 중..."
                        isMyTeamSelected -> "내 팀: 전력 배치(칸 배경색) + 피격은 💥 오버레이"
                        manualMode -> "다른 팀: 클릭으로 피격 후보 찍고 저장 (저장된 피격은 💥)"
                        else -> "다른 팀: 빈 그리드 + 저장된 피격 💥만 표시"
                    }
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF555555))
                }
            }

            // 내 팀 로딩/에러 (내 팀일 때만)
            if (isMyTeamSelected) {
                when {
                    myPlacementLoading -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    myPlacementError != null -> {
                        Text(
                            text = myPlacementError!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

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
                            ViewerCellHeader("", 92.dp, 56.dp)
                            headers.forEach {
                                ViewerCellHeader("${it.title}\n${it.scoreText}", 88.dp, 56.dp)
                            }
                        }

                        rows.forEachIndexed { rowIdx, rowName ->
                            Row {
                                ViewerCellHeader(rowName, 92.dp, 44.dp)

                                for (colIdx in 0 until colsCount) {
                                    val key = colIdx to rowIdx

                                    // ✅ 피격은 항상 💥로만 표시 (배경 변경 X)
                                    val isHit = hitCells.contains(key)

                                    // ✅ 수동 후보(저장 전 표시) - 다른 팀 + 수동모드
                                    val isManualPick =
                                        (!isMyTeamSelected && manualMode && manualPicks.contains(key))

                                    ViewerCell(
                                        width = 88.dp,
                                        height = 44.dp,
                                        isHeader = false,
                                        background = cellBg(colIdx, rowIdx),
                                        isHit = isHit,
                                        isManualPick = isManualPick,
                                        onClick = if (!isMyTeamSelected && manualMode) {
                                            { onCellClick(colIdx, rowIdx) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 저장 버튼: 다른 팀 + 수동모드에서만
            if (!isMyTeamSelected && manualMode) {
                Button(
                    onClick = {
                        val tid = selectedTeamId ?: return@Button
                        isSaving = true

                        gameRepo.addHitCellsManualForTeam(
                            teamId = tid,
                            cells = manualPicks.toList(),
                            attackerTeamId = null,
                            onSuccess = {
                                isSaving = false
                                manualPicks = emptySet()
                                toast("수동 피격 표기 저장 완료!")
                            },
                            onFail = { e ->
                                isSaving = false
                                toast("수동 저장 실패: ${e.message ?: "unknown"}")
                            }
                        )
                    },
                    enabled = canSaveManual,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(if (isSaving) "저장중..." else "피격 위치 저장(${manualPicks.size})")
                }
            }
        }

        SnackbarHost(
            hostState = snack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun ViewerCellHeader(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
) {
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

/**
 * ✅ 최종 규칙 반영:
 * - 내 팀 배치: background로 칠한다(원래 방식)
 * - 피격(hitCells): background 절대 변경 없이 💥 오버레이로만 표시
 * - 수동 후보: 저장 전 ✚ 오버레이 (hit가 있으면 hit가 우선)
 */
@Composable
private fun ViewerCell(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    isHeader: Boolean,
    background: Color? = null,
    isHit: Boolean = false,
    isManualPick: Boolean = false,
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
            .then(if (onClick != null && !isHeader) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        // 저장 전 수동 후보(다른 팀 선택 시)
        if (!isHeader && isManualPick && !isHit) {
            Text("✚", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1976D2))
        }

        // 피격 표시 (항상 최상단)
        if (!isHeader && isHit) {
            Text("💥", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetDropdownAllTeams(
    enabled: Boolean,
    teams: List<Pair<Int, String>>,
    selectedId: Int?,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = teams.firstOrNull { it.first == selectedId }?.second ?: "팀 선택"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor()
                .width(160.dp),
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text("팀") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            teams.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}
