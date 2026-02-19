package com.aba.cpx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.aba.cpx.data.model.Team
import com.aba.cpx.ui.common.ScreenContainer
import com.aba.cpx.ui.screens.*
import com.aba.cpx.ui.theme.CPXTheme

enum class Screen {
    Intro,
    Login,
    PowerPlacement,
    Waiting,
    MyBoardView,      // ✅ 추가 (내 전력배치 보기)
    Attack,           // ✅ 추가 (3칸 공격)
    ManagerDashboard,
    PlacementView
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
    var viewTeamScore by remember { mutableStateOf<Int?>(null) }   // ✅ 추가
    val gameId = "default_game" // ✅ 고정 사용

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
            backStack.removeAt(backStack.lastIndex) // ✅ removeLast() 대신
            true
        } else false
    }

    // ✅ 팀 화면에서 뒤로가기 정책:
    // - Waiting: 완전 차단
    // - Attack/MyBoardView: 원하면 차단 가능 (여기선 "차단 안 함"으로 두되 자동 전환이 주 흐름)
    val backBlockedScreens = setOf(Screen.Waiting)
    val backEnabled = backStack.size > 1 && !backBlockedScreens.contains(currentScreen)

    BackHandler(enabled = backEnabled) { popBack() }

    // ✅ Waiting에서 back 눌러도 먹기
    if (currentScreen == Screen.Waiting) {
        BackHandler(enabled = true) { /* do nothing */ }
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

                    val next = if (managerFlag) {
                        Screen.ManagerDashboard
                    } else {
                        if (team.status.lowercase() == "waiting") Screen.PowerPlacement else Screen.Waiting
                    }

                    // ✅ 로그인 이후 Intro/Login으로 되돌아가지 않게 루트 교체
                    navigate(next, clearBackStack = true)
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
                    status = team.status,
                    onMoveToWaiting = {
                        // ✅ 로컬 상태도 ready로 반영
                        loggedInTeam = team.copy(status = "ready")
                        // ✅ Waiting은 루트로 두고 뒤로가기 차단
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
                    onSubmitted = {
                        // 제출 후에는 Waiting으로 보내면 -> 다시 자동 라우팅 됨
                        navigate(Screen.Waiting, clearBackStack = true)
                    },
                    onNotMyTurn = {
                        // 턴이 넘어가면 내 배치 보기로
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
                    navigate(Screen.PlacementView)
                }
            )
        }

        Screen.PlacementView -> {
            PowerPlacementViewerScreen(
                teamId = viewTeamId,
                teamName = viewTeamName,
                score = viewTeamScore,
                onBack = { popBack() }
            )
        }
    }
}
