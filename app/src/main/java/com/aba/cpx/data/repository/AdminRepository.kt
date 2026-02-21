package com.aba.cpx.data.repository

import com.aba.cpx.data.model.TeamStatus
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 운영/관리 기능(종료, 초기화, 운영화면 전용 조회)을 Repository 레이어에서 수행
 *
 * ✅ 원칙
 * - games 관련 처리는 GameRepository
 * - teams 관련 처리는 TeamRepository
 * - powerPlacements 관련 처리는 PowerPlacementRepository
 *
 * AdminRepository는 "조합/오케스트레이션"만 담당한다.
 */
class AdminRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val gameRepo: GameRepository = GameRepository(db),
    private val teamRepo: TeamRepository = TeamRepository(db),
    private val powerPlacementRepo: PowerPlacementRepository = PowerPlacementRepository(db),
) {
    // ------------------------------------------------------------
    // ✅ 운영 화면 전용: 전력배치 점수 로딩
    // ------------------------------------------------------------
    suspend fun loadPlacementScores(teamIds: List<Int>): Map<Int, Int> {
        return powerPlacementRepo.loadPlacementScores(teamIds)
    }

    // ------------------------------------------------------------
    // ✅ 종료:
    // - games/{gameId} 문서를 그대로 복사하여 games/{archiveId}로 저장 (날짜 기반 ID)
    // - games/{gameId}.state = completed
    // - 모든 teams.status = completed
    // ------------------------------------------------------------
    suspend fun finishGameAndArchiveAndCompleteTeams(gameId: String): String {
        // 1) games 처리(아카이브 + completed)는 GameRepository가 담당
        val archiveId = gameRepo.finishGameAndArchive(gameId)

        // 2) teams 처리(completed)는 TeamRepository가 담당
        //    (트랜잭션에 묶을 필요가 없고, 운영 기능이므로 단순 일괄 업데이트)
        teamRepo.updateAllTeamsStatusSuspend(TeamStatus.COMPLETED)

        return archiveId
    }

    // ------------------------------------------------------------
    // ✅ 초기화:
    // - teams.status = preparing
    // - games/{gameId} 필드 전체 초기화 + state waiting
    // - games/{gameId}/turns 서브컬렉션 모두 삭제
    // - powerPlacements 전체 초기화(placements/hitCells 비움)
    // ------------------------------------------------------------
    suspend fun resetAll(gameId: String) {
        // 1) teams preparing
        teamRepo.updateAllTeamsStatusSuspend(TeamStatus.PREPARING)

        // 2) games 초기화 + turns 삭제는 GameRepository가 담당
        gameRepo.resetLiveGame(gameId)

        // 3) powerPlacements 전체 초기화
        powerPlacementRepo.resetAllPowerPlacements()
    }
}