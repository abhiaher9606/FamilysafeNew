package com.familysafe.app

import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shows one family member's stored location trail for a chosen day:
 * the route drawn as a line on a map, with start/end markers and a summary.
 * You can step back up to HISTORY_DAYS days.
 */
class HistoryActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FAMILY = "family_id"
        const val EXTRA_UID = "uid"
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val familyId = intent.getStringExtra(EXTRA_FAMILY)
        val uid = intent.getStringExtra(EXTRA_UID)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "Family member"

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = remember {
                if (prefs.contains(KEY_DARK_MODE)) prefs.getBoolean(KEY_DARK_MODE, false)
                else systemDark
            }
            FamilySafeTheme(dark = dark) {
                if (familyId == null || uid == null) {
                    MissingDataScreen { finish() }
                } else {
                    HistoryScreen(
                        familyId = familyId,
                        uid = uid,
                        name = name,
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

private data class TrailPoint(val lat: Double, val lng: Double, val t: Long)

/** Start of local day, `daysAgo` days before today. */
private fun dayStartMillis(daysAgo: Int): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -daysAgo)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun formatDay(startMs: Long): String =
    SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(startMs))

private fun formatTime(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

/** Total distance of the trail in metres. */
private fun totalDistance(points: List<TrailPoint>): Double {
    var sum = 0.0
    val out = FloatArray(1)
    for (i in 1 until points.size) {
        Location.distanceBetween(
            points[i - 1].lat, points[i - 1].lng,
            points[i].lat, points[i].lng,
            out
        )
        sum += out[0]
    }
    return sum
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    familyId: String,
    uid: String,
    name: String,
    onBack: () -> Unit
) {
    // 0 = today, 1 = yesterday, ... up to HISTORY_DAYS - 1
    var daysAgo by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val points = remember { mutableStateOf<List<TrailPoint>>(emptyList()) }

    val dayStart = remember(daysAgo) { dayStartMillis(daysAgo) }
    val dayEnd = remember(daysAgo) { dayStart + 24L * 60L * 60L * 1000L }

    LaunchedEffect(daysAgo) {
        loading = true
        error = null
        trailQueryForRange(familyId, uid, dayStart, dayEnd)
            .get()
            .addOnSuccessListener { snap ->
                points.value = snap.documents.mapNotNull { d ->
                    val lat = d.getDouble("lat")
                    val lng = d.getDouble("lng")
                    val t = d.getLong("t")
                    if (lat != null && lng != null && t != null) TrailPoint(lat, lng, t) else null
                }
                loading = false
            }
            .addOnFailureListener {
                error = it.message ?: "Could not load history"
                loading = false
            }
    }

    val list = points.value

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$name - history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            // day selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    enabled = daysAgo < HISTORY_DAYS - 1,
                    onClick = { if (daysAgo < HISTORY_DAYS - 1) daysAgo++ }
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Earlier day")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (daysAgo) {
                            0 -> "Today"
                            1 -> "Yesterday"
                            else -> formatDay(dayStart)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(formatDay(dayStart), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(
                    enabled = daysAgo > 0,
                    onClick = { if (daysAgo > 0) daysAgo-- }
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Later day")
                }
            }

            // summary line
            if (!loading && error == null) {
                val summary = if (list.isEmpty()) {
                    "No location recorded this day."
                } else {
                    val km = totalDistance(list) / 1000.0
                    val first = formatTime(list.first().t)
                    val last = formatTime(list.last().t)
                    "%d points  -  %s to %s  -  %.1f km".format(list.size, first, last, km)
                }
                Text(
                    summary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (error == null) {
                    TrailMap(points = list, dark = false)
                }
                when {
                    loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    error != null -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Could not load history: $error",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    list.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No location was recorded for ${if (daysAgo == 0) "today" else "this day"} yet. History is only recorded while that person has sharing ON.")
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailMap(points: List<TrailPoint>, dark: Boolean) {
    val latLngs = remember(points) { points.map { LatLng(it.lat, it.lng) } }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            latLngs.lastOrNull() ?: LatLng(20.5937, 78.9629), // India fallback
            if (latLngs.isEmpty()) 4f else 14f
        )
    }

    // fit all points into view once they load
    LaunchedEffect(latLngs) {
        if (latLngs.size >= 2) {
            val b = LatLngBounds.builder()
            latLngs.forEach { b.include(it) }
            try {
                cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
            } catch (e: Exception) {
                cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(latLngs.last(), 14f))
            }
        } else if (latLngs.size == 1) {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 15f))
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(mapType = MapType.NORMAL)
    ) {
        if (latLngs.size >= 2) {
            Polyline(points = latLngs, width = 12f)
            Marker(state = MarkerState(position = latLngs.first()), title = "Start")
            Marker(state = MarkerState(position = latLngs.last()), title = "Latest")
        } else if (latLngs.size == 1) {
            Marker(state = MarkerState(position = latLngs.first()), title = "Location")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingDataScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Box(
            Modifier.fillMaxSize().padding(pad),
            contentAlignment = Alignment.Center
        ) { Text("Could not open history.") }
    }
}
