package com.aba.cpx.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aba.cpx.R
import com.aba.cpx.data.model.Config
import com.aba.cpx.data.repository.ConfigRepository

@Composable
fun IntroScreen(
    onStartClick: () -> Unit
) {
    val configRepo = remember { ConfigRepository() }
    var config by remember { mutableStateOf<Config?>(null) }

    // ✅ 깜빡임 애니메이션 (가독성을 위해 최소 투명도를 0.2로 설정해도 좋습니다)
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        config = configRepo.fetchMainConfig()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. 배경 이미지 로직
        val imageUrl = config?.url
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Background",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.bg),
                contentDescription = "Default Background",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // 2. 하단 텍스트 로직 (배경 없이 깜빡임)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp), // 하단에서 약간 더 위로 올림
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val label = config?.text?.takeIf { it.isNotBlank() } ?: "START"

            Text(
                text = label,
                // ✅ 텍스트 스타일 강화 (Bold + 그림자 추가)
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.8f),
                        offset = Offset(4f, 4f),
                        blurRadius = 8f
                    )
                ),
                color = Color.White, // 명확하게 흰색 지정
                modifier = Modifier
                    .alpha(alpha) // 애니메이션 적용
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onStartClick()
                    }
                    .padding(16.dp)
            )
        }
    }
}