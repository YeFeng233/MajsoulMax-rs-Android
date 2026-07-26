package moe.majsoulmax.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import moe.majsoulmax.app.R

/**
 * Owns the single notification channel. Created from `Application.onCreate` in
 * both processes, because either one may be the first to post.
 */
object NotificationHelper {

    const val CHANNEL_ID = "tunnel"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            },
        )
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Posting without POST_NOTIFICATIONS is a silent no-op rather than a crash,
     * which matters because the foreground service still has to run.
     */
    fun notify(context: Context, id: Int, notification: Notification) {
        if (!hasPermission(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
