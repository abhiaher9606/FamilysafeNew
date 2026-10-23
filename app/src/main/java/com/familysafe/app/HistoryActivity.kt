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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shows one family member's stored location trail for a chosen day:
 * the route drawn as a line on a free OpenStreetMap map (no API key, no
 * billing), with start/end markers and a summary. Steps back HISTORY_DAYS days.
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

        // osmdroid setup: identify the app and keep the tile cache in the app's
        // own internal storage, so no storage permission is needed.
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        cfg.osmdroidBasePath = File(cacheDir, "osmdroid")
        cfg.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

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
                if (error == null && list.isNotEmpty()) {
                    TrailMap(points = list, modifier = Modifier.fillMaxSize())
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
private fun TrailMap(points: List<TrailPoint>, modifier: Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.clear()
            val geoPoints = points.map { GeoPoint(it.lat, it.lng) }
            if (geoPoints.size >= 2) {
                val line = Polyline(map).apply {
                    setPoints(geoPoints)
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.color = android.graphics.Color.parseColor("#1565C0")
                }
                map.overlays.add(line)
                map.overlays.add(
                    Marker(map).apply {
                        position = geoPoints.first()
                        title = "Start"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                )
                map.overlays.add(
                    Marker(map).apply {
                        position = geoPoints.last()
                        title = "Latest"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                )
                val bbox = BoundingBox.fromGeoPointsSafe(geoPoints)
                map.post {
                    try {
                        map.zoomToBoundingBox(bbox, false, 80)
                    } catch (e: Exception) {
                        map.controller.setZoom(14.0)
                        map.controller.setCenter(geoPoints.last())
                    }
                }
            } else if (geoPoints.size == 1) {
                map.controller.setZoom(16.0)
                map.controller.setCenter(geoPoints.first())
                map.overlays.add(
                    Marker(map).apply {
                        position = geoPoints.first()
                        title = "Location"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                )
            }
            map.invalidate()
        }
    )
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
