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
    // - ‘나의 전략 확인’을 눌렀을 때만 true
    // - 턴이 끝나면 자동 false
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

    // ------------------------------------------------------------
    // ✅ 뒤로가기 정책 (스택 1개여도 종료되지 않게)
    // - Waiting: 완전 차단
    // - 스택 > 1: pop
    // - 스택 == 1:
    //    - 팀 화면(Attack/MyBoardView/PowerPlacement) 이면 Waiting으로
    //    - 그 외(Intro/Login/ManagerDashboard 등)면 시스템 back
    // ------------------------------------------------------------
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
     */
    suspend fun isMyTurnNow(team: Team, gameId: String): Boolean {
        return try {
            val snap = db.collection("games").document(gameId).get().await()
            if (!snap.exists()) return false

            val currentTeamId = (snap.getLong("currentTeamId") ?: 0L).toInt()
            if (currentTeamId != 0) return currentTeamId == team.id

            val turnIndex = (snap.getLong("turnIndex") ?: 0L).toInt()
            val orderList = snap.get("order") as? List<*>

            if (!orderList.isNullOrEmpty()) {
                val items = orderList.mapNotNull { any ->
                    val m = any as? Map<*, *> ?: return@mapNotNull null
                    val tid = (m["teamId"] as? Number)?.toInt() ?: return@mapNotNull null
                    val ord = (m["order"] as? Number)?.toInt() ?: 0
                    tid to ord
                }.sortedBy { it.second }

                if (items.isNotEmpty()) {
                    val idx = if (turnIndex >= 0) turnIndex % items.size else 0
                    return items[idx].first == team.id
                }
            }

            (team.order == turnIndex) || (team.order == turnIndex + 1)
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------
    // ✅ 전역 턴 네비게이션 가드 (충돌 해결)
    //
    // 규칙:
    // 1) "내 턴이 새로 시작되는 순간"에는 무조건 Attack으로 이동 (보드뷰 예외 무시)
    // 2) 내 턴이 이미 진행중일 때:
    //    - 기본은 Attack에 머물게 함
    //    - 단, stayOnBoardDuringMyTurn=true 이고 현재 화면이 MyBoardView이면 머무르게 허용
    // 3) 내 턴이 끝나면(=myTurn false) stayOnBoardDuringMyTurn 자동 해제
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
                            // ✅ 로그인 직후 liveGame이 아직 안 왔을 수 있어 1회 fallback
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
                    onSubmitted = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.Waiting, clearBackStack = true)
                    },
                    onNotMyTurn = {
                        stayOnBoardDuringMyTurn = false
                        navigate(Screen.MyBoardView, clearBackStack = true)
                    },
                    onGoMyStrategy = {
                        // ✅ 내가 눌러서 전략 확인하려는 경우만 예외 허용
                        stayOnBoardDuringMyTurn = true
                        navigate(Screen.MyBoardView, clearBackStack = true)
                    }
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
                onBack = { popBack(); Unit }
            )
        }
    }
}