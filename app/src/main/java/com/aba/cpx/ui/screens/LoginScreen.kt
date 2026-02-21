package com.aba.cpx.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aba.cpx.R
import com.aba.cpx.data.model.Team
import com.aba.cpx.data.model.TeamStatus
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (team: Team, isManager: Boolean) -> Unit
) {
    // -------------------------
    // Firestore 로딩 상태
    // -------------------------
    var teams by remember { mutableStateOf<List<Team>>(emptyList()) }
    var managers by remember { mutableStateOf<List<Team>>(emptyList()) }

    var isLoadingTeams by remember { mutableStateOf(true) }
    var isLoadingManagers by remember { mutableStateOf(true) }

    var errorTeams by remember { mutableStateOf<String?>(null) }
    var errorManagers by remember { mutableStateOf<String?>(null) }

    // -------------------------
    // 팝업(로그인 다이얼로그) 상태
    // -------------------------
    var showLoginDialog by remember { mutableStateOf(false) }
    var dialogTeam by remember { mutableStateOf<Team?>(null) }

    // ✅ 팝업이 "운영팀 로그인"인지 구분
    var dialogIsManager by remember { mutableStateOf(false) }

    var passwordInput by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }

    fun openLoginDialog(team: Team, isManager: Boolean) {
        dialogTeam = team
        dialogIsManager = isManager
        passwordInput = ""
        loginError = null
        showLoginDialog = true
    }

    // ✅ Firestore 문서 -> Team 변환 (status: String -> TeamStatus)
    fun parseTeam(d: DocumentSnapshot): Team? {
        val idLong = d.getLong("id") ?: return null
        val name = d.getString("name") ?: return null
        val orderLong = d.getLong("order") ?: 0L
        val pw = d.getString("password") ?: ""

        val statusKey = d.getString("status") ?: "preparing"
        val statusEnum = TeamStatus.fromKey(statusKey)

        return Team(
            id = idLong.toInt(),
            name = name,
            order = orderLong.toInt(),
            password = pw,
            status = statusEnum
        )
    }

    // -------------------------
    // Firestore 구독: teams
    // -------------------------
    DisposableEffect(Unit) {
        val db = FirebaseFirestore.getInstance()

        var regTeams: ListenerRegistration? = null
        regTeams = db.collection("teams")
            .orderBy("order")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    errorTeams = e.message ?: "팀 목록을 불러오지 못했습니다."
                    isLoadingTeams = false
                    return@addSnapshotListener
                }

                val list = snapshot?.documents.orEmpty()
                    .mapNotNull { parseTeam(it) }

                teams = list
                isLoadingTeams = false
                errorTeams = null

                dialogTeam?.let { sel ->
                    if (showLoginDialog && !dialogIsManager && list.none { it.id == sel.id }) {
                        showLoginDialog = false
                        dialogTeam = null
                        passwordInput = ""
                        loginError = null
                    } else if (showLoginDialog && !dialogIsManager) {
                        dialogTeam = list.firstOrNull { it.id == sel.id } ?: dialogTeam
                    }
                }
            }

        onDispose { regTeams?.remove() }
    }

    // -------------------------
    // Firestore 구독: managers
    // -------------------------
    DisposableEffect(Unit) {
        val db = FirebaseFirestore.getInstance()

        var regManagers: ListenerRegistration? = null
        regManagers = db.collection("managers")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    errorManagers = e.message ?: "운영팀 목록을 불러오지 못했습니다."
                    isLoadingManagers = false
                    return@addSnapshotListener
                }

                val list = snapshot?.documents.orEmpty()
                    .mapNotNull { parseTeam(it) }

                managers = list
                isLoadingManagers = false
                errorManagers = null

                dialogTeam?.let { sel ->
                    if (showLoginDialog && dialogIsManager && list.none { it.id == sel.id }) {
                        showLoginDialog = false
                        dialogTeam = null
                        passwordInput = ""
                        loginError = null
                    } else if (showLoginDialog && dialogIsManager) {
                        dialogTeam = list.firstOrNull { it.id == sel.id } ?: dialogTeam
                    }
                }
            }

        onDispose { regManagers?.remove() }
    }

    val anyLoading = isLoadingTeams || isLoadingManagers
    val anyError = errorTeams != null || errorManagers != null

    // -------------------------
    // UI (✅ 배경 추가)
    // -------------------------
    Box(modifier = Modifier.fillMaxSize()) {

        // ✅ 1) 배경 이미지 (Intro와 동일하게 꽉 채움)
        Image(
            painter = painterResource(id = R.drawable.bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // ✅ 2) 가독성용 오버레이(선택)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
        )

        // ✅ 3) 컨텐츠
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("팀을 선택하세요", style = MaterialTheme.typography.titleLarge)

                    // -------------------------
                    // Teams 섹션
                    // -------------------------
                    SectionTitle("팀")

                    when {
                        isLoadingTeams -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) { CircularProgressIndicator() }
                        }

                        errorTeams != null -> {
                            Text(
                                text = errorTeams!!,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        teams.isEmpty() -> {
                            Text("등록된 팀이 없습니다.", textAlign = TextAlign.Center)
                        }

                        else -> {
                            ButtonGrid5(
                                items = teams,
                                onItemClick = { openLoginDialog(it, isManager = false) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // -------------------------
                    // Managers 섹션
                    // -------------------------
                    SectionTitle("운영팀")

                    when {
                        isLoadingManagers -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) { CircularProgressIndicator() }
                        }

                        errorManagers != null -> {
                            Text(
                                text = errorManagers!!,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        managers.isEmpty() -> {
                            Text("등록된 운영팀이 없습니다.", textAlign = TextAlign.Center)
                        }

                        else -> {
                            ButtonGrid5(
                                items = managers,
                                onItemClick = { openLoginDialog(it, isManager = true) }
                            )
                        }
                    }

                    if (anyError && !anyLoading) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "일부 목록을 불러오지 못했습니다.",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // -------------------------
        // 로그인 다이얼로그 (팀/운영팀 공통)
        // -------------------------
        if (showLoginDialog && dialogTeam != null) {
            val team = dialogTeam!!
            val titleText = if (dialogIsManager) "운영팀 로그인" else "팀 로그인"

            AlertDialog(
                onDismissRequest = {
                    showLoginDialog = false
                    dialogTeam = null
                    passwordInput = ""
                    loginError = null
                },
                title = { Text(titleText) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${team.name}")

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                loginError = null
                            },
                            label = { Text("비밀번호") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (loginError != null) {
                            Text(
                                text = loginError!!,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = passwordInput.isNotEmpty(),
                        onClick = {
                            if (passwordInput == team.password) {
                                loginError = null
                                showLoginDialog = false

                                onLoginSuccess(
                                    team.copy(password = ""),
                                    dialogIsManager
                                )
                            } else {
                                loginError = "비밀번호가 올바르지 않습니다."
                            }
                        }
                    ) { Text("로그인") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showLoginDialog = false
                            dialogTeam = null
                            passwordInput = ""
                            loginError = null
                        }
                    ) { Text("취소") }
                }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * ✅ 한 줄에 최대 5개씩 버튼을 배치하는 그리드
 */
@Composable
private fun ButtonGrid5(
    items: List<Team>,
    onItemClick: (Team) -> Unit
) {
    val chunked = remember(items) { items.chunked(5) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        chunked.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { team ->
                    TeamButton(
                        text = team.name,
                        onClick = { onItemClick(team) },
                        modifier = Modifier.weight(1f)
                    )
                }

                repeat(5 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun TeamButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .background(Color.LightGray, shape = RoundedCornerShape(10.dp))
            .border(1.dp, Color.Gray, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, textAlign = TextAlign.Center, fontSize = 15.sp)
    }
}