package com.familysafe.app

import android.Manifest
import android.os.Bundle
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class Member(val name: String, val status: String, val sharing: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FamilySafeApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySafeApp() {
    var sharing by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }

    // Rebuilt on every change of `sharing`, so the list always matches the switch.
    val members = listOf(
        Member("You", if (sharing) "Sharing your location" else "This phone", sharing),
        Member("Family member", "Waiting for invitation", false)
    )

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
                    onClick = { showInvite = true },
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

            items(members) { member ->
                ListItem(
                    leadingContent = {
                        Icon(
                            if (member.sharing) Icons.Default.LocationOn else Icons.Default.LocationOff,
                            contentDescription = null
                        )
                    },
                    headlineContent = { Text(member.name) },
                    supportingContent = { Text(member.status) },
                    trailingContent = {
                        Text(if (member.sharing) "Sharing" else "Private")
                    }
                )
                HorizontalDivider()
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
        AlertDialog(
            onDismissRequest = { showInvite = false },
            confirmButton = { TextButton(onClick = { showInvite = false }) { Text("Close") } },
            title = { Text("Invite family member") },
            text = {
                Text("The production version will generate a one-time invitation code/link. The invited person must accept and grant location permission before their location is shared.")
            }
        )
    }
}
