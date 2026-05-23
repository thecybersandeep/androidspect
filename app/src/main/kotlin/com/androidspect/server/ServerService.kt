package com.androidspect.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.androidspect.MainActivity
import com.androidspect.R
import java.net.NetworkInterface

/**
 * Foreground service that hosts the embedded HTTP server. Lives as long as
 * the user keeps AndroidSpect running. Survives task removal because the
 * server is the *product*; the Compose UI is just a convenience surface.
 */
class ServerService : LifecycleService() {

    private var server: AndroidSpectServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground()
        if (server == null) {
            val port = currentPort(applicationContext)
            server = AndroidSpectServer(applicationContext, port = port).also {
                it.start()
                currentInstance = it
            }
        }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        currentInstance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForeground() {
        val title = getString(R.string.server_notification_title)
        val ip = primaryIPv4() ?: "127.0.0.1"
        val text = getString(R.string.server_notification_text, ip, currentPort(applicationContext))
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.server_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false); enableLights(false); enableVibration(false) }
            nm.createNotificationChannel(ch)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidSpect:server").apply {
            setReferenceCounted(false)
            acquire(/* timeout = */ 12L * 60 * 60 * 1000) // 12h cap
        }
    }

    companion object {
        const val CHANNEL_ID = "androidspect.server"
        const val NOTIFICATION_ID = 0xD10
        const val DEFAULT_PORT = AndroidSpectServer.DEFAULT_PORT
        const val PREFS = "androidspect"
        const val KEY_PORT = "port"

        @Volatile
        private var currentInstance: AndroidSpectServer? = null

        /**
         * Returns the first 8 bytes of the active TLS cert's SHA-256 fingerprint
         * formatted as `AA:BB:CC:…`, or null if the server isn't running.
         * The Compose UI shows this so the user can verify the cert in their
         * browser matches the one their phone generated (defense against MITM).
         */
        fun tlsFingerprintShort(): String? =
            currentInstance?.tls?.let { TlsManager.fingerprintShort(it) }

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }

        /** Read configured port (default 8008) from SharedPreferences. */
        fun currentPort(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PORT, DEFAULT_PORT)
                .coerceIn(1024, 65535)

        /** Persist a new port. Caller is responsible for restarting the server. */
        fun setPort(context: Context, port: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PORT, port.coerceIn(1024, 65535)).apply()
        }

        /**
         * Cheap, lock-free check: ask ActivityManager which services are
         * currently running for our process. Good enough for the dashboard
         * polling at 2 s intervals.
         */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            // getRunningServices is deprecated for cross-process inspection but
            // remains fully supported for *our own* services.
            val target = ServerService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == target }
        }

        fun primaryIPv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr.hostAddress?.contains('.') == true
                }?.hostAddress
        }.getOrNull()
    }
}
