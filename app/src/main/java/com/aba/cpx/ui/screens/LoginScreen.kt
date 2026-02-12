package com.aba.cpx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aba.cpx.data.model.Team
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (team: String) -> Unit
) {
    var selectedTeam by remember { mutableStateOf<Team?>(null) }
    var passwordInput by remember { mutableStateOf("") }

    // Firestore 로딩 상태
    var teams by remember { mutableStateOf<List<Team>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 로그인 검증 에러 (예: 비번 불일치)
    var loginError by remember { mutableStateOf<String?>(null) }

    // ✅ Firestore: teams 컬렉션 실시간 구독
    DisposableEffect(Unit) {
        val db = FirebaseFirestore.getInstance()
        val query = db.collection("teams").orderBy("order")

        var reg: ListenerRegistration? = null
        reg = query.addSnapshotListener { snapshot, e ->
            if (e != null) {
                errorMsg = e.message ?: "팀 목록을 불러오지 못했습니다."
                isLoading = false
                return@addSnapshotListener
            }

            val docs = snapshot?.documents.orEmpty()
            val list = docs.mapNotNull { d ->
                // Firestore 필드: id(Int), name(String), order(Int), password(String)
                val idLong = d.getLong("id") ?: return@mapNotNull null
                val name = d.getString("name") ?: return@mapNotNull null
                val orderLong = d.getLong("order") ?: 0L
                val pw = d.getString("password") ?: ""

                Team(
                    id = idLong.toInt(),
                    name = name,
                    order = orderLong.toInt(),
                    password = pw
                )
            }

            teams = list
            isLoading = false
            errorMsg = null

            // 선택된 팀이 목록에서 사라졌으면 선택 해제
            selectedTeam?.let { sel ->
                if (teams.none { it.id == sel.id }) {
                    selectedTeam = null
                    passwordInput = ""
                    loginError = null
                }
            }
        }

        onDispose { reg?.remove() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F0F0)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("팀을 선택하세요", style = MaterialTheme.typography.titleLarge)

                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) { CircularProgressIndicator() }
                    }

                    errorMsg != null -> {
                        Text(
                            text = errorMsg!!,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    teams.isEmpty() -> {
                        Text("등록된 팀이 없습니다.", textAlign = TextAlign.Center)
                    }

                    else -> {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            items(teams, key = { it.id }) { team ->
                                TeamButton(
                                    text = team.name, // ✅ name으로 표시
                                    isSelected = selectedTeam?.id == team.id,
                                    onClick = {
                                        selectedTeam = team
                                        passwordInput = ""
                                        loginError = null
                                    }
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                        }
                    }
                }

                // ✅ 로그인 에러 메시지(비번 불일치 등)
                if (loginError != null) {
                    Text(
                        text = loginError!!,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            loginError = null
                        },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                        enabled = selectedTeam != null && !isLoading && errorMsg == null
                    )

                    Button(
                        onClick = {
                            val team = selectedTeam ?: return@Button
                            if (passwordInput == team.password) {
                                loginError = null
                                onLoginSuccess(team.name)
                            } else {
                                loginError = "비밀번호가 올바르지 않습니다."
                            }
                        },
                        modifier = Modifier.height(56.dp),
                        enabled = selectedTeam != null && passwordInput.isNotEmpty() && !isLoading && errorMsg == null
                    ) {
                        Text("로그인", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TeamButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.Black

    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 60.dp)
            .background(backgroundColor, shape = RoundedCornerShape(8.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = contentColor, textAlign = TextAlign.Center)
    }
}
