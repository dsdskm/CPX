package com.aba.cpx.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.aba.cpx.R
import com.aba.cpx.data.model.Game
import com.aba.cpx.data.model.GameState
import com.aba.cpx.data.repository.GameRepository
import kotlinx.coroutines.delay

@Composable
fun WaitingScreen(
    teamId: Int,
    teamName: String,
    gameId: String = "default_game",
    onGoAttack: () -> Unit,
    onGoMyBoardView: () -> Unit
) {
    val gameRepo = remember { GameRepository() }

    var game by remember { mutableStateOf<Game?>(null) }
    var dots by remember { mutableIntStateOf(0) }

    // 점 애니메이션
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            dots = (dots + 1) % 4
        }
    }

    // 게임 상태 구독
    DisposableEffect(gameId) {
        val reg = gameRepo.listenGame(
            gameId = gameId,
            onUpdate = { g -> game = g },
            onError = { /* 여기선 조용히 */ }
        )
        onDispose { reg.remove() }
    }

    // ✅ 상태 변하면 화면 이동
    LaunchedEffect(game?.state, game?.currentTeamId) {
        val g = game ?: return@LaunchedEffect
        if (g.state == GameState.WAITING) return@LaunchedEffect

        if (g.currentTeamId == teamId) {
            onGoAttack()
        } else {
            onGoMyBoardView()
        }
    }

    val suffix = when (dots) {
        0 -> ""
        1 -> "."
        2 -> ".."
        else -> "..."
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ✅ 배경 이미지
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // ✅ 약한 오버레이(원하면 alpha만 조절)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.10f))
        )

        // ✅ 중앙 문구 (가독성 위해 흰색)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "곧 전투가 시작됩니다$suffix",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
        }
    }
}