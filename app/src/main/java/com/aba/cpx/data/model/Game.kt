package com.aba.cpx.data.model

enum class GameState { READY, RUNNING, PAUSED, FINISHED, ABORTED }

data class GameOrderItem(
    val teamId: Int = 0,
    val teamName: String = "",
    val order: Int = 0
)

data class Game(
    val id: String = "",
    val state: GameState = GameState.READY,
    val order: List<GameOrderItem> = emptyList(),
    val turnIndex: Int = 0,
    val currentTeamId: Int? = null,
    val turnToken: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val updatedAt: Long? = null,

    // ✅ 현재 점수(차감 결과)
    val scoresByTeamId: Map<Int, Int> = emptyMap(),

    // ✅ 추가: 원점수(게임 시작 시 고정)
    val initialScoresByTeamId: Map<Int, Int> = emptyMap(),

    // 기존
    val lastAttackedTeamId: Int? = null,

    // (있다면 유지)
    val attackedTargetsByTeamId: Map<Int, List<Int>> = emptyMap(),
    val lastTargetByTeamId: Map<Int, Int> = emptyMap(),
)
