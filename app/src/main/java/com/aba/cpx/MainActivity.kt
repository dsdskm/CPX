package com.aba.cpx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aba.cpx.data.model.Team
import com.aba.cpx.ui.screens.IntroScreen
import com.aba.cpx.ui.screens.LoginScreen
import com.aba.cpx.ui.screens.PowerPlacementScreen
import com.aba.cpx.ui.theme.CPXTheme

// 현재 표시할 화면을 정의하는 Enum
enum class Screen {
    Intro,
    Login,
    PowerPlacement
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CPXTheme {
                AppNavigator()
            }
        }
    }
}

@Composable
fun AppNavigator() {
    var currentScreen by remember { mutableStateOf(Screen.Intro) }

    // ✅ 로그인 후 Team 전체를 들고가야 status를 보여줄 수 있음
    var loggedInTeam by remember { mutableStateOf<Team?>(null) }

    when (currentScreen) {
        Screen.Intro -> {
            IntroScreen(onStartClick = { currentScreen = Screen.Login })
        }

        Screen.Login -> {
            // ✅ LoginScreen이 team "String"이 아니라 Team을 넘겨야 함
            //    (지금 PowerPlacementScreen이 team + status를 필요로 함)
            LoginScreen(
                onLoginSuccess = { teamName ->
                    // ⚠️ 현재 LoginScreen은 teamName(String)만 넘기고 있어서
                    //    여기서 status를 만들 수가 없음.
                    //    해결: LoginScreen의 onLoginSuccess 시그니처를 (Team) -> Unit 으로 바꾸는 걸 권장.
                    //    일단 임시로 status는 "waiting"으로 고정.
                    loggedInTeam = Team(
                        id = -1,
                        name = teamName,
                        order = 0,
                        password = "",
                        status = "waiting"
                    )
                    currentScreen = Screen.PowerPlacement
                }
            )
        }

        Screen.PowerPlacement -> {
            val team = loggedInTeam
            PowerPlacementScreen(
                team = team?.name ?: "Unknown",
                status = team?.status ?: "waiting"
            )
        }
    }
}

@Composable
fun MainScreen(team: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "환영합니다, $team!",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}
