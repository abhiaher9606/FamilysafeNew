package com.familysafe.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

data class Member(
    val name: String,
    val phone: String,
    val code: String,
    val status: String,
    val sharing: Boolean = false
)

private const val PREFS = "familysafe"
private const val KEY_MEMBERS = "members"

private fun loadMembers(context: Context): List<Member> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_MEMBERS, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Member(
                name = o.getString("name"),
                phone = o.getString("phone"),
                code = o.getString("code"),
                status = o.getString("status"),
                sharing = o.optBoolean("sharing", false)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveMembers(context: Context, members: List<Member>) {
    val arr = JSONArray()
    members.forEach { m ->
        arr.put(
            JSONObject()
                .put("name", m.name)
                .put("phone", m.phone)
                .put("code", m.code)
                .put("status", m.status)
                .put("sharing", m.sharing)
        )
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_MEMBERS, arr.toString())
        .apply()
}

private fun newInviteCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = SecureRandom()
    return (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
}

private fun inviteMessage(name: String, code: String): String =
    "Hi $name, I'm inviting you to join my family on FamilySafe, a private app where " +
        "every person chooses whether to share their own location.\n\n" +
        "Your invite code: $code\n\n" +
        "Nothing is shared unless you open the app, accept, and turn sharing on yourself."

private fun openWhatsApp(context: Context, phone: String, text: String) {
    val digits = phone.filter { it.isDigit() }
    val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open WhatsApp", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FamilySafeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySafeApp() {
    val context = LocalContext.current

    var sharing by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var inviteName by remember { mutableStateOf("") }
    var invitePhone by remember { mutableStateOf("+91 ") }

    val members = remember {
        mutableStateListOf<Member>().also { it.addAll(loadMembers(context)) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        sharing = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

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
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "Private family location",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Only people you invite can see your location. Every member controls their own sharing.")
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = sharing,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    } else {
                                        sharing = false
                                    }
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(if (sharing) "Location sharing ON" else "Location sharing OFF")
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Invite family member")
                }
            }

            item {
                Text("Family", style = MaterialTheme.typography.titleLarge)
            }

            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            if (sharing) Icons.Default.LocationOn else Icons.Default.LocationOff,
                            contentDescription = null
                        )
                    },
                    headlineContent = { Text("You") },
                    supportingContent = {
                        Text(if (sharing) "Sharing your location" else "This phone")
                    },
                    trailingContent = { Text(if (sharing) "Sharing" else "Private") }
                )
                HorizontalDivider()
            }

            items(members) { member ->
                ListItem(
                    leadingContent = {
                        Icon(Icons.Default.LocationOff, contentDescription = null)
                    },
                    headlineContent = { Text(member.name) },
                    supportingContent = { Text(member.status) },
                    trailingContent = {
                        IconButton(onClick = {
                            members.remove(member)
                            saveMembers(context, members)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                )
                HorizontalDivider()
            }

            if (members.isEmpty()) {
                item {
                    Text("No family members yet. Tap \"Invite family member\" to add someone.")
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

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("OK") } },
            title = { Text("Privacy first") },
            text = {
                Text("FamilySafe is designed for consent-based location sharing. No member can secretly enable another person's GPS. Production location data will be protected by authenticated access rules and encrypted transport.")
            }
        )
    }

    if (showInvite) {
        val canSend = inviteName.isNotBlank() && invitePhone.count { it.isDigit() } >= 8

        fun createInvite(): String {
            val code = newInviteCode()
            members.add(
                Member(
                    name = inviteName.trim(),
                    phone = invitePhone.trim(),
                    code = code,
                    status = "Invitation sent - code $code"
                )
            )
            saveMembers(context, members)
            return inviteMessage(inviteName.trim(), code)
        }

        AlertDialog(
            onDismissRequest = { showInvite = false },
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
                        enabled = inviteName.isNotBlank(),
                        onClick = {
                            val message = createInvite()
                            showInvite = false
                            shareText(context, message)
                        }
                    ) { Text("Send using another app / SMS") }
                }
            },
            confirmButton = {
                Button(
                    enabled = canSend,
                    onClick = {
                        val phone = invitePhone
                        val message = createInvite()
                        showInvite = false
                        openWhatsApp(context, phone, message)
                    }
                ) { Text("Send on WhatsApp") }
            },
            dismissButton = {
                TextButton(onClick = { showInvite = false }) { Text("Cancel") }
            }
        )
    }
}
