package com.codepress.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

/** 1234567 -> "1.2 MB". Used for file sizes, download progress and speed. */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

/** Status-bar notifications: model copy/download progress, AI replies, file changes, approvals. */
class Notifier(context: Context) {

    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)
    private var lastProgressAt = 0L

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val sys = ctx.getSystemService(NotificationManager::class.java)
            sys.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Downloads and copies", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress of local model downloads and copies" },
            )
            sys.createNotificationChannel(
                NotificationChannel(CH_EVENTS, "AI and file updates", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "AI replies, new files and approval requests" },
            )
        }
    }

    private fun tapIntent(): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(id: Int, n: Notification) {
        try {
            if (nm.areNotificationsEnabled()) nm.notify(id, n)
        } catch (e: SecurityException) {
            // permission missing: nothing to show
        }
    }

    /** [percent] 0..100, or -1 when the total size is unknown. */
    fun progress(title: String, text: String, percent: Int) {
        val now = SystemClock.elapsedRealtime()
        if (percent in 0..99 && now - lastProgressAt < 600) return
        lastProgressAt = now
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(tapIntent())
        if (percent >= 0) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        post(ID_PROGRESS, b.build())
    }

    fun progressDone(title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(tapIntent())
            .build()
        post(ID_PROGRESS, n)
    }

    fun cancelProgress() {
        nm.cancel(ID_PROGRESS)
    }

    fun aiReplied(project: String, text: String) =
        event(ID_AI, "$project · AI replied", text.take(300))

    fun filesChanged(project: String, count: Int) =
        event(ID_FILES, "$project · files updated", if (count == 1) "1 file was created or changed" else "$count files were created or changed")

    fun needsApproval(project: String, what: String) =
        event(ID_APPROVAL, "$project · approval needed", what.take(200))

    private fun event(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tapIntent())
            .build()
        post(id, n)
    }

    private companion object {
        const val CH_PROGRESS = "progress"
        const val CH_EVENTS = "events"
        const val ID_PROGRESS = 101
        const val ID_AI = 201
        const val ID_FILES = 202
        const val ID_APPROVAL = 203
    }
}
