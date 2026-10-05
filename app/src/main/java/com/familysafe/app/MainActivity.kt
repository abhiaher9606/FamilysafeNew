package com.familysafe.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.security.SecureRandom

data class FamilyMember(
    val uid: String,
    val name: String,
    val sharing: Boolean,
    val lat: Double?,
    val lng: Double?,
    val updatedAtMillis: Long?
)

data class PendingInvite(val code: String, val inviteeName: String)

private const val PREFS = "familysafe"
private const val KEY_NAME = "my_name"
private const val KEY_FAMILY = "family_id"
private const val KEY_SHARING = "sharing"

private val db: FirebaseFirestore
    get() = FirebaseFirestore.getInstance()

private fun membersRef(familyId: String): CollectionReference =
    db.collection("families").document(familyId).collection("members")

// ---------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------

private fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private fun newInviteCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = SecureRandom()
    return (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
}

private fun inviteMessage(inviteeName: String, inviterName: String, code: String): String =
    "Hi $inviteeName, $inviterName is inviting you to join their family on FamilySafe, " +
        "a private app where every person chooses whether to share their own location.\n\n" +
        "Invite code: $code\n\n" +
        "Install FamilySafe, tap \"Join with a code\" and enter this code. " +
        "Nothing is shared unless you turn sharing on yourself."

private fun openWhatsApp(context: Context, phone: String, text: String) {
    val digits = phone.filter { it.isDigit() }
    val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: Exception) {
        toast(context, "Couldn't open WhatsApp")
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Send invitation"))
    } catch (e: Exception) {
        toast(context, "No app available to share")
    }
}

private fun openMap(context: Context, name: String, lat: Double, lng: Double) {
    val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, geo))
    } catch (e: Exception) {
        try {
            val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
            context.startActivity(Intent(Intent.ACTION_VIEW, web))
        } catch (e2: Exception) {
            toast(context, "No maps app found")
        }
    }
}

private fun timeAgo(millis: Long?): String {
    if (millis == null) return "just now"
    val seconds = (System.currentTimeMillis() - millis) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86400} d ago"
    }
}

// ---------------------------------------------------------------
// Firebase operations
// ---------------------------------------------------------------

private fun createFamily(
    uid: String,
    name: String,
    onResult: (familyId: String?, error: String?) -> Unit
) {
    val familyRef = db.collection("families").document()
    val memberRef = familyRef.collection("members").document(uid)
    val batch = db.batch()
    batch.set(
        familyRef,
        mapOf<String, Any>(
            "ownerUid" to uid,
            "createdAt" to FieldValue.serverTimestamp()
        )
    )
    batch.set(
        memberRef,
        mapOf<String, Any>(
            "uid" to uid,
            "name" to name,
            "sharing" to false,
            "joinedAt" to FieldValue.serverTimestamp()
        )
    )
    batch.commit()
        .addOnSuccessListener { onResult(familyRef.id, null) }
        .addOnFailureListener { onResult(null, it.message ?: "Could not create the family") }
}

private fun createInvite(
    familyId: String,
    uid: String,
    inviterName: String,
    inviteeName: String,
    code: String,
    onResult: (error: String?) -> Unit
) {
    db.collection("invites").document(code).set(
        mapOf<String, Any>(
            "familyId" to familyId,
            "inviterUid" to uid,
            "inviterName" to inviterName,
            "inviteeName" to inviteeName,
            "used" to false,
            "createdAt" to FieldValue.serverTimestamp()
        )
    )
        .addOnSuccessListener { onResult(null) }
        .addOnFailureListener { onResult(it.message ?: "Could not create the invitation") }
}

private fun deleteInvite(code: String) {
    db.collection("invites").document(code).delete()
}

