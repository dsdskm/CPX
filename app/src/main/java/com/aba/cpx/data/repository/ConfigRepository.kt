package com.aba.cpx.data.repository

import com.aba.cpx.data.model.Config
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class ConfigRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    suspend fun fetchMainConfig(): Config? {
        return try {
            val snapshot = db.collection("config").document("main").get().await()
            if (snapshot.exists()) {
                Config(
                    text = snapshot.getString("text") ?: "",
                    url = snapshot.getString("url") ?: ""
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}