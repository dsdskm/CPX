package com.aba.cpx.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IntroScreen(onStartClick: () -> Unit) {
    // "START" 텍스트의 깜빡이는 애니메이션을 위한 alpha 값
    val infiniteTransition = rememberInfiniteTransition(label = "BlinkingStart")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "StartAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 1. 중앙에 "CPX" 텍스트 표시
        Text(
            text = "CPX",
            fontSize = 96.sp, // 글자 크기 키움
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineLarge
        )

        // 2. 하단에 깜빡이는 "START" 텍스트 추가
        Text(
            text = "START",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter) // 하단 중앙에 배치
                .padding(bottom = 128.dp)
                .alpha(alpha) // 애니메이션으로 alpha 값 적용
                .clickable(onClick = onStartClick) // 3. 클릭 시 onStartClick 콜백 실행
        )
    }
}