private fun joinWithCode(
    uid: String,
    name: String,
    code: String,
    onResult: (familyId: String?, error: String?) -> Unit
) {
    val inviteRef = db.collection("invites").document(code)
    inviteRef.get()
        .addOnSuccessListener { snap ->
            val familyId = snap.getString("familyId")
            if (!snap.exists() || snap.getBoolean("used") == true || familyId == null) {
                onResult(null, "This code is not valid or was already used.")
                return@addOnSuccessListener
            }
            val batch = db.batch()
            batch.update(
                inviteRef,
                mapOf<String, Any>("used" to true, "usedBy" to uid)
            )
            batch.set(
                membersRef(familyId).document(uid),
                mapOf<String, Any>(
                    "uid" to uid,
                    "name" to name,
                    "sharing" to false,
                    "inviteCode" to code,
                    "joinedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit()
                .addOnSuccessListener { onResult(familyId, null) }
                .addOnFailureListener { onResult(null, it.message ?: "Could not join the family") }
        }
        .addOnFailureListener { onResult(null, it.message ?: "Could not check the code") }
}

private fun setSharingFlag(familyId: String, uid: String, sharing: Boolean) {
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

private fun writeLocation(familyId: String, uid: String, lat: Double, lng: Double) {
    membersRef(familyId).document(uid).update(
        mapOf<String, Any>(
            "lat" to lat,
            "lng" to lng,
            "updatedAt" to FieldValue.serverTimestamp()
        )
    )
}

// ---------------------------------------------------------------
// Activity + UI
// ---------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FamilySafeApp() }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySafeApp() {
    val context = LocalContext.current
    val p = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var uid by remember { mutableStateOf<String?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var myName by remember { mutableStateOf(p.getString(KEY_NAME, "") ?: "") }
    var familyId by remember { mutableStateOf<String?>(p.getString(KEY_FAMILY, null)) }
    var sharing by remember {
        mutableStateOf(
            p.getBoolean(KEY_SHARING, false) &&
                p.getString(KEY_FAMILY, null) != null &&
                hasLocationPermission(context)
        )
    }
    var busy by remember { mutableStateOf(false) }

    var showPrivacy by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var inviteName by remember { mutableStateOf("") }
    var invitePhone by remember { mutableStateOf("+91 ") }
    var joinCode by remember { mutableStateOf("") }

    val members = remember { mutableStateListOf<FamilyMember>() }
    val pending = remember { mutableStateListOf<PendingInvite>() }

    // ---- 1. Sign in (anonymous, no password needed) ----
    LaunchedEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val current = auth.currentUser
        if (current != null) {
            uid = current.uid
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { uid = it.user?.uid }
                .addOnFailureListener { authError = it.message ?: "Sign-in failed" }
        }
    }

    // ---- 2. Live list of family members ----
    DisposableEffect(familyId, uid) {
        val fid = familyId
        val me = uid
        syncError = null
        var registration: ListenerRegistration? = null
        if (fid != null && me != null) {
            registration = membersRef(fid).addSnapshotListener { snap, err ->
                if (err != null) {
                    syncError = err.message
                    return@addSnapshotListener
                }
                if (snap != null) {
                    members.clear()
                    snap.documents.forEach { d ->
                        members.add(
                            FamilyMember(
                                uid = d.getString("uid") ?: d.id,
                                name = d.getString("name") ?: "Member",
                                sharing = d.getBoolean("sharing") ?: false,
                                lat = d.getDouble("lat"),
                                lng = d.getDouble("lng"),
                                updatedAtMillis = d.getTimestamp("updatedAt")?.toDate()?.time
                            )
                        )
                    }
                }
            }
        } else {
            members.clear()
        }
        onDispose { registration?.remove() }
    }

    // ---- 3. Invitations I sent that nobody has used yet ----
    DisposableEffect(uid) {
        val me = uid
        var registration: ListenerRegistration? = null
        if (me != null) {
            registration = db.collection("invites")
                .whereEqualTo("inviterUid", me)
                .addSnapshotListener { snap, err ->
                    if (err == null && snap != null) {
                        pending.clear()
                        snap.documents
                            .filter { it.getBoolean("used") != true }
                            .forEach {
                                pending.add(
                                    PendingInvite(it.id, it.getString("inviteeName") ?: "")
                                )
                            }
                    }
                }
        }
        onDispose { registration?.remove() }
    }

    // ---- 4. Send my location while sharing is ON (app open) ----
    DisposableEffect(sharing, familyId, uid) {
        val fid = familyId
        val me = uid
        var client: com.google.android.gms.location.FusedLocationProviderClient? = null
        var callback: LocationCallback? = null
        if (sharing && fid != null && me != null && hasLocationPermission(context)) {
            val c = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                30_000L
            ).setMinUpdateIntervalMillis(15_000L).build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    writeLocation(fid, me, loc.latitude, loc.longitude)
                }
            }
            c.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) writeLocation(fid, me, loc.latitude, loc.longitude)
            }
            try {
                c.requestLocationUpdates(request, cb, Looper.getMainLooper())
                client = c
                callback = cb
            } catch (e: SecurityException) {
                // permission missing: nothing to do
            }
        }
        onDispose {
            val c = client
            val cb = callback
            if (c != null && cb != null) c.removeLocationUpdates(cb)
        }
    }

    // ---- actions ----
    fun beginSharing() {
        val me = uid
        val fid = familyId
        if (me == null || fid == null) return
        sharing = true
        p.edit().putBoolean(KEY_SHARING, true).apply()
        setSharingFlag(fid, me, true)
    }

    fun stopSharing() {
        val me = uid
        val fid = familyId
        sharing = false
        p.edit().putBoolean(KEY_SHARING, false).apply()
        if (me != null && fid != null) setSharingFlag(fid, me, false)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            beginSharing()
        } else {
            toast(context, "Location permission is needed to share your location")
        }
    }

    fun sendInvite(viaWhatsApp: Boolean) {
        val me = uid ?: return
        val inviteeName = inviteName.trim()
        val phone = invitePhone
        busy = true

        fun proceed(fid: String) {
            val code = newInviteCode()
            createInvite(fid, me, myName, inviteeName, code) { error ->
                busy = false
                if (error != null) {
                    toast(context, error)
                    return@createInvite
                }
                showInvite = false
                val message = inviteMessage(inviteeName, myName, code)
                if (viaWhatsApp) openWhatsApp(context, phone, message) else shareText(context, message)
            }
        }

        val existing = familyId
        if (existing != null) {
            proceed(existing)
        } else {
            createFamily(me, myName) { newId, error ->
                if (newId == null) {
                    busy = false
                    toast(context, error ?: "Could not create the family")
                    return@createFamily
                }
                familyId = newId
                p.edit().putString(KEY_FAMILY, newId).apply()
                proceed(newId)
            }
        }
    }

    fun joinFamily() {
        val me = uid ?: return
        busy = true
        joinWithCode(me, myName, joinCode.trim()) { newId, error ->
            busy = false
            if (newId == null) {
                toast(context, error ?: "Could not join the family")
                return@joinWithCode
            }
            familyId = newId
            p.edit().putString(KEY_FAMILY, newId).apply()
            showJoin = false
            toast(context, "You joined the family. Turn on sharing when you are ready.")
        }
    }

    fun leaveFamily() {
        val me = uid
        val fid = familyId
        if (me != null && fid != null) {
            membersRef(fid).document(me).delete()
        }
        sharing = false
        familyId = null
        p.edit().remove(KEY_FAMILY).putBoolean(KEY_SHARING, false).apply()
        members.clear()
    }

    val sortedMembers = members.sortedWith(
        compareByDescending<FamilyMember> { it.uid == uid }.thenBy { it.name.lowercase() }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamilySafe") },
                actions = {
                    IconButton(onClick = { showPrivacy = true }) {
                        Icon(Icons.Default.Lock, contentDescription = "Privacy")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (uid == null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            if (authError != null) {
                                Text("Could not connect", style = MaterialTheme.typography.titleMedium)
                                Text(authError ?: "", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("Connecting...")
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "Private family location",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Only people in your family group can see your location, and only while this switch is ON. Your location is shared while the app is open.")
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = sharing,
                                enabled = uid != null && familyId != null,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (hasLocationPermission(context)) {
                                            beginSharing()
                                        } else {
                                            permissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        }
                                    } else {
                                        stopSharing()
                                    }
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(if (sharing) "Location sharing ON" else "Location sharing OFF")
                        }
                        if (familyId == null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Invite someone or join with a code to start sharing.")
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        inviteName = ""
                        invitePhone = "+91 "
                        showInvite = true
                    },
                    enabled = uid != null && myName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Invite family member")
                }
            }

            if (familyId == null) {
                item {
                    OutlinedButton(
                        onClick = {
                            joinCode = ""
                            showJoin = true
                        },
                        enabled = uid != null && myName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Join with a code")
                    }
                }
            }

            if (syncError != null) {
                item {
                    Text(
                        "Could not load family: ${syncError}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                Text("Family", style = MaterialTheme.typography.titleLarge)
            }

            if (familyId != null && sortedMembers.isEmpty() && syncError == null) {
                item { Text("Loading family...") }
            }

            items(sortedMembers, key = { "m_" + it.uid }) { m ->
                val isMe = m.uid == uid
                val hasLocation = m.sharing && m.lat != null && m.lng != null
                ListItem(
                    leadingContent = {
                        Icon(
                            if (hasLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                            contentDescription = null
                        )
                    },
                    headlineContent = { Text(if (isMe) "${m.name} (You)" else m.name) },
                    supportingContent = {
                        Text(
                            when {
                                hasLocation -> "Updated ${timeAgo(m.updatedAtMillis)}"
                                m.sharing -> "Sharing - waiting for location"
                                else -> "Not sharing"
                            }
                        )
                    },
                    trailingContent = {
                        if (hasLocation) {
                            TextButton(
                                onClick = { openMap(context, m.name, m.lat!!, m.lng!!) }
                            ) { Text("Map") }
                        }
                    }
                )
                HorizontalDivider()
            }

            items(pending, key = { "i_" + it.code }) { inv ->
                ListItem(
                    headlineContent = { Text("Invited: ${inv.inviteeName}") },
                    supportingContent = { Text("Waiting to join - code ${inv.code}") },
                    trailingContent = {
                        IconButton(onClick = { deleteInvite(inv.code) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Cancel invitation")
                        }
                    }
                )
                HorizontalDivider()
            }

            if (familyId != null) {
                item {
                    TextButton(onClick = { showLeave = true }) {
                        Text("Leave this family")
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Emergency", style = MaterialTheme.typography.titleMedium)
                        Text("The SOS feature will notify your chosen family contacts after you enable it.")
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { }) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("SOS")
                        }
                    }
                }
            }
        }
    }

    // ---- dialogs ----

    if (myName.isBlank()) {
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Welcome to FamilySafe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What should your family call you?")
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        val n = nameInput.trim()
                        myName = n
                        p.edit().putString(KEY_NAME, n).apply()
                    }
                ) { Text("Continue") }
            }
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("OK") } },
            title = { Text("Privacy first") },
            text = {
                Text("FamilySafe shares your location only with people in your family group, and only while your sharing switch is ON. Nobody can turn on another person's sharing. You can switch it off or leave the family at any time. Your location is stored in Firebase, protected by sign-in and access rules.")
            }
        )
    }

    if (showInvite) {
        AlertDialog(
            onDismissRequest = { if (!busy) showInvite = false },
            title = { Text("Invite family member") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inviteName,
                        onValueChange = { inviteName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = invitePhone,
                        onValueChange = { invitePhone = it },
                        label = { Text("WhatsApp number with country code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        enabled = inviteName.isNotBlank() && !busy,
                        onClick = { sendInvite(false) }
                    ) { Text("Send using another app / SMS") }
                }
            },
            confirmButton = {
                Button(
                    enabled = inviteName.isNotBlank() &&
                        invitePhone.count { it.isDigit() } >= 8 && !busy,
                    onClick = { sendInvite(true) }
                ) { Text(if (busy) "Please wait..." else "Send on WhatsApp") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { showInvite = false }
                ) { Text("Cancel") }
            }
        )
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { if (!busy) showJoin = false },
            title = { Text("Join with a code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter the invite code you received.")
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = {
                            joinCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8)
                        },
                        label = { Text("Invite code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = joinCode.length == 8 && !busy,
                    onClick = { joinFamily() }
                ) { Text(if (busy) "Please wait..." else "Join") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { showJoin = false }
                ) { Text("Cancel") }
            }
        )
    }

    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text("Leave this family?") },
            text = {
                Text("Your sharing will stop and you will disappear from the family list. You can join again with a new invite code.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeave = false
                    leaveFamily()
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { showLeave = false }) { Text("Cancel") }
            }
        )
    }
}
