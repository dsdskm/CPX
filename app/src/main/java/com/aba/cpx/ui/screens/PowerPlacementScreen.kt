package com.aba.cpx.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import kotlinx.coroutines.launch

private data class ColumnHeader(
    val title: String,
    val scoreText: String
)

private enum class UnitType { TANK, CANNON1, CANNON2, CANNON3, INFANTRY }

private data class ShapeDef(
    val type: UnitType,
    val label: String,
    val blocks: List<Pair<Int, Int>>
)

private data class Placement(
    val id: Int,
    val type: UnitType,
    val cells: List<Pair<Int, Int>>
)

@Composable
fun PowerPlacementScreen(
    team: String,
    status: String
) {
    val headers = listOf(
        ColumnHeader("사복부", "10점"),
        ColumnHeader("통신대", "10점"),
        ColumnHeader("레이더", "8점"),
        ColumnHeader("무기고", "8점"),
        ColumnHeader("보급소", "6점"),
        ColumnHeader("비행장", "6점"),
        ColumnHeader("병원", "4점"),
        ColumnHeader("방송국", "4점"),
        ColumnHeader("발전소", "2점"),
        ColumnHeader("철도", "2점"),
    )

    val rows = listOf(
        "서울", "수원", "인천", "오산", "천안",
        "대전", "전주", "광주", "대구", "부산"
    )

    val colsCount = headers.size
    val rowsCount = rows.size

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    // ✅ Toast 대신 Material3 Snackbar로 구현
    val snackHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showToast(msg: String) {
        scope.launch {
            snackHostState.currentSnackbarData?.dismiss()
            snackHostState.showSnackbar(msg)
        }
    }

    // ✅ status -> 한글 (대기 -> 대기중)
    val statusKo = remember(status) {
        when (status.lowercase()) {
            "waiting" -> "대기중"
            "ready" -> "준비 완료"
            "working" -> "진행중"
            "completed" -> "완료"
            "paused" -> "중지"
            else -> status
        }
    }

    // ✅ 점수 파싱: "10점" -> 10
    val colPoints: List<Int> = remember(headers) {
        headers.map { h -> h.scoreText.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    // ✅ 전력 모양 정의
    val shapes = remember {
        listOf(
            ShapeDef(UnitType.TANK, "탱크", listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)),
            ShapeDef(UnitType.CANNON1, "대포1", listOf(0 to -1, 0 to 0, 0 to 1)),      // 세로 3, 가운데 기준
            ShapeDef(UnitType.CANNON2, "대포2", listOf(1 to -1, 0 to 0, 0 to 1)),      // xO / OX / OX
            ShapeDef(UnitType.CANNON3, "대포3", listOf(1 to -1, 0 to 0, -1 to 1)),     // 대각 3
            ShapeDef(UnitType.INFANTRY, "보병", listOf(0 to 0)),
        )
    }

    var selected by remember { mutableStateOf(shapes.first()) }

    // ✅ 배치 최대치
    val maxTank = 2
    val maxCannonTotal = 4
    val maxInfantry = 8

    var remainTank by remember { mutableStateOf(maxTank) }
    var remainCannonTotal by remember { mutableStateOf(maxCannonTotal) }
    var remainInfantry by remember { mutableStateOf(maxInfantry) }

    // ✅ "준비 완료" 확정 상태 (true면 더 이상 재배치 불가)
    var isLocked by remember { mutableStateOf(false) }

    // ✅ 준비 완료 컨펌 다이얼로그 표시
    var showConfirmDialog by remember { mutableStateOf(false) }

    // 배치 단위 저장
    var placements by remember { mutableStateOf<List<Placement>>(emptyList()) }
    var nextPlacementId by remember { mutableStateOf(1) }

    // 셀 -> 배치
    val cellToPlacement: Map<Pair<Int, Int>, Placement> = remember(placements) {
        buildMap {
            placements.forEach { p -> p.cells.forEach { cell -> put(cell, p) } }
        }
    }

    // ✅ "진짜" 색상
    val tankColor = Color(0xFFFF2D2D)         // 빨간색
    val cannonColor = Color(0xFF6EC8FF)       // 하늘색
    val infantryColor = Color(0xFF222222)     // 아주 어두운 회색

    fun unitColor(type: UnitType): Color = when (type) {
        UnitType.TANK -> tankColor
        UnitType.CANNON1, UnitType.CANNON2, UnitType.CANNON3 -> cannonColor
        UnitType.INFANTRY -> infantryColor
    }

    fun isInsideGrid(c: Int, r: Int): Boolean = c in 0 until colsCount && r in 0 until rowsCount

    fun remainingOf(type: UnitType): Int = when (type) {
        UnitType.TANK -> remainTank
        UnitType.INFANTRY -> remainInfantry
        UnitType.CANNON1, UnitType.CANNON2, UnitType.CANNON3 -> remainCannonTotal
    }

    fun canPlaceNow(def: ShapeDef): Boolean = remainingOf(def.type) > 0

    fun dec(type: UnitType) {
        when (type) {
            UnitType.TANK -> remainTank -= 1
            UnitType.INFANTRY -> remainInfantry -= 1
            UnitType.CANNON1, UnitType.CANNON2, UnitType.CANNON3 -> remainCannonTotal -= 1
        }
    }

    fun inc(type: UnitType) {
        when (type) {
            UnitType.TANK -> remainTank += 1
            UnitType.INFANTRY -> remainInfantry += 1
            UnitType.CANNON1, UnitType.CANNON2, UnitType.CANNON3 -> remainCannonTotal += 1
        }
    }

    fun allPlacedMessage(type: UnitType): String = when (type) {
        UnitType.TANK -> "모든 탱크가 배치되었습니다."
        UnitType.INFANTRY -> "모든 보병이 배치되었습니다."
        UnitType.CANNON1, UnitType.CANNON2, UnitType.CANNON3 -> "모든 대포가 배치되었습니다."
    }

    fun tryPlace(anchorCol: Int, anchorRow: Int) {
        if (isLocked) {
            showToast("준비 완료 이후에는 전력을 재배치할 수 없습니다.")
            return
        }

        // 1) 남은 개수 없음 -> 토스트
        if (!canPlaceNow(selected)) {
            showToast(allPlacedMessage(selected.type))
            return
        }

        val targetCells = selected.blocks.map { (dx, dy) -> (anchorCol + dx) to (anchorRow + dy) }

        // 2) 배치 불가(경계/겹침) -> 토스트
        val outOfBounds = targetCells.any { (c, r) -> !isInsideGrid(c, r) }
        val overlapped = targetCells.any { cellToPlacement.containsKey(it) }

        if (outOfBounds || overlapped) {
            showToast("해당 위치에는 배치할 수 없습니다.")
            return
        }

        // 3) 배치 성공
        val newPlacement = Placement(
            id = nextPlacementId,
            type = selected.type,
            cells = targetCells
        )
        placements = placements + newPlacement
        nextPlacementId += 1
        dec(selected.type)
    }

    fun removePlacement(p: Placement) {
        if (isLocked) {
            showToast("준비 완료 이후에는 전력을 재배치할 수 없습니다.")
            return
        }
        placements = placements.filterNot { it.id == p.id }
        inc(p.type)
    }

    // 배치된 개수
    val placedTank = maxTank - remainTank
    val placedCannon = maxCannonTotal - remainCannonTotal
    val placedInfantry = maxInfantry - remainInfantry

    // ✅ 준비 완료 버튼 활성 조건: 모든 병력 배치 완료
    val isAllPlaced = remainTank == 0 && remainCannonTotal == 0 && remainInfantry == 0

    // 점수 계산
    val filledCells = remember(placements) { placements.flatMap { it.cells } }

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

    val totalScore: Int = remember(colScoreSum) { colScoreSum.sum() }

    // ✅ SnackBar Host (토스트 역할) + 우측하단 "준비 완료" 버튼을 겹쳐 배치
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
            // 최상단: 좌측 팀-상태 / 우측 버튼 그룹
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$team - $statusKo",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    shapes.forEach { def ->
                        val isSelected = def.type == selected.type
                        val enabled = canPlaceNow(def) && !isLocked

                        val countText = when (def.type) {
                            UnitType.TANK -> "${placedTank}/${maxTank}"
                            UnitType.INFANTRY -> "${placedInfantry}/${maxInfantry}"
                            UnitType.CANNON1, UnitType.CANNON2, UnitType.CANNON3 -> "${placedCannon}/${maxCannonTotal}"
                        }

                        UnitSelectButton(
                            label = def.label,
                            countText = countText,
                            isSelected = isSelected,
                            enabled = enabled,
                            color = unitColor(def.type),
                            blocks = def.blocks,
                            onClick = {
                                if (isLocked) {
                                    showToast("준비 완료 이후에는 전력을 재배치할 수 없습니다.")
                                } else {
                                    selected = def
                                }
                            }
                        )
                    }
                }
            }

            // 표 (가운데 정렬)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                        // 헤더 Row
                        Row {
                            TableCell("", 92.dp, 56.dp, true, background = null, onClick = null)
                            headers.forEach {
                                TableCell(
                                    "${it.title}\n${it.scoreText}",
                                    88.dp,
                                    56.dp,
                                    true,
                                    background = null,
                                    onClick = null
                                )
                            }
                        }

                        // 그리드
                        rows.forEachIndexed { rowIdx, rowName ->
                            Row {
                                TableCell(rowName, 92.dp, 44.dp, true, background = null, onClick = null)

                                headers.forEachIndexed { colIdx, _ ->
                                    val placement = cellToPlacement[colIdx to rowIdx]
                                    val bg = placement?.let { unitColor(it.type) } ?: Color.White

                                    TableCell(
                                        text = "",
                                        width = 88.dp,
                                        height = 44.dp,
                                        isHeader = false,
                                        background = bg,
                                        onClick = {
                                            val p = cellToPlacement[colIdx to rowIdx]
                                            if (p != null) removePlacement(p) else tryPlace(colIdx, rowIdx)
                                        }
                                    )
                                }
                            }
                        }

                        // 세로라인 배치점수 Row
                        Row {
                            TableCell("세로라인\n배치점수", 92.dp, 44.dp, true, background = null, onClick = null)
                            headers.forEachIndexed { colIdx, _ ->
                                TableCell("${colScoreSum[colIdx]}점", 88.dp, 44.dp, false, background = null, onClick = null)
                            }
                        }
                    }
                }
            }

            // 하단 합계 텍스트
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "배치점수 합계 : ",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${totalScore}점",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ✅ 우측 하단 "준비 완료" 버튼
        Button(
            onClick = { showConfirmDialog = true },
            enabled = isAllPlaced && !isLocked,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = navBarPadding.calculateBottomPadding() + 16.dp
                )
        ) {
            Text("준비 완료")
        }

        // ✅ 준비 완료 확인 다이얼로그
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text("준비 완료") },
                text = {
                    Text("준비 완료 이후에는 전력을 재배치할 수 없습니다. 이대로 배치를 완료하시겠습니까?")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConfirmDialog = false
                            isLocked = true
                            showToast("배치가 완료되었습니다.")
                        }
                    ) {
                        Text("확인")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        // ✅ 토스트(스낵바) 표시 위치: 하단 중앙
        SnackbarHost(
            hostState = snackHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBarPadding.calculateBottomPadding() + 12.dp)
        )
    }
}

