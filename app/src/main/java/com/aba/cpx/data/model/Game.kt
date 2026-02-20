package com.aba.cpx.data.model

/**
 * 게임 상태는 아래 4개만 허용
 * DB 저장값(key)과 UI 표시값(labelKo)을 함께 관리
 *
 * - waiting   : 대기중
 * - working   : 진행중
 * - paused    : 일시정지
 * - completed : 종료
 */
enum class GameState(val key: String, val labelKo: String) {
    WAITING("waiting", "대기중"),
    WORKING("working", "진행중"),
    PAUSED("paused", "일시정지"),
    COMPLETED("completed", "종료");

    companion object {
        /**
         * DB/네트워크에서 들어온 문자열을 enum으로 안전 변환
         * - null/공백/알 수 없는 값이면 WAITING 기본
         */
        fun fromKey(key: String?): GameState {
            if (key.isNullOrBlank()) return WAITING
            return entries.firstOrNull { it.key == key } ?: WAITING
        }
    }
}

data class GameOrderItem(
    val teamId: Int = 0,
    val teamName: String = "",
    val order: Int = 0
)

data class Game(
    val id: String = "",
    val state: GameState = GameState.WAITING,  // ✅ 기본값: waiting(대기중)
    val order: List<GameOrderItem> = emptyList(),
    val turnIndex: Int = 0,
    val currentTeamId: Int? = null,
    val turnToken: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val updatedAt: Long? = null,

    // ✅ 현재 점수(차감 결과)
    val scoresByTeamId: Map<Int, Int> = emptyMap(),

    // ✅ 원점수(게임 시작 시 고정)
    val initialScoresByTeamId: Map<Int, Int> = emptyMap(),

    // 기존
    val lastAttackedTeamId: Int? = null,

    // (있다면 유지)
    val attackedTargetsByTeamId: Map<Int, List<Int>> = emptyMap(),
    val lastTargetByTeamId: Map<Int, Int> = emptyMap(),
) {
    /** UI 표시용 한글 텍스트 */
    val stateTextKo: String get() = state.labelKo

    /** DB 저장용 영어 key */
    val stateKey: String get() = state.key
}