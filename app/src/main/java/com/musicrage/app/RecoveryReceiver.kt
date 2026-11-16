package com.musicrage.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.musicrage.app.data.MusicPreferences
import com.musicrage.app.online.OnlineService
import com.musicrage.app.online.OnlineStore
import com.musicrage.app.playback.PlaybackService
import com.musicrage.app.playback.PlaybackStore

/**
 * Fired by the watchdog alarm that the playback service keeps pushing back while it is healthy. If it actually fires,
 * the service stopped refreshing it (the process was killed), so playback is restarted.
 */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return
        if (!MusicPreferences.autoRecover(context)) return
        var needed = false
        var failed = false

        if (PlaybackStore.isSessionAlive(context) && PlaybackStore.heartbeatAgeMs(context) >= 20_000L) {
            needed = true
            val start = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_RECOVER)
            if (runCatching { context.startForegroundService(start) }.isFailure) failed = true
        }
        if (OnlineStore.isAlive(context) && OnlineStore.beatAgeMs(context) >= 20_000L) {
            needed = true
            val start = Intent(context, OnlineService::class.java).setAction(OnlineService.ACTION_RECOVER)
            if (runCatching { context.startForegroundService(start) }.isFailure) failed = true
        }
        if (needed && failed) showTapToResume(context)
    }

    private fun showTapToResume(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playback stopped", NotificationManager.IMPORTANCE_HIGH)
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle("Music Rage was closed")
                .setContentText("Tap to continue playing")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(2201, notification)
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.musicrage.app.action.WATCHDOG"
        private const val CHANNEL = "music_rage_stopped"
    }
}
