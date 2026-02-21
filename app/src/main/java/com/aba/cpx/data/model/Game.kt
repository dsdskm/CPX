package com.aba.cpx.data.model

data class GameOrderItem(
    val teamId: Int = 0,
    val teamName: String = "",
    val order: Int = 0
)

enum class GameState(val key: String, val labelKo: String) {
    WAITING("waiting", "대기"),
    WORKING("working", "진행"),
    PAUSED("paused", "일시정지"),
    COMPLETED("completed", "종료"),
}

data class Game(
    val id: String = "",
    val state: GameState = GameState.WAITING,

    val order: List<GameOrderItem> = emptyList(),
    val turnIndex: Int = 0,
    val currentTeamId: Int? = null,
    val turnToken: String? = null,
    val lastAttackedTeamId: Int? = null,

    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val updatedAt: Long? = null,

    /**
     * ✅ 현재 점수(표시/게임진행용)
     * - 항상 initial - damageTaken + bonus 로 재계산된 값이 들어오도록 Repository에서 보장
     */
    val scoresByTeamId: Map<Int, Int> = emptyMap(),

    /**
     * ✅ 시작 점수(전력배치 점수)
     */
    val initialScoresByTeamId: Map<Int, Int> = emptyMap(),

    /**
     * ✅ 종료 점수(게임 종료 시 스냅샷)
     */
    val finalScoresByTeamId: Map<Int, Int> = emptyMap(),

    /**
     * ✅ 내가 피격되어 깎인 점수 누적(감점)
     */
    val damageTakenByTeamId: Map<Int, Int> = emptyMap(),

    /**
     * ✅ 내가 명중시켜 얻은 점수 누적(가점)
     */
    val bonusByTeamId: Map<Int, Int> = emptyMap(),

    /**
     * ✅ 공격 기록
     */
    val attackedTargetsByTeamId: Map<Int, List<Int>> = emptyMap(),
    val lastTargetByTeamId: Map<Int, Int> = emptyMap(),
)