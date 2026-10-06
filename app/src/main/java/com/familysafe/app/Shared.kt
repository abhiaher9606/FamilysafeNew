package com.familysafe.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

// Values and helpers used by both the screen (MainActivity) and the
// background service (LocationShareService).

internal const val PREFS = "familysafe"
internal const val KEY_NAME = "my_name"
internal const val KEY_FAMILY = "family_id"
internal const val KEY_SHARING = "sharing"
internal const val KEY_SEEN_SOS = "seen_sos"
internal const val KEY_BG_HELP_SHOWN = "bg_help_shown"

internal val db: FirebaseFirestore
    get() = FirebaseFirestore.getInstance()

internal fun membersRef(familyId: String): CollectionReference =
    db.collection("families").document(familyId).collection("members")

internal fun sosRef(familyId: String): CollectionReference =
    db.collection("families").document(familyId).collection("sos")

internal fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

internal fun setSharingFlag(familyId: String, uid: String, sharing: Boolean) {
    val updates: Map<String, Any> = if (sharing) {
        mapOf("sharing" to true)
    } else {
        mapOf(
            "sharing" to false,
            "lat" to FieldValue.delete(),
            "lng" to FieldValue.delete()
        )
    }
    membersRef(familyId).document(uid).update(updates)
}

internal fun writeLocation(familyId: String, uid: String, lat: Double, lng: Double) {
    membersRef(familyId).document(uid).update(
        mapOf<String, Any>(
            "lat" to lat,
            "lng" to lng,
            "updatedAt" to FieldValue.serverTimestamp()
        )
    )
}

internal fun sendSos(
    familyId: String,
    uid: String,
    name: String,
    lat: Double?,
    lng: Double?,
    onResult: (sosId: String?, error: String?) -> Unit
) {
    val data = hashMapOf<String, Any>(
        "uid" to uid,
        "name" to name,
        "active" to true,
        "createdAt" to FieldValue.serverTimestamp()
    )
    if (lat != null && lng != null) {
        data["lat"] = lat
        data["lng"] = lng
    }
    sosRef(familyId).add(data)
        .addOnSuccessListener { onResult(it.id, null) }
        .addOnFailureListener { onResult(null, it.message ?: "Could not send the SOS") }
}

internal fun resolveSos(familyId: String, sosId: String) {
    sosRef(familyId).document(sosId).update("active", false)
}