@Composable
private fun UnitSelectButton(
    label: String,
    countText: String,
    isSelected: Boolean,
    enabled: Boolean,
    color: Color,
    blocks: List<Pair<Int, Int>>,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFF333333) else Color(0xFF999999)
    val bg = if (isSelected) Color(0xFFEAEAEA) else Color.White
    val textColor = if (enabled) Color.Black else Color(0xFFAAAAAA)

    Row(
        modifier = Modifier
            .border(1.dp, borderColor)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShapeMiniPreview(blocks = blocks, color = color)

        Column {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = textColor)
            Text(text = countText, style = MaterialTheme.typography.bodySmall, color = textColor)
        }
    }
}

@Composable
private fun ShapeMiniPreview(
    blocks: List<Pair<Int, Int>>,
    color: Color
) {
    val minX = blocks.minOf { it.first }
    val minY = blocks.minOf { it.second }
    val norm = blocks.map { (x, y) -> (x - minX) to (y - minY) }

    val maxX = norm.maxOf { it.first }
    val maxY = norm.maxOf { it.second }

    val block = 6.dp
    val gap = 2.dp

    Box(
        modifier = Modifier
            .width((maxX + 1) * (block + gap))
            .height((maxY + 1) * (block + gap))
    ) {
        norm.forEach { (x, y) ->
            Box(
                modifier = Modifier
                    .offset(x = x * (block + gap), y = y * (block + gap))
                    .size(block)
                    .background(color)
            )
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
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
