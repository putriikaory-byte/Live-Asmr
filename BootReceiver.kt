package com.liveasmr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = context.getSharedPreferences("live", Context.MODE_PRIVATE)
        if (!p.getBoolean("wasLive", false)) return
        if (p.getLong("endAt", 0L) <= System.currentTimeMillis()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("live_resume", "Lanjutkan live", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = Intent(context, MainActivity::class.java)
            .putExtra("resume", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, "live_resume")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Live ASMR terhenti")
            .setContentText("HP sudah menyala lagi. Ketuk untuk melanjutkan live.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(31, n)
    }
}
