package com.aba.cpx.data.model

data class Team(
    val id: Int,
    val name: String,
    val order: Int = 0,
    val password: String = "",
    val status:String = "waiting"
)