package com.familysafe.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.security.SecureRandom
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

// Values and helpers used by both the screen (MainActivity) and the
// background service (LocationShareService).

internal const val PREFS = "familysafe"
internal const val KEY_NAME = "my_name"
internal const val KEY_FAMILY = "family_id"
internal const val KEY_SHARING = "sharing"
internal const val KEY_SEEN_SOS = "seen_sos"
internal const val KEY_BG_HELP_SHOWN = "bg_help_shown"
internal const val KEY_DARK_MODE = "dark_mode"
internal const val KEY_POWER_SOS = "power_sos_enabled"
internal const val KEY_SOS_PRESSES = "power_sos_presses"
internal const val KEY_POWER_SOS_ID = "power_sos_active_id"
internal const val KEY_LOCK_ENABLED = "lock_enabled"
internal const val KEY_PIN_HASH = "pin_hash"
internal const val KEY_PIN_SALT = "pin_salt"
internal const val KEY_BIOMETRIC = "lock_biometric"

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

// ---------------------------------------------------------------
// Location history (full-day traces)
// ---------------------------------------------------------------
// Each point is stored with "t" as epoch milliseconds (a plain Long),
// so a day can be read with a single-field range query and old points
// cleaned up with a single-field query - neither needs a composite index.

internal const val HISTORY_DAYS = 30
internal const val HISTORY_RETENTION_MS = HISTORY_DAYS.toLong() * 24L * 60L * 60L * 1000L

internal fun trailRef(familyId: String, uid: String): CollectionReference =
    membersRef(familyId).document(uid).collection("trail")

/** Append one point to this member's trail. */
internal fun writeTrailPoint(familyId: String, uid: String, lat: Double, lng: Double) {
    trailRef(familyId, uid).add(
        mapOf<String, Any>(
            "lat" to lat,
            "lng" to lng,
            "t" to System.currentTimeMillis()
        )
    )
}

/** Delete this member's own points older than the retention window. */
internal fun cleanupOldTrail(familyId: String, uid: String) {
    val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS
    trailRef(familyId, uid)
        .whereLessThan("t", cutoff)
        .limit(400)
        .get()
        .addOnSuccessListener { snap ->
            if (snap.isEmpty) return@addOnSuccessListener
            val batch = db.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            batch.commit()
        }
}

/** All points for one member between two epoch-ms bounds, oldest first. */
internal fun trailQueryForRange(familyId: String, uid: String, startMs: Long, endMs: Long): Query =
    trailRef(familyId, uid)
        .whereGreaterThanOrEqualTo("t", startMs)
        .whereLessThan("t", endMs)
        .orderBy("t", Query.Direction.ASCENDING)

// ---------------------------------------------------------------
// Security lock (PIN) helpers
// ---------------------------------------------------------------
// The PIN is never stored directly - only a salted SHA-256 hash of it.

internal fun newSalt(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

internal fun hashPin(pin: String, salt: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun lockEnabled(context: Context): Boolean {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return p.getBoolean(KEY_LOCK_ENABLED, false) && p.getString(KEY_PIN_HASH, null) != null
}

internal fun checkPin(context: Context, pin: String): Boolean {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val salt = p.getString(KEY_PIN_SALT, null) ?: return false
    val stored = p.getString(KEY_PIN_HASH, null) ?: return false
    return hashPin(pin, salt) == stored
}

internal fun setPin(context: Context, pin: String) {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val salt = newSalt()
    p.edit()
        .putString(KEY_PIN_SALT, salt)
        .putString(KEY_PIN_HASH, hashPin(pin, salt))
        .apply()
}

internal fun clearLock(context: Context) {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    p.edit()
        .putBoolean(KEY_LOCK_ENABLED, false)
        .putBoolean(KEY_BIOMETRIC, false)
        .remove(KEY_PIN_HASH)
        .remove(KEY_PIN_SALT)
        .apply()
}
