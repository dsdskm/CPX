package com.aba.cpx.data.model

data class ColumnHeader(
    val title: String,
    val scoreText: String
)

val headers = listOf(
    ColumnHeader("사복부", "10점"),
    ColumnHeader("통신대", "10점"),
    ColumnHeader("레이더", "8점"),
    ColumnHeader("무기고", "8점"),
    ColumnHeader("보급소", "6점"),
    ColumnHeader("비행장", "6점"),
    ColumnHeader("병원", "4점"),
    ColumnHeader("방송국", "4점"),
    ColumnHeader("발전소", "2점"),
    ColumnHeader("철도", "2점"),
)

val rows = listOf(
    "서울", "수원", "인천", "오산", "천안",
    "대전", "전주", "광주", "대구", "부산"
)