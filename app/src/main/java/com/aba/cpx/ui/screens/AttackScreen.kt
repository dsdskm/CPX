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

private data class ColumnHeaderA(val title: String, val scoreText: String)
private enum class UnitTypeA { TANK, CANNON1, CANNON2, CANNON3, INFANTRY }

@Composable
fun AttackScreen(
    teamId: Int,
    teamName: String,
    gameId: String = "default_game",
    onSubmitted: () -> Unit,
    onNotMyTurn: () -> Unit,
) {
    val headers = listOf(
        ColumnHeaderA("사복부", "10점"),
        ColumnHeaderA("통신대", "10점"),
        ColumnHeaderA("레이더", "8점"),
        ColumnHeaderA("무기고", "8점"),
        ColumnHeaderA("보급소", "6점"),
        ColumnHeaderA("비행장", "6점"),
        ColumnHeaderA("병원", "4점"),
        ColumnHeaderA("방송국", "4점"),
        ColumnHeaderA("발전소", "2점"),
        ColumnHeaderA("철도", "2점"),
    )
    val rows = listOf("서울", "수원", "인천", "오산", "천안", "대전", "전주", "광주", "대구", "부산")

    val colsCount = headers.size
    val required = 3

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val repo = remember { GameRepository() }
    val placementRepo = remember { PowerPlacementRepository() }

    var game by remember { mutableStateOf<Game?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // ✅ 공격 선택(내 턴 + 수동OFF에서만)
    var attackPicks by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }

    // ✅ 조회/수동입력 대상팀 (항상 선택 가능)
    var selectedTeamId by remember { mutableStateOf<Int?>(null) }

    // ✅ 선택된 팀의 실제 피격 현황(DB)
    var hitCells by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    var hitLoading by remember { mutableStateOf(false) }

    // ✅ 수동 입력 모드
    var manualMode by remember { mutableStateOf(false) }
    var manualPicks by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }

    // ✅ 내 팀 전력 배치(색칠용) 1회 로드
    var myUnitsLoading by remember { mutableStateOf(true) }
    var myUnitsError by remember { mutableStateOf<String?>(null) }
    var myUnits by remember { mutableStateOf<Map<Pair<Int, Int>, UnitTypeA>>(emptyMap()) }

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

    fun parseType(type: String): UnitTypeA? =
        runCatching { UnitTypeA.valueOf(type) }.getOrNull()

    // 게임 구독
    DisposableEffect(gameId) {
        val reg = repo.listenGame(
            gameId = gameId,
            onUpdate = { g -> game = g },
            onError = { e -> toast("게임 구독 오류: ${e.message ?: "unknown"}") }
        )
        onDispose { reg.remove() }
    }

    // 내 턴 아니면 콜백 유지
    LaunchedEffect(game?.state, game?.currentTeamId) {
        val g = game ?: return@LaunchedEffect
        if (g.state != GameState.RUNNING) return@LaunchedEffect
        if (g.currentTeamId != teamId) onNotMyTurn()
    }

    val isMyTurn = (game?.state == GameState.RUNNING && game?.currentTeamId == teamId)

    // 팀 목록
    val allTeams: List<Pair<Int, String>> = remember(game, teamId, teamName) {
        val ordered = game?.order.orEmpty()
            .sortedBy { it.order }
            .map { it.teamId to it.teamName }
        if (ordered.isNotEmpty()) ordered else listOf(teamId to teamName)
    }

    // 드롭다운 기본 선택: (내 마지막 공격 타겟 -> 없으면 내 팀)
    LaunchedEffect(game?.updatedAt, allTeams) {
        val g = game ?: return@LaunchedEffect
        if (selectedTeamId != null && allTeams.any { it.first == selectedTeamId }) return@LaunchedEffect

        val lastPicked = g.lastTargetByTeamId[teamId]
        selectedTeamId =
            lastPicked?.takeIf { id -> allTeams.any { it.first == id } }
                ?: teamId
    }

    val selectedName = allTeams.firstOrNull { it.first == selectedTeamId }?.second ?: "-"
    val isMyTeamSelected = (selectedTeamId == teamId)

    // ✅ 내 팀 배치 로드(1회)
    LaunchedEffect(teamId) {
        myUnitsLoading = true
        myUnitsError = null
        myUnits = emptyMap()

        placementRepo.loadOnce(
            teamId = teamId,
            onSuccess = { doc: PowerPlacement? ->
                val map = buildMap<Pair<Int, Int>, UnitTypeA> {
                    doc?.placements.orEmpty().forEach { p ->
                        val ut = parseType(p.type) ?: return@forEach
                        p.cells.forEach { c -> put(c.c to c.r, ut) }
                    }
                }
                myUnits = map
                myUnitsLoading = false
            },
            onFail = { e ->
                myUnitsError = e.message ?: "내 전력 배치를 불러오지 못했습니다."
                myUnitsLoading = false
            }
        )
    }

    // 선택된 팀 피격 현황 구독(항상)
    DisposableEffect(selectedTeamId) {
        val tid = selectedTeamId

        // 팀 바뀌면 선택들 초기화(혼동 방지)
        attackPicks = emptyList()
        manualPicks = emptySet()
        hitCells = emptySet()
        hitLoading = false

        if (tid == null) {
            onDispose { }
        } else {
            hitLoading = true
            val reg = repo.listenHitCellsForTeam(
                teamId = tid,
                onUpdate = { set ->
                    hitCells = set
                    hitLoading = false
                },
                onError = { e ->
                    hitLoading = false
                    toast("피격 좌표 구독 오류: ${e.message ?: "unknown"}")
                }
            )
            onDispose { reg.remove() }
        }
    }

    // 모드 변경 시 선택 초기화
    LaunchedEffect(manualMode) {
        attackPicks = emptyList()
        manualPicks = emptySet()
    }

    fun toggleCell(c: Int, r: Int) {
        val key = c to r

        // 수동 모드: manualPicks 토글
        if (manualMode) {
            if (isSubmitting) return
            manualPicks = if (manualPicks.contains(key)) manualPicks - key else manualPicks + key
            return
        }

        // 공격 모드: 내 턴일 때만
        if (!isMyTurn) {
            toast("지금은 공격 차례가 아닙니다. (조회/수동입력은 가능)")
            return
        }

        val targetId = selectedTeamId
        if (targetId == null) {
            toast("대상팀을 선택하세요.")
            return
        }
        if (targetId == teamId) {
            toast("내 팀은 공격 대상이 될 수 없습니다. (수동입력 모드로 전환 가능)")
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
    val targetId = selectedTeamId

    val canAttack =
        !manualMode &&
                isMyTurn &&
                !isSubmitting &&
                targetId != null &&
                targetId != teamId &&
                (lastAttackedTeamId == null || targetId != lastAttackedTeamId) &&
                attackPicks.size == required

    val canSaveManual =
        manualMode &&
                !isSubmitting &&
                targetId != null &&
                manualPicks.isNotEmpty()

    // ✅ 셀 배경(배경색은 “내 팀 배치 표시”에만 사용)
    fun cellBackground(c: Int, r: Int): Color {
        if (!isMyTeamSelected) return Color.White

        val key = c to r
        val tank = Color(0xFFFF2D2D)
        val cannon = Color(0xFF6EC8FF)
        val infantry = Color(0xFF222222)

        return when (myUnits[key]) {
            UnitTypeA.TANK -> tank
            UnitTypeA.CANNON1, UnitTypeA.CANNON2, UnitTypeA.CANNON3 -> cannon
            UnitTypeA.INFANTRY -> infantry
            null -> Color.White
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
                mode = TurnBarMode.ATTACK,
                selectedCount = attackPicks.size,
                requiredCount = required,
                rightContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TargetDropdownAllTeams(
                            enabled = !isSubmitting,
                            teams = allTeams,
                            selectedId = selectedTeamId,
                            onSelect = { newId -> selectedTeamId = newId }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = manualMode,
                            onClick = { if (!isSubmitting) manualMode = !manualMode },
                            label = { Text(if (manualMode) "수동입력 ON" else "수동입력 OFF") }
                        )
                    }
                }
            )

            // 상태 안내
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
                        isMyTeamSelected -> "내 팀: 전력 배치(색칠) + 피격은 💥 오버레이"
                        manualMode -> "수동 입력: ✚ 로 찍고 저장하면 💥 로 표시됩니다."
                        isMyTurn -> "공격: 🎯 로 ${required}칸 선택 후 제출하세요. (피격은 💥)"
                        else -> "조회: 피격(💥) 확인 가능. 수동입력도 가능합니다."
                    }
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF555555))

                    if (isMyTeamSelected) {
                        if (myUnitsLoading) {
                            Spacer(Modifier.height(6.dp))
                            Text("내 배치 로딩중...", style = MaterialTheme.typography.labelMedium, color = Color(0xFF666666))
                        }
                        if (myUnitsError != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(myUnitsError!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text("피격 ${hitCells.size}개", style = MaterialTheme.typography.labelMedium, color = Color(0xFF444444))
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
                            AttackCellHeader("", 92.dp, 56.dp)
                            headers.forEach { AttackCellHeader("${it.title}\n${it.scoreText}", 88.dp, 56.dp) }
                        }

                        rows.forEachIndexed { r, rowName ->
                            Row {
                                AttackCellHeader(rowName, 92.dp, 44.dp)

                                for (c in 0 until colsCount) {
                                    val key = c to r

                                    val isHit = hitCells.contains(key)

                                    // ✅ 공격 선택(수동 OFF일 때만) 표시: 🎯 + 순번
                                    val attackIndex = if (!manualMode) attackPicks.indexOf(key) else -1
                                    val isAttackPick = attackIndex >= 0

                                    // ✅ 수동 후보(수동 ON) 표시: ✚
                                    val isManualPick = manualMode && manualPicks.contains(key)

                                    AttackCell(
                                        width = 88.dp,
                                        height = 44.dp,
                                        background = cellBackground(c, r),
                                        isHit = isHit,
                                        isAttackPick = isAttackPick,
                                        attackOrder = if (isAttackPick) (attackIndex + 1) else null,
                                        isManualPick = isManualPick,
                                        onClick = { toggleCell(c, r) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 하단 액션
            if (manualMode) {
                Button(
                    onClick = {
                        val tid = targetId ?: return@Button
                        isSubmitting = true

                        repo.addHitCellsManualForTeam(
                            teamId = tid,
                            cells = manualPicks.toList(),
                            attackerTeamId = null,
                            onSuccess = {
                                // ✅ 즉시 반영(optimistic)
                                hitCells = hitCells + manualPicks
                                manualPicks = emptySet()
                                isSubmitting = false
                                toast("수동 피격 입력 저장 완료!")
                            },
                            onFail = { e ->
                                isSubmitting = false
                                toast("수동 저장 실패: ${e.message ?: "unknown"}")
                            }
                        )
                    },
                    enabled = canSaveManual,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (isSubmitting) "저장중..." else "피격 좌표 저장(${manualPicks.size})")
                }
            } else {
                Button(
                    onClick = { showConfirm = true },
                    enabled = canAttack,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (isSubmitting) "전송중..." else "공격 확인(${attackPicks.size}/$required)")
                }
            }
        }

        // 공격 최종 확인 다이얼로그
        if (showConfirm && !manualMode) {
            val g = game
            val tId = targetId
            val tName = allTeams.firstOrNull { it.first == tId }?.second ?: (tId?.toString() ?: "-")

            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showConfirm = false },
                title = { Text("공격 제출") },
                text = { Text("선택한 ${required}칸으로 [$tName] 팀을 공격할까요?\n※ 한 팀은 두 번 연속 공격당할 수 없습니다.") },
                confirmButton = {
                    Button(
                        enabled = canAttack && tId != null,
                        onClick = {
                            val token = g?.turnToken
                            if (g == null || token.isNullOrBlank()) {
                                toast("게임 정보(turnToken)가 없습니다.")
                                return@Button
                            }

                            val currentLast = g.lastAttackedTeamId
                            if (currentLast != null && tId == currentLast) {
                                toast("직전 공격당한 팀은 공격할 수 없습니다.")
                                return@Button
                            }

                            showConfirm = false
                            isSubmitting = true

                            val payload = mapOf(
                                "type" to "attack",
                                "targetTeamId" to tId,
                                "prevLastAttackedTeamId" to (currentLast ?: -1),
                                "cells" to attackPicks.map { (c, r) -> mapOf("c" to c, "r" to r) }
                            )

                            repo.submitTurn(
                                gameId = gameId,
                                teamId = teamId,
                                turnToken = token,
                                payload = payload,
                                onSuccess = {
                                    // ✅ 즉시 반영(optimistic) — “바로 공격한 곳이 표시”
                                    if (selectedTeamId == tId) {
                                        hitCells = hitCells + attackPicks.toSet()
                                    }
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
private fun AttackCellHeader(text: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
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
 * ✅ 규칙
 * - 배경색은 “내 팀 배치(색칠)” 표현용
 * - 피격(hitCells)은 배경 변화 없이 💥 오버레이
 * - 공격 선택은 🎯 + 순번 오버레이
 * - 수동 후보는 ✚ 오버레이 (hit가 있으면 hit 우선)
 */
@Composable
private fun AttackCell(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    background: Color,
    isHit: Boolean,
    isAttackPick: Boolean,
    attackOrder: Int?,
    isManualPick: Boolean,
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
        // 수동 후보(저장 전) — hit가 없을 때만
        if (isManualPick && !isHit) {
            Text("✚", style = MaterialTheme.typography.titleMedium, color = Color(0xFF1976D2))
        }

        // 공격 선택 — hit/수동보다 우선순위 낮게(겹치면 헷갈리니까 hit가 없을 때만)
        if (isAttackPick && !isHit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎯", style = MaterialTheme.typography.titleMedium)
                if (attackOrder != null) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = attackOrder.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF333333)
                    )
                }
            }
        }

        // 피격 표시 — 항상 최상단
        if (isHit) {
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
            label = { Text("대상팀") },
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
