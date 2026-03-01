package com.aba.cpx

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.model.Team
import com.aba.cpx.data.model.TeamStatus
import com.aba.cpx.data.repository.GameRepository
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
    MyBoardView,
    Attack,
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

    val gameId = "default_game"

    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val gameRepo = remember { GameRepository() }

    // ✅ 전역 game 구독 (리컴포즈 보장)
    var liveGame by remember { mutableStateOf<Game?>(null, neverEqualPolicy()) }
    DisposableEffect(gameId) {
        gameRepo.ensureLiveGameExists(gameId)
        val reg = gameRepo.listenGame(
            gameId = gameId,
            onUpdate = { g -> liveGame = g },
            onError = { e -> Log.e("AppNavigator", "listenGame error: ${e.message}", e) }
        )
        onDispose { reg.remove() }
    }

    // ✅ “내 턴인데도 전략 화면(보드뷰)에 잠깐 머무를지” 플래그
    var stayOnBoardDuringMyTurn by remember { mutableStateOf(false) }

    fun navigate(to: Screen, clearBackStack: Boolean = false) {
        if (clearBackStack) {
            if (backStack.size == 1 && backStack.lastOrNull() == to) return
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

    val onBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val backBlockedScreens = setOf(Screen.Waiting)
    val teamScreens = setOf(Screen.PowerPlacement, Screen.MyBoardView, Screen.Attack)

    BackHandler(enabled = true) {
        // 1) Waiting은 완전 차단
        if (backBlockedScreens.contains(currentScreen)) return@BackHandler

        // 2) 스택이 2개 이상이면 pop
        if (popBack()) return@BackHandler

        // 3) 스택이 1개인데 팀 유저 화면이면 Waiting으로 보내 종료 방지
        val isTeamUser = (loggedInTeam != null && !isManager)
        if (isTeamUser && teamScreens.contains(currentScreen)) {
            stayOnBoardDuringMyTurn = false
            navigate(Screen.Waiting, clearBackStack = true)
            return@BackHandler
        }

        // 4) 그 외는 시스템 기본 동작(종료/이전)
        onBackDispatcher?.onBackPressed()
    }

    /**
     * ✅ 로그인 직후 1회용 fallback: 내 턴 판단
     * - GameRepository.submitTurn()에서 "라운드마다 시작팀 1칸 회전" 규칙을 쓰고 있으니
     *   여기 계산도 동일 규칙으로 맞춰야 함.
     */
    suspend fun isMyTurnNow(team: Team, gameId: String): Boolean {
        return try {
            val snap = db.collection("games").document(gameId).get().await()
            if (!snap.exists()) return false

            val stateStr = snap.getString("state") ?: ""
            val state = stateStr.trim().lowercase()

            val currentTeamId = snap.getLong("currentTeamId")?.toInt()
            if (state == GameState.WORKING.key && currentTeamId != null) {
                return currentTeamId == team.id
            }

            val turnIndex = (snap.getLong("turnIndex") ?: 0L).toInt()
            val orderList = snap.get("order") as? List<*>
            if (orderList.isNullOrEmpty()) return false

            val teamIdsInOrder: List<Int> = orderList.mapNotNull { any ->
                val m = any as? Map<*, *> ?: return@mapNotNull null
                (m["teamId"] as? Number)?.toInt()
            }

            val teamCount = teamIdsInOrder.size
            if (teamCount <= 0) return false

            // ✅ submitTurn()와 동일한 회전 규칙
            val round = turnIndex / teamCount       // 0=1R, 1=2R...
            val posInRound = turnIndex % teamCount  // 0..teamCount-1
            val roundStartOffset = round % teamCount
            val rotatedIndex = (roundStartOffset + posInRound) % teamCount

            val expectedCurrentTeamId = teamIdsInOrder.getOrNull(rotatedIndex) ?: return false
            expectedCurrentTeamId == team.id
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------
    // ✅ 전역 턴 네비게이션 가드
    // ------------------------------------------------------------
    var prevMyTurn by remember { mutableStateOf(false) }

    LaunchedEffect(
        liveGame?.state,
        liveGame?.currentTeamId,
        loggedInTeam?.id,
        isManager,
        currentScreen,
        stayOnBoardDuringMyTurn
    ) {
        val team = loggedInTeam ?: return@LaunchedEffect
        if (isManager) return@LaunchedEffect

        val g = liveGame ?: return@LaunchedEffect
        val myTurnNow = (g.state == GameState.WORKING && g.currentTeamId == team.id)

        // 턴 끝나면 전략 허용 해제
        if (!myTurnNow && stayOnBoardDuringMyTurn) {
            stayOnBoardDuringMyTurn = false
        }

        // ✅ 내 턴 "막 시작"하면 무조건 Attack
        if (myTurnNow && !prevMyTurn) {
            if (currentScreen != Screen.Attack) {
                navigate(Screen.Attack, clearBackStack = true)
            }
            prevMyTurn = true
            return@LaunchedEffect
        }

        // ✅ 내 턴 진행중이면 기본적으로 Attack 유지
        if (myTurnNow) {
            val allowStay = (currentScreen == Screen.MyBoardView && stayOnBoardDuringMyTurn)
            if (!allowStay && currentScreen != Screen.Attack) {
                navigate(Screen.Attack, clearBackStack = true)
            }
            prevMyTurn = true
            return@LaunchedEffect
        }

        // ✅ 내 턴이 아니면 Attack에 있지 않도록
        if (!myTurnNow) {
            if (currentScreen == Screen.Attack) {
                navigate(Screen.MyBoardView, clearBackStack = true)
            }
            prevMyTurn = false
        }
    }

    // ✅✅✅ 공통 로그아웃 (모든 화면에서 동일하게 사용)
    fun logoutAndGoLogin() {
        loggedInTeam = null
        isManager = false
        stayOnBoardDuringMyTurn = false
        prevMyTurn = false
        navigate(Screen.Login, clearBackStack = true)
    }

    when (currentScreen) {
        Screen.Intro -> {
            IntroScreen(onStartClick = { navigate(Screen.Login) })
        }

        Screen.Login -> {
            LoginScreen(
                onLoginSuccess = { team, managerFlag ->
                    loggedInTeam = team
                    isManager = managerFlag
                    stayOnBoardDuringMyTurn = false
                    prevMyTurn = false

                    if (managerFlag) {
                        navigate(Screen.ManagerDashboard, clearBackStack = true)
                        return@LoginScreen
                    }

                    when (team.status) {
                        TeamStatus.PREPARING -> navigate(Screen.PowerPlacement, clearBackStack = true)
                        TeamStatus.READY -> navigate(Screen.Waiting, clearBackStack = true)

                        TeamStatus.WORKING -> {
                            scope.launch {
                                val myTurn = isMyTurnNow(team, gameId)
                                val next = if (myTurn) Screen.Attack else Screen.MyBoardView
                                navigate(next, clearBackStack = true)
                            }
                        }

                        TeamStatus.COMPLETED -> navigate(Screen.MyBoardView, clearBackStack = true)
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
                    status = team.status.key,
                    onMoveToWaiting = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.Waiting, clearBackStack = true)
                    },
                    onLogout = { logoutAndGoLogin() }
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
                    onGoAttack = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.Attack, clearBackStack = true)
                    },
                    onGoMyBoardView = {
                        navigate(Screen.MyBoardView, clearBackStack = true)
                    }
                )
            }
        }

        Screen.MyBoardView -> {
            val team = loggedInTeam
            if (team == null) {
                navigate(Screen.Login, clearBackStack = true)
            } else {
                PowerPlacementViewerScreen(
                    teamId = team.id,
                    teamName = team.name,
                    onBack = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.Waiting, clearBackStack = true)
                    },
                    gameId = gameId,
                    onGoAttack = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.Attack, clearBackStack = true)
                    },
                    onLogout = { logoutAndGoLogin() }
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
                    onSubmitted = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.Waiting, clearBackStack = true)
                    },
                    onNotMyTurn = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.MyBoardView, clearBackStack = true)
                    },
                    onGoMyStrategy = {
                        stayOnBoardDuringMyTurn = true
                        navigate(Screen.MyBoardView, clearBackStack = true)
                    },
                    onLogout = { logoutAndGoLogin() }
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
                },
                onLogout = { logoutAndGoLogin() }
            )
        }

        Screen.PlacementViewer -> {
            PowerPlacementViewerScreen(
                teamId = viewTeamId,
                teamName = viewTeamName,
                score = viewTeamScore,
                onBack = { popBack(); Unit },
                onLogout = { logoutAndGoLogin() }
            )
        }
    }
}