package com.aba.cpx.ui.screens

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.aba.cpx.R
import com.aba.cpx.data.model.CellDto
import com.aba.cpx.data.model.PlacementDto
import com.aba.cpx.data.model.PowerPlacement
import com.aba.cpx.data.model.TeamStatus
import com.aba.cpx.data.model.headers
import com.aba.cpx.data.model.rows
import com.aba.cpx.data.repository.PowerPlacementRepository
import com.aba.cpx.data.repository.TeamRepository
import kotlinx.coroutines.launch

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

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun PowerPlacementScreen(
    teamId: Int,
    teamName: String,
    status: String,
    onMoveToWaiting: () -> Unit,
    onLogout: () -> Unit,
) {
    val colsCount = headers.size
    val rowsCount = rows.size

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    // ✅ Snackbar
    val snackHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun showToast(msg: String) {
        scope.launch {
            snackHostState.currentSnackbarData?.dismiss()
            snackHostState.showSnackbar(msg)
        }
    }

    val statusKo = remember(status) { TeamStatus.fromKey(status).labelKo }
    Log.d("KKH", "PowerPlacementScreen statusKo $statusKo")

    // ✅ 점수 파싱
    val colPoints: List<Int> = remember(headers) {
        headers.map { h -> h.scoreText.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    // ✅ 전력 모양 정의
    val shapes = remember {
        listOf(
            ShapeDef(UnitType.TANK, "탱크", listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)),
            ShapeDef(UnitType.CANNON1, "대포1", listOf(0 to -1, 0 to 0, 0 to 1)),
            ShapeDef(UnitType.CANNON2, "대포2", listOf(1 to -1, 0 to 0, 0 to 1)),
            ShapeDef(UnitType.CANNON3, "대포3", listOf(1 to -1, 0 to 0, -1 to 1)),
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

    // ✅ 팀 status가 ready면 잠금
    var isLocked by remember { mutableStateOf(status.lowercase() == "ready") }

    // ✅ 준비 완료 컨펌
    var showConfirmDialog by remember { mutableStateOf(false) }

    // ✅ 로그아웃 컨펌 다이얼로그 (추가)
    var showLogoutDialog by remember { mutableStateOf(false) }

    // ✅ 복원/저장 진행 상태
    var isRestoring by remember { mutableStateOf(true) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // 배치 저장
    var placements by remember { mutableStateOf<List<Placement>>(emptyList()) }
    var nextPlacementId by remember { mutableStateOf(1) }

    // 셀 -> 배치
    val cellToPlacement: Map<Pair<Int, Int>, Placement> = remember(placements) {
        buildMap {
            placements.forEach { p -> p.cells.forEach { cell -> put(cell, p) } }
        }
    }

    // ✅ 색상
    val tankColor = Color(0xFFFF2D2D)
    val cannonColor = Color(0xFF6EC8FF)
    val infantryColor = Color(0xFF222222)

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
        if (isRestoring || isSubmitting) return

        if (!canPlaceNow(selected)) {
            showToast(allPlacedMessage(selected.type))
            return
        }

        val targetCells = selected.blocks.map { (dx, dy) -> (anchorCol + dx) to (anchorRow + dy) }
        val outOfBounds = targetCells.any { (c, r) -> !isInsideGrid(c, r) }
        val overlapped = targetCells.any { cellToPlacement.containsKey(it) }

        if (outOfBounds || overlapped) {
            showToast("해당 위치에는 배치할 수 없습니다.")
            return
        }

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
        if (isRestoring || isSubmitting) return

        placements = placements.filterNot { it.id == p.id }
        inc(p.type)
    }

    val placedTank = maxTank - remainTank
    val placedCannon = maxCannonTotal - remainCannonTotal
    val placedInfantry = maxInfantry - remainInfantry

    val isAllPlaced = remainTank == 0 && remainCannonTotal == 0 && remainInfantry == 0

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

    fun toPlacementDtos(list: List<Placement>): List<PlacementDto> =
        list.map { p ->
            PlacementDto(
                id = p.id,
                type = p.type.name,
                cells = p.cells.map { (c, r) -> CellDto(c = c, r = r) }
            )
        }

    fun dtoToUnitType(type: String): UnitType? =
        runCatching { UnitType.valueOf(type) }.getOrNull()

    fun fromPlacementDtos(dtos: List<PlacementDto>): List<Placement> =
        dtos.mapNotNull { dto ->
            val ut = dtoToUnitType(dto.type) ?: return@mapNotNull null
            val cells = dto.cells.map { it.c to it.r }
            Placement(id = dto.id, type = ut, cells = cells)
        }

    fun recomputeRemaining(list: List<Placement>) {
        val tanks = list.count { it.type == UnitType.TANK }
        val infs = list.count { it.type == UnitType.INFANTRY }
        val cannons = list.count {
            it.type == UnitType.CANNON1 || it.type == UnitType.CANNON2 || it.type == UnitType.CANNON3
        }

        remainTank = (maxTank - tanks).coerceAtLeast(0)
        remainInfantry = (maxInfantry - infs).coerceAtLeast(0)
        remainCannonTotal = (maxCannonTotal - cannons).coerceAtLeast(0)
    }

    val repo = remember { PowerPlacementRepository() }
    val teamRepo = remember { TeamRepository() }

    LaunchedEffect(teamId) {
        isLocked = status.lowercase() == "ready"
        isRestoring = true
        restoreError = null

        repo.loadOnce(
            teamId = teamId,
            onSuccess = { doc ->
                val restored = fromPlacementDtos(doc?.placements.orEmpty())
                placements = restored
                recomputeRemaining(restored)

                val maxId = restored.maxOfOrNull { it.id } ?: 0
                nextPlacementId = maxId + 1

                isRestoring = false
            },
            onFail = { e ->
                restoreError = e.message ?: "배치 정보를 불러오지 못했습니다."
                isRestoring = false
            }
        )
    }

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
                .background(Color.Black.copy(alpha = 0.20f))
        )

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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$teamName - $statusKo",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${totalScore}점",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        modifier = Modifier.weight(2.0f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            shapes.forEach { def ->
                                val isSelected = def.type == selected.type
                                val enabled = canPlaceNow(def) && !isLocked && !isRestoring && !isSubmitting

                                val countText = when (def.type) {
                                    UnitType.TANK -> "${placedTank}/${maxTank}"
                                    UnitType.INFANTRY -> "${placedInfantry}/${maxInfantry}"
                                    UnitType.CANNON1, UnitType.CANNON2, UnitType.CANNON3 ->
                                        "${placedCannon}/${maxCannonTotal}"
                                }

                                UnitSelectButton(
                                    label = def.label,
                                    countText = countText,
                                    isSelected = isSelected,
                                    enabled = enabled,
                                    color = unitColor(def.type),
                                    blocks = def.blocks,
                                    onClick = {
                                        if (isLocked) showToast("준비 완료 이후에는 전력을 재배치할 수 없습니다.")
                                        else selected = def
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Button(
                            onClick = { showConfirmDialog = true },
                            enabled = isAllPlaced && !isLocked && !isRestoring && !isSubmitting
                        ) {
                            Text(if (isSubmitting) "저장중..." else "준비 완료")
                        }

                        Spacer(Modifier.width(8.dp))

                        // ✅✅✅ 로그아웃 버튼: 준비완료 버튼처럼 Button + 빨간색 + 라운드 느낌
                        Button(
                            onClick = { showLogoutDialog = true },
                            enabled = !isRestoring && !isSubmitting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("로그아웃", color = Color.White)
                        }
                    }
                }
            }

            if (isRestoring) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(10.dp))
                        Text("배치 불러오는 중...")
                    }
                }
            }

            if (!isRestoring && restoreError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f))
                ) {
                    Text(
                        text = restoreError!!,
                        color = Color.Red,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, Color(0xFF999999))
                        .padding(8.dp)
                ) {
                    val gridW = maxWidth
                    val gridH = maxHeight

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
                                    TableCell(
                                        "${it.title}\n${it.scoreText}",
                                        cellW,
                                        headerH,
                                        true,
                                        background = null,
                                        onClick = null
                                    )
                                }
                            }

                            rows.forEachIndexed { rowIdx, rowName ->
                                Row {
                                    TableCell(rowName, leftColW, rowH, true, background = null, onClick = null)

                                    for (colIdx in 0 until colsCount) {
                                        val placement = cellToPlacement[colIdx to rowIdx]
                                        val bg = placement?.let { unitColor(it.type) } ?: Color.White

                                        TableCell(
                                            text = "",
                                            width = cellW,
                                            height = rowH,
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

                            Row {
                                TableCell("세로라인\n배치점수", leftColW, footerH, true, background = null, onClick = null)
                                headers.forEachIndexed { colIdx, _ ->
                                    TableCell(
                                        "${colScoreSum[colIdx]}점",
                                        cellW,
                                        footerH,
                                        false,
                                        background = null,
                                        onClick = null
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ✅ 준비완료 Confirm Dialog
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { if (!isSubmitting) showConfirmDialog = false },
                title = { Text("준비 완료") },
                text = { Text("준비 완료 이후에는 전력을 재배치할 수 없습니다. 이대로 배치를 완료하시겠습니까?") },
                confirmButton = {
                    Button(
                        enabled = !isSubmitting,
                        onClick = {
                            showConfirmDialog = false
                            isSubmitting = true

                            val doc = PowerPlacement(
                                teamId = teamId,
                                placements = toPlacementDtos(placements)
                            )

                            repo.savePlacement(
                                doc = doc,
                                onSuccess = {
                                    teamRepo.updateStatusByTeamId(
                                        teamId = teamId,
                                        newStatus = "ready",
                                        onSuccess = {
                                            isSubmitting = false
                                            isLocked = true
                                            showToast("준비 완료! 대기 화면으로 이동합니다.")
                                            onMoveToWaiting()
                                        },
                                        onFail = { e ->
                                            isSubmitting = false
                                            showToast("팀 상태 업데이트 실패: ${e.message ?: "unknown"}")
                                        }
                                    )
                                },
                                onFail = { e ->
                                    isSubmitting = false
                                    showToast("저장 실패: ${e.message ?: "unknown"}")
                                }
                            )
                        }
                    ) { Text("확인") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isSubmitting,
                        onClick = { showConfirmDialog = false }
                    ) { Text("취소") }
                }
            )
        }

        // ✅✅✅ 로그아웃 Confirm Dialog (추가)
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("로그아웃", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

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