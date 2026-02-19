package com.aba.cpx.data.model

data class CellDto(
    val c: Int = 0,
    val r: Int = 0
)

data class PlacementDto(
    val id: Int = 0,
    val type: String = "",
    val cells: List<CellDto> = emptyList()
)

data class HitCellDto(
    val c: Int = 0,
    val r: Int = 0,
    val attackerTeamId: Int = 0,   // 누가 공격했는지
    val at: com.google.firebase.Timestamp? = null
)

data class PowerPlacement(
    val teamId: Int = 0,
    val placements: List<PlacementDto> = emptyList(),

    // ✅ 추가: 공격당한 좌표 기록
    val hitCells: List<HitCellDto> = emptyList(),

    val updatedAt: com.google.firebase.Timestamp? = null
)