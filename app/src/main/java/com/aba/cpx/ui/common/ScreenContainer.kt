package com.aba.cpx.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * ✅ 앱 전체 공통 상/하단 여백 컨테이너
 * 모든 Screen을 이 안에 넣으면 자동 적용됨
 */
@Composable
fun ScreenContainer(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 50.dp, bottom = 50.dp) // ✅ 여기서 전역 여백
    ) {
        content()
    }
}
