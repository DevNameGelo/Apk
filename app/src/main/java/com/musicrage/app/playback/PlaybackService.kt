package com.musicrage.app.playback

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musicrage.app.MainActivity
import com.musicrage.app.R
import com.musicrage.app.RecoveryReceiver
import com.musicrage.app.data.MusicPreferences

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var floating: FloatingPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var errorStreak = 0
    private var recoveryPending = false
    private var lastWatchdogArm = 0L

    private val heartbeat = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null) {
                PlaybackStore.saveProgress(this@PlaybackService, exo)
                val now = System.currentTimeMillis()
                if (exo.playWhenReady && now - lastWatchdogArm > 20_000L) {
                    lastWatchdogArm = now
                    armWatchdog()
                }
            }
            handler.postDelayed(this, 2_000L)
        }
    }

    private val recoveryTimeout = Runnable {
        if (recoveryPending) {
            recoveryPending = false
            val exo = player
            if (exo == null || !exo.isPlaying) {
                toast("Couldn't resume automatically. Tap play to continue.")
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                if (exo == null || !exo.playWhenReady) PlaybackStore.setSessionAlive(this@PlaybackService, false)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                PlaybackStore.saveQueue(this@PlaybackService, player)
            } else if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY)) {
                PlaybackStore.saveProgress(this@PlaybackService, player)
            }
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                syncIntent(player)
            }
            updateOverlay()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) errorStreak = 0
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && recoveryPending) {
                recoveryPending = false
                handler.removeCallbacks(recoveryTimeout)
                val title = player?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                toast(if (title.isNotBlank()) "Playback recovered: $title" else "Playback recovered")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes(), !MusicPreferences.playOverOthers(this))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(playerListener)
        player = exo

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkBitmapLoader(this))
            .build()
        val provider = DefaultMediaNotificationProvider.Builder(this).build()
        provider.setSmallIcon(R.drawable.ic_stat_music)
        setMediaNotificationProvider(provider)

        floating = FloatingPlayer(this) { player }
        AppVisibility.listener = { updateOverlay() }

        handler.postDelayed(heartbeat, 2_000L)

        // A previous instance that never reached onDestroy while music was meant to be playing means the process
        // was killed (low memory, a game's memory cleaner, an OEM task killer).
        val killed = PlaybackStore.isSessionAlive(this)
        if (killed) {
            toast("Music Rage was closed in the background")
            if (MusicPreferences.autoRecover(this)) {
                beginRecovery()
            } else {
                PlaybackStore.setSessionAlive(this, false)
                restorePaused()
            }
        } else {
            restorePaused()
        }
        updateOverlay()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECOVER -> {
                startForegroundSafely()
                val exo = player
                if (exo != null && !exo.isPlaying && !recoveryPending) {
                    if (exo.mediaItemCount > 0) {
                        recoveryPending = true
                        exo.prepare()
                        exo.playWhenReady = true
                        handler.removeCallbacks(recoveryTimeout)
                        handler.postDelayed(recoveryTimeout, 10_000L)
                    } else {
                        beginRecovery()
                    }
                }
            }
            ACTION_SYNC_OVERLAY -> {
                player?.setAudioAttributes(audioAttributes(), !MusicPreferences.playOverOthers(this))
                updateOverlay()
            }
            // The online player just claimed or released the single media notification.
            ACTION_SYNC_ONLINE -> syncWithOnline()
        }
        val result = super.onStartCommand(intent, flags, startId)
        // Sticky restart is only a request; Android still decides whether the process comes back.
        return if (result == START_NOT_STICKY) START_STICKY else result
    }

    /** While the online player owns the notification this service stays silent: paused and notification-less. */
    private fun syncWithOnline() {
        val exo = player ?: return
        if (PlaybackSupervisor.current() == PlaybackSupervisor.Owner.ONLINE) {
            if (exo.playWhenReady) exo.pause()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    /** media3 calls this whenever player state changes. Suppress it while the online player owns the notification. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (PlaybackSupervisor.current() == PlaybackSupervisor.Owner.ONLINE && !startInForegroundRequired) {
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the service alive while music is playing after the app is swiped away.
        val exo = player
        if (exo == null || !exo.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AppVisibility.listener = null
        floating?.hide()
        floating = null
        player?.let {
            PlaybackStore.saveQueue(this, it)
            it.removeListener(playerListener)
        }
        // Reaching onDestroy is a clean shutdown, so the next start is not treated as a kill.
        PlaybackStore.setSessionAlive(this, false)
        cancelWatchdog()
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }

    // ---- Recovery -----------------------------------------------------------------------------------------------

    private fun beginRecovery() {
        val exo = player ?: return
        val saved = PlaybackStore.load(this)
        if (saved == null) {
            PlaybackStore.setSessionAlive(this, false)
            return
        }
        recoveryPending = true
        startForegroundSafely()
        exo.setMediaItems(saved.items, saved.index, saved.positionMs)
        exo.shuffleModeEnabled = saved.shuffle
        exo.repeatMode = saved.repeatMode
        applySavedSpeed(exo)
        exo.prepare()
        exo.playWhenReady = true
        toast("Recovering playback…")
        handler.removeCallbacks(recoveryTimeout)
        handler.postDelayed(recoveryTimeout, 10_000L)
    }

    /** Loads the last queue without starting it, so the app opens on the song you were listening to. */
    private fun restorePaused() {
        val exo = player ?: return
        if (exo.mediaItemCount > 0) return
        val saved = PlaybackStore.load(this) ?: return
        exo.setMediaItems(saved.items, saved.index, saved.positionMs)
        exo.shuffleModeEnabled = saved.shuffle
        exo.repeatMode = saved.repeatMode
        applySavedSpeed(exo)
        exo.playWhenReady = false
        exo.prepare()
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private fun applySavedSpeed(exo: ExoPlayer) {
        val speed = MusicPreferences.playbackSpeed(this)
        exo.playbackParameters = PlaybackParameters(speed, speed)
    }

    private fun handlePlayerError() {
        val exo = player ?: return
        errorStreak += 1
        if (errorStreak <= 2) {
            val position = exo.currentPosition.coerceAtLeast(0L)
            val index = exo.currentMediaItemIndex
            handler.postDelayed({
                val current = player ?: return@postDelayed
                current.seekTo(index, position)
                current.prepare()
                current.playWhenReady = true
            }, 700L * errorStreak)
        } else {
            errorStreak = 0
            if (exo.hasNextMediaItem()) {
                toast("Skipped a file that can't be played")
                exo.seekToNextMediaItem()
                exo.prepare()
                exo.playWhenReady = true
            } else {
                toast("This file can't be played")
                exo.playWhenReady = false
            }
        }
    }

    private fun syncIntent(exo: Player) {
        val intended = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && exo.mediaItemCount > 0
        // While recovering, the flag stays set until playback actually resumes or the attempt times out.
        if (intended || !recoveryPending) PlaybackStore.setSessionAlive(this, intended)
        if (!intended && !recoveryPending) cancelWatchdog()
    }

    private fun startForegroundSafely() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RECOVERY, "Playback recovery", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = Notification.Builder(this, CHANNEL_RECOVERY)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle("Music Rage")
                .setContentText("Restoring playback")
                .setOngoing(true)
                .build()
            startForeground(RECOVERY_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }
    }

    // ---- Watchdog alarm -----------------------------------------------------------------------------------------

    private fun watchdogIntent(): PendingIntent {
        val intent = Intent(this, RecoveryReceiver::class.java).setAction(RecoveryReceiver.ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Pushes the watchdog 60 s into the future. It only ever fires if this service stops refreshing it. */
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
        runCatching {
            val alarms = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.cancel(watchdogIntent())
        }
        lastWatchdogArm = 0L
    }

    // ---- Overlay / helpers --------------------------------------------------------------------------------------

    private fun updateOverlay() {
        val overlay = floating ?: return
        val exo = player
        val wanted = MusicPreferences.floatingEnabled(this) &&
            Settings.canDrawOverlays(this) &&
            !AppVisibility.visible &&
            exo != null && exo.mediaItemCount > 0
        if (wanted) overlay.show() else overlay.hide()
    }

    private fun toast(message: String) {
        handler.post { com.musicrage.app.Toaster.show(this, message, long = true) }
    }

    companion object {
        const val ACTION_RECOVER = "com.musicrage.app.action.RECOVER"
        const val ACTION_SYNC_OVERLAY = "com.musicrage.app.action.SYNC_OVERLAY"
        const val ACTION_SYNC_ONLINE = "com.musicrage.app.action.SYNC_ONLINE"
        private const val CHANNEL_RECOVERY = "music_rage_recovery"
        private const val RECOVERY_NOTIFICATION_ID = 1001
    }
}
