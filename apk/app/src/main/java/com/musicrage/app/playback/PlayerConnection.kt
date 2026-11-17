package com.musicrage.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.musicrage.app.data.AudioTrack
import com.musicrage.app.data.MusicPreferences
import com.musicrage.app.online.OnlinePlayer
import kotlin.math.abs

@Immutable
data class PlayerSnapshot(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val queueSize: Int = 0
)

/**
 * Owns the MediaController. It reconnects on its own when the playback service dies, queues commands until a
 * connection exists, and exposes state that Compose can read directly.
 */
class PlayerConnection(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    var snapshot by mutableStateOf(PlayerSnapshot())
        private set

    /** Kept apart from [snapshot] so only the seek bars recompose on every tick. */
    var positionMs by mutableLongStateOf(0L)
        private set

    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var ticking = false
    private var released = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            this@PlayerConnection.controller = null
            runCatching { controller.removeListener(playerListener) }
            runCatching { controller.release() }
            refresh()
            scheduleReconnect()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            refresh()
            handler.postDelayed(this, if (snapshot.isPlaying) 250L else 700L)
        }
    }

    private val reconnect = Runnable { connect() }

    fun connect() {
        if (released) return
        if (controller?.isConnected == true || future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val request = MediaController.Builder(appContext, token)
            .setListener(controllerListener)
            .buildAsync()
        future = request
        request.addListener({
            future = null
            val connected = runCatching { request.get() }.getOrNull()
            if (connected == null || released) {
                if (connected != null) runCatching { connected.release() }
                if (!released) scheduleReconnect()
            } else {
                controller = connected
                connected.addListener(playerListener)
                refresh()
                val queued = ArrayList(pending)
                pending.clear()
                queued.forEach { action -> runCatching { action(connected) } }
            }
        }, appContext.mainExecutor)
    }

    private fun scheduleReconnect() {
        if (released) return
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, 800L)
    }

    /** Call when the UI becomes visible; makes sure there is a live connection and state is fresh. */
    fun start() {
        released = false
        ticking = true
        connect()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        ticking = false
        handler.removeCallbacks(ticker)
    }

    fun release() {
        released = true
        ticking = false
        handler.removeCallbacksAndMessages(null)
        controller?.let {
            runCatching { it.removeListener(playerListener) }
            runCatching { it.release() }
        }
        controller = null
        future?.let { runCatching { MediaController.releaseFuture(it) } }
        future = null
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) {
            action(c)
        } else {
            pending.add(action)
            connect()
        }
    }

    private fun refresh() {
        val c = controller
        if (c == null || !c.isConnected) {
            if (snapshot.connected) snapshot = snapshot.copy(connected = false)
            return
        }
        val item = c.currentMediaItem
        val metadata = item?.mediaMetadata
        val reported = c.duration
        val duration = if (reported != C.TIME_UNSET && reported > 0L) reported else (metadata?.durationMs ?: 0L)
        val state = c.playbackState
        val next = PlayerSnapshot(
            connected = true,
            hasMedia = item != null,
            mediaId = item?.mediaId?.takeIf { it.isNotEmpty() },
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            album = metadata?.albumTitle?.toString().orEmpty(),
            artworkUri = metadata?.artworkUri,
            isPlaying = c.playWhenReady && state != Player.STATE_ENDED && item != null,
            durationMs = duration,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            queueSize = c.mediaItemCount
        )
        if (next != snapshot) snapshot = next
        val position = c.currentPosition.coerceAtLeast(0L)
        if (abs(position - positionMs) >= 40L || position == 0L) positionMs = position
    }

    // ---- Commands -----------------------------------------------------------------------------------------------

    /** Starts [tracks] at [startIndex]. [shuffle] = null keeps the current shuffle setting. */
    fun playQueue(tracks: List<AudioTrack>, startIndex: Int, shuffle: Boolean? = null) {
        if (tracks.isEmpty()) return
        val limit = 400
        val from = if (tracks.size > limit) (startIndex - limit / 2).coerceIn(0, tracks.size - limit) else 0
        val window = if (tracks.size > limit) tracks.subList(from, from + limit) else tracks
        val start = (startIndex - from).coerceIn(0, window.lastIndex)
        val items = window.map { it.toMediaItem() }
        PlaybackSupervisor.claim(appContext, PlaybackSupervisor.Owner.LOCAL)
        OnlinePlayer.pauseForTakeover()
        withController { c ->
            val speed = MusicPreferences.playbackSpeed(appContext)
            if (shuffle != null) c.shuffleModeEnabled = shuffle
            c.setPlaybackParameters(PlaybackParameters(speed, speed))
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
    }

    fun playSingle(item: MediaItem) {
        PlaybackSupervisor.claim(appContext, PlaybackSupervisor.Owner.LOCAL)
        OnlinePlayer.pauseForTakeover()
        withController { c ->
            val speed = MusicPreferences.playbackSpeed(appContext)
            c.setPlaybackParameters(PlaybackParameters(speed, speed))
            c.setMediaItem(item)
            c.prepare()
            c.play()
        }
    }

    fun togglePlayPause() {
        withController { c ->
            if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
                c.pause()
            } else {
                PlaybackSupervisor.claim(appContext, PlaybackSupervisor.Owner.LOCAL)
                OnlinePlayer.pauseForTakeover()
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
                c.play()
            }
        }
    }

    fun pause() {
        withController { c -> c.pause() }
    }

    fun next() {
        withController { c ->
            if (c.hasNextMediaItem()) c.seekToNextMediaItem() else if (c.mediaItemCount > 0) c.seekTo(0, 0L)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
        }
    }

    fun previous() {
        withController { c ->
            if (c.currentPosition > 3_000L || !c.hasPreviousMediaItem()) c.seekTo(0L) else c.seekToPreviousMediaItem()
        }
    }

    fun seekToFraction(fraction: Float) {
        withController { c ->
            val duration = c.duration
            val total = if (duration != C.TIME_UNSET && duration > 0L) duration else snapshot.durationMs
            if (total > 0L) {
                val target = (total * fraction.coerceIn(0f, 1f)).toLong()
                c.seekTo(target)
                positionMs = target
            }
        }
    }

    fun toggleShuffle() {
        withController { c -> c.shuffleModeEnabled = !c.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        withController { c ->
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 10f)
        MusicPreferences.setPlaybackSpeed(appContext, clamped)
        // Natural rate scaling: pitch follows speed.
        withController { c -> c.setPlaybackParameters(PlaybackParameters(clamped, clamped)) }
    }
}
