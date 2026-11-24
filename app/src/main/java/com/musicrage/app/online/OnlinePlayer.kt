package com.musicrage.app.online

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicrage.app.data.MusicPreferences
import com.musicrage.app.data.OnlineVideo

/**
 * One online song while it plays. `hasNext` is always true: after a song ends a similar one is
 * picked automatically, so the Online tab never hands playback back to the local library.
 */
data class OnlineState(
    val video: OnlineVideo? = null,
    val isPlaying: Boolean = false,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = true
)

/** True while the current song is still being fetched/prepared (drives the loading overlay). */
object OnlineLoading {
    var active by mutableStateOf(false)
}

/** No ads exist in the converter MP3 stream; kept as an always-false flag for the UI. */
object OnlineAdState {
    var showing by mutableStateOf(false)
}

/**
 * Plays online songs by streaming the converter's MP3 directly with Android's MediaPlayer —
 * the same audio the mp3juice site itself serves. There is no YouTube embed and no WebView:
 * nothing for a video host to refuse, no ads to skip, and playback starts as soon as the
 * MP3 arrives instead of after a browser boots a whole video page.
 */
object OnlinePlayer {
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSeek = 0.0
    private var queue: List<OnlineVideo> = emptyList()
    private var index = -1
    private var lastSave = 0L
    private var fetchThread: Thread? = null

    /**
     * Rises every time a new song starts or playback stops. Events still arriving from an old
     * stream carry an older generation and are dropped, so a new track never inherits the
     * previous track's position.
     */
    private var generation = 0L

    var state by mutableStateOf(OnlineState())
        private set
    var positionSec by mutableDoubleStateOf(0.0)
        private set
    var durationSec by mutableDoubleStateOf(0.0)
        private set

    /** Called on the main thread whenever playback state changes (the service refreshes its notification). */
    var listener: (() -> Unit)? = null

    /** Lets this player pause the local-file player when online playback starts. */
    var pauseLocal: (() -> Unit)? = null

    /** Called by the supervisor when local playback takes over the notification: pause without touching its own state. */
    fun pauseForTakeover() {
        runCatching { player?.takeIf { prepared }?.pause() }
    }

