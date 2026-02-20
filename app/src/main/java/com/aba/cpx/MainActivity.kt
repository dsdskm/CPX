package com.aba.cpx

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.aba.cpx.data.model.Team
import com.aba.cpx.data.model.TeamStatus
import com.aba.cpx.ui.common.ScreenContainer
import com.aba.cpx.ui.screens.*
import com.aba.cpx.ui.theme.CPXTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class Screen {
    Intro,
    Login,
    PowerPlacement,
    Waiting,
    MyBoardView,      // ✅ 내 전력배치 보기
    Attack,           // ✅ 3칸 공격
    ManagerDashboard,
    PlacementViewer
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CPXTheme {
                ScreenContainer {
                    AppNavigator()
                }
            }
        }
    }
}

@Composable
fun AppNavigator() {
    val backStack = remember { mutableStateListOf(Screen.Intro) }
    val currentScreen = backStack.last()

    var loggedInTeam by remember { mutableStateOf<Team?>(null) }
    var isManager by remember { mutableStateOf(false) }

    // manager placement view target
    var viewTeamId by remember { mutableStateOf(0) }
    var viewTeamName by remember { mutableStateOf("") }
    var viewTeamScore by remember { mutableStateOf<Int?>(null) }

    val gameId = "default_game" // ✅ 고정 사용

    // ✅ Firestore + coroutine scope
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()

    fun navigate(to: Screen, clearBackStack: Boolean = false) {
        if (clearBackStack) {
            backStack.clear()
            backStack.add(to)
            return
        }
        if (backStack.lastOrNull() != to) backStack.add(to)
    }

    fun popBack(): Boolean {
        return if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            true
        } else false
    }

    // ✅ 팀 화면에서 뒤로가기 정책:
    // - Waiting: 완전 차단
    val backBlockedScreens = setOf(Screen.Waiting)
    val backEnabled = backStack.size > 1 && !backBlockedScreens.contains(currentScreen)

    // ✅ Compose BackHandler는 ()->Unit 이므로 Boolean 반환값은 무시 처리
    BackHandler(enabled = backEnabled) { popBack(); Unit }

    // ✅ Waiting에서 back 눌러도 먹기
    if (currentScreen == Screen.Waiting) {
        BackHandler(enabled = true) { /* do nothing */ }
    }

    /**
     * ✅ WORKING 상태에서 "내 턴"인지 판정
     *
     * 우선순위:
     * 1) game.currentTeamId 가 있으면 그게 최우선
     * 2) game.order(list) + turnIndex로 계산
     * 3) 마지막 fallback: team.order 와 turnIndex 비교 (0-based/1-based 둘 다 커버)
     */
    suspend fun isMyTurnNow(team: Team, gameId: String): Boolean {
        return try {
            // ⚠️ 게임 문서 위치: GameRepository 구현을 모르므로 일반적으로 "games/{gameId}" 가정
            // 만약 컬렉션명이 다르면 여기만 바꾸면 됨.
            val snap = db.collection("games").document(gameId).get().await()
            if (!snap.exists()) return false

            val currentTeamId = (snap.getLong("currentTeamId") ?: 0L).toInt()
            if (currentTeamId != 0) {
                return currentTeamId == team.id
            }

            val turnIndex = (snap.getLong("turnIndex") ?: 0L).toInt()

            // order 배열이 있으면 그걸로 계산
            val orderList = snap.get("order") as? List<*>
            if (!orderList.isNullOrEmpty()) {
                val items = orderList.mapNotNull { any ->
                    val m = any as? Map<*, *> ?: return@mapNotNull null
                    val teamId = (m["teamId"] as? Number)?.toInt() ?: return@mapNotNull null
                    val order = (m["order"] as? Number)?.toInt() ?: 0
                    teamId to order
                }.sortedBy { it.second }

                if (items.isNotEmpty()) {
                    val idx = if (turnIndex >= 0) turnIndex % items.size else 0
                    val currentTeamIdByIndex = items[idx].first
                    return currentTeamIdByIndex == team.id
                }
            }

            // 마지막 fallback: team.order vs turnIndex (0-based/1-based 모두 커버)
            // - 팀 order가 0부터면: order == turnIndex
            // - 팀 order가 1부터면: order == turnIndex + 1
            (team.order == turnIndex) || (team.order == turnIndex + 1)
        } catch (_: Exception) {
            false
        }
    }

    when (currentScreen) {
        Screen.Intro -> {
            IntroScreen(onStartClick = { navigate(Screen.Login) })
        }

        Screen.Login -> {
            LoginScreen(
                onLoginSuccess = { team, managerFlag ->
                    // Global
                    loggedInTeam = team
                    isManager = managerFlag

                    // ✅ 매니저는 기존대로
                    if (managerFlag) {
                        navigate(Screen.ManagerDashboard, clearBackStack = true)
                        return@LoginScreen
                    }

                    when (team.status) {
                        TeamStatus.PREPARING -> {
                            // 준비중 -> 전력배치
                            navigate(Screen.PowerPlacement, clearBackStack = true)
                        }

                        TeamStatus.READY -> {
                            // 준비완료 -> 대기
                            navigate(Screen.Waiting, clearBackStack = true)
                        }

                        TeamStatus.WORKING -> {
                            // 진행중 -> 내 턴이면 Attack, 아니면 내 보드 보기
                            scope.launch {
                                val myTurn = isMyTurnNow(team, gameId)
                                val next = if (myTurn) Screen.Attack else Screen.MyBoardView
                                navigate(next, clearBackStack = true)
                            }
                        }

                        TeamStatus.COMPLETED -> {
                            // 종료 -> 정책이 애매하지만, 일단 Waiting으로 보냄(필요시 Intro/완료화면으로 변경 가능)
                            navigate(Screen.MyBoardView, clearBackStack = true)
                        }
                    }
                }
            )
        }

        Screen.PowerPlacement -> {
            val team = loggedInTeam
            if (team == null) {
                navigate(Screen.Login, clearBackStack = true)
            } else {
                PowerPlacementScreen(
                    teamId = team.id,
                    teamName = team.name,
                    // ✅ 화면/DB에서 영어 String이 필요하면 key로 전달
                    status = team.status.key,
                    onMoveToWaiting = {
                        navigate(Screen.Waiting, clearBackStack = true)
                    }
                )
            }
        }

        Screen.Waiting -> {
            val team = loggedInTeam
            if (team == null) {
                navigate(Screen.Login, clearBackStack = true)
            } else {
                WaitingScreen(
                    teamId = team.id,
                    teamName = team.name,
                    gameId = gameId,
                    onGoAttack = { navigate(Screen.Attack, clearBackStack = true) },
                    onGoMyBoardView = { navigate(Screen.MyBoardView, clearBackStack = true) }
                )
            }
        }

        Screen.MyBoardView -> {
            val team = loggedInTeam
            if (team == null) {
                navigate(Screen.Login, clearBackStack = true)
            } else {
                // ✅ 내 배치 보기(읽기) + 내 턴 되면 Attack으로 자동 이동
                PowerPlacementViewerScreen(
                    teamId = team.id,
                    teamName = team.name,
                    onBack = {
                        // 정책: 팀 화면에선 보통 back 의미 없으니 Waiting으로 보내는게 안전
                        navigate(Screen.Waiting, clearBackStack = true)
                    },
                    gameId = gameId,
                    onGoAttack = {
                        navigate(Screen.Attack, clearBackStack = true)
                    }
                )
            }
        }

        Screen.Attack -> {
            val team = loggedInTeam
            if (team == null) {
                navigate(Screen.Login, clearBackStack = true)
            } else {
                AttackScreen(
                    teamId = team.id,
                    teamName = team.name,
                    gameId = gameId,
                    onSubmitted = { navigate(Screen.Waiting, clearBackStack = true) },
                    onNotMyTurn = { navigate(Screen.MyBoardView, clearBackStack = true) },
                    onGoMyStrategy = {
                        navigate(
                            Screen.MyBoardView,
                            clearBackStack = false
                        )
                    } // ✅ "나의 전략 확인"
                )
            }
        }

        Screen.ManagerDashboard -> {
            ManagerDashboardScreen(
                gameId = gameId,
                onViewPlacement = { tId, tName, score ->
                    viewTeamId = tId
                    viewTeamName = tName
                    viewTeamScore = score
                    navigate(Screen.PlacementViewer)
                }
            )
        }

        Screen.PlacementViewer -> {
            PowerPlacementViewerScreen(
                teamId = viewTeamId,
                teamName = viewTeamName,
                score = viewTeamScore,
                // ✅ onBack이 Unit 타입이면 Boolean 반환값 무시 처리
                onBack = { popBack(); Unit }
            )
        }
    }
}
