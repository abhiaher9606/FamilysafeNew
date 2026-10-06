package com.familysafe.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration

/**
 * Keeps sharing the user's location (and listening for SOS alerts from the
 * family) while the app is closed. Android requires a visible notification
 * for this, so the user always sees that sharing is ON and can stop it.
 */
class LocationShareService : Service() {

    companion object {
        const val ACTION_START = "com.familysafe.app.START"
        const val ACTION_STOP = "com.familysafe.app.STOP"
        private const val CHANNEL_SHARING = "sharing"
        private const val CHANNEL_SOS = "sos"
        private const val NOTIF_SHARING_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, LocationShareService::class.java)
                .setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationShareService::class.java))
        }
    }

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var sosListener: ListenerRegistration? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()

        if (intent?.action == ACTION_STOP) {
            stopFromNotification()
            return START_NOT_STICKY
        }

        try {
            enterForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fid = prefs.getString(KEY_FAMILY, null)
        val me = FirebaseAuth.getInstance().currentUser?.uid
        val wantsSharing = prefs.getBoolean(KEY_SHARING, false)

        if (fid == null || me == null || !wantsSharing || !hasLocationPermission(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (client == null) startLocationUpdates(fid, me)
        if (sosListener == null) listenForSos(fid, me)
        return START_STICKY
    }

    override fun onDestroy() {
        val c = client
        val cb = callback
        if (c != null && cb != null) c.removeLocationUpdates(cb)
        client = null
        callback = null
        sosListener?.remove()
        sosListener = null
        super.onDestroy()
    }

    // ---- location ----

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(fid: String, me: String) {
        val c = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            60_000L
        ).setMinUpdateIntervalMillis(30_000L).build()

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
            stopSelf()
        }
    }

    // ---- SOS alerts from other family members ----

    private fun listenForSos(fid: String, me: String) {
        sosListener = sosRef(fid)
            .whereEqualTo("active", true)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener

                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val seen = (prefs.getStringSet(KEY_SEEN_SOS, null) ?: emptySet()).toMutableSet()
                var changed = false

                for (change in snap.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val d = change.document
                    if (d.getString("uid") == me) continue
                    if (seen.contains(d.id)) continue

                    val created = d.getTimestamp("createdAt")?.toDate()?.time
                    if (created != null && System.currentTimeMillis() - created > 30 * 60 * 1000L) {
                        continue
                    }

                    seen.add(d.id)
                    changed = true
                    showSosNotification(
                        d.id,
                        d.getString("name") ?: "A family member",
                        d.getDouble("lat"),
                        d.getDouble("lng")
                    )
                }

                if (changed) {
                    if (seen.size > 200) seen.clear()
                    prefs.edit().putStringSet(KEY_SEEN_SOS, seen).apply()
                }
            }
    }

    private fun showSosNotification(sosId: String, name: String, lat: Double?, lng: Double?) {
        val tapIntent = if (lat != null && lng != null) {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode("SOS - $name")})")
            )
        } else {
            Intent(this, MainActivity::class.java)
        }
        val pending = PendingIntent.getActivity(
            this,
            sosId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = if (lat != null && lng != null) {
            "$name needs help. Tap to see their location."
        } else {
            "$name needs help. Open FamilySafe."
        }

        val notification = Notification.Builder(this, CHANNEL_SOS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS from $name")
            .setContentText(text)
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(sosId.hashCode(), notification)
    }

    // ---- notification plumbing ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        val sharing = NotificationChannel(
            CHANNEL_SHARING,
            "Location sharing",
            NotificationManager.IMPORTANCE_LOW
        )
        sharing.description = "Shown while FamilySafe is sharing your location"
        nm.createNotificationChannel(sharing)

        val sos = NotificationChannel(
            CHANNEL_SOS,
            "SOS alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        sos.description = "Emergency alerts from your family"
        sos.enableVibration(true)
        nm.createNotificationChannel(sos)
    }

    private fun enterForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_SHARING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("FamilySafe is sharing your location")
            .setContentText("Your family can see where you are. Tap to open the app.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopAction)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_SHARING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_SHARING_ID, notification)
        }
    }

    private fun stopFromNotification() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fid = prefs.getString(KEY_FAMILY, null)
        val me = FirebaseAuth.getInstance().currentUser?.uid
        prefs.edit().putBoolean(KEY_SHARING, false).apply()
        if (fid != null && me != null) setSharingFlag(fid, me, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