    /** Called by the supervisor's ONLINE claim so a queued local start does not keep playing under the online song. */
    fun pauseLocalForTakeover() {
        pauseLocal?.invoke()
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            OnlineStore.onboard(context.applicationContext)
        }
        startClock()
    }

    // ---- Window placement (compatibility) ------------------------------------------------------------------------
    // The player is audio-only now, so there is no view to place, park or host anywhere. These
    // keep their names so the activity, service and screens still compile; each is a no-op, and
    // inOverlay stays true so the "Display over other apps" hint never shows again.

    var inOverlay by mutableStateOf(true)
        private set

    fun attach(activity: Activity) { }
    fun detach() { }
    fun releaseHost() { }
    fun overlayAllowed(): Boolean = true
    fun syncHost(activity: Activity) { }
    fun hostInBackground(warn: Boolean = true) { }
    fun park() { }
    fun place(x: Int, y: Int, width: Int, height: Int) { }

    // ---- Playback -----------------------------------------------------------------------------------------------

    private var userInitiated = true
    private var autoplayBusy = false
    private var endedGeneration = -1L

    fun play(video: OnlineVideo, list: List<OnlineVideo>) {
        // Only the chosen song is queued. What plays after it is picked from similar music, never from the
        // list it was tapped in (Continue playing, results, ...), which used to jump to unrelated songs.
        queue = listOf(video)
        index = 0
        userInitiated = true
        startVideo(video, 0.0, true)
    }

    /** Used by the service after the process was killed: reload the last song at its saved position. */
    fun recover(): Boolean {
        val context = appContext ?: return false
        val last = OnlineStore.loadLast(context) ?: return false
        queue = listOf(last.first)
        index = 0
        startVideo(last.first, last.second, false)
        return true
    }

    private fun startVideo(video: OnlineVideo, startSec: Double, startService: Boolean, heal: Boolean = false) {
        if (!Regex("[A-Za-z0-9_-]{6,20}").matches(video.id)) return
        val context = appContext ?: return
        if (!heal) {
            OnlineStore.recordPlay(context, video)
            OnlineStore.addHistory(context, video)
            OnlineStore.log(context, "play ${video.id}")
        }
        // Claim the single media notification before anything starts.
        com.musicrage.app.playback.PlaybackSupervisor.claim(context, com.musicrage.app.playback.PlaybackSupervisor.Owner.ONLINE)
        generation += 1
        val gen = generation
        state = OnlineState(video, isPlaying = true, hasPrev = index > 0, hasNext = true)
        positionSec = startSec
        durationSec = video.duration.takeIf { it > 0.0 } ?: 0.0
        OnlineLoading.active = true
        OnlineStore.saveLast(context, video, startSec)
        OnlineStore.setAlive(context, true)
        if (startService) {
            runCatching { context.startForegroundService(IntentHolder.serviceIntent(context)) }
        }

        // Stop whatever was playing before, then fetch the MP3 on a worker thread.
        releasePlayer()
        fetchThread = Thread {
            val link = runCatching {
                com.musicrage.app.data.OnlineApi.prepareMp3("https://www.youtube.com/watch?v=${video.id}")
            }.getOrNull()
            main.post {
                if (gen != generation) return@post
                if (link == null) {
                    OnlineStore.log(context, "stream unavailable ${video.id}")
                    giveUp(video)
                } else {
                    openStream(link, startSec)
                }
            }
        }.also { it.start() }
        listener?.invoke()
    }

    /** Points the MediaPlayer at the converter's MP3 and starts it. Must run on the main thread. */
    private fun openStream(url: String, startSec: Double) {
        val context = appContext ?: return
        val gen = generation
        val mp = MediaPlayer()
        prepared = false
        pendingSeek = startSec
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setDataSource(url)
        mp.setOnPreparedListener { p ->
            if (gen != generation) {
                runCatching { p.release() }
                return@setOnPreparedListener
            }
            prepared = true
            durationSec = p.duration / 1000.0
            if (pendingSeek > 1.0 && pendingSeek < durationSec - 2.0) p.seekTo(pendingSeek.toInt() * 1000)
            p.start()
            OnlineLoading.active = false
            state = state.copy(isPlaying = true)
            OnlineStore.setAlive(context, true)
            listener?.invoke()
        }
        mp.setOnCompletionListener { p ->
            if (gen != generation) {
                runCatching { p.release() }
                return@setOnCompletionListener
            }
            onTrackEnded()
        }
        mp.setOnErrorListener { p, what, extra ->
            OnlineStore.log(context, "media error $what/$extra ${state.video?.id ?: "?"}")
            runCatching { p.release() }
            if (generation == gen) main.post { giveUp(state.video ?: return@post) }
            true
        }
        runCatching {
            mp.prepareAsync()
            player = mp
        }.onFailure {
            OnlineStore.log(context, "prepare failed ${state.video?.id ?: "?"}")
            runCatching { mp.release() }
            giveUp(state.video ?: return)
        }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    private fun giveUp(video: OnlineVideo) {
        val context = appContext ?: return
        OnlineStore.addDead(context, video.id)
        OnlineStore.log(context, "dead ${video.id}")
        OnlineLoading.active = false
        if (!userInitiated || index + 1 < queue.size) {
            // The song came from autoplay (or has a successor): quietly pick another one.
            if (index + 1 < queue.size) {
                index += 1
                startVideo(queue[index], 0.0, false)
            } else {
                autoplaySimilar(context)
            }
        } else {
            // The song the person chose cannot play here. Instead of leaving a dead play button, quietly play
            // another upload of the same song.
            OnlineStore.removeHistory(context, video.id)
            swapForAlternative(video)
        }
    }

    private var swapCount = 0

    private fun swapForAlternative(video: OnlineVideo) {
        val context = appContext ?: return
        if (autoplayBusy || swapCount >= 3) {
            OnlineLoading.active = false
            state = state.copy(isPlaying = false)
            listener?.invoke()
            return
        }
        autoplayBusy = true
        swapCount += 1
        val gen = generation
        val avoid = OnlineStore.dead(context)
        OnlineLoading.active = true
        Thread {
            val alternative = runCatching { com.musicrage.app.data.OnlineApi.alternatives(video, avoid) }
                .getOrDefault(emptyList()).firstOrNull()
            main.post {
                autoplayBusy = false
                if (gen != generation) return@post
                if (alternative == null) {
                    OnlineLoading.active = false
                    state = state.copy(isPlaying = false)
                    listener?.invoke()
                } else {
                    queue = listOf(alternative)
                    index = 0
                    userInitiated = true
                    startVideo(alternative, 0.0, false)
                }
            }
        }.start()
    }

    fun toggle() {
        val mp = player ?: return
        if (state.isPlaying) {
            runCatching { if (prepared) mp.pause() }
            state = state.copy(isPlaying = false)
        } else {
            appContext?.let { com.musicrage.app.playback.PlaybackSupervisor.claim(it, com.musicrage.app.playback.PlaybackSupervisor.Owner.ONLINE) }
            pauseLocal?.invoke()
            runCatching { if (prepared) mp.start() else mp.prepareAsync() }
            state = state.copy(isPlaying = true)
        }
        listener?.invoke()
    }

    fun pause() {
        if (state.isPlaying) toggle()
    }

    fun toggleIfPaused() {
        if (!state.isPlaying) toggle()
    }

    fun next() {
        val context = appContext
        val video = state.video
        if (context != null && video != null && positionSec < 20.0) OnlineStore.recordSkip(context, video)
        if (index + 1 < queue.size) {
            index += 1
            startVideo(queue[index], 0.0, false)
        } else {
            autoplaySimilar(context)
        }
    }

    fun previous() {
        if (positionSec > 5.0 || index <= 0) {
            seekTo(0.0)
        } else {
            index -= 1
            startVideo(queue[index], 0.0, false)
        }
    }

    fun seekTo(seconds: Double) {
        positionSec = seconds
        val mp = player ?: return
        runCatching { if (prepared) mp.seekTo((seconds * 1000).toInt()) }
        listener?.invoke()
    }

    private fun onTrackEnded() {
        if (endedGeneration == generation) return
        endedGeneration = generation
        releasePlayer()
        state = state.copy(isPlaying = false)
        val video = state.video
        val context = appContext
        if (context != null && video != null) {
            OnlineStore.setAlive(context, false)
            OnlineStore.recordFinished(context, video)
        }
        if (index + 1 < queue.size) {
            index += 1
            startVideo(queue[index], 0.0, false)
        } else {
            autoplaySimilar(context)
        }
    }

    /**
     * After a song ends (or Next is pressed with nothing queued) a similar song is chosen, so the
     * Online tab NEVER hands playback back to the local library. Songs similar to the one that just
     * finished (the artist's other songs, covers of the same song) come first; only when that comes
     * up empty does the old ladder run — the artists the person plays most, then popular songs.
     * Songs already played or dead are skipped.
     */
    /** True for things that look like a single song (not mixes, streams, playlists or podcasts). */
    private fun looksLikeSong(video: OnlineVideo): Boolean {
        val title = video.title.lowercase()
        val noise = listOf("hours", "hour mix", "top 100", "top 50", "top 20", "top 10", "playlist", " mix", "compilation",
            "nonstop", "non-stop", "non stop", "full album", "best of", "jukebox", "24/7", "live stream", "hits 20", "trending",
            "podcast", "episode", "interview", "set #", "dj set", "recording", "snippet", "preview")
        return title.length < 140 && noise.none { title.contains(it) } && video.playable
    }

    private fun autoplaySimilar(context: Context?) {
        val finished = state.video ?: return
        if (context == null || autoplayBusy) return
        autoplayBusy = true
        val gen = generation
        val seen = OnlineStore.history(context).map { it.id }.toSet() + queue.map { it.id }
        val avoid = OnlineStore.dead(context)
        val artist = finished.channel.trim()
        val seed = finished.title.substringBefore(" (").substringBefore(" [").trim()
        OnlineLoading.active = true
        Thread {
            // 1) Songs similar to the one that just ended.
            val related = runCatching { com.musicrage.app.data.OnlineApi.related(finished) }.getOrDefault(emptyList())
            var pick = related.filter { it.id !in seen && it.id !in avoid && looksLikeSong(it) }.randomOrNull()
            // 2) The old ladder, only when similar came up empty: this song, favourites, popular.
            if (pick == null) {
                val queries = buildList<String> {
                    if (seed.isNotBlank()) add(seed)
                    if (artist.isNotBlank()) add("$artist songs")
                    OnlineStore.favoriteChannels(context, 3).filter { !it.equals(artist, ignoreCase = true) }.forEach { add("$it songs") }
                    add("popular songs")
                }.distinct()
                for (query in queries) {
                    val found = runCatching { com.musicrage.app.data.OnlineApi.search(query) }.getOrDefault(emptyList())
                    val candidates = found.filter { it.id !in seen && it.id !in avoid && looksLikeSong(it) }
                    if (candidates.isNotEmpty()) {
                        pick = candidates.take(5).random()
                        break
                    }
                }
            }
            val chosen = pick
            main.post {
                autoplayBusy = false
                if (gen != generation) return@post
                if (chosen == null) {
                    OnlineLoading.active = false
                    state = state.copy(isPlaying = false)
                    listener?.invoke()
                } else {
                    userInitiated = false
                    queue = queue + chosen
                    index = queue.size - 1
                    startVideo(chosen, 0.0, false)
                }
            }
        }.start()
    }

    // ---- Position clock ------------------------------------------------------------------------------------------

    /**
     * Ticks the position while a song plays (the MediaPlayer offers no polling API). Also saves the
     * position every few seconds so crash recovery resumes near where the phone died.
     */
    private var clockStarted = false
    private fun startClock() {
        if (clockStarted) return
        clockStarted = true
        main.postDelayed(object : Runnable {
            override fun run() {
                runCatching { clockTick() }
                main.postDelayed(this, 500L)
            }
        }, 500L)
    }

    private fun clockTick() {
        val mp = player ?: return
        if (!prepared || !state.isPlaying) return
        positionSec = mp.currentPosition / 1000.0
        val now = System.currentTimeMillis()
        val context = appContext
        if (context != null && now - lastSave > 5_000L) {
            lastSave = now
            OnlineStore.savePosition(context, positionSec)
        }
        listener?.invoke()
    }

    /** Full stop used when the LOCAL player takes over: clears state so the app shows the local player again. */
    fun stop(clearView: Boolean = true) {
        generation += 1
        runCatching { player?.stop() }
        releasePlayer()
        OnlineLoading.active = false
        state = OnlineState()
        positionSec = 0.0
        durationSec = 0.0
        queue = emptyList()
        index = -1
        val context = appContext
        if (context != null) {
            OnlineStore.setAlive(context, false)
            OnlineStore.savePosition(context, 0.0)
            OnlineStore.clearLast(context)
        }
        listener?.invoke()
    }

    /** Builds the foreground-service intent lazily so this file needs no Intent import churn. */
    private object IntentHolder {
        fun serviceIntent(context: Context): android.content.Intent =
            android.content.Intent(context, OnlineService::class.java)
    }
}
