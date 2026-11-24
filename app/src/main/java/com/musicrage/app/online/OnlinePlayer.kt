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
import org.json.JSONArray
import org.json.JSONObject

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
object OnlineStore {
    private const val KEY_LAST = "online_last"
    private const val KEY_POSITION = "online_position"
    private const val KEY_ALIVE = "online_alive"
    private const val KEY_BEAT = "online_beat"
    private const val KEY_HISTORY = "online_history"

    private fun prefs(context: Context) = context.getSharedPreferences(MusicPreferences.FILE, Context.MODE_PRIVATE)

    private fun toJson(video: OnlineVideo) = JSONObject()
        .put("id", video.id).put("title", video.title).put("channel", video.channel).put("thumb", video.thumb)
        .put("permalink", video.permalink)
        // A NaN here throws JSONException ("Forbidden numeric value") on save and crashed the app on
        // the Online tab when an entry from an older build was rewritten, so it is never stored.
        .put("duration", if (video.duration.isNaN() || video.duration < 0) 0.0 else video.duration)
        .put("playable", video.playable)
        .put("album", video.album)

    private fun fromJson(obj: JSONObject) = OnlineVideo(
        obj.optString("id"),
        obj.optString("title"),
        obj.optString("channel"),
        obj.optString("thumb"),
        obj.optString("permalink"),
        // Entries saved by older builds have no "duration"; the no-fallback optDouble would hand
        // back NaN and blow up the very next save. Anything unreadable counts as "unknown" (0), not NaN.
        obj.optDouble("duration", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        obj.optBoolean("playable", true),
        obj.optLong("likes", 0L),
        // Older entries have no "album"; the download falls back to the song title when blank.
        obj.optString("album")
    )

    fun saveLast(context: Context, video: OnlineVideo, positionSec: Double) {
        prefs(context).edit().putString(KEY_LAST, toJson(video).toString()).putFloat(KEY_POSITION, positionSec.toFloat()).apply()
    }

    fun savePosition(context: Context, positionSec: Double) {
        prefs(context).edit().putFloat(KEY_POSITION, positionSec.toFloat()).apply()
    }

    /** Removes the "continue playing" pointer so a stopped player cannot be resurrected by mistake. */
    fun clearLast(context: Context) {
        prefs(context).edit().remove(KEY_LAST).remove(KEY_POSITION).putBoolean(KEY_ALIVE, false).apply()
    }

    fun loadLast(context: Context): Pair<OnlineVideo, Double>? {
        val raw = prefs(context).getString(KEY_LAST, null) ?: return null
        val position = prefs(context).getFloat(KEY_POSITION, 0f).toDouble()
        return runCatching { fromJson(JSONObject(raw)) to position }.getOrNull()
    }

    fun history(context: Context): List<OnlineVideo> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    fun removeHistory(context: Context, id: String) {
        val kept = history(context).filter { it.id != id }
        prefs(context).edit().putString(KEY_HISTORY, JSONArray().apply { kept.forEach { put(toJson(it)) } }.toString()).apply()
    }

    fun addHistory(context: Context, video: OnlineVideo) {
        val list = (history(context).filter { it.id != video.id } + video).takeLast(60)
        prefs(context).edit().putString(KEY_HISTORY, JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()).apply()
    }

    fun isAlive(context: Context) = prefs(context).getBoolean(KEY_ALIVE, false)

    fun setAlive(context: Context, alive: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALIVE, alive).apply()
    }

    /** The moment the app last saw the player alive; recovery uses this after a process kill. */
    fun beat(context: Context) {
        prefs(context).edit().putLong(KEY_BEAT, System.currentTimeMillis()).apply()
    }

    fun beatAgeMs(context: Context): Long =
        System.currentTimeMillis() - prefs(context).getLong(KEY_BEAT, 0L)

    // ---- Self-healing state (blocked ids, listening taste, dead ids, log) ------------------------------------------

    private const val KEY_BLOCKED = "online_blocked"
    private const val KEY_TASTE = "online_taste"
    private const val KEY_DEAD = "online_dead"
    private const val KEY_LOG = "online_log"
    private const val KEY_SEEN = "online_seen_v3"

    /**
     * Runs once per install: drops the SoundCloud-era saved state. SoundCloud track ids are pure
     * numbers and can never play in the YouTube player, so they are removed from history, taste
     * and the "continue playing" pointer (a blank slate, not a crash on the first tap).
     */
    fun onboard(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_SEEN, false)) return
        val numeric = Regex("\\d{6,20}")
        val edit = p.edit().putBoolean(KEY_SEEN, true)
        val oldHistory = history(context)
        if (oldHistory.isNotEmpty() && oldHistory.any { numeric.matches(it.id) }) {
            val kept = oldHistory.filter { !numeric.matches(it.id) }
            val array = JSONArray()
            kept.forEach { array.put(toJson(it)) }
            edit.putString(KEY_HISTORY, array.toString())
        }
        val last = loadLast(context)
        if (last != null && numeric.matches(last.first.id)) {
            edit.remove(KEY_LAST).remove(KEY_POSITION).putBoolean(KEY_ALIVE, false)
        }
        if (p.getStringSet(KEY_DEAD, emptySet())?.orEmpty()?.any { numeric.matches(it) } == true) {
            edit.remove(KEY_DEAD)
        }
        if (p.getStringSet(KEY_BLOCKED, emptySet())?.orEmpty()?.any { numeric.matches(it) } == true) {
            edit.remove(KEY_BLOCKED)
        }
        edit.apply()
    }

    // ---- What the person listens to ------------------------------------------------------------------------------

    fun taste(context: Context): Map<String, Int> {
        val raw = prefs(context).getString(KEY_TASTE, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optInt(it) }
        }.getOrDefault(emptyMap())
    }

    private fun bumpTaste(context: Context, channel: String, delta: Int) {
        if (channel.isBlank()) return
        val map = taste(context).toMutableMap()
        map[channel] = (map[channel] ?: 0) + delta
        prefs(context).edit().putString(KEY_TASTE, JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun recordPlay(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, 1)
    fun recordFinished(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, 2)
    fun recordSkip(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, -1)

    fun favoriteChannels(context: Context, limit: Int): List<String> =
        taste(context).entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key }.take(limit)

    fun dead(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DEAD, emptySet())?.toSet() ?: emptySet()

    fun addDead(context: Context, id: String) {
        prefs(context).edit().putStringSet(KEY_DEAD, dead(context) + id).apply()
    }

    /** Small ring buffer of what the watchdog saw and fixed. Never shown; kept for diagnosis. */
    fun log(context: Context, line: String) {
        val lines = (prefs(context).getString(KEY_LOG, "").orEmpty().split("\n").filter { it.isNotBlank() } +
            "${System.currentTimeMillis()} $line").takeLast(40)
        prefs(context).edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }
}
