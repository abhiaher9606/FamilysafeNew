package com.familysafe.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.CancellationTokenSource
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
        const val ACTION_CANCEL_COUNTDOWN = "com.familysafe.app.CANCEL_COUNTDOWN"
        const val ACTION_IM_SAFE = "com.familysafe.app.IM_SAFE"
        private const val CHANNEL_SHARING = "sharing"
        private const val CHANNEL_SOS = "sos"
        private const val CHANNEL_MY_SOS = "my_sos"
        private const val NOTIF_SHARING_ID = 1001
        private const val NOTIF_MY_SOS_ID = 1002

        /** Longest allowed gap between two power-button presses. */
        private const val MAX_GAP_MS = 1500L
        /** Time the user has to cancel an accidental power-button SOS. */
        private const val COUNTDOWN_MS = 5000L

        fun start(context: Context) {
            val intent = Intent(context, LocationShareService::class.java)
                .setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationShareService::class.java))
        }

        /** Called when the user cancels their SOS from inside the app. */
        fun clearMySos(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_POWER_SOS_ID).apply()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_MY_SOS_ID)
        }
    }

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var sosListener: ListenerRegistration? = null

    // power-button SOS
    private var screenReceiver: BroadcastReceiver? = null
    private val pressTimes = ArrayList<Long>()
    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunning = false
    private val sendRunnable = Runnable { sendPowerButtonSos() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()

        if (intent?.action == ACTION_STOP) {
            stopFromNotification()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_COUNTDOWN) {
            cancelCountdown()
            return if (client != null) START_STICKY else stopIfIdle()
        }
        if (intent?.action == ACTION_IM_SAFE) {
            markSafe()
            return if (client != null) START_STICKY else stopIfIdle()
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
        if (screenReceiver == null) listenForPowerButton()
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
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        handler.removeCallbacks(sendRunnable)
        countdownRunning = false
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

    // ---- power-button SOS ----
    // Android does not let apps read the power button directly. Each press
    // turns the screen off or on, so we count quick screen off/on changes.

    private fun listenForPowerButton() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onPowerPress()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = r
    }

    private fun onPowerPress() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_POWER_SOS, false)) {
            pressTimes.clear()
            return
        }
        if (countdownRunning) return

        val now = SystemClock.elapsedRealtime()
        if (pressTimes.isNotEmpty() && now - pressTimes.last() > MAX_GAP_MS) {
            pressTimes.clear()
        }
        pressTimes.add(now)

        // Trial phase: only 3 or 4 presses. 5 presses would also start the
        // phone's own Emergency SOS, which calls 112.
        val needed = prefs.getInt(KEY_SOS_PRESSES, 3).coerceIn(3, 4)
        if (pressTimes.size >= needed) {
            pressTimes.clear()
            startCountdown()
        }
    }

    private fun startCountdown() {
        countdownRunning = true
        vibrate(longArrayOf(0, 400, 150, 400, 150, 400))

        val cancel = PendingIntent.getService(
            this,
            2,
            Intent(this, LocationShareService::class.java).setAction(ACTION_CANCEL_COUNTDOWN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_MY_SOS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS will be sent in 5 seconds")
            .setContentText("Power button pressed. Tap Cancel if this was a mistake.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis() + COUNTDOWN_MS)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        notifyMySos(notification)

        handler.postDelayed(sendRunnable, COUNTDOWN_MS)
    }

    private fun cancelCountdown() {
        handler.removeCallbacks(sendRunnable)
        countdownRunning = false
        pressTimes.clear()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_MY_SOS_ID)
    }

    @SuppressLint("MissingPermission")
    private fun sendPowerButtonSos() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fid = prefs.getString(KEY_FAMILY, null)
        val me = FirebaseAuth.getInstance().currentUser?.uid
        val name = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: "A family member"
        if (fid == null || me == null) {
            countdownRunning = false
            return
        }

        notifyMySos(
            Notification.Builder(this, CHANNEL_MY_SOS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Sending SOS...")
                .setContentText("Getting your location")
                .setOngoing(true)
                .build()
        )

        fun send(lat: Double?, lng: Double?) {
            sendSos(fid, me, name, lat, lng) { sosId, error ->
                countdownRunning = false
                if (sosId != null) {
                    prefs.edit().putString(KEY_POWER_SOS_ID, sosId).apply()
                    vibrate(longArrayOf(0, 800))
                    showSosSentNotification(lat != null)
                } else {
                    notifyMySos(
                        Notification.Builder(this, CHANNEL_MY_SOS)
                            .setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentTitle("SOS could not be sent")
                            .setContentText(error ?: "Check your internet and try again from the app.")
                            .setContentIntent(openAppIntent())
                            .setAutoCancel(true)
                            .build()
                    )
                }
            }
        }

        if (!hasLocationPermission(this)) {
            send(null, null)
            return
        }
        try {
            val c = LocationServices.getFusedLocationProviderClient(this)
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        send(loc.latitude, loc.longitude)
                    } else {
                        c.lastLocation
                            .addOnSuccessListener { last -> send(last?.latitude, last?.longitude) }
                            .addOnFailureListener { send(null, null) }
                    }
                }
                .addOnFailureListener {
                    c.lastLocation
                        .addOnSuccessListener { last -> send(last?.latitude, last?.longitude) }
                        .addOnFailureListener { send(null, null) }
                }
        } catch (e: SecurityException) {
            send(null, null)
        }
    }

    private fun showSosSentNotification(withLocation: Boolean) {
        val safe = PendingIntent.getService(
            this,
            3,
            Intent(this, LocationShareService::class.java).setAction(ACTION_IM_SAFE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (withLocation) {
            "Your family was alerted with your location."
        } else {
            "Your family was alerted. Your location could not be found."
        }
        notifyMySos(
            Notification.Builder(this, CHANNEL_MY_SOS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS sent")
                .setContentText(text)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "I'm safe - cancel SOS", safe)
                .build()
        )
    }

    private fun markSafe() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fid = prefs.getString(KEY_FAMILY, null)
        val sosId = prefs.getString(KEY_POWER_SOS_ID, null)
        if (fid != null && sosId != null) resolveSos(fid, sosId)
        prefs.edit().remove(KEY_POWER_SOS_ID).apply()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_MY_SOS_ID)
    }

    private fun stopIfIdle(): Int {
        if (client == null) stopSelf()
        return START_NOT_STICKY
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyMySos(notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_MY_SOS_ID, notification)
        } catch (e: SecurityException) {
            // notifications not allowed; the SOS itself still goes out
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            // no vibrator
        }
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

        val mySos = NotificationChannel(
            CHANNEL_MY_SOS,
            "My SOS (power button)",
            NotificationManager.IMPORTANCE_HIGH
        )
        mySos.description = "Countdown and status when you send an SOS with the power button"
        mySos.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        mySos.enableVibration(true)
        nm.createNotificationChannel(mySos)
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
