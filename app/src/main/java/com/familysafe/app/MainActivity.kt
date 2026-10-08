package com.familysafe.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
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

data class SosAlert(
    val id: String,
    val uid: String,
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val createdMillis: Long?
)

// ---------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun hasBackgroundLocation(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun sharingPermissions(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return list.toTypedArray()
}

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
        context.startActivity(Intent.createChooser(send, "Send"))
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

private fun openBatterySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e2: Exception) {
            toast(context, "Open Settings > Apps > FamilySafe > Battery")
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

/** Current location if possible, otherwise the last known one, otherwise null. */
@SuppressLint("MissingPermission")
private fun fetchBestLocation(context: Context, onResult: (Location?) -> Unit) {
    if (!hasLocationPermission(context)) {
        onResult(null)
        return
    }
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
        .addOnSuccessListener { loc ->
            if (loc != null) {
                onResult(loc)
            } else {
                client.lastLocation
                    .addOnSuccessListener { last -> onResult(last) }
                    .addOnFailureListener { onResult(null) }
            }
        }
        .addOnFailureListener {
            client.lastLocation
                .addOnSuccessListener { last -> onResult(last) }
                .addOnFailureListener { onResult(null) }
        }
}

// ---------------------------------------------------------------
// Firebase operations (family + invitations)
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

// ---------------------------------------------------------------
// Activity + UI
// ---------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setContent {
            val systemDark = isSystemInDarkTheme()
            var dark by remember {
                mutableStateOf(
                    if (prefs.contains(KEY_DARK_MODE)) prefs.getBoolean(KEY_DARK_MODE, false)
                    else systemDark
                )
            }
            // Status bar and navigation bar icons follow the app's own mode.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent)
                else SystemBarStyle.light(transparent, transparent)
                this@MainActivity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            FamilySafeTheme(dark = dark) {
                FamilySafeApp(
                    dark = dark,
                    onToggleDark = {
                        dark = !dark
                        prefs.edit().putBoolean(KEY_DARK_MODE, dark).apply()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySafeApp(dark: Boolean, onToggleDark: () -> Unit) {
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
    var showBgHelp by remember { mutableStateOf(false) }
    var showSosConfirm by remember { mutableStateOf(false) }
    var sosShareText by remember { mutableStateOf<String?>(null) }
    var powerSos by remember { mutableStateOf(p.getBoolean(KEY_POWER_SOS, false)) }
    var sosPresses by remember { mutableStateOf(p.getInt(KEY_SOS_PRESSES, 3).coerceIn(3, 4)) }
    var showSosRules by remember { mutableStateOf(false) }
    var rulesTurnOn by remember { mutableStateOf(false) }
    var inviteName by remember { mutableStateOf("") }
    var invitePhone by remember { mutableStateOf("+91 ") }
    var joinCode by remember { mutableStateOf("") }

    val members = remember { mutableStateListOf<FamilyMember>() }
    val pending = remember { mutableStateListOf<PendingInvite>() }
    val sosAlerts = remember { mutableStateListOf<SosAlert>() }

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

    // ---- 4. Active SOS alerts in my family ----
    DisposableEffect(familyId, uid) {
        val fid = familyId
        val me = uid
        var registration: ListenerRegistration? = null
        if (fid != null && me != null) {
            registration = sosRef(fid)
                .whereEqualTo("active", true)
                .addSnapshotListener { snap, err ->
                    if (err == null && snap != null) {
                        sosAlerts.clear()
                        snap.documents.forEach { d ->
                            sosAlerts.add(
                                SosAlert(
                                    id = d.id,
                                    uid = d.getString("uid") ?: "",
                                    name = d.getString("name") ?: "Family member",
                                    lat = d.getDouble("lat"),
                                    lng = d.getDouble("lng"),
                                    createdMillis = d.getTimestamp("createdAt")?.toDate()?.time
                                )
                            )
                        }
                    }
                }
        } else {
            sosAlerts.clear()
        }
        onDispose { registration?.remove() }
    }

    // ---- 5. Make sure the background service is running while sharing is ON ----
    LaunchedEffect(sharing, familyId, uid) {
        if (sharing && familyId != null && uid != null && hasLocationPermission(context)) {
            try {
                LocationShareService.start(context)
            } catch (e: Exception) {
                // could not start right now; the switch can be toggled again
            }
        }
    }

    // ---- 6. Re-read the sharing state when coming back to the app ----
    // (the notification has a "Stop sharing" button that can change it)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        sharing = p.getBoolean(KEY_SHARING, false) &&
            p.getString(KEY_FAMILY, null) != null &&
            hasLocationPermission(context)
    }

    // ---- actions ----
    fun beginSharing() {
        val me = uid
        val fid = familyId
        if (me == null || fid == null) return
        sharing = true
        p.edit().putBoolean(KEY_SHARING, true).apply()
        setSharingFlag(fid, me, true)
        try {
            LocationShareService.start(context)
        } catch (e: Exception) {
            toast(context, "Could not start background sharing")
        }
        if (!p.getBoolean(KEY_BG_HELP_SHOWN, false)) {
            p.edit().putBoolean(KEY_BG_HELP_SHOWN, true).apply()
            showBgHelp = true
        }
    }

    fun stopSharing() {
        val me = uid
        val fid = familyId
        sharing = false
        p.edit().putBoolean(KEY_SHARING, false).apply()
        LocationShareService.stop(context)
        if (me != null && fid != null) setSharingFlag(fid, me, false)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            beginSharing()
            if (!hasNotificationPermission(context)) {
                toast(context, "Notifications are off, so you won't see the sharing notice or SOS alerts.")
            }
        } else {
            toast(context, "Location permission is needed to share your location")
        }
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast(context, "Not allowed all the time. Sharing may stop when the app is closed on some phones.")
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
        LocationShareService.stop(context)
        if (me != null && fid != null) {
            membersRef(fid).document(me).delete()
        }
        sharing = false
        familyId = null
        p.edit().remove(KEY_FAMILY).putBoolean(KEY_SHARING, false).apply()
        members.clear()
    }

    fun triggerSos() {
        val me = uid ?: return
        val fid = familyId ?: return
        busy = true
        fetchBestLocation(context) { loc ->
            sendSos(fid, me, myName, loc?.latitude, loc?.longitude) { _, error ->
                busy = false
                if (error != null) {
                    toast(context, error)
                    return@sendSos
                }
                showSosConfirm = false
                sosShareText = if (loc != null) {
                    "SOS! I need help. My location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                } else {
                    "SOS! I need help."
                }
            }
        }
    }

    val sortedMembers = members.sortedWith(
        compareByDescending<FamilyMember> { it.uid == uid }.thenBy { it.name.lowercase() }
    )
    val nowMs = System.currentTimeMillis()
    val visibleSos = sosAlerts.filter {
        it.createdMillis == null || nowMs - it.createdMillis < 2 * 60 * 60 * 1000L
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamilySafe") },
                actions = {
                    IconButton(onClick = onToggleDark) {
                        Icon(
                            if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode"
                        )
                    }
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

            items(visibleSos, key = { "s_" + it.id }) { alert ->
                val mine = alert.uid == uid
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            if (mine) "Your SOS is active" else "SOS from ${alert.name}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("Sent ${timeAgo(alert.createdMillis)}")
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (alert.lat != null && alert.lng != null) {
                                Button(
                                    onClick = { openMap(context, alert.name, alert.lat, alert.lng) }
                                ) { Text("Open map") }
                            }
                            if (mine) {
                                OutlinedButton(
                                    onClick = {
                                        val fid = familyId
                                        if (fid != null) resolveSos(fid, alert.id)
                                        LocationShareService.clearMySos(context)
                                    }
                                ) { Text("I'm safe - cancel SOS") }
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
                        Text("Only people in your family group can see your location, and only while this switch is ON. While it is ON, sharing continues in the background and a notification stays visible.")
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = sharing,
                                enabled = uid != null && familyId != null,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (hasLocationPermission(context) && hasNotificationPermission(context)) {
                                            beginSharing()
                                        } else {
                                            permissionLauncher.launch(sharingPermissions())
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
                        } else {
                            TextButton(onClick = { showBgHelp = true }) {
                                Text("Background sharing tips")
                            }
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
                        Text("SOS sends an alert and your current location to everyone in your family. Family members whose sharing is ON get a phone notification, even when the app is closed.")
                        Spacer(Modifier.height(10.dp))
                        Button(
                            enabled = uid != null && familyId != null,
                            onClick = { showSosConfirm = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SosRed,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("SOS", fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = powerSos,
                                onCheckedChange = { on ->
                                    if (on) {
                                        rulesTurnOn = true
                                        showSosRules = true
                                    } else {
                                        powerSos = false
                                        p.edit().putBoolean(KEY_POWER_SOS, false).apply()
                                    }
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Power button SOS", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("Press the power button $sosPresses times quickly to send SOS, even with the screen off or the app closed.")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(3, 4).forEach { n ->
                                FilterChip(
                                    selected = sosPresses == n,
                                    onClick = {
                                        sosPresses = n
                                        p.edit().putInt(KEY_SOS_PRESSES, n).apply()
                                    },
                                    label = { Text("$n presses") }
                                )
                            }
                        }
                        Text(
                            if (sosPresses == 3)
                                "If 3 presses opens the camera or another feature on your phone, choose 4 presses."
                            else
                                "Press exactly 4 times. Pressing 5 times can start the phone's own Emergency SOS, which calls 112.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (powerSos && !sharing) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Turn on Location sharing above. Power button SOS only works while sharing is ON.",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = {
                            rulesTurnOn = false
                            showSosRules = true
                        }) { Text("SOS rules") }
                        if (familyId == null) {
                            Spacer(Modifier.height(6.dp))
                            Text("Join or create a family first.")
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
                Text("FamilySafe shares your location only with people in your family group, and only while your sharing switch is ON (a notification stays visible while it is). Nobody can turn on another person's sharing. You can switch it off or leave the family at any time. SOS is the only exception: it sends your location once, and only when you press it. Your data is stored in Firebase, protected by sign-in and access rules.")
            }
        )
    }

    if (showBgHelp) {
        AlertDialog(
            onDismissRequest = { showBgHelp = false },
            title = { Text("Keep sharing when the app is closed") },
            text = {
                Text("1. Tap \"Allow all the time\" and choose \"Allow all the time\" for location.\n\n2. Open Battery settings and set FamilySafe to \"Unrestricted\" or \"No restrictions\". On Xiaomi/Redmi, Oppo, Vivo and Samsung phones also allow Autostart and keep the app out of \"sleeping apps\".\n\nWhile sharing is ON you will see a notification. Family members with sharing ON also get SOS alerts as notifications.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBgHelp = false
                    if (hasBackgroundLocation(context)) {
                        toast(context, "Already allowed all the time")
                    } else {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text("Allow all the time") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBgHelp = false
                    openBatterySettings(context)
                }) { Text("Battery settings") }
            }
        )
    }

    if (showSosConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) showSosConfirm = false },
            title = { Text("Send SOS?") },
            text = {
                Text("Everyone in your family will get an emergency alert with your current location, even if your sharing switch is OFF.")
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = { triggerSos() }
                ) { Text(if (busy) "Sending..." else "Send SOS") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { showSosConfirm = false }
                ) { Text("Cancel") }
            }
        )
    }

    val sosText = sosShareText
    if (sosText != null) {
        AlertDialog(
            onDismissRequest = { sosShareText = null },
            title = { Text("SOS sent") },
            text = {
                Text("Your family has been alerted in the app. Only members whose sharing is ON get a phone notification, so you can also send it by WhatsApp or SMS.")
            },
            confirmButton = {
                Button(onClick = {
                    shareText(context, sosText)
                    sosShareText = null
                }) { Text("Send by WhatsApp / SMS") }
            },
            dismissButton = {
                TextButton(onClick = { sosShareText = null }) { Text("Done") }
            }
        )
    }

    if (showSosRules) {
        AlertDialog(
            onDismissRequest = { showSosRules = false },
            title = { Text("Power button SOS - rules") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "How it works\n" +
                                "1. Press the power button $sosPresses times quickly, less than 1.5 seconds between presses. It works with the screen off and the app closed.\n" +
                                "2. Your phone vibrates and a notification counts down 5 seconds. Tap Cancel in it if it was a mistake.\n" +
                                "3. After 5 seconds an SOS with your location goes to everyone in your family. Tap \"I'm safe - cancel SOS\" in the notification or the app to end it.\n\n" +
                                "Rules\n" +
                                "- It works only while Location sharing is ON (the \"sharing your location\" notification is showing).\n" +
                                "- After a phone restart, open FamilySafe once.\n" +
                                "- If 3 presses opens the camera, Google Assistant or another feature on your phone, switch to 4 presses.\n" +
                                "- FamilySafe never calls 112. But on many phones (Android 12+, Samsung) pressing the power button 5 times starts the phone's own Emergency SOS, which does call 112. Never press more times than needed.\n" +
                                "- During testing, turn off the phone's own Emergency SOS: Settings > Safety & emergency > Emergency SOS (on Samsung: Settings > Safety and emergency > Emergency SOS / Send SOS messages).\n" +
                                "- Phones in a pocket or bag can press the button by mistake. Feel for the vibration and cancel if needed.\n" +
                                "- Try it once with your family so everyone knows what the alert looks like."
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showSosRules = false
                    if (rulesTurnOn) {
                        powerSos = true
                        p.edit().putBoolean(KEY_POWER_SOS, true).apply()
                        if (!sharing) {
                            toast(context, "Now turn on Location sharing so power button SOS can work.")
                        }
                    }
                }) { Text(if (rulesTurnOn) "I understand, turn on" else "OK") }
            },
            dismissButton = {
                if (rulesTurnOn) {
                    TextButton(onClick = { showSosRules = false }) { Text("Cancel") }
                }
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
