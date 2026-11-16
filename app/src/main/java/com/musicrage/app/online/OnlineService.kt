package com.musicrage.app.online

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.musicrage.app.MainActivity
import com.musicrage.app.R
import com.musicrage.app.RecoveryReceiver
import com.musicrage.app.data.MusicPreferences
import com.musicrage.app.playback.PlaybackSupervisor

/**
 * Foreground media service for the online player. It owns the notification and media session (play, pause, next,
 * previous from the notification, lock screen and headset) and keeps the process alive while the embedded player
 * runs in the background.
 */
class OnlineService : Service() {
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var recovering = false
    private var lastArm = 0L

    private val heartbeat = object : Runnable {
        override fun run() {
            OnlineStore.beat(this@OnlineService)
            val now = System.currentTimeMillis()
            if (OnlinePlayer.state.isPlaying && now - lastArm > 20_000L) {
                lastArm = now
                armWatchdog()
            }
            // The local player took over the single media notification: go silent and stop.
            if (PlaybackSupervisor.current() == PlaybackSupervisor.Owner.LOCAL) {
                OnlinePlayer.stop()
                stopSelf()
                return
            }
            handler.postDelayed(this, 2_000L)
        }
    }

    private val recoveryTimeout = Runnable {
        if (recovering) {
            recovering = false
            if (!OnlinePlayer.state.isPlaying) {
                // A failed recovery is quiet: the Online tab already shows the "continue playing" entry,
                // and a toast here would fight with the local player's own recovery messages.
                OnlinePlayer.stop()
            }
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        OnlinePlayer.init(this)
        createChannel()
        session = MediaSession(this, "MusicRageOnline")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = OnlinePlayer.toggleIfPaused()
            override fun onPause() = OnlinePlayer.pause()
            override fun onSkipToNext() = OnlinePlayer.next()
            override fun onSkipToPrevious() = OnlinePlayer.previous()
            override fun onSeekTo(pos: Long) = OnlinePlayer.seekTo(pos / 1000.0)
        })
        session.isActive = true
        OnlinePlayer.listener = { refresh() }
        // startForegroundService() requires startForeground() promptly, even if nothing is playing yet.
        refresh(force = true)
        handler.postDelayed(heartbeat, 2_000L)

        // A previous instance that was playing and never reached onDestroy means the process was killed.
        val killed = OnlineStore.isAlive(this)
        if (killed && MusicPreferences.autoRecover(this) && OnlinePlayer.state.video == null) beginRecovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> OnlinePlayer.toggle()
            ACTION_NEXT -> OnlinePlayer.next()
            ACTION_PREVIOUS -> OnlinePlayer.previous()
            ACTION_STAND_DOWN -> {
                // The local player owns the notification now: hide ours and stop. Playback state was
                // already cleared by the supervisor, so the service must not re-post anything.
                OnlinePlayer.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECOVER -> {
                refresh(force = true)
                if (!recovering && !OnlinePlayer.state.isPlaying) beginRecovery()
            }
        }
        refresh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing after the app is swiped away; stop only if nothing is playing.
        if (!OnlinePlayer.state.isPlaying) stopSelf() else handler.post { OnlinePlayer.hostInBackground() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        OnlinePlayer.listener = null
        // A full clear (not just a pause): the service is going away, so no video may keep "playing"
        // in the UI after this. If local music owns the notification this was already done by the supervisor.
        if (PlaybackSupervisor.current() != PlaybackSupervisor.Owner.ONLINE) OnlinePlayer.stop()
        OnlinePlayer.releaseHost()
        OnlineStore.setAlive(this, false)
        cancelWatchdog()
        session.release()
        super.onDestroy()
    }

    private fun beginRecovery() {
        toast("Music Rage was closed in the background")
        recovering = OnlinePlayer.recover()
        if (recovering) {
            toast("Recovering playback…")
            handler.removeCallbacks(recoveryTimeout)
            handler.postDelayed(recoveryTimeout, 12_000L)
        }
    }

    // ---- Notification ---------------------------------------------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Online player", NotificationManager.IMPORTANCE_LOW))
    }

    private fun action(icon: Int, label: String, action: String, code: Int): Notification.Action {
        val intent = PendingIntent.getService(
            this, code, Intent(this, OnlineService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, intent).build()
    }

    private fun refresh(force: Boolean = false) {
        val state = OnlinePlayer.state
        val video = state.video
        val playing = state.isPlaying

        if (recovering && playing) {
            recovering = false
            handler.removeCallbacks(recoveryTimeout)
            toast("Playback recovered" + (video?.let { ": ${it.title}" } ?: ""))
        }

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, video?.title ?: "Online player")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, video?.channel ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (OnlinePlayer.durationSec * 1000).toLong())
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    (OnlinePlayer.positionSec * 1000).toLong(),
                    1f
                )
                .build()
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_ONLINE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(video?.title ?: "Online player")
            .setContentText(video?.channel ?: "")
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(action(R.drawable.ic_skip_prev, "Previous", ACTION_PREVIOUS, 1))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 2)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE, 2)
            )
            .addAction(action(R.drawable.ic_skip_next, "Next", ACTION_NEXT, 3))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        runCatching {
            if (playing || recovering || force) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    // ---- Watchdog -------------------------------------------------------------------------------------------------

    private fun watchdogIntent(): PendingIntent {
        val intent = Intent(this, RecoveryReceiver::class.java).setAction(RecoveryReceiver.ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(this, 8, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun armWatchdog() {
        runCatching {
            val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + 60_000L
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, watchdogIntent())
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, watchdogIntent())
            }
        }
    }

    private fun cancelWatchdog() {
        runCatching { (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(watchdogIntent()) }
    }

    private fun toast(message: String) {
        handler.post { com.musicrage.app.Toaster.show(this, message, long = true) }
    }

    companion object {
        const val ACTION_TOGGLE = "com.musicrage.app.online.TOGGLE"
        const val ACTION_NEXT = "com.musicrage.app.online.NEXT"
        const val ACTION_PREVIOUS = "com.musicrage.app.online.PREVIOUS"
        const val ACTION_RECOVER = "com.musicrage.app.online.RECOVER"
        const val ACTION_STAND_DOWN = "com.musicrage.app.online.STAND_DOWN"
        private const val CHANNEL = "music_rage_online"
        private const val NOTIFICATION_ID = 2002
    }
}
