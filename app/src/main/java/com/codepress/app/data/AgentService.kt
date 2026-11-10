package com.codepress.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps CodePress alive while the AI is building something, so the work continues
 * when the user switches to another app or locks the screen.
 * Started when a run begins and stopped when it ends.
 */
class AgentService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val project = intent?.getStringExtra(EXTRA_PROJECT) ?: "CodePress"
        ensureChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val tap = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("$project · AI is working")
            .setContentText("Building in the background. You can use other apps.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(tap)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // Could not become a foreground service (for example notifications blocked by the system): the run still continues while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodePress:agent").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "AI running in background", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while the AI is building your project" },
            )
        }
    }

    companion object {
        private const val CHANNEL = "agent_running"
        private const val NOTIF_ID = 301
        private const val EXTRA_PROJECT = "project"

        fun start(context: Context, project: String) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentService::class.java).putExtra(EXTRA_PROJECT, project),
                )
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AgentService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
