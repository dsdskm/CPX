package com.aba.cpx.data.repository

import com.aba.cpx.data.model.PowerPlacement
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class PowerPlacementRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private fun docRef(teamId: Int) =
        db.collection("powerPlacements").document(teamId.toString())

    fun savePlacement(
        doc: PowerPlacement,
        onSuccess: () -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val data = hashMapOf<String, Any>(
            "teamId" to doc.teamId,
            "placements" to doc.placements.map { p ->
                mapOf(
                    "id" to p.id,
                    "type" to p.type,
                    "cells" to p.cells.map { cell -> mapOf("c" to cell.c, "r" to cell.r) }
                )
            },
            "updatedAt" to FieldValue.serverTimestamp()
        )

        docRef(doc.teamId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFail(e) }
    }

    fun loadOnce(
        teamId: Int,
        onSuccess: (PowerPlacement?) -> Unit,
        onFail: (Exception) -> Unit
    ) {
        docRef(teamId)
            .get()
            .addOnSuccessListener { snap ->
                onSuccess(snap.toObject(PowerPlacement::class.java))
            }
            .addOnFailureListener { e -> onFail(e) }
    }
}
